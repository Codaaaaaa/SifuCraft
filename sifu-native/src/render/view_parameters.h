#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>

namespace sifucraft::render {
struct ViewParameters {
    std::array<float, 16> world_to_clip{}, view_to_clip{}, view_to_clip_no_aa{};
    std::array<float, 4> inverse_device_z{}, jitter{};
    std::array<float, 3> camera_cm{};
    std::uint32_t x = 0, y = 0, width = 0, height = 0, buffer_width = 0, buffer_height = 0, frame = 0;
    float near_cm = 0;
};
// Validate an owned diagnostic readback against generated PDB fields and the
// actual depth allocation. A generic 8 KB constant buffer is not sufficient.
bool ParseViewParameters(std::span<const std::uint8_t> bytes, std::uint32_t depth_width,
                         std::uint32_t depth_height, ViewParameters& output);
}
