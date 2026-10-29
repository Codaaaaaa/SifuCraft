#include "render/world_scene.h"
#include "generated/sifu_build.g.h"
namespace sifucraft::render {
void WorldScene::Reset() {
    valid_ = false; depth_.Reset(); view_.reset(); buffer_.reset(); device_.reset(); bytes_ = 0;
}
HRESULT WorldScene::Capture(ID3D11DeviceContext* context, const DepthIdentity& expected,
                            ID3D11ShaderResourceView* depth, ID3D11Buffer* parameters, UINT verified_bytes) {
    valid_ = false;
    if (!context || !parameters || !depth || context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE) return E_INVALIDARG;
    Com<ID3D11Device> device, owner; context->GetDevice(device.put()); parameters->GetDevice(owner.put());
    D3D11_BUFFER_DESC desc{}; parameters->GetDesc(&desc);
    if (owner.get() != device.get() || !(desc.BindFlags & D3D11_BIND_CONSTANT_BUFFER) || desc.ByteWidth != verified_bytes ||
        desc.ByteWidth < gen::off::FViewUniformShaderParameters::FrameNumber + 4 || desc.ByteWidth > 65536 || desc.ByteWidth % 16)
        return DXGI_ERROR_UNSUPPORTED;
    if (device_.get() != device.get() || bytes_ != desc.ByteWidth || !view_) {
        view_.reset(); buffer_.reset(); device_ = std::move(device); bytes_ = 0;
        desc.Usage = D3D11_USAGE_DEFAULT; desc.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        desc.CPUAccessFlags = desc.StructureByteStride = 0; desc.MiscFlags = D3D11_RESOURCE_MISC_BUFFER_ALLOW_RAW_VIEWS;
        auto hr = device_->CreateBuffer(&desc, nullptr, buffer_.put());
        if (FAILED(hr)) return hr;
        D3D11_SHADER_RESOURCE_VIEW_DESC srv{}; srv.Format = DXGI_FORMAT_R32_TYPELESS;
        srv.ViewDimension = D3D11_SRV_DIMENSION_BUFFEREX; srv.BufferEx.NumElements = desc.ByteWidth / 4;
        srv.BufferEx.Flags = D3D11_BUFFEREX_SRV_FLAG_RAW;
        if (FAILED(hr = device_->CreateShaderResourceView(buffer_.get(), &srv, view_.put()))) return hr;
        bytes_ = desc.ByteWidth;
    }
    auto hr = depth_.CaptureHeld(context, expected, depth);
    if (hr != S_OK) return hr;
    context->CopyResource(buffer_.get(), parameters);
    valid_ = true; return S_OK;
}
}
