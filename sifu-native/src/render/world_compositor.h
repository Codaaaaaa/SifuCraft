#pragma once
#include <d3d11_1.h>
#include "bridge/world_reader.h"
#include "render/com_ptr.h"
#include "render/world_scene.h"
#include "render/gpu_timer.h"
namespace sifucraft::render {
class WorldCompositor {
public:
    HRESULT Prepare(const world_image::Frame& frame);
    // Moves another compositor's CPU Prepare result in; GPU objects stay, buffers are recycled.
    void TakePrepared(WorldCompositor& from);
    HRESULT Draw(ID3D11Device* device, ID3D11Texture2D* backbuffer, const WorldScene& scene);
    void Reset();
    std::size_t pixels() const { return pixel_count_; }
    std::size_t patches() const { return points_.size(); }
    std::size_t missing_depth() const { return missing_depth_; }
    bool hasContent()const{return prepared_id_&&(!points_.empty()||constants_.fog_color[3]>0);}
    GpuTimer::Stats gpuStats()const{return gpu_timer_.stats();}
#ifdef SIFUCRAFT_GEOMETRY_DIAGNOSTICS
    std::uint64_t geometryHash()const{
        std::uint64_t hash=0xcbf29ce484222325ull;const auto* data=reinterpret_cast<const std::uint8_t*>(points_.data());
        for(std::size_t i=0;i<points_.size()*sizeof(Point);i++){hash^=data[i];hash*=0x100000001b3ull;}return hash;
    }
#endif
private:
    struct Point { float x, y, width, height; float corners[4]; };
    struct Corner { float depths[4]{}; std::uint32_t stamp = 0; };
    struct Distance { float value = 0; std::uint32_t stamp = 0; };
    struct Constants {
        float inverse_mc[16];
        float camera_scale[4];
        float image_target[4];
        float allocation[4];
        float fog_color[4],fog_ranges[4];
    } constants_{};
    void ResetGpu();
    HRESULT Initialize(ID3D11Device* device);
    std::vector<Point> points_;
    std::vector<Distance> source_distances_;
    std::vector<float> source_depths_;
    std::vector<Corner> source_corners_;
    std::vector<std::uint8_t> source_color_;
    std::uint32_t corner_stamp_ = 0;
    std::uint64_t prepared_id_ = 0;
    std::size_t missing_depth_ = 0;
    std::size_t pixel_count_ = 0;
    bool upload_ = false;
    UINT capacity_ = 0;
    UINT color_width_ = 0, color_height_ = 0;
    Com<ID3D11Device> device_;
    Com<ID3D11DeviceContext1> context_;
    Com<ID3D11VertexShader> vertex_;
    Com<ID3D11PixelShader> pixel_;
    Com<ID3D11VertexShader> fog_vertex_;
    Com<ID3D11PixelShader> fog_pixel_;
    Com<ID3D11InputLayout> layout_;
    Com<ID3D11Buffer> instances_, constants_buffer_;
    Com<ID3D11Texture2D> color_texture_;
    Com<ID3D11ShaderResourceView> color_view_;
    Com<ID3D11BlendState> blend_;
    Com<ID3D11RasterizerState> raster_;
    Com<ID3D11DepthStencilState> depth_;
    GpuTimer gpu_timer_;
};
}
