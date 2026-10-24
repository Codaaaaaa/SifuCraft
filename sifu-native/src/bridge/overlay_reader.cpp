#include "bridge/overlay_reader.h"

#include <atomic>
#include <cstdio>
#include <cstring>

namespace sifucraft::overlay {

static_assert(std::atomic_ref<std::uint32_t>::is_always_lock_free);

std::wstring MappingName(std::uint64_t mc_session, std::uint64_t sifu_session) {
    wchar_t name[96]{};
    std::swprintf(name, 96, L"Local\\SifuCraft.Overlay.%016llX.%016llX",
                  static_cast<unsigned long long>(mc_session), static_cast<unsigned long long>(sifu_session));
    return name;
}

bool Consume(std::uint8_t* memory, std::uint64_t mc_session, std::uint64_t sifu_session,
             std::uint32_t world, std::uint32_t epoch, std::uint64_t* last_id, Frame* result) {
    if (!memory || !mc_session || !sifu_session || !world || !epoch) return false;
    auto& global = *reinterpret_cast<Header*>(memory);
    if (std::atomic_ref(global.magic).load(std::memory_order_acquire) != kMagic) return false;
    if (global.version != kVersion || global.mapping_bytes != kMappingBytes || global.layout_hash != kLayoutHash ||
        global.max_width != kMaxWidth || global.max_height != kMaxHeight || global.slot_bytes != kSlotBytes ||
        global.slots != kSlots || global.format != kRgba8 ||
        global.mc_session != mc_session || global.sifu_session != sifu_session) return false;

    FrameHeader* newest = nullptr;
    // Retain at most one claimed slot; the producer is always free to use the other two.
    for (std::uint32_t i = 0; i < kSlots; ++i) {
        auto* slot = reinterpret_cast<FrameHeader*>(memory + sizeof(Header) + i * kSlotBytes);
        std::uint32_t expected = Ready;
        if (!std::atomic_ref(slot->state).compare_exchange_strong(expected, Reading, std::memory_order_acquire)) continue;
        const bool valid = ValidFrame(*slot) && slot->world_generation == world && slot->anchor_epoch == epoch &&
            slot->frame_id > *last_id && (!newest || slot->frame_id > newest->frame_id);
        if (!valid) {
            std::atomic_ref(slot->state).store(Free, std::memory_order_release);
            continue;
        }
        if (newest) std::atomic_ref(newest->state).store(Free, std::memory_order_release);
        newest = slot;
    }
    if (!newest) return false;
    try {
        result->pixels.resize(newest->payload_bytes);
        result->header = *newest;
        std::memcpy(result->pixels.data(), reinterpret_cast<const std::uint8_t*>(newest) + sizeof(FrameHeader), newest->payload_bytes);
        *last_id = newest->frame_id;
    } catch (...) {
        std::atomic_ref(newest->state).store(Free, std::memory_order_release);
        throw;
    }
    std::atomic_ref(newest->state).store(Free, std::memory_order_release);
    return true;
}

bool Reader::Read(std::uint64_t mc_session, std::uint64_t sifu_session, std::uint32_t world,
                  std::uint32_t epoch, Frame* frame) {
    if (!mc_session || !sifu_session) { Close(); return false; }
    if (mc_session != mc_session_ || sifu_session != sifu_session_) {
        Close();
        const auto name = MappingName(mc_session, sifu_session);
        if (!mapping_.Open(name.c_str(), kMappingBytes)) return false;
        mc_session_ = mc_session; sifu_session_ = sifu_session;
    }
    return Consume(mapping_.data(), mc_session, sifu_session, world, epoch, &last_id_, frame);
}

void Reader::Close() {
    mapping_.Close();
    mc_session_ = sifu_session_ = last_id_ = 0;
}

} // namespace sifucraft::overlay
