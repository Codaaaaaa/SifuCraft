#include "render/compositor.h"
#include "render/pipeline_state.h"

#include <d3dcompiler.h>
#include <cstring>
#include <cstdio>

namespace sifucraft::render {
namespace {
constexpr char kShader[] = R"hlsl(
Texture2D image : register(t0);
SamplerState nearestPixel : register(s0);
cbuffer Frame : register(b0) { float4 inverseArea; float flip; float3 padding; };
struct Vertex { float4 position : SV_Position; float2 uv : TEXCOORD0; };
Vertex vertexMain(uint index : SV_VertexID) {
    Vertex vertex;
    vertex.uv = float2(index == 2 ? 2 : 0, index == 1 ? 2 : 0);
    vertex.position = float4(vertex.uv.x * 2 - 1, 1 - vertex.uv.y * 2, 0, 1);
    return vertex;
}
bool inversePixel(float2 uv) {
    return all(uv >= inverseArea.xy) && all(uv < inverseArea.zw);
}
float4 readImage(float2 uv) {
    if (flip > 0) uv.y = 1 - uv.y;
    return image.Sample(nearestPixel, uv);
}
float4 normalPixel(Vertex vertex) : SV_Target {
    float4 pixel = readImage(vertex.uv);
    // Inverse GUI blending preserves the alpha of the hand underneath. Only a pure
    // crosshair contribution on transparent background needs the host inverse pass.
    if (inversePixel(vertex.uv) && pixel.a == 0) discard;
    return pixel;
}
float4 inversePixelShader(Vertex vertex) : SV_Target {
    if (!inversePixel(vertex.uv)) discard;
    float4 pixel = readImage(vertex.uv);
    if (pixel.a != 0) discard;
    return float4(pixel.rgb, 0);
}
)hlsl";

HRESULT Compile(const char* entry, const char* profile, Com<ID3DBlob>& result) {
    Com<ID3DBlob> error;
    const auto hr = D3DCompile(kShader, sizeof(kShader) - 1, "sifucraft_hud", nullptr, nullptr, entry, profile,
                              D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, result.put(), error.put());
    if (FAILED(hr) && error) std::fprintf(stderr, "HUD shader %s: %s\n", entry, static_cast<const char*>(error->GetBufferPointer()));
    return hr;
}

}

void Compositor::Reset() {
    view_.reset(); texture_.reset(); constants_.reset(); depth_.reset(); raster_.reset();
    sampler_.reset(); inverse_blend_.reset(); blend_.reset(); invert_.reset(); normal_.reset();
    vertex_.reset(); context_.reset(); device_.reset(); width_ = height_ = 0;
}

HRESULT Compositor::Initialize(ID3D11Device* device) {
    if (device_.get() == device && constants_) return S_OK;
    Reset();
    device->AddRef(); device_.reset(device);
    HRESULT hr;
    Com<ID3D11DeviceContext> context;
    device->GetImmediateContext(context.put());
    hr = context->QueryInterface(__uuidof(ID3D11DeviceContext1), reinterpret_cast<void**>(context_.put()));
    if (FAILED(hr)) return hr;
    Com<ID3DBlob> blob;
    if (FAILED(hr = Compile("vertexMain", "vs_5_0", blob))) return hr;
    if (FAILED(hr = device->CreateVertexShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr, vertex_.put()))) return hr;
    if (FAILED(hr = Compile("normalPixel", "ps_5_0", blob))) return hr;
    if (FAILED(hr = device->CreatePixelShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr, normal_.put()))) return hr;
    if (FAILED(hr = Compile("inversePixelShader", "ps_5_0", blob))) return hr;
    if (FAILED(hr = device->CreatePixelShader(blob->GetBufferPointer(), blob->GetBufferSize(), nullptr, invert_.put()))) return hr;
    D3D11_BLEND_DESC blend{};
    auto& rt = blend.RenderTarget[0];
    rt.BlendEnable = TRUE;
    rt.SrcBlend = D3D11_BLEND_ONE; rt.DestBlend = D3D11_BLEND_INV_SRC_ALPHA; rt.BlendOp = D3D11_BLEND_OP_ADD;
    rt.SrcBlendAlpha = D3D11_BLEND_ZERO; rt.DestBlendAlpha = D3D11_BLEND_ONE; rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
    rt.RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    if (FAILED(hr = device->CreateBlendState(&blend, blend_.put()))) return hr;
    rt.SrcBlend = D3D11_BLEND_INV_DEST_COLOR; rt.DestBlend = D3D11_BLEND_INV_SRC_COLOR;
    if (FAILED(hr = device->CreateBlendState(&blend, inverse_blend_.put()))) return hr;
    D3D11_SAMPLER_DESC sampler{};
    sampler.Filter = D3D11_FILTER_MIN_MAG_MIP_POINT;
    sampler.AddressU = sampler.AddressV = sampler.AddressW = D3D11_TEXTURE_ADDRESS_CLAMP;
    sampler.MaxLOD = D3D11_FLOAT32_MAX;
    if (FAILED(hr = device->CreateSamplerState(&sampler, sampler_.put()))) return hr;
    D3D11_RASTERIZER_DESC raster{};
    raster.FillMode = D3D11_FILL_SOLID; raster.CullMode = D3D11_CULL_NONE; raster.DepthClipEnable = TRUE;
    if (FAILED(hr = device->CreateRasterizerState(&raster, raster_.put()))) return hr;
    D3D11_DEPTH_STENCIL_DESC depth{};
    depth.DepthEnable = FALSE; depth.DepthFunc = D3D11_COMPARISON_ALWAYS;
    if (FAILED(hr = device->CreateDepthStencilState(&depth, depth_.put()))) return hr;
    D3D11_BUFFER_DESC constants{};
    constants.ByteWidth = 32; constants.Usage = D3D11_USAGE_DYNAMIC;
    constants.BindFlags = D3D11_BIND_CONSTANT_BUFFER; constants.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    return device->CreateBuffer(&constants, nullptr, constants_.put());
}

