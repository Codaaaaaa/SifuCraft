#pragma once
#include <cstdint>
#include <d3d11.h>
#include "render/com_ptr.h"

namespace sifucraft::render {
struct DepthIdentity {
    // Comparison tokens only: never dispatch COM methods through these borrowed addresses.
    std::uintptr_t resource = 0, srv = 0;
    UINT width = 0, height = 0;
    bool operator==(const DepthIdentity&) const = default;
};
struct DepthBinding {
    enum Stage { None, VS, HS, DS, GS, PS, CS, OM } stage = None;
    UINT slot = 0, views_seen = 0;
};
const char* DepthStageName(DepthBinding::Stage stage);
DXGI_FORMAT DepthReadFormat(DXGI_FORMAT format);

// Immediate-context thread only. Getters supply owned references; a missing source never
// authorizes AddRef on a naked engine pointer. Capture does not alter host bindings/content.
class DepthCopy {
public:
    HRESULT Capture(ID3D11DeviceContext* context, const DepthIdentity& expected, DepthBinding* binding);
    // Caller holds a live getter-acquired reference (for the H9/H10 -> H8 handoff).
    HRESULT CaptureHeld(ID3D11DeviceContext* context, const DepthIdentity& expected, ID3D11ShaderResourceView* held);
    void Invalidate() { valid_ = false; }
    void Reset();
    ID3D11Texture2D* texture() const { return valid_ ? texture_.get() : nullptr; }
    ID3D11ShaderResourceView* view() const { return valid_ ? view_.get() : nullptr; }
    const D3D11_TEXTURE2D_DESC& description() const { return desc_; }
private:
    HRESULT Copy(ID3D11DeviceContext* context, ID3D11Resource* source, const DepthIdentity& expected);
    Com<ID3D11Device> device_;
    Com<ID3D11Texture2D> texture_;
    Com<ID3D11ShaderResourceView> view_;
    D3D11_TEXTURE2D_DESC desc_{};
    bool valid_ = false;
};
}
