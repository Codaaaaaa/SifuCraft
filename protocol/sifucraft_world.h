// Independent pre-HUD world color + device-depth transport. Never shares HUD slots.
#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace sifucraft::world_image {
inline constexpr std::uint32_t kMagic = 0x57574653; // SFWW
inline constexpr std::uint32_t kVersion = 2, kMaxWidth = 1920, kMaxHeight = 1080, kSlots = 3;
inline constexpr std::uint32_t kFogNone=0,kFogWater=1,kFogLava=2;
inline constexpr std::uint32_t kPlaneBytes = kMaxWidth * kMaxHeight * 4;
inline constexpr std::uint32_t kSlotBytes = 256 + 2 * kPlaneBytes;
inline constexpr std::uint32_t kMappingBytes = 64 + kSlots * kSlotBytes;
inline constexpr std::uint32_t kRgba8Depth32 = 1, kPremultiplied = 1, kBottomUp = 2, kZeroToOne = 4;
enum State : std::uint32_t { Free, Writing, Ready, Reading };
struct Header {
    std::uint32_t magic, version, mapping_bytes, max_width, max_height, slot_bytes, slots, format;
    std::uint64_t mc_session, sifu_session, layout_hash, reserved;
};
struct FrameHeader {
    std::uint32_t state, width, height, stride;
    std::uint64_t frame_id, captured_ms; // Windows GetTickCount64 in both processes
    std::uint32_t world_generation, anchor_epoch, flags, payload_bytes;
    float camera_cm[3], cm_per_block;
    float projection[16], view_rotation[16]; // JOML column-major, relative MC world -> camera
    std::uint64_t sifu_frame;
    std::uint32_t fog_kind;
    float fog_color[4],fog_ranges[4]; // environmental start/end, render-distance start/end, metres
    std::uint32_t reserved[5];
};
static_assert(sizeof(Header) == 64 && sizeof(FrameHeader) == 256);
static_assert(offsetof(FrameHeader, captured_ms) == 24 && offsetof(FrameHeader, camera_cm) == 48 &&
              offsetof(FrameHeader, projection) == 64 && offsetof(FrameHeader, view_rotation) == 128 &&
              offsetof(FrameHeader, sifu_frame) == 192 && offsetof(FrameHeader,fog_kind)==200 &&
              offsetof(FrameHeader,fog_color)==204 && offsetof(FrameHeader,fog_ranges)==220);
inline constexpr std::uint32_t kLayout[] = {
    kMagic, kVersion, kMaxWidth, kMaxHeight, kSlots, kSlotBytes, kMappingBytes, kRgba8Depth32,
    sizeof(Header), sizeof(FrameHeader), 0, 4, 8, 12, 16, 24, 32, 36, 40, 44, 48, 60, 64, 128, 192,
    Free, Writing, Ready, Reading, kPremultiplied, kBottomUp, kZeroToOne,200,204,220,236,kFogNone,kFogWater,kFogLava
};
inline constexpr std::uint64_t kLayoutHash = [] {
    std::uint64_t hash = 14695981039346656037ull;
    for (auto value : kLayout) for (int shift = 0; shift < 32; shift += 8) { hash ^= (value >> shift) & 255u; hash *= 1099511628211ull; }
    return hash;
}();
inline bool ValidFrame(const FrameHeader& f, std::uint64_t now) {
    if (!f.width || f.width > kMaxWidth || !f.height || f.height > kMaxHeight || f.stride != f.width * 4 ||
        f.payload_bytes != f.stride * f.height * 2 || !f.frame_id || !f.captured_ms || now < f.captured_ms || now - f.captured_ms > 250 ||
        !f.world_generation || !f.anchor_epoch || !f.sifu_frame || f.cm_per_block != 100 ||
        !(f.flags & kPremultiplied) || (f.flags & ~(kPremultiplied | kBottomUp | kZeroToOne))) return false;
    for (float v : f.camera_cm) if (!std::isfinite(v)) return false;
    for (float v : f.projection) if (!std::isfinite(v)) return false;
    for (float v : f.view_rotation) if (!std::isfinite(v)) return false;
    if(f.fog_kind>kFogLava)return false;
    for(float v:f.fog_color)if(!std::isfinite(v)||v<0||v>1||(!f.fog_kind&&v!=0))return false;
    for(float v:f.fog_ranges)if(!std::isfinite(v)||v < -1024||v>1e6f||(!f.fog_kind&&v!=0))return false;
    if(f.fog_kind&&(f.fog_ranges[1]<=f.fog_ranges[0]||f.fog_ranges[3]<=f.fog_ranges[2]))return false;
    for(auto v:f.reserved)if(v)return false;
    // A finite all-zero matrix must not masquerade as a usable camera.
    return std::fabs(f.projection[0]) > .0001f && std::fabs(f.projection[5]) > .0001f &&
        std::fabs(f.projection[11]) > .0001f && std::fabs(f.view_rotation[15] - 1) < .0001f;
}
}
