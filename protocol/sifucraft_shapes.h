// Independent, complete collision-section snapshots. MC owns slots; Native owns acknowledgement.
#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

namespace sifucraft::shape_data {
inline constexpr std::uint32_t kMagic = 0x48534653, kVersion = 2;
inline constexpr std::uint32_t kSlots = 2, kPayloadBytes = 8 * 1024 * 1024;
inline constexpr std::uint32_t kSlotBytes = 192 + kPayloadBytes, kMappingBytes = 128 + kSlots * kSlotBytes;
inline constexpr std::uint32_t kMaxSections = 8192, kMaxBoxes = 262144;
inline constexpr std::uint32_t kMaxPieces = 65535;
enum State : std::uint32_t { Free, Writing, Ready, Reading };
struct Header {
    std::uint32_t magic, version, mapping_bytes, slot_bytes;
    std::uint64_t mc_session, sifu_session, layout_hash;
    std::uint64_t ack_seq, applied_revision;
    std::uint32_t world, ready;
    std::uint64_t map_hash, save_key, ack_ms;
    std::uint64_t reserved[5];
};
struct FrameHeader {
    std::uint32_t state, payload_bytes;
    std::uint64_t revision, captured_ms;
    std::uint32_t world, sections;
    std::uint64_t save_key;
    char map[128];
    std::uint64_t reserved[3];
};
struct Section {
    std::int32_t x, y, z; // Unreal axes in blocks, relative to this map's origin
    std::uint32_t boxes;
    std::uint64_t revision, hash;
};
struct Box {
    float min[3], max[3]; // Unreal axes, centimetres relative to section origin
    std::uint64_t source_key; // (MC section-local block << 32) | (shape piece + 1)
};
static_assert(sizeof(Header) == 128 && sizeof(FrameHeader) == 192 && sizeof(Section) == 32 && sizeof(Box) == 32);
static_assert(offsetof(Box,source_key)==24);
static_assert(offsetof(Header, ack_seq) == 40 && offsetof(Header, ack_ms) == 80 && offsetof(FrameHeader, map) == 40);
inline constexpr std::uint32_t kLayout[] = {kMagic,kVersion,kMappingBytes,kSlotBytes,kSlots,kMaxSections,kMaxBoxes,
    sizeof(Header),sizeof(FrameHeader),sizeof(Section),sizeof(Box),0,4,8,16,24,32,40,48,56,60,64,72,80,128,192,
    Free,Writing,Ready,Reading,24,kMaxPieces};
inline constexpr std::uint64_t kLayoutHash = [] {
    std::uint64_t h = 14695981039346656037ull;
    for (auto v : kLayout) for (int shift = 0; shift < 32; shift += 8) { h ^= (v >> shift) & 255u; h *= 1099511628211ull; }
    return h;
}();
inline std::uint64_t Hash(const void* data, std::size_t size) {
    std::uint64_t h = 14695981039346656037ull;
    const auto* p = static_cast<const std::uint8_t*>(data);
    for (std::size_t i = 0; i < size; ++i) { h ^= p[i]; h *= 1099511628211ull; }
    return h;
}
inline bool ValidBox(const Box& b) {
    const auto piece=static_cast<std::uint32_t>(b.source_key);
    if((b.source_key>>32)>=4096||!piece||piece>kMaxPieces)return false;
    for (int i = 0; i < 3; ++i) if (!std::isfinite(b.min[i]) || !std::isfinite(b.max[i]) ||
        b.min[i] < -3200 || b.max[i] > 4800 || b.min[i] >= b.max[i]) return false;
    return true;
}
}
