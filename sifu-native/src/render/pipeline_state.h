#pragma once
#include <array>
#include <d3d11_1.h>
#include "render/com_ptr.h"

namespace sifucraft::render {
// SwapDeviceContextState activates D3D11's mutable dispatch tables and erases
// binding observation hooks. Save/restore the actual bindings instead.
class PipelineState {
public:
    explicit PipelineState(ID3D11DeviceContext1* context);
    ~PipelineState();
    bool ready() const { return ready_; }
    PipelineState(const PipelineState&) = delete;
    PipelineState& operator=(const PipelineState&) = delete;
private:
    struct Classes {
        std::array<ID3D11ClassInstance*, 256> objects{};
        UINT count = 256;
        ~Classes() { for (UINT i = 0; i < count && i < objects.size(); ++i) if (objects[i]) objects[i]->Release(); }
    } vs_classes_, ps_classes_, gs_classes_, hs_classes_, ds_classes_;
    ID3D11DeviceContext1* context_;
    bool ready_ = false;
    Com<ID3D11VertexShader> vs_;
    Com<ID3D11PixelShader> ps_;
    Com<ID3D11GeometryShader> gs_;
    Com<ID3D11HullShader> hs_;
    Com<ID3D11DomainShader> ds_;
    std::array<std::array<Com<ID3D11ShaderResourceView>, 128>, 6> resources_;
    std::array<Com<ID3D11RenderTargetView>, 8> targets_;
    Com<ID3D11DepthStencilView> dsv_;
    std::array<Com<ID3D11UnorderedAccessView>, 64> om_uavs_, cs_uavs_;
    UINT uav_count_ = 8, target_count_ = 0;
    Com<ID3D11Buffer> vs_cb_, ps_cb_, vertex_buffer_;
    UINT vs_first_ = 0, vs_count_ = 0, ps_first_ = 0, ps_count_ = 0;
    UINT vertex_stride_ = 0, vertex_offset_ = 0;
    Com<ID3D11InputLayout> layout_;
    D3D11_PRIMITIVE_TOPOLOGY topology_{};
    Com<ID3D11SamplerState> sampler_;
    Com<ID3D11BlendState> blend_;
    FLOAT blend_factor_[4]{};
    UINT sample_mask_ = 0, stencil_ref_ = 0;
    Com<ID3D11DepthStencilState> depth_;
    Com<ID3D11RasterizerState> raster_;
    std::array<D3D11_VIEWPORT, 16> viewports_{};
    std::array<D3D11_RECT, 16> scissors_{};
    UINT viewport_count_ = 16, scissor_count_ = 16;
    Com<ID3D11Predicate> predicate_;
    BOOL predicate_value_ = FALSE;
};
}
