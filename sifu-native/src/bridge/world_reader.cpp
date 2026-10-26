#include "bridge/world_reader.h"
#include <atomic>
#include <cstdio>
#include <cstring>
namespace sifucraft::world_image {
static_assert(std::atomic_ref<std::uint32_t>::is_always_lock_free);
std::wstring MappingName(std::uint64_t mc, std::uint64_t sifu) {
    wchar_t name[96]{};
    std::swprintf(name, 96, L"Local\\SifuCraft.World.%016llX.%016llX", static_cast<unsigned long long>(mc), static_cast<unsigned long long>(sifu));
    return name;
}
bool Consume(std::uint8_t* memory, std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, std::uint32_t epoch,
             std::uint64_t now, std::uint64_t* last_id, Frame* frame) {
    if (!memory || !mc || !sifu || !world || !epoch || !last_id || !frame) return false;
    auto& header = *reinterpret_cast<Header*>(memory);
    if (std::atomic_ref(header.magic).load(std::memory_order_acquire) != kMagic || header.version != kVersion ||
        header.mapping_bytes != kMappingBytes || header.max_width != kMaxWidth || header.max_height != kMaxHeight ||
        header.slot_bytes != kSlotBytes || header.slots != kSlots || header.format != kRgba8Depth32 ||
        header.mc_session != mc || header.sifu_session != sifu || header.layout_hash != kLayoutHash) return false;
    FrameHeader* newest = nullptr;
    for (std::uint32_t i = 0; i < kSlots; ++i) {
        auto* slot = reinterpret_cast<FrameHeader*>(memory + sizeof(Header) + i * kSlotBytes);
        std::uint32_t expected = Ready;
        if (!std::atomic_ref(slot->state).compare_exchange_strong(expected, Reading, std::memory_order_acquire)) continue;
        if (!ValidFrame(*slot, now) || slot->world_generation != world || slot->anchor_epoch != epoch ||
            slot->frame_id <= *last_id || (newest && slot->frame_id <= newest->frame_id)) {
            std::atomic_ref(slot->state).store(Free, std::memory_order_release); continue;
        }
        if (newest) std::atomic_ref(newest->state).store(Free, std::memory_order_release);
        newest = slot;
    }
    if (!newest) return false;
    try {
        frame->header = *newest; frame->pixels.resize(newest->payload_bytes);
        std::memcpy(frame->pixels.data(), reinterpret_cast<const std::uint8_t*>(newest) + sizeof(FrameHeader), newest->payload_bytes);
        *last_id = newest->frame_id;
    } catch (...) { std::atomic_ref(newest->state).store(Free, std::memory_order_release); throw; }
    std::atomic_ref(newest->state).store(Free, std::memory_order_release); return true;
}
bool Reader::Read(std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, std::uint32_t epoch, std::uint64_t now, Frame* frame) {
    if (!mc || !sifu) { Close(); return false; }
    if (mc != mc_ || sifu != sifu_) {
        Close(); const auto name = MappingName(mc, sifu);
        if (!mapping_.Open(name.c_str(), kMappingBytes)) return false;
        mc_ = mc; sifu_ = sifu;
    }
    return Consume(mapping_.data(), mc, sifu, world, epoch, now, &last_id_, frame);
}
void Reader::Close() { mapping_.Close(); mc_ = sifu_ = last_id_ = 0; }
}
