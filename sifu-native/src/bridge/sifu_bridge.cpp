#include "bridge/sifu_bridge.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <utility>

#include "core/log.h"

namespace sifucraft::bridge {

namespace {

using namespace protocol;

constexpr int kSeqlockAttempts = 4;
constexpr std::uint64_t kPingLogEvery = 100;

template <typename T>
std::atomic_ref<T> Atomic(T& value) {
    return std::atomic_ref<T>(value);
}

// x86-64 keeps stores ordered with stores and loads with loads; the seqlock and ring only need the
// compiler not to move plain field accesses across the sequence/index updates.
inline void CompilerBarrier() { std::atomic_signal_fence(std::memory_order_seq_cst); }

std::uint64_t UnixMicros() {
    FILETIME now;
    GetSystemTimePreciseAsFileTime(&now);
    const std::uint64_t ticks = (static_cast<std::uint64_t>(now.dwHighDateTime) << 32) | now.dwLowDateTime;
    return (ticks - 116444736000000000ull) / 10;
}

template <typename T>
T* At(std::uint8_t* base, std::uint32_t offset) {
    return reinterpret_cast<T*>(base + offset);
}

}  // namespace

bool SifuBridge::Start() {
    if (!mapping_.Open(kMappingName, kMappingSize)) {
        SC_LOG("[bridge] cannot open shared memory %ls (error %lu) -- bridge disabled", kMappingName,
               mapping_.last_error());
        return false;
    }
    std::uint8_t* base = mapping_.data();
    header_ = At<ProtocolHeader>(base, kHeaderOffset);
    self_ = At<EndpointStatus>(base, kSifuStatusOffset);
    peer_ = At<EndpointStatus>(base, kMcStatusOffset);
    snapshot_ = At<SifuSnapshot>(base, kSifuSnapshotOffset);
    peer_snapshot_ = At<McSnapshot>(base, kMcSnapshotOffset);
    manifest_ = At<CollisionManifest>(base, kCollisionManifestOffset);
    block_table_ = At<BlockTable>(base, kBlockTableOffset);
    enemy_table_ = At<EnemyTable>(base, kEnemyTableOffset);
    outgoing_ = At<EventRing>(base, kSifuToMcRingOffset);
    incoming_ = At<EventRing>(base, kMcToSifuRingOffset);

    header_->version = kVersion;
    header_->mapping_size = kMappingSize;
    Atomic(header_->layout_hash).store(kLayoutHash, std::memory_order_relaxed);
    Atomic(header_->magic).store(kMagic, std::memory_order_release);

    session_ = ((UnixMicros() << 12) ^ GetCurrentProcessId()) | 1;
    // Drop whatever a previous Sifu session left unread, and skip events addressed to it.
    Atomic(outgoing_->write_index)
        .store(Atomic(outgoing_->read_index).load(std::memory_order_acquire), std::memory_order_release);
    Atomic(incoming_->read_index)
        .store(Atomic(incoming_->write_index).load(std::memory_order_acquire), std::memory_order_release);

    self_->version = kVersion;
    Atomic(self_->layout_hash).store(kLayoutHash, std::memory_order_relaxed);
    self_->pid = GetCurrentProcessId();
    Atomic(self_->session).store(session_, std::memory_order_relaxed);
    Atomic(self_->heartbeat).store(0, std::memory_order_relaxed);
    Atomic(self_->state).store(static_cast<std::uint32_t>(EndpointState::Running), std::memory_order_relaxed);
    Atomic(self_->magic).store(kMagic, std::memory_order_release);
    PublishCollision({});  // nothing yet: a previous Sifu session's grid is gone
    PublishEnemies(0, {});

    SC_LOG("[bridge] shared memory %s: %ls (%u bytes) protocol v%u layout=%016llX session=%016llX",
           mapping_.created() ? "created" : "opened", kMappingName, kMappingSize, kVersion,
           static_cast<unsigned long long>(kLayoutHash), static_cast<unsigned long long>(session_));
    return true;
}

void SifuBridge::MarkClosing() {
    if (self_) Atomic(self_->state).store(static_cast<std::uint32_t>(EndpointState::Closing), std::memory_order_release);
}

void SifuBridge::Tick(const PlayerFrame& frame, std::uint64_t now_ms) {
    if (!self_) return;
    ++stats_.ticks;
    Atomic(self_->unix_us).store(UnixMicros(), std::memory_order_relaxed);
    Atomic(self_->heartbeat).store(++heartbeat_, std::memory_order_release);
    TrackPeer(now_ms);
    PumpEvents();
    Publish(frame);
    if (peer_connected_) {
        ReadPeerSnapshot();
        ReadBlocks();
    } else {
        peer_state_.valid = false;
    }
}

bool SifuBridge::SendInput(EventType type, std::int32_t code, std::int32_t action, std::int32_t modifiers, std::uint32_t gui_revision) {
    if (!self_ || !peer_connected_) return false;
    const InputPayload payload{code, action, modifiers, gui_revision};
    if (!Send(type, &payload, sizeof(payload))) return false;
    ++stats_.input_events;
    return true;
}
bool SifuBridge::SendGuiMotion(double x, double y, double scroll) {
    const GuiMousePayload payload{x, y, scroll};
    if (!self_ || !peer_connected_ || !Send(EventType::GuiMouseMotion, &payload, sizeof(payload))) return false;
    ++stats_.input_events;
    return true;
}

bool SifuBridge::SendOwnerChanged(InputOwner owner) {
    if (!self_ || !peer_connected_) return false;
    const OwnerPayload payload{static_cast<std::uint32_t>(owner), 0};
    return Send(EventType::OwnerChanged, &payload, sizeof(payload));
}

void SifuBridge::TrackPeer(std::uint64_t now_ms) {
    // If this side itself stalled, the peer's heartbeat could not be observed meanwhile: restart
    // its timer instead of judging it.
    const bool stalled = last_tick_ms_ != 0 && now_ms - last_tick_ms_ > kHeartbeatTimeoutMs / 2;
    last_tick_ms_ = now_ms;
    if (stalled && peer_heartbeat_ms_ != 0) peer_heartbeat_ms_ = now_ms;
    if (Atomic(peer_->magic).load(std::memory_order_acquire) != kMagic) {
        if (peer_connected_) Disconnect("status cleared", now_ms);
        return;
    }
    const std::uint32_t version = peer_->version;
    const std::uint64_t layout = Atomic(peer_->layout_hash).load(std::memory_order_relaxed);
    if (version != kVersion || layout != kLayoutHash) {
        if (peer_connected_) Disconnect("incompatible", now_ms);
        if (!peer_incompatible_) {
            SC_LOG("[bridge] INCOMPATIBLE peer: protocol v%u layout=%016llX (expected v%u layout=%016llX)", version,
                   static_cast<unsigned long long>(layout), kVersion, static_cast<unsigned long long>(kLayoutHash));
            peer_incompatible_ = true;
        }
        return;
    }
    peer_incompatible_ = false;

    const std::uint64_t session = Atomic(peer_->session).load(std::memory_order_acquire);
    const std::uint64_t heartbeat = Atomic(peer_->heartbeat).load(std::memory_order_acquire);
    if (session != peer_session_) {
        if (peer_connected_) Disconnect("peer restarted", now_ms);
        peer_session_ = session;
        peer_pid_ = peer_->pid;
        peer_heartbeat_ = heartbeat;
        peer_heartbeat_ms_ = 0;  // a new session counts as alive only once its heartbeat moves
        peer_last_event_id_ = 0;
    } else if (heartbeat != peer_heartbeat_) {
        peer_heartbeat_ = heartbeat;
        peer_heartbeat_ms_ = now_ms;
    }

    const auto state = static_cast<EndpointState>(Atomic(peer_->state).load(std::memory_order_acquire));
    const bool beating = peer_heartbeat_ms_ != 0 && now_ms - peer_heartbeat_ms_ < kHeartbeatTimeoutMs;
    if (peer_connected_) {
        if (state == EndpointState::Closing) {
            Disconnect("peer closing", now_ms);
        } else if (!beating) {
            Disconnect("heartbeat timeout", now_ms);
        }
    } else if (state == EndpointState::Running && beating) {
        peer_connected_ = true;
        peer_connected_ms_ = now_ms;
        ++stats_.connects;
        SC_LOG("[bridge] peer %s: minecraft pid=%u session=%016llX (connect #%llu)",
               stats_.connects > 1 ? "RECONNECTED" : "CONNECTED", peer_pid_,
               static_cast<unsigned long long>(peer_session_), static_cast<unsigned long long>(stats_.connects));
    }
}

void SifuBridge::Disconnect(const char* reason, std::uint64_t now_ms) {
    peer_connected_ = false;
    ++stats_.disconnects;
    SC_LOG("[bridge] peer DISCONNECTED (%s): pid=%u session=%016llX after %.1f s", reason, peer_pid_,
           static_cast<unsigned long long>(peer_session_), (now_ms - peer_connected_ms_) / 1000.0);
}

void SifuBridge::PumpEvents() {
    EventRing& ring = *incoming_;
    const std::uint64_t write = Atomic(ring.write_index).load(std::memory_order_acquire);
    std::uint64_t read = Atomic(ring.read_index).load(std::memory_order_relaxed);
    if (write < read || write - read > kRingCapacity) {
        // The producer re-attached and reset its index: nothing in between is valid.
        read = write;
    }
    std::uint64_t last_id = Atomic(ring.acked_id).load(std::memory_order_relaxed);
    while (read < write) {
        CompilerBarrier();
        Event event;
        std::memcpy(&event, &ring.slots[read % kRingCapacity], sizeof(event));
        ++read;
        ++stats_.events_received;
        if (event.source_session != peer_session_ || event.target_session != session_) {
            ++stats_.events_stale;
            continue;
        }
        if (event.id <= peer_last_event_id_) {
            ++stats_.events_duplicate;
            continue;
        }
        peer_last_event_id_ = event.id;
        last_id = event.id;

        switch (static_cast<EventType>(event.type)) {
            case EventType::Ping: {
                PingPayload ping{};
                std::memcpy(&ping, event.payload, sizeof(ping));
                ++stats_.pings;
                if (stats_.pings <= 3 || stats_.pings % kPingLogEvery == 0) {
                    SC_LOG("[bridge] ping #%llu id=%llu nonce=%016llX", static_cast<unsigned long long>(stats_.pings),
                           static_cast<unsigned long long>(event.id), static_cast<unsigned long long>(ping.nonce));
                }
                Send(EventType::Pong, &ping, sizeof(ping));
                break;
            }
            case EventType::EnemyDamage:
                if (peer_connected_ && event.size == sizeof(EnemyDamagePayload)) damage_events_.push_back(event);
                else ++stats_.events_unknown;
                break;
            default:
                ++stats_.events_unknown;
                break;
        }
    }
    Atomic(ring.acked_id).store(last_id, std::memory_order_relaxed);
    Atomic(ring.read_index).store(read, std::memory_order_release);
}

void SifuBridge::DrainEnemyDamage(std::vector<Event>* events) {
    events->clear();
    events->swap(damage_events_);
}

bool SifuBridge::SendDamageResult(const DamageResultPayload& result) {
    return self_ && peer_connected_ && Send(EventType::DamageResult, &result, sizeof(result));
}

bool SifuBridge::SendPlayerHit(const PlayerHitPayload& hit) {
    return self_ && peer_connected_ && Send(EventType::PlayerHit, &hit, sizeof(hit));
}

bool SifuBridge::Send(EventType type, const void* payload, std::uint32_t size) {
    EventRing& ring = *outgoing_;
    const std::uint64_t write = Atomic(ring.write_index).load(std::memory_order_relaxed);
    const std::uint64_t read = Atomic(ring.read_index).load(std::memory_order_acquire);
    if (write - read >= kRingCapacity || size > sizeof(Event::payload)) {
        ++stats_.events_dropped_full;
        return false;
    }
    Event& slot = ring.slots[write % kRingCapacity];
    slot.id = next_event_id_++;
    slot.source_session = session_;
    slot.target_session = peer_session_;
    slot.type = static_cast<std::uint32_t>(type);
    slot.size = size;
    std::memset(slot.payload, 0, sizeof(slot.payload));
    std::memcpy(slot.payload, payload, size);
    CompilerBarrier();
    Atomic(ring.write_index).store(write + 1, std::memory_order_release);
    ++stats_.events_sent;
    return true;
}

void SifuBridge::Publish(const PlayerFrame& frame) {
    SifuSnapshot& s = *snapshot_;
    auto seq = Atomic(s.seq);
    // Round down to even: a previous writer may have died mid-update and left the sequence odd.
    const std::uint64_t start = seq.load(std::memory_order_relaxed) & ~1ull;
    seq.store(start + 1, std::memory_order_relaxed);
    CompilerBarrier();
    s.frame = ++frame_;
    s.unix_us = UnixMicros();
    s.world_generation = frame.world_generation;
    s.flags = frame.flags;
    s.player_location = frame.player_location;
    s.player_rotation = frame.player_rotation;
    s.player_velocity = frame.player_velocity;
    s.camera_location = frame.camera_location;
    s.camera_rotation = frame.camera_rotation;
    s.camera_fov = frame.camera_fov;
    s.health = frame.health;
    s.max_health = frame.max_health;
    const std::size_t length = std::min(frame.world_path.size(), sizeof(s.world_path) - 1);
    std::memcpy(s.world_path, frame.world_path.data(), length);
    s.world_path[length] = '\0';
    s.mouse_dx = frame.mouse_dx;
    s.mouse_dy = frame.mouse_dy;
    s.scroll = frame.scroll;
    s.input_owner = static_cast<std::uint32_t>(frame.input_owner);
    s.capsule_half_height = frame.capsule_half_height;
    s.anchor_epoch = frame.anchor_epoch;
    std::memset(s.language, 0, sizeof(s.language));
    std::memcpy(s.language, frame.language.data(), std::min(frame.language.size(), sizeof(s.language) - 1));
    CompilerBarrier();
    seq.store(start + 2, std::memory_order_release);
}

void SifuBridge::PublishCollision(const CollisionInfo& info) {
    if (!manifest_) return;
    CollisionManifest& m = *manifest_;
    auto seq = Atomic(m.seq);
    const std::uint64_t start = seq.load(std::memory_order_relaxed) & ~1ull;
    seq.store(start + 1, std::memory_order_relaxed);
    CompilerBarrier();
    m.generation = info.generation;
    m.world_generation = info.world_generation;
    m.block_count = info.block_count;
    m.cell_count = info.cell_count;
    const auto copy = [](char* out, std::size_t capacity, const std::string& text) {
        const std::size_t length = std::min(text.size(), capacity - 1);
        std::memcpy(out, text.data(), length);
        out[length] = '\0';
    };
    copy(m.map, sizeof(m.map), info.map);
    copy(m.path, sizeof(m.path), info.path);
    CompilerBarrier();
    seq.store(start + 2, std::memory_order_release);
}

void SifuBridge::PublishEnemies(std::uint32_t world_generation, const std::vector<EnemyEntry>& entries) {
    if (!enemy_table_) return;
    EnemyTable& table = *enemy_table_;
    auto seq = Atomic(table.seq);
    const std::uint64_t start = seq.load(std::memory_order_relaxed) & ~1ull;
    seq.store(start + 1, std::memory_order_relaxed);
    CompilerBarrier();
    table.frame = frame_;
    table.world_generation = world_generation;
    table.count = static_cast<std::uint32_t>(std::min<std::size_t>(entries.size(), kMaxEnemies));
    std::copy_n(entries.begin(), table.count, table.entries);
    CompilerBarrier();
    seq.store(start + 2, std::memory_order_release);
}

void SifuBridge::ReadPeerSnapshot() {
    McSnapshot& s = *peer_snapshot_;
    for (int attempt = 0; attempt < kSeqlockAttempts; ++attempt) {
        const std::uint64_t before = Atomic(s.seq).load(std::memory_order_acquire);
        if (before & 1) continue;
        CompilerBarrier();
        McSnapshot copy;
        std::memcpy(&copy, &s, sizeof(copy));
        CompilerBarrier();
        if (Atomic(s.seq).load(std::memory_order_acquire) != before) continue;
        if (before == 0) return;  // never written
        peer_state_.valid = true;
        peer_state_.frame = copy.frame;
        peer_state_.last_sifu_frame = copy.last_sifu_frame;
        peer_state_.flags = copy.flags;
        peer_state_.perspective = copy.perspective;
        peer_state_.player_location = copy.player_location;
        peer_state_.player_rotation = copy.player_rotation;
        peer_state_.player_velocity = copy.player_velocity;
        peer_state_.camera_location = copy.camera_location;
        peer_state_.camera_rotation = copy.camera_rotation;
        peer_state_.camera_fov = copy.camera_fov;
        peer_state_.anchor_epoch = copy.anchor_epoch;
        peer_state_.collision_generation = copy.collision_generation;
        peer_state_.sifu_session = copy.sifu_session;
        peer_state_.world_generation = copy.world_generation;
        peer_state_.gui_input_ack = copy.gui_input_ack;
        return;
    }
}

void SifuBridge::ReadBlocks() {
    BlockTable& t = *block_table_;
    for (int attempt = 0; attempt < kSeqlockAttempts; ++attempt) {
        const std::uint64_t before = Atomic(t.seq).load(std::memory_order_acquire);
        if (before == block_seq_ || before == 0) return;  // unchanged, or never written
        if (before & 1) continue;
        CompilerBarrier();
        BlockList copy;
        copy.generation = t.generation;
        copy.seq = before;
        copy.palette_generation = t.palette_generation;
        const std::uint32_t count = std::min(t.count, kMaxBlocks);
        copy.map.assign(t.map, strnlen(t.map, sizeof(t.map)));
        copy.entries.assign(t.entries, t.entries + count);
        CompilerBarrier();
        if (Atomic(t.seq).load(std::memory_order_acquire) != before) continue;
        block_seq_ = before;
        blocks_ = std::move(copy);
        return;
    }
}

std::string SifuBridge::Describe() const {
    if (!self_) return "bridge disabled";
    const long long lag = peer_connected_ ? static_cast<long long>(frame_ - peer_state_.last_sifu_frame) : -1;
    return FormatString(
        "peer=%s pid=%u connects=%llu disconnects=%llu frame=%llu echo_lag=%lld peer_frame=%llu peer_flags=0x%X "
        "pings=%llu input=%llu sent=%llu dropped=%llu stale=%llu dup=%llu unknown=%llu",
        peer_connected_ ? "connected" : "absent", peer_pid_, static_cast<unsigned long long>(stats_.connects),
        static_cast<unsigned long long>(stats_.disconnects), static_cast<unsigned long long>(frame_), lag,
        static_cast<unsigned long long>(peer_state_.frame), peer_state_.flags,
        static_cast<unsigned long long>(stats_.pings), static_cast<unsigned long long>(stats_.input_events),
        static_cast<unsigned long long>(stats_.events_sent), static_cast<unsigned long long>(stats_.events_dropped_full),
        static_cast<unsigned long long>(stats_.events_stale), static_cast<unsigned long long>(stats_.events_duplicate),
        static_cast<unsigned long long>(stats_.events_unknown));
}

}  // namespace sifucraft::bridge
