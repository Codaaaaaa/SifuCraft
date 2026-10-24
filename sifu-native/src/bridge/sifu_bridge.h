#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "bridge/shared_mapping.h"
#include "sifucraft_protocol.h"

namespace sifucraft::bridge {

// What Sifu publishes every tick (Unreal space).
struct PlayerFrame {
    std::uint32_t world_generation = 0;
    std::uint32_t flags = 0;  // protocol::kSnapshotHas*
    protocol::Vec3 player_location{};
    protocol::Rot3 player_rotation{};
    protocol::Vec3 player_velocity{};
    protocol::Vec3 camera_location{};
    protocol::Rot3 camera_rotation{};
    float camera_fov = 0.0f;
    float health = 0.0f;
    float max_health = 0.0f;
    std::string world_path;
    protocol::InputOwner input_owner = protocol::InputOwner::Sifu;
    double mouse_dx = 0.0;  // running totals routed to Minecraft
    double mouse_dy = 0.0;
    double scroll = 0.0;
    float capsule_half_height = 0.0f;
    std::uint32_t anchor_epoch = 0;
};

// Newest consistent copy of the Minecraft snapshot.
struct McState {
    bool valid = false;
    std::uint64_t frame = 0;
    std::uint64_t last_sifu_frame = 0;
    std::uint32_t flags = 0;  // protocol::kMc*
    std::uint32_t perspective = 0;
    protocol::Vec3 player_location{};
    protocol::Rot3 player_rotation{};
    protocol::Vec3 player_velocity{};
    protocol::Vec3 camera_location{};
    protocol::Rot3 camera_rotation{};
    float camera_fov = 0.0f;
    std::uint32_t anchor_epoch = 0;  // the SifuSnapshot.anchor_epoch these fields are anchored to
    std::uint32_t collision_generation = 0;  // the CollisionManifest.generation Minecraft has loaded
};

// The voxel grid Sifu currently publishes (protocol::CollisionManifest).
struct CollisionInfo {
    std::uint32_t generation = 0;
    std::uint32_t world_generation = 0;
    std::uint32_t block_count = 0;
    std::uint64_t cell_count = 0;
    std::string map;
    std::string path;  // UTF-8
};

// The blocks Minecraft has placed in the map Sifu shows (protocol::BlockTable).
struct BlockList {
    std::uint32_t generation = 0;  // 0 = Minecraft has not published a table yet
    std::uint64_t seq = 0;         // the table's sequence when read: changes with every write
    std::uint32_t palette_generation = 0;
    std::string map;
    std::vector<protocol::BlockEntry> entries;
};

struct BridgeStats {
    std::uint64_t ticks = 0;
    std::uint64_t connects = 0;
    std::uint64_t disconnects = 0;
    std::uint64_t events_received = 0;
    std::uint64_t events_sent = 0;
    std::uint64_t events_dropped_full = 0;  // our outgoing ring was full
    std::uint64_t events_stale = 0;         // addressed to/from another session
    std::uint64_t events_duplicate = 0;     // id not newer than the last one seen
    std::uint64_t events_unknown = 0;
    std::uint64_t pings = 0;
    std::uint64_t input_events = 0;
};

// The Sifu endpoint of the shared-memory bridge. Single-threaded: call everything from one thread,
// except MarkClosing(), which only performs one atomic store.
class SifuBridge {
public:
    bool Start();
    void Tick(const PlayerFrame& frame, std::uint64_t now_ms);
    void MarkClosing();

    // Input routed to Minecraft (type Key or MouseButton). Dropped while no peer is connected.
    bool SendInput(protocol::EventType type, std::int32_t code, std::int32_t action, std::int32_t modifiers);
    bool SendOwnerChanged(protocol::InputOwner owner);
    // Publishes the manifest of the current collision grid; call when it changes.
    void PublishCollision(const CollisionInfo& info);
    // Replaces the whole nearby-enemy table (Sifu owns enemy state). Runtime thread only.
    void PublishEnemies(std::uint32_t world_generation, const std::vector<protocol::EnemyEntry>& entries);
    const McState& peer_state() const { return peer_state_; }
    // The latest consistent block table; kept while Minecraft is away (its world keeps the blocks).
    const BlockList& blocks() const { return blocks_; }

    bool started() const { return self_ != nullptr; }
    bool peer_connected() const { return peer_connected_; }
    std::uint32_t peer_pid() const { return peer_pid_; }
    std::uint64_t session() const { return session_; }
    std::uint64_t frame() const { return frame_; }
    const BridgeStats& stats() const { return stats_; }

    // One-line summary for the log.
    std::string Describe() const;

private:
    void TrackPeer(std::uint64_t now_ms);
    void Disconnect(const char* reason, std::uint64_t now_ms);
    void PumpEvents();
    bool Send(protocol::EventType type, const void* payload, std::uint32_t size);
    void Publish(const PlayerFrame& frame);
    void ReadPeerSnapshot();
    void ReadBlocks();

    SharedMapping mapping_;
    protocol::ProtocolHeader* header_ = nullptr;
    protocol::EndpointStatus* self_ = nullptr;
    protocol::EndpointStatus* peer_ = nullptr;
    protocol::SifuSnapshot* snapshot_ = nullptr;
    protocol::McSnapshot* peer_snapshot_ = nullptr;
    protocol::CollisionManifest* manifest_ = nullptr;
    protocol::BlockTable* block_table_ = nullptr;
    protocol::EnemyTable* enemy_table_ = nullptr;
    protocol::EventRing* outgoing_ = nullptr;
    protocol::EventRing* incoming_ = nullptr;

    std::uint64_t session_ = 0;
    std::uint64_t heartbeat_ = 0;
    std::uint64_t frame_ = 0;
    std::uint64_t next_event_id_ = 1;

    bool peer_connected_ = false;
    bool peer_incompatible_ = false;
    std::uint32_t peer_pid_ = 0;
    std::uint64_t peer_session_ = 0;
    std::uint64_t peer_heartbeat_ = 0;
    std::uint64_t peer_heartbeat_ms_ = 0;  // when the peer heartbeat last advanced (0 = not yet)
    std::uint64_t peer_connected_ms_ = 0;
    std::uint64_t peer_last_event_id_ = 0;
    std::uint64_t last_tick_ms_ = 0;

    McState peer_state_;
    std::uint64_t block_seq_ = 0;
    BlockList blocks_;
    BridgeStats stats_;
};

}  // namespace sifucraft::bridge
