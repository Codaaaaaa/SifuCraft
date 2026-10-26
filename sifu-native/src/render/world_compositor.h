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
    std::size_t pixels() const { return points_.size(); }
    std::size_t missing_depth() const { return missing_depth_; }
private:
    struct Point { float x, y, z; std::uint32_t color; float corners[4]; };
    struct Constants {
        float inverse_mc[16];
        float camera_scale[4];
        float image_target[4];
        float allocation[4];
    } constants_{};
    void ResetGpu();
    HRESULT Initialize(ID3D11Device* device);
    std::vector<Point> points_;
    std::vector<std::uint32_t> source_indices_;
    std::vector<float> source_distances_;
    std::uint64_t prepared_id_ = 0;
    std::size_t missing_depth_ = 0;
    bool upload_ = false;
    UINT capacity_ = 0;
    Com<ID3D11Device> device_;
    Com<ID3D11DeviceContext1> context_;
    Com<ID3D11VertexShader> vertex_;
    Com<ID3D11PixelShader> pixel_;
    Com<ID3D11InputLayout> layout_;
    Com<ID3D11Buffer> instances_, constants_buffer_;
    Com<ID3D11BlendState> blend_;
    Com<ID3D11RasterizerState> raster_;
    Com<ID3D11DepthStencilState> depth_;
};
}
