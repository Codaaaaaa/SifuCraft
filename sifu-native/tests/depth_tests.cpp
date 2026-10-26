#include "render/depth_copy.h"
#include "render/depth_observer.h"
#include "render/view_parameters.h"
#include "render/compositor.h"
#include "generated/sifu_build.g.h"
#include "core/safe_memory.h"
#include <cstdio>
#include <cmath>
#include <cstring>
#include <vector>
#include <limits>
#include <d3d11_1.h>

using namespace sifucraft::render;
namespace {
int failures = 0;
void Check(bool ok, const char* why) { std::printf("%s %s\n", ok ? "ok" : "FAIL", why); if (!ok) ++failures; }
ULONG Refs(IUnknown* object) { object->AddRef(); return object->Release(); }
bool ReadDepth(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* texture, float expected, bool stencil = false) {
    if (!texture) return false;
    D3D11_TEXTURE2D_DESC desc{}; texture->GetDesc(&desc);
    desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = desc.MiscFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    Com<ID3D11Texture2D> staging;
    if (FAILED(device->CreateTexture2D(&desc, nullptr, staging.put()))) return false;
    context->CopyResource(staging.get(), texture);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
    bool ok = true;
    for (UINT y = 0; y < desc.Height; ++y) for (UINT x = 0; x < desc.Width; ++x) {
        std::uint32_t pixel; std::memcpy(&pixel, static_cast<const char*>(mapped.pData) + y * mapped.RowPitch + x * 4, 4);
        const float z = float(pixel & 0xFFFFFF) / 16777215.0f;
        ok = ok && std::fabs(z - expected) < 0.000001f && (!stencil || pixel >> 24 == 0x6a);
    }
    context->Unmap(staging.get(), 0);
    return ok;
}
}
int main() {
    {
        ViewParameters main,small;main.width=1920;main.height=1080;main.buffer_width=small.buffer_width=1920;main.buffer_height=small.buffer_height=1080;
        small.width=small.height=248;
        std::array<ViewParameters,2> views{small,main};
        Check(SelectViewParameters(views,{0,0,1920,1080},true)==1,"PS raster viewport selects the validated main view instead of a small secondary view");
        Check(SelectViewParameters(views,{0,0,1920,1080},false)==-1,"stale CS raster viewport cannot resolve different views");
        views[0]=main;
        Check(SelectViewParameters(views)==0,"duplicate equivalent views are one unambiguous projection");
        views[0].camera_cm[0]=1;
        Check(SelectViewParameters(views,{0,0,1920,1080},true)==-1,"same viewport with different cameras remains ambiguous");
    }
    {
        namespace f = sifucraft::gen::off::FViewUniformShaderParameters;
        std::vector<std::uint8_t> bytes(f::FrameNumber + 4);
        auto put = [&](std::size_t offset, float value) { std::memcpy(bytes.data() + offset, &value, sizeof(value)); };
        put(f::ViewToClipNoAA, 1); put(f::ViewToClipNoAA + 5 * 4, 1);
        put(f::NearPlane, 10); put(f::InvDeviceZToWorldZTransform + 8, .1f);
        put(f::ViewSizeAndInvSize, 1281); put(f::ViewSizeAndInvSize + 4, 721);
        put(f::ViewSizeAndInvSize + 8, 1.f / 1281); put(f::ViewSizeAndInvSize + 12, 1.f / 721);
        put(f::BufferSizeAndInvSize, 1284); put(f::BufferSizeAndInvSize + 4, 724);
        put(f::BufferSizeAndInvSize + 8, 1.f / 1284); put(f::BufferSizeAndInvSize + 12, 1.f / 724);
        ViewParameters view;
        Check(ParseViewParameters(bytes, 1284, 724, view) && view.width == 1281 && view.height == 721 && view.buffer_width == 1284,
              "valid view parameters preserve active rect separately from padded depth allocation");
        Check(!ParseViewParameters(bytes, 1920, 1080, view) && view.width == 0,
              "foreign buffer/allocation cannot produce stale view parameters");
        put(f::ViewRectMin, 4);
        Check(!ParseViewParameters(bytes, 1284, 724, view), "reject active rect extending past its depth allocation");
        put(f::ViewRectMin, 0); put(f::WorldToClip, std::numeric_limits<float>::quiet_NaN());
        Check(!ParseViewParameters(bytes, 1284, 724, view) && !ParseViewParameters({}, 1284, 724, view),
              "nonfinite projection and truncated buffers fail closed");
    }
    Check(sifucraft::mem::Install(), "install guarded reads for independent COM hook tests");
    Com<ID3D11Device> device; Com<ID3D11DeviceContext> context;
    auto hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION,
                              device.put(), nullptr, context.put());
    Check(SUCCEEDED(hr), "independent WARP device");
    if (FAILED(hr)) return 1;
    D3D11_TEXTURE2D_DESC desc{};
    desc.Width = 8; desc.Height = 4; desc.MipLevels = desc.ArraySize = desc.SampleDesc.Count = 1;
    desc.Format = DXGI_FORMAT_R24G8_TYPELESS; desc.Usage = D3D11_USAGE_DEFAULT;
    desc.BindFlags = D3D11_BIND_DEPTH_STENCIL | D3D11_BIND_SHADER_RESOURCE;
    Com<ID3D11Texture2D> source;
    Check(SUCCEEDED(device->CreateTexture2D(&desc, nullptr, source.put())), "fixture depth texture");
    D3D11_DEPTH_STENCIL_VIEW_DESC dsv_desc{};
    dsv_desc.Format = DXGI_FORMAT_D24_UNORM_S8_UINT; dsv_desc.ViewDimension = D3D11_DSV_DIMENSION_TEXTURE2D;
    Com<ID3D11DepthStencilView> dsv;
    Check(SUCCEEDED(device->CreateDepthStencilView(source.get(), &dsv_desc, dsv.put())), "fixture DSV");
    D3D11_SHADER_RESOURCE_VIEW_DESC srv_desc{};
    srv_desc.Format = DXGI_FORMAT_R24_UNORM_X8_TYPELESS; srv_desc.ViewDimension = D3D11_SRV_DIMENSION_TEXTURE2D;
    srv_desc.Texture2D.MipLevels = 1;
    Com<ID3D11ShaderResourceView> srv;
    Check(SUCCEEDED(device->CreateShaderResourceView(source.get(), &srv_desc, srv.put())), "fixture SRV");
    if (!source || !dsv || !srv) return 1;
    context->ClearDepthStencilView(dsv.get(), D3D11_CLEAR_DEPTH | D3D11_CLEAR_STENCIL, .25f, 0x6a);
    const DepthIdentity expected{reinterpret_cast<std::uintptr_t>(source.get()), reinterpret_cast<std::uintptr_t>(srv.get()), 8, 4};
    DepthCopy copy; DepthBinding binding;
    Check(copy.Capture(context.get(), expected, &binding) == S_FALSE && !copy.texture() && !copy.view(),
          "unbound engine address is never dereferenced or treated as a live COM reference");
    auto* view = srv.get(); context->PSSetShaderResources(7, 1, &view);
    const D3D11_VIEWPORT viewport{1, 2, 3, 4, .1f, .9f}; context->RSSetViewports(1, &viewport);
    context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
    const auto source_refs = Refs(source.get()), view_refs = Refs(srv.get());
    hr = copy.Capture(context.get(), expected, &binding);
    if (FAILED(hr)) std::printf("depth copy HRESULT=0x%08lX\n", static_cast<unsigned long>(hr));
    Check(hr == S_OK && binding.stage == DepthBinding::PS && binding.slot == 7, "acquire live PS depth through context-owned references");
    Check(copy.view() && copy.texture() != source.get() && ReadDepth(device.get(), context.get(), copy.texture(), .25f),
          "copy scene depth pixels into independent shader-readable texture");
    Check(ReadDepth(device.get(), context.get(), source.get(), .25f, true), "source depth and stencil remain intact");
    Check(Refs(source.get()) == source_refs && Refs(srv.get()) == view_refs, "balanced source/view references after scan and copy");
    Com<ID3D11ShaderResourceView> restored;
    context->PSGetShaderResources(7, 1, restored.put());
    Check(restored.get() == srv.get(), "host shader-resource binding unchanged"); restored.reset();
    D3D11_VIEWPORT after{}; UINT count = 1; context->RSGetViewports(&count, &after);
    D3D11_PRIMITIVE_TOPOLOGY topology{}; context->IAGetPrimitiveTopology(&topology);
    Check(count == 1 && std::memcmp(&viewport, &after, sizeof(after)) == 0 && topology == D3D11_PRIMITIVE_TOPOLOGY_LINELIST,
          "host viewport and topology unchanged");
    for (int i = 0; i < 8; ++i) copy.Capture(context.get(), expected, &binding);
    Check(Refs(source.get()) == source_refs && Refs(srv.get()) == view_refs, "repeated captures do not retain source references");
    auto bad = expected; ++bad.width;
    Check(copy.Capture(context.get(), bad, &binding) == DXGI_ERROR_UNSUPPORTED && !copy.view(), "dimension mismatch cannot expose stale copied depth");
    bad = expected; bad.resource = 0x10001; bad.srv = 0x10002;
    Check(copy.Capture(context.get(), bad, &binding) == S_FALSE && !copy.view(), "stale/unreadable comparison tokens never dispatch COM");
    ID3D11ShaderResourceView* empty = nullptr; context->PSSetShaderResources(7, 1, &empty);
    context->CSSetShaderResources(11, 1, &view);
    Check(copy.Capture(context.get(), expected, &binding) == S_OK && binding.stage == DepthBinding::CS && binding.slot == 11,
          "find depth in compute bindings");
    context->CSSetShaderResources(11, 1, &empty);
    context->VSSetShaderResources(3, 1, &view);
    Check(copy.Capture(context.get(), expected, &binding) == S_OK && binding.stage == DepthBinding::VS && binding.slot == 3,
          "find depth in vertex bindings");
    context->VSSetShaderResources(3, 1, &empty);
    auto* native_dsv = dsv.get(); context->OMSetRenderTargets(0, nullptr, native_dsv);
    Check(copy.Capture(context.get(), expected, &binding) == S_OK && binding.stage == DepthBinding::OM,
          "safe DSV fallback uses an owned output-merger reference");
    Com<ID3D11DepthStencilView> restored_dsv; context->OMGetRenderTargets(0, nullptr, restored_dsv.put());
    Check(restored_dsv.get() == dsv.get(), "source DSV binding preserved"); restored_dsv.reset();
    context->OMSetRenderTargets(0, nullptr, nullptr);
    Check(copy.Capture(context.get(), expected, &binding) == S_FALSE && !copy.view(), "missing source invalidates older successful snapshots");
    Com<ID3D11DeviceContext> deferred;
    device->CreateDeferredContext(0, deferred.put());
    Check(copy.Capture(deferred.get(), expected, &binding) == E_INVALIDARG, "deferred contexts cannot perform the H8 probe");
    Check(copy.Capture(nullptr, expected, &binding) == E_INVALIDARG &&
          DepthReadFormat(DXGI_FORMAT_R8G8B8A8_UNORM) == DXGI_FORMAT_UNKNOWN, "reject invalid context and color texture format");
    copy.Reset(); Check(!copy.texture() && !copy.view(), "disable/device change clears owned resources");
    {
        namespace observer = sifucraft::render::depth_observer;
        auto vtable = *reinterpret_cast<void***>(context.get());
        auto old_ps = vtable[8], old_cs = vtable[67];
        const auto context_refs = Refs(context.get()), srv_refs = Refs(srv.get());
        Check(!observer::Install(deferred.get()), "binding hooks reject deferred contexts");
        Check(observer::Install(context.get()) && observer::Installed(), "install H9 and H10 on the verified WARP immediate context");
        Check(vtable[8] != old_ps && vtable[67] != old_cs, "both approved binding slots patched");
        {
            const auto hooked_ps = vtable[8], hooked_cs = vtable[67];
            D3D11_TEXTURE2D_DESC color_desc{}; color_desc.Width=color_desc.Height=4;
            color_desc.MipLevels=color_desc.ArraySize=color_desc.SampleDesc.Count=1;
            color_desc.Format=DXGI_FORMAT_R8G8B8A8_UNORM; color_desc.BindFlags=D3D11_BIND_RENDER_TARGET;
            Com<ID3D11Texture2D> color; device->CreateTexture2D(&color_desc,nullptr,color.put());
            sifucraft::overlay::Frame hud; hud.header={sifucraft::overlay::Ready,4,4,16,1,3,4,sifucraft::overlay::kPremultiplied,64,0,0,0,0,1,0};
            hud.pixels.resize(64); Compositor compositor;
            Check(compositor.Draw(device.get(),color.get(),hud,true)==S_OK,"draw real HUD pipeline while depth observers are installed");
            const auto current_table=*reinterpret_cast<void***>(context.get());
            Check(current_table[8]==hooked_ps&&current_table[67]==hooked_cs,
                  "manual pipeline isolation keeps both binding observers live without mutable context-state emulation");
        }
        Scope scope{1, 2, 3, 4, GetTickCount64(), true};
        observer::SetTarget(scope, expected);
        auto* value = srv.get();
        context->PSSetShaderResources(5, 1, &value);
        Com<ID3D11ShaderResourceView> actual;
        context->PSGetShaderResources(5, 1, actual.put());
        Check(actual.get() == value, "pixel setter still forwards the exact target slot and view"); actual.reset();
        context->PSSetShaderResources(5, 1, &empty);
        Check(copy.Capture(context.get(), expected, &binding) == S_FALSE, "Present-style scan sees no binding after native cleanup");
        auto held = observer::Take(context.get(), scope, expected, GetTickCount64());
        Check(held.view.get() == value && held.stage == DepthBinding::PS && held.slot == 5,
              "H9 retains getter-acquired depth through unbinding for H8");
        Check(copy.CaptureHeld(context.get(), expected, held.view.get()) == S_OK &&
              ReadDepth(device.get(), context.get(), copy.texture(), .25f), "H8 copies the held source after the host has unbound it");
        held = {};
        Check(!observer::Take(context.get(), scope, expected, GetTickCount64()).view, "consume each observed snapshot once");
        Check(Refs(srv.get()) == srv_refs, "consumed PS observation balances its held SRV reference");
        scope.updated_ms = GetTickCount64(); observer::SetTarget(scope, expected);
        D3D11_BUFFER_DESC cb_desc{}; cb_desc.ByteWidth = 64; cb_desc.Usage = D3D11_USAGE_DEFAULT;
        cb_desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        Com<ID3D11Buffer> cb; device->CreateBuffer(&cb_desc, nullptr, cb.put());
        Check(bool(cb), "independent constant buffer for one-shot projection diagnostics");
        auto* cb_value = cb.get(); context->CSSetConstantBuffers(3, 1, &cb_value);
        observer::RequestParameters();
        context->CSSetShaderResources(17, 1, &value); context->CSSetShaderResources(17, 1, &empty);
        ID3D11Buffer* cb_empty = nullptr; context->CSSetConstantBuffers(3, 1, &cb_empty);
        auto parameters = observer::TakeParameters(context.get(), scope, expected, GetTickCount64());
        Check(parameters.observed_ms && parameters.buffers[3].get() == cb.get() && parameters.stage == DepthBinding::CS &&
              std::memcmp(&parameters.viewport, &viewport, sizeof(viewport)) == 0 && Refs(cb.get()) == 2,
              "one-shot getters retain bound parameters and viewport through host unbinding");
        parameters = {};
        Check(Refs(cb.get()) == 1 && !observer::TakeParameters(context.get(), scope, expected, GetTickCount64()).observed_ms,
              "consumed diagnostic releases buffer reference and cannot be replayed");
        observer::StreamParameters(DepthBinding::CS, 3); context->CSSetConstantBuffers(3, 1, &cb_value);
        for (int i = 0; i < 12; ++i) context->CSSetShaderResources(17, 1, &value);
        context->CSSetConstantBuffers(3, 1, &cb_empty);
        context->CSSetShaderResources(17, 1, &value); // Later depth pass without a View buffer.
        context->CSSetShaderResources(17, 1, &empty);
        parameters = observer::TakeParameters(context.get(), scope, expected, GetTickCount64());
        unsigned retained = 0; for (auto& buffer : parameters.buffers) retained += bool(buffer);
        Check(retained == 1 && parameters.buffers[3].get() == cb.get() && !parameters.full_snapshot && Refs(cb.get()) == 2,
              "production stream retains only the verified slot and replaces references without accumulating them");
        parameters = {}; observer::StreamParameters();
        Check(Refs(cb.get()) == 1, "stopping parameter stream balances the selected buffer reference");
        held = observer::Take(context.get(), scope, expected, GetTickCount64());
        Check(held.view.get() == value && held.stage == DepthBinding::CS && held.slot == 17,
              "H10 retains a compute-stage source without altering the setter"); held = {};
        for (int i = 0; i < 20; ++i) context->PSSetShaderResources(5, 1, &value);
        context->PSSetShaderResources(5, 1, &empty);
        held = observer::Take(context.get(), scope, expected, GetTickCount64()); held = {};
        Check(Refs(srv.get()) == srv_refs, "replacing repeated observations retains at most one reference");
        context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
        auto changed = scope; ++changed.epoch; changed.updated_ms = GetTickCount64();
        Check(!observer::Take(context.get(), changed, expected, GetTickCount64()).view, "anchor changes reject the previous observation");
        observer::SetTarget(changed, expected);
        context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
        auto wrong_identity = expected; ++wrong_identity.resource;
        Check(!observer::Take(context.get(), changed, wrong_identity, GetTickCount64()).view, "resource identity mismatch discards the observation");
        observer::SetTarget(changed, expected);
        context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
        Check(!observer::Take(context.get(), changed, expected, GetTickCount64() + 251).view, "expired observations cannot substitute for current scene depth");
        auto old_scope = changed; old_scope.updated_ms = GetTickCount64() - 300;
        observer::SetTarget(old_scope, expected);
        context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
        Check(!observer::Take(context.get(), old_scope, expected, GetTickCount64()).view, "stale configuration never collects a source");
        Com<ID3D11Device> other_device; Com<ID3D11DeviceContext> other_context;
        const auto created = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_WARP, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION,
            other_device.put(), nullptr, other_context.put());
        Check(SUCCEEDED(created), "second device for exact-context filtering and recreation");
        if (other_context) {
            Check(observer::Install(other_context.get()), "change target context after restoring previous slots");
            changed.updated_ms = GetTickCount64(); observer::SetTarget(changed, expected);
            context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
            Check(!observer::Take(other_context.get(), changed, expected, GetTickCount64()).view,
                  "same-depth bindings on another context are forwarded but not observed");
            Check(observer::Install(context.get()), "restore the original target without changing immutable forwarders");
        }
        changed.updated_ms = GetTickCount64(); observer::SetTarget(changed, expected);
        context->PSSetShaderResources(5, 1, &value); context->PSSetShaderResources(5, 1, &empty);
        const auto late_ps = vtable[8];
        Check(observer::Disable() && !observer::Installed() && vtable[8] == old_ps && vtable[67] == old_cs,
              "disable restores both exact original slots");
        Check(Refs(srv.get()) == srv_refs && Refs(context.get()) == context_refs, "disable releases held source and context references");
        using Setter = void(STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT, ID3D11ShaderResourceView* const*);
        reinterpret_cast<Setter>(late_ps)(context.get(), 5, 1, &value);
        context->PSGetShaderResources(5, 1, actual.put());
        Check(actual.get() == value && !observer::Take(context.get(), changed, expected, GetTickCount64()).view,
              "late callers of an old hook address still forward after disable"); actual.reset();
        context->PSSetShaderResources(5, 1, &empty);
        for (int i = 0; i < 3; ++i) {
            Check(observer::Install(context.get()) && observer::Disable(), "repeat install/uninstall without leaving patched slots");
        }
        Check(ReadDepth(device.get(), context.get(), source.get(), .25f, true), "binding observers leave native depth and stencil pixels unchanged");
        std::printf("observer stats: %s\n", observer::TakeStats().c_str());
    }
    std::printf("D3D11 read-only depth copy %s (%d failures)\n", failures ? "FAIL" : "PASS", failures);
    return failures ? 1 : 0;
}
