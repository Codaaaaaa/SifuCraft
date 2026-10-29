#include "render/view_parameters.h"
#include <cmath>
#include <cstring>
#include "generated/sifu_build.g.h"

namespace sifucraft::render {
int SelectViewParameters(std::span<const ViewParameters> views, std::array<float,4> raster, bool pixel_stage) {
    if(views.empty())return -1;
    int selected=-1;
    if(pixel_stage&&raster[2]>0&&raster[3]>0) {
        for(std::size_t i=0;i<views.size();++i){const auto& v=views[i];
            if(v.x!=raster[0]||v.y!=raster[1]||v.width!=raster[2]||v.height!=raster[3])continue;
            if(selected>=0&&views[selected]!=v)return -1;
            selected=static_cast<int>(i);
        }
        if(selected>=0)return selected;
    }
    for(std::size_t i=1;i<views.size();++i)if(views[i]!=views[0])return -1;
    return 0;
}
bool ParseViewParameters(std::span<const std::uint8_t> bytes, std::uint32_t depth_width,
                         std::uint32_t depth_height, ViewParameters& output) {
    namespace fields = gen::off::FViewUniformShaderParameters;
    output = {};
    auto read = [&]<typename T>(std::size_t offset, T& result) {
        if (offset > bytes.size() || sizeof(T) > bytes.size() - offset) return false;
        std::memcpy(&result, bytes.data() + offset, sizeof(T)); return true;
    };
    ViewParameters candidate;
    std::array<float, 4> origin{}, size{}, buffer{};
    if (!read(fields::WorldToClip, candidate.world_to_clip) || !read(fields::ViewToClip, candidate.view_to_clip) ||
        !read(fields::ViewToClipNoAA, candidate.view_to_clip_no_aa) || !read(fields::InvDeviceZToWorldZTransform, candidate.inverse_device_z) ||
        !read(fields::WorldCameraOrigin, candidate.camera_cm) || !read(fields::TemporalAAJitter, candidate.jitter) ||
        !read(fields::ViewRectMin, origin) || !read(fields::ViewSizeAndInvSize, size) ||
        !read(fields::BufferSizeAndInvSize, buffer) || !read(fields::NearPlane, candidate.near_cm) || !read(fields::FrameNumber, candidate.frame)) return false;
    auto finite = [](const auto& values) { for (float value : values) if (!std::isfinite(value)) return false; return true; };
    auto integer = [](float value) { return std::isfinite(value) && value >= 0 && value <= 8192 && std::floor(value) == value; };
    if (!finite(candidate.world_to_clip) || !finite(candidate.view_to_clip) || !finite(candidate.view_to_clip_no_aa) ||
        !finite(candidate.inverse_device_z) || !finite(candidate.camera_cm) || !finite(candidate.jitter) || !finite(size) || !finite(buffer) ||
        !integer(origin[0]) || !integer(origin[1]) || !integer(size[0]) || !integer(size[1]) ||
        !integer(buffer[0]) || !integer(buffer[1]) || !size[0] || !size[1] ||
        buffer[0] != depth_width || buffer[1] != depth_height || origin[0] + size[0] > buffer[0] || origin[1] + size[1] > buffer[1] ||
        std::fabs(size[0] * size[2] - 1) > .0001f || std::fabs(size[1] * size[3] - 1) > .0001f ||
        std::fabs(buffer[0] * buffer[2] - 1) > .0001f || std::fabs(buffer[1] * buffer[3] - 1) > .0001f ||
        !std::isfinite(candidate.near_cm) || candidate.near_cm <= 0 || candidate.near_cm > 10000 ||
        std::fabs(candidate.view_to_clip_no_aa[0]) < .0001f || std::fabs(candidate.view_to_clip_no_aa[5]) < .0001f ||
        candidate.inverse_device_z[2] <= 0) return false;
    candidate.x = static_cast<std::uint32_t>(origin[0]); candidate.y = static_cast<std::uint32_t>(origin[1]);
    candidate.width = static_cast<std::uint32_t>(size[0]); candidate.height = static_cast<std::uint32_t>(size[1]);
    candidate.buffer_width = depth_width; candidate.buffer_height = depth_height;
    output = candidate; return true;
}
}
