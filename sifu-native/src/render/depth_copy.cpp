#include "render/depth_copy.h"
#include <array>

namespace sifucraft::render {
const char* DepthStageName(DepthBinding::Stage stage) {
    constexpr const char* names[] = {"none", "VS", "HS", "DS", "GS", "PS", "CS", "OM"};
    return stage >= DepthBinding::None && stage <= DepthBinding::OM ? names[stage] : "invalid";
}
DXGI_FORMAT DepthReadFormat(DXGI_FORMAT format) {
    switch (format) {
        case DXGI_FORMAT_R24G8_TYPELESS: case DXGI_FORMAT_D24_UNORM_S8_UINT: return DXGI_FORMAT_R24_UNORM_X8_TYPELESS;
        case DXGI_FORMAT_R32_TYPELESS: case DXGI_FORMAT_D32_FLOAT: return DXGI_FORMAT_R32_FLOAT;
        case DXGI_FORMAT_R16_TYPELESS: case DXGI_FORMAT_D16_UNORM: return DXGI_FORMAT_R16_UNORM;
        case DXGI_FORMAT_R32G8X24_TYPELESS: case DXGI_FORMAT_D32_FLOAT_S8X24_UINT: return DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS;
        default: return DXGI_FORMAT_UNKNOWN;
    }
}
void DepthCopy::Reset() {
    valid_ = false; view_.reset(); texture_.reset(); device_.reset(); desc_ = {};
}
HRESULT DepthCopy::Copy(ID3D11DeviceContext* context, ID3D11Resource* source, const DepthIdentity& expected) {
    Com<ID3D11Device> device, source_device;
    context->GetDevice(device.put()); source->GetDevice(source_device.put());
    if (!device || device.get() != source_device.get()) return DXGI_ERROR_INVALID_CALL;
    Com<ID3D11Texture2D> image;
    auto hr = source->QueryInterface(__uuidof(ID3D11Texture2D), reinterpret_cast<void**>(image.put()));
    if (FAILED(hr)) return hr;
    D3D11_TEXTURE2D_DESC desc{}; image->GetDesc(&desc);
    const auto read_format = DepthReadFormat(desc.Format);
    if (desc.Width != expected.width || desc.Height != expected.height || desc.SampleDesc.Count != 1 ||
        desc.MipLevels != 1 || desc.ArraySize != 1 || read_format == DXGI_FORMAT_UNKNOWN) return DXGI_ERROR_UNSUPPORTED;
    if (device_.get() != device.get() || !texture_ || desc.Width != desc_.Width || desc.Height != desc_.Height || desc.Format != desc_.Format) {
        Reset();
        D3D11_TEXTURE2D_DESC own = desc;
        own.Usage = D3D11_USAGE_DEFAULT; own.BindFlags = D3D11_BIND_SHADER_RESOURCE;
        own.CPUAccessFlags = own.MiscFlags = 0;
        // A typeless resource permits a read-only depth SRV; typed depth copies use the same
        // format family, which D3D11 CopyResource allows.
        switch (read_format) {
            case DXGI_FORMAT_R24_UNORM_X8_TYPELESS: own.Format = DXGI_FORMAT_R24G8_TYPELESS; break;
            case DXGI_FORMAT_R32_FLOAT: own.Format = DXGI_FORMAT_R32_TYPELESS; break;
            case DXGI_FORMAT_R16_UNORM: own.Format = DXGI_FORMAT_R16_TYPELESS; break;
            default: own.Format = DXGI_FORMAT_R32G8X24_TYPELESS; break;
        }
        if (FAILED(hr = device->CreateTexture2D(&own, nullptr, texture_.put()))) return hr;
        D3D11_SHADER_RESOURCE_VIEW_DESC srv{};
        srv.Format = read_format; srv.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D; srv.Texture2D.MipLevels = 1;
        if (FAILED(hr = device->CreateShaderResourceView(texture_.get(), &srv, view_.put()))) { Reset(); return hr; }
        device_ = std::move(device); desc_ = desc;
    }
    context->CopyResource(texture_.get(), image.get());
    valid_ = true;
    return S_OK;
}
HRESULT DepthCopy::Capture(ID3D11DeviceContext* context, const DepthIdentity& expected, DepthBinding* result) {
    valid_ = false;
    if (result) *result = {};
    if (!context || !expected.resource || !expected.width || !expected.height || expected.width > 16384 || expected.height > 16384)
        return E_INVALIDARG;
    if (context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE) return E_INVALIDARG;
    DepthBinding binding;
    for (int stage = DepthBinding::PS; stage >= DepthBinding::VS; --stage) {
        std::array<ID3D11ShaderResourceView*, D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT> views{};
        switch (stage) {
            case DepthBinding::VS: context->VSGetShaderResources(0, views.size(), views.data()); break;
            case DepthBinding::HS: context->HSGetShaderResources(0, views.size(), views.data()); break;
            case DepthBinding::DS: context->DSGetShaderResources(0, views.size(), views.data()); break;
            case DepthBinding::GS: context->GSGetShaderResources(0, views.size(), views.data()); break;
            default: context->PSGetShaderResources(0, views.size(), views.data()); break;
        }
        Com<ID3D11Resource> matched;
        for (UINT slot = 0; slot < views.size(); ++slot) if (views[slot]) {
            ++binding.views_seen;
            Com<ID3D11Resource> resource; views[slot]->GetResource(resource.put());
            if (!matched && reinterpret_cast<std::uintptr_t>(resource.get()) == expected.resource) {
                matched = std::move(resource); binding.stage = static_cast<DepthBinding::Stage>(stage); binding.slot = slot;
            }
            views[slot]->Release(); // exactly the reference returned by the context getter
        }
        if (matched) { if (result) *result = binding; return Copy(context, matched.get(), expected); }
    }
    {
        std::array<ID3D11ShaderResourceView*, D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT> views{};
        context->CSGetShaderResources(0, views.size(), views.data());
        Com<ID3D11Resource> matched;
        for (UINT slot = 0; slot < views.size(); ++slot) if (views[slot]) {
            ++binding.views_seen;
            Com<ID3D11Resource> resource; views[slot]->GetResource(resource.put());
            if (!matched && reinterpret_cast<std::uintptr_t>(resource.get()) == expected.resource) {
                matched = std::move(resource); binding.stage = DepthBinding::CS; binding.slot = slot;
            }
            views[slot]->Release();
        }
        if (matched) { if (result) *result = binding; return Copy(context, matched.get(), expected); }
    }
    Com<ID3D11DepthStencilView> dsv;
    context->OMGetRenderTargets(0, nullptr, dsv.put());
    if (dsv) {
        ++binding.views_seen;
        Com<ID3D11Resource> resource; dsv->GetResource(resource.put());
        if (reinterpret_cast<std::uintptr_t>(resource.get()) == expected.resource) {
            binding.stage = DepthBinding::OM;
            if (result) *result = binding;
            return Copy(context, resource.get(), expected);
        }
    }
    if (result) *result = binding;
    return S_FALSE;
}
HRESULT DepthCopy::CaptureHeld(ID3D11DeviceContext* context, const DepthIdentity& expected, ID3D11ShaderResourceView* held) {
    valid_ = false;
    if (!context || !held || !expected.resource || !expected.width || !expected.height ||
        expected.width > 16384 || expected.height > 16384 || context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE) return E_INVALIDARG;
    Com<ID3D11Resource> resource; held->GetResource(resource.put());
    if (reinterpret_cast<std::uintptr_t>(resource.get()) != expected.resource) return S_FALSE;
    return Copy(context, resource.get(), expected);
}
}
