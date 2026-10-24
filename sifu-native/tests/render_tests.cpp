#include "render/compositor.h"
#include <windows.h>
#include <cstdio>
#include <cmath>
#include <cstring>

using namespace sifucraft;
using render::Com;
namespace {
int failures = 0;
void Check(bool ok, const char* text) { std::printf("%s %s\n", ok ? "ok" : "FAIL", text); if (!ok) ++failures; }
bool Near(int actual, int expected) { return std::abs(actual - expected) <= 2; }
}
int main() {
    Com<ID3D11Device> device;
    Com<ID3D11DeviceContext> context;
    const auto window = CreateWindowExW(0, L"STATIC", L"SifuCraft render test", WS_OVERLAPPEDWINDOW,
                                       0, 0, 64, 64, nullptr, nullptr, GetModuleHandleW(nullptr), nullptr);
    DXGI_SWAP_CHAIN_DESC chain_desc{};
    chain_desc.BufferDesc.Width = chain_desc.BufferDesc.Height = 4;
    chain_desc.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    chain_desc.SampleDesc.Count = chain_desc.BufferCount = 1;
    chain_desc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; chain_desc.OutputWindow = window; chain_desc.Windowed = TRUE;
    Com<IDXGISwapChain> chain;
    auto hr = D3D11CreateDeviceAndSwapChain(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION,
        &chain_desc, chain.put(), device.put(), nullptr, context.put());
    Check(SUCCEEDED(hr), "create independent D3D11 WARP swap chain");
    if (FAILED(hr)) return 1;
    render::Compositor compositor;
    {
        Com<ID3D11Texture2D> image;
        chain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(image.put()));
        Com<ID3D11RenderTargetView> rtv;
        device->CreateRenderTargetView(image.get(), nullptr, rtv.put());
        const float background[] = {0.2f, 0.4f, 0.6f, 1};
        context->ClearRenderTargetView(rtv.get(), background);
        D3D11_TEXTURE2D_DESC sentinel_desc{}; image->GetDesc(&sentinel_desc);
        Com<ID3D11Texture2D> sentinel;
        Com<ID3D11RenderTargetView> sentinel_view;
        device->CreateTexture2D(&sentinel_desc, nullptr, sentinel.put());
        device->CreateRenderTargetView(sentinel.get(), nullptr, sentinel_view.put());
        auto* before_target = sentinel_view.get();
        context->OMSetRenderTargets(1, &before_target, nullptr);
        const D3D11_VIEWPORT viewport{1, 2, 3, 4, 0.1f, 0.9f};
        const D3D11_RECT scissor{1, 2, 3, 4};
        context->RSSetViewports(1, &viewport); context->RSSetScissorRects(1, &scissor);
        context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
        overlay::Frame frame;
        frame.header = {overlay::Ready, 4, 4, 16, 1, 1, 1, overlay::kPremultiplied, 64, 0, 0, 1, 1, 1, 0};
        frame.pixels.assign(64, 0);
        // Top-left: white inverse crosshair. Top-right: half-alpha premultiplied red. Bottom-left: opaque green.
        frame.pixels[0] = frame.pixels[1] = frame.pixels[2] = 255;
        frame.pixels[12] = frame.pixels[15] = 128;
        frame.pixels[48 + 1] = frame.pixels[48 + 3] = 255;
        hr = compositor.Draw(device.get(), image.get(), frame, true);
        if (FAILED(hr)) std::printf("Draw HRESULT=0x%08lX\n", static_cast<unsigned long>(hr));
        Check(SUCCEEDED(hr), "compile shaders and composite alpha plus crosshair");
        Com<ID3D11RenderTargetView> restored;
        context->OMGetRenderTargets(1, restored.put(), nullptr);
        Check(restored.get() == sentinel_view.get(), "restore host render target, not the overlay target");
        D3D11_VIEWPORT after{}; UINT count = 1; context->RSGetViewports(&count, &after);
        Check(count == 1 && std::memcmp(&after, &viewport, sizeof(after)) == 0, "restore exact host viewport");
        D3D11_RECT after_scissor{}; count = 1; context->RSGetScissorRects(&count, &after_scissor);
        Check(count == 1 && std::memcmp(&after_scissor, &scissor, sizeof(scissor)) == 0, "restore host scissor");
        D3D11_PRIMITIVE_TOPOLOGY topology{}; context->IAGetPrimitiveTopology(&topology);
        Check(topology == D3D11_PRIMITIVE_TOPOLOGY_LINELIST, "restore input-assembler topology");
        D3D11_TEXTURE2D_DESC staging_desc{}; image->GetDesc(&staging_desc);
        staging_desc.Usage = D3D11_USAGE_STAGING; staging_desc.BindFlags = 0; staging_desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        Com<ID3D11Texture2D> staging; device->CreateTexture2D(&staging_desc, nullptr, staging.put());
        context->CopyResource(staging.get(), image.get());
        D3D11_MAPPED_SUBRESOURCE pixels{};
        hr = context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &pixels);
        Check(SUCCEEDED(hr), "read composite pixels independently");
        if (SUCCEEDED(hr)) {
            auto* p = static_cast<const unsigned char*>(pixels.pData);
            Check(Near(p[0], 204) && Near(p[1], 153) && Near(p[2], 102), "inverse crosshair blends against host scene");
            Check(Near(p[12], 153) && Near(p[13], 51) && Near(p[14], 76) && p[15] == 255, "premultiplied half-alpha and host alpha");
            Check(p[pixels.RowPitch * 3 + 1] == 255, "top-down image orientation");
            Check(Near(p[4], 51) && Near(p[5], 102) && Near(p[6], 153), "transparent pixels preserve host scene");
            context->Unmap(staging.get(), 0);
        }
        frame.header.flags |= overlay::kBottomUp;
        frame.header.invert_right = frame.header.invert_bottom = 0;
        context->ClearRenderTargetView(rtv.get(), background);
        Check(SUCCEEDED(compositor.Draw(device.get(), image.get(), frame, true)), "draw bottom-up GPU image");
        context->CopyResource(staging.get(), image.get());
        if (SUCCEEDED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &pixels))) {
            const auto* p = static_cast<const unsigned char*>(pixels.pData);
            Check(p[1] == 255 && p[0] == 0, "flip bottom-up source only, without moving GUI bounds");
            context->Unmap(staging.get(), 0);
        }
    }
    context->OMSetRenderTargets(0, nullptr, nullptr);
    // The compositor is deliberately still alive. A retained backbuffer in its isolated context
    // state, or in a cached RTV, makes ResizeBuffers fail here.
    Check(SUCCEEDED(chain->ResizeBuffers(1, 8, 8, DXGI_FORMAT_R8G8B8A8_UNORM, 0)), "release every backbuffer reference before ResizeBuffers");
    Check(SUCCEEDED(chain->ResizeBuffers(1, 8, 8, DXGI_FORMAT_R10G10B10A2_UNORM, 0)), "recreate the 10-bit SDR format used by the game");
    {
        Com<ID3D11Texture2D> image; chain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(image.put()));
        Com<ID3D11RenderTargetView> view; device->CreateRenderTargetView(image.get(), nullptr, view.put());
        const float background[] = {0.2f, 0.4f, 0.6f, 1};
        context->ClearRenderTargetView(view.get(), background);
        overlay::Frame frame;
        frame.header = {overlay::Ready, 1, 1, 4, 2, 1, 1, overlay::kPremultiplied, 4, 0, 0, 0, 0, 1, 0};
        frame.pixels = {128, 0, 0, 128};
        Check(SUCCEEDED(compositor.Draw(device.get(), image.get(), frame, true)), "blend 8-bit GUI into a 10-bit game backbuffer");
        D3D11_TEXTURE2D_DESC desc{}; image->GetDesc(&desc);
        desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        Com<ID3D11Texture2D> staging; device->CreateTexture2D(&desc, nullptr, staging.put());
        context->CopyResource(staging.get(), image.get());
        D3D11_MAPPED_SUBRESOURCE pixels{};
        if (SUCCEEDED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &pixels))) {
            std::uint32_t pixel; std::memcpy(&pixel, pixels.pData, 4);
            Check(Near((pixel & 1023) * 255 / 1023, 153) && Near(((pixel >> 10) & 1023) * 255 / 1023, 51) &&
                  Near(((pixel >> 20) & 1023) * 255 / 1023, 76) && (pixel >> 30) == 3, "10-bit packed pixel channels and preserved alpha");
            context->Unmap(staging.get(), 0);
        } else Check(false, "read 10-bit test pixels");
    }
    compositor.Reset();
    DestroyWindow(window);
    std::printf("D3D11 compositor %s (%d failures)\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
