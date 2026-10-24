// Optional image transport, independent of the v12 gameplay bridge. Single producer (Minecraft),
// single consumer (renderer or offline probe). A mapping belongs to one pair of bridge sessions.
#pragma once

#include <cstddef>
#include <cstdint>

namespace sifucraft::overlay {

inline constexpr std::uint32_t kMagic = 0x564F4653; // SFOV
inline constexpr std::uint32_t kVersion = 1;
inline constexpr std::uint32_t kMaxWidth = 1920;
inline constexpr std::uint32_t kMaxHeight = 1080;
inline constexpr std::uint32_t kSlots = 3;
inline constexpr std::uint32_t kPixels = kMaxWidth * kMaxHeight * 4;
inline constexpr std::uint32_t kSlotBytes = 64 + kPixels;
inline constexpr std::uint32_t kMappingBytes = 64 + kSlots * kSlotBytes;
inline constexpr std::uint32_t kRgba8 = 1;
inline constexpr std::uint32_t kPremultiplied = 1;
inline constexpr std::uint32_t kBottomUp = 2;

// State transitions use interprocess atomics. The writer may replace Ready, never Reading.
// The reader must claim Ready -> Reading BEFORE inspecting any slot metadata or pixels.
enum State : std::uint32_t { Free = 0, Writing = 1, Ready = 2, Reading = 3 };

struct Header {
    std::uint32_t magic, version, mapping_bytes, max_width, max_height, slot_bytes, slots, format;
    std::uint64_t mc_session, sifu_session, layout_hash, reserved;
};

struct FrameHeader {
    std::uint32_t state, width, height, stride;
    std::uint64_t frame_id;
    std::uint32_t world_generation, anchor_epoch, flags, payload_bytes;
    std::uint8_t reserved[24];
};

static_assert(sizeof(Header) == 64 && sizeof(FrameHeader) == 64);
static_assert(offsetof(FrameHeader, frame_id) == 16 && offsetof(FrameHeader, payload_bytes) == 36);

// Same ordered list and FNV-1a calculation in OverlayProtocol.java.
inline constexpr std::uint32_t kLayout[] = {
    kMagic, kVersion, kMaxWidth, kMaxHeight, kSlots, kSlotBytes, kMappingBytes, kRgba8,
    sizeof(Header), sizeof(FrameHeader), 0, 4, 8, 12, 16, 24, 28, 32, 36,
    Free, Writing, Ready, Reading, kPremultiplied, kBottomUp
};
inline constexpr std::uint64_t kLayoutHash = [] {
    std::uint64_t hash = 14695981039346656037ull;
    for (const auto value : kLayout) for (int shift = 0; shift < 32; shift += 8) {
        hash ^= (value >> shift) & 255u;
        hash *= 1099511628211ull;
    }
    return hash;
}();

inline bool ValidFrame(const FrameHeader& frame) {
    return frame.width > 0 && frame.width <= kMaxWidth && frame.height > 0 && frame.height <= kMaxHeight &&
        frame.stride == frame.width * 4 && frame.payload_bytes == frame.stride * frame.height &&
        frame.frame_id != 0 && frame.world_generation != 0 && frame.anchor_epoch != 0 &&
        (frame.flags & kPremultiplied) && !(frame.flags & ~(kPremultiplied | kBottomUp));
}

} // namespace sifucraft::overlay