HRESULT Compositor::Upload(const overlay::Frame& frame) {
    const auto& h = frame.header;
    if (width_ != h.width || height_ != h.height || !texture_) {
        view_.reset(); texture_.reset(); width_ = height_ = 0;
        D3D11_TEXTURE2D_DESC desc{};
        desc.Width = h.width; desc.Height = h.height; desc.MipLevels = desc.ArraySize = desc.SampleDesc.Count = 1;
        desc.Format = DXGI_FORMAT_R8G8B8A8_UNORM; desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_SHADER_RESOURCE; desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        HRESULT hr = device_->CreateTexture2D(&desc, nullptr, texture_.put());
        if (FAILED(hr)) return hr;
        if (FAILED(hr = device_->CreateShaderResourceView(texture_.get(), nullptr, view_.put()))) return hr;
        width_ = h.width; height_ = h.height;
    }
    D3D11_MAPPED_SUBRESOURCE mapped{};
    HRESULT hr = context_->Map(texture_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped);
    if (FAILED(hr)) return hr;
    for (UINT y = 0; y < height_; ++y) {
        std::memcpy(static_cast<std::uint8_t*>(mapped.pData) + y * mapped.RowPitch,
                    frame.pixels.data() + y * h.stride, h.stride);
    }
    context_->Unmap(texture_.get(), 0);
    return S_OK;
}

HRESULT Compositor::Draw(ID3D11Device* device, ID3D11Texture2D* backbuffer, const overlay::Frame& frame, bool upload) {
    if (!device || !backbuffer || !overlay::ValidFrame(frame.header) || frame.pixels.size() != frame.header.payload_bytes) return E_INVALIDARG;
    const bool new_device = device_.get() != device;
    HRESULT hr = Initialize(device);
    if (FAILED(hr)) { Reset(); return hr; }
    if (upload || new_device || !texture_) if (FAILED(hr = Upload(frame))) return hr;
    D3D11_TEXTURE2D_DESC desc{};
    backbuffer->GetDesc(&desc);
    if (desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM && desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM &&
        desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM_SRGB && desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM_SRGB &&
        desc.Format != DXGI_FORMAT_R10G10B10A2_UNORM) return DXGI_ERROR_UNSUPPORTED;
    Com<ID3D11RenderTargetView> target;
    if (FAILED(hr = device->CreateRenderTargetView(backbuffer, nullptr, target.put()))) return hr;
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(hr = context_->Map(constants_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) return hr;
    const auto& h = frame.header;
    const float params[8] = {float(h.invert_left) / h.width, float(h.invert_top) / h.height,
        float(h.invert_right) / h.width, float(h.invert_bottom) / h.height,
        (h.flags & overlay::kBottomUp) ? 1.0f : 0.0f, 0, 0, 0};
    std::memcpy(mapped.pData, params, sizeof(params));
    context_->Unmap(constants_.get(), 0);
    PipelineState isolate(context_.get());
    if (!isolate.ready()) return DXGI_ERROR_UNSUPPORTED;
    auto* rtv = target.get();
    context_->OMSetRenderTargets(1, &rtv, nullptr);
    context_->OMSetBlendState(blend_.get(), nullptr, 0xffffffffu);
    context_->OMSetDepthStencilState(depth_.get(), 0);
    context_->RSSetState(raster_.get());
    const D3D11_VIEWPORT viewport{0, 0, float(desc.Width), float(desc.Height), 0, 1};
    context_->RSSetViewports(1, &viewport);
    context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->IASetInputLayout(nullptr);
    context_->VSSetShader(vertex_.get(), nullptr, 0);
    context_->PSSetShader(normal_.get(), nullptr, 0);
    auto* srv = view_.get(); auto* sampler = sampler_.get(); auto* constants = constants_.get();
    context_->PSSetShaderResources(0, 1, &srv);
    context_->PSSetSamplers(0, 1, &sampler);
    context_->PSSetConstantBuffers(0, 1, &constants);
    context_->Draw(3, 0);
    if (h.invert_right > h.invert_left && h.invert_bottom > h.invert_top) {
        context_->OMSetBlendState(inverse_blend_.get(), nullptr, 0xffffffffu);
        context_->PSSetShader(invert_.get(), nullptr, 0);
        context_->Draw(3, 0);
    }
    return S_OK;
}
}
