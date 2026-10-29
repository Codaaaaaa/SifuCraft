#pragma once

#include <d3d11_1.h>
#include "bridge/overlay_reader.h"
#include "render/com_ptr.h"

namespace sifucraft::render {

// All methods run on the immediate context's thread. Owns no backbuffer/RTV between draws.
// PipelineState preserves host bindings without enabling D3D11 context-state emulation.
class Compositor {
public:
    HRESULT Draw(ID3D11Device* device, ID3D11Texture2D* backbuffer, const overlay::Frame& frame, bool upload);
    void Reset();
private:
    HRESULT Initialize(ID3D11Device* device);
    HRESULT Upload(const overlay::Frame& frame);
    Com<ID3D11Device> device_;
    Com<ID3D11DeviceContext1> context_;
    Com<ID3D11VertexShader> vertex_;
    Com<ID3D11PixelShader> normal_, invert_;
    Com<ID3D11BlendState> blend_, inverse_blend_;
    Com<ID3D11SamplerState> sampler_;
    Com<ID3D11RasterizerState> raster_;
    Com<ID3D11DepthStencilState> depth_;
    Com<ID3D11Buffer> constants_;
    Com<ID3D11Texture2D> texture_;
    Com<ID3D11ShaderResourceView> view_;
    UINT width_ = 0, height_ = 0;
};
}
