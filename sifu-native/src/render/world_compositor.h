#pragma once
#include <d3d11_1.h>
#include "bridge/world_reader.h"
#include "render/com_ptr.h"
#include "render/world_scene.h"
namespace sifucraft::render {
class WorldCompositor {
public:
    HRESULT Prepare(const world_image::Frame& frame);
    HRESULT Draw(ID3D11Device* device, ID3D11Texture2D* backbuffer, const WorldScene& scene);
    void Reset();
    std::size_t pixels() const { return pixel_count_; }
    std::size_t patches() const { return points_.size(); }
    std::size_t missing_depth() const { return missing_depth_; }
private:
    struct Point { float x, y, width, height; float corners[4]; };
    struct Corner { float depths[4]{}; std::uint32_t stamp = 0; };
    struct Distance { float value = 0; std::uint32_t stamp = 0; };
    struct Constants {
        float inverse_mc[16];
        float camera_scale[4];
        float image_target[4];
        float allocation[4];
    } constants_{};
    void ResetGpu();
    HRESULT Initialize(ID3D11Device* device);
    std::vector<Point> points_;
    std::vector<Distance> source_distances_;
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
    Com<ID3D11InputLayout> layout_;
    Com<ID3D11Buffer> instances_, constants_buffer_;
    Com<ID3D11Texture2D> color_texture_;
    Com<ID3D11ShaderResourceView> color_view_;
    Com<ID3D11BlendState> blend_;
    Com<ID3D11RasterizerState> raster_;
    Com<ID3D11DepthStencilState> depth_;
};
}
