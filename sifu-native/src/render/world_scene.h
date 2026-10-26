#pragma once
#include "render/depth_copy.h"
namespace sifucraft::render {
// Copies both resources on H8. The constant buffer reference was acquired by a
// context getter, and its layout was verified through an asynchronous readback.
class WorldScene {
public:
    HRESULT Capture(ID3D11DeviceContext* context, const DepthIdentity& expected,
                    ID3D11ShaderResourceView* depth, ID3D11Buffer* parameters, UINT verified_bytes);
    void Reset();
    void Invalidate() { valid_ = false; }
    ID3D11ShaderResourceView* depth() const { return valid_ ? depth_.view() : nullptr; }
    ID3D11ShaderResourceView* parameters() const { return valid_ ? view_.get() : nullptr; }
    UINT width() const { return depth_.description().Width; }
    UINT height() const { return depth_.description().Height; }
private:
    DepthCopy depth_;
    Com<ID3D11Device> device_;
    Com<ID3D11Buffer> buffer_;
    Com<ID3D11ShaderResourceView> view_;
    UINT bytes_ = 0;
    bool valid_ = false;
};
}
