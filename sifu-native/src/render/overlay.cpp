#include "render/overlay.h"

#include <windows.h>
#include <dxgi.h>
#include <atomic>
#include <cwchar>
#include <cstdio>
#include <cstring>

#include "bridge/overlay_reader.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/vtable_hook.h"
#include "render/compositor.h"
#include "render/depth_probe.h"
#include "render/depth_observer.h"

namespace sifucraft::render {
namespace {
using Present = HRESULT(WINAPI*)(IDXGISwapChain*, UINT, UINT);
std::atomic<Present> original{nullptr};
std::atomic<IDXGISwapChain*> target{nullptr};
std::atomic<HWND> target_window{nullptr};
std::atomic<std::int32_t> pixel_color_space{-1};
std::atomic<bool> exiting{false}, drawing{false}, capture{false}, pattern{false};
std::atomic<std::uint64_t> presents{0}, draws{0}, uploads{0}, expired{0}, failures{0}, active{0};
SRWLOCK scope_lock = SRWLOCK_INIT, render_lock = SRWLOCK_INIT, presentation_lock = SRWLOCK_INIT;
Scope current;
Presentation presented;
hooks::VtableHook hook;
gen::hook::Target plan{"Present", 0, 8 * sizeof(void*), 0};
overlay::Reader reader;
overlay::Frame latest;
Scope previous_scope;
std::uint64_t received_ms = 0;
// The game DLL stays loaded until process termination. GPU resources must never be destroyed
// by the CRT under the loader lock; device changes reset this object on the render thread.
Compositor& compositor = *new Compositor;
bool last_error_logged = false;

bool DxgiCode(std::uintptr_t address) {
    MEMORY_BASIC_INFORMATION page{};
    if (!VirtualQuery(reinterpret_cast<void*>(address), &page, sizeof(page)) || page.State != MEM_COMMIT ||
        !(page.Protect & (PAGE_EXECUTE | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY))) return false;
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                            reinterpret_cast<LPCWSTR>(address), &module)) return false;
    wchar_t path[MAX_PATH]{};
    if (!GetModuleFileNameW(module, path, MAX_PATH)) return false;
    const auto* leaf = std::wcsrchr(path, L'\\');
    return _wcsicmp(leaf ? leaf + 1 : path, L"dxgi.dll") == 0;
}

// Diagnostic capture requested by the one-shot SifuCraft/overlay-capture control file.
// Runs on the render thread and releases its staging texture before returning.
bool SaveCapture(ID3D11Device* device, ID3D11Texture2D* image, const wchar_t* filename = L"overlay.bmp") {
    D3D11_TEXTURE2D_DESC desc{};
    image->GetDesc(&desc);
    if (desc.SampleDesc.Count != 1) return false;
    const bool bgra = desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM || desc.Format == DXGI_FORMAT_B8G8R8A8_UNORM_SRGB;
    const bool ten_bit = desc.Format == DXGI_FORMAT_R10G10B10A2_UNORM;
    if (!bgra && !ten_bit && desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM && desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM_SRGB) return false;
    desc.BindFlags = desc.MiscFlags = 0; desc.Usage = D3D11_USAGE_STAGING; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    Com<ID3D11Texture2D> staging;
    if (FAILED(device->CreateTexture2D(&desc, nullptr, staging.put()))) return false;
    Com<ID3D11DeviceContext> context; device->GetImmediateContext(context.put());
    context->CopyResource(staging.get(), image);
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped))) return false;
    const auto path = log::RootDirectory() + L"\\" + filename;
    FILE* file = _wfopen(path.c_str(), L"wb");
    if (file) {
        BITMAPFILEHEADER head{};
        head.bfType = 0x4d42; head.bfOffBits = sizeof(head) + sizeof(BITMAPINFOHEADER);
        head.bfSize = head.bfOffBits + desc.Width * desc.Height * 4;
        BITMAPINFOHEADER info{};
        info.biSize = sizeof(info); info.biWidth = desc.Width; info.biHeight = -static_cast<LONG>(desc.Height);
        info.biPlanes = 1; info.biBitCount = 32; info.biCompression = BI_RGB;
        std::fwrite(&head, sizeof(head), 1, file); std::fwrite(&info, sizeof(info), 1, file);
        std::vector<std::uint8_t> row(desc.Width * 4);
        for (UINT y = 0; y < desc.Height; ++y) {
            std::memcpy(row.data(), static_cast<std::uint8_t*>(mapped.pData) + y * mapped.RowPitch, row.size());
            if (ten_bit) {
                for (UINT x = 0; x < desc.Width; ++x) {
                    std::uint32_t pixel = 0; std::memcpy(&pixel, row.data() + x * 4, 4);
                    row[x * 4] = static_cast<std::uint8_t>(((pixel >> 20) & 1023) * 255 / 1023);
                    row[x * 4 + 1] = static_cast<std::uint8_t>(((pixel >> 10) & 1023) * 255 / 1023);
                    row[x * 4 + 2] = static_cast<std::uint8_t>((pixel & 1023) * 255 / 1023);
                    row[x * 4 + 3] = static_cast<std::uint8_t>((pixel >> 30) * 85);
                }
            } else if (!bgra) for (UINT x = 0; x < desc.Width; ++x) std::swap(row[x * 4], row[x * 4 + 2]);
            std::fwrite(row.data(), row.size(), 1, file);
        }
        const bool success = std::ferror(file) == 0;
        std::fclose(file);
        context->Unmap(staging.get(), 0);
        return success;
    }
    context->Unmap(staging.get(), 0);
    return false;
}

Presentation Paint(IDXGISwapChain* chain) {
    const auto color_space = pixel_color_space.load();
    // HDR/PQ and linear scRGB require a separate luminance conversion; do not blend SDR UI as-is.
    if (color_space != gen::value::EColorSpaceAndEOTF::ERec709_sRGB) {
        if (!last_error_logged) SC_LOG("[overlay] unsupported swap-chain color space %u", color_space);
        last_error_logged = true;
        return {};
    }
    // One diagnostic frame, including transparent, premultiplied and inverse pixels, on the
    // real game device before enabling live image consumption. Does not require input ownership.
    if (pattern.exchange(false)) {
        Com<ID3D11Device> device;
        Com<ID3D11Texture2D> image;
        if (FAILED(chain->GetDevice(__uuidof(ID3D11Device), reinterpret_cast<void**>(device.put()))) ||
            FAILED(chain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(image.put())))) return {};
        overlay::Frame test;
        test.header = {overlay::Ready, 4, 4, 16, 1, 1, 1, overlay::kPremultiplied, 64, 1, 1, 2, 2, 1, 0};
        test.pixels.assign(64, 0);
        test.pixels[0] = test.pixels[3] = 128;
        test.pixels[12] = test.pixels[13] = test.pixels[15] = 255;
        test.pixels[20] = test.pixels[21] = test.pixels[22] = 255;
        const auto result = compositor.Draw(device.get(), image.get(), test, true);
        const bool saved = SUCCEEDED(result) && SaveCapture(device.get(), image.get(), L"overlay-pattern.bmp");
        D3D11_TEXTURE2D_DESC desc{}; image->GetDesc(&desc);
        SC_LOG("[overlay] game-device pattern %s HRESULT=0x%08lX capture=%d backbuffer=%ux%u format=%u colorspace=%u",
            SUCCEEDED(result) ? "PASS" : "FAIL", static_cast<unsigned long>(result), saved, desc.Width, desc.Height, desc.Format, color_space);
        compositor.Reset(); received_ms = 0;
        return {};
    }
    Scope scope;
    AcquireSRWLockShared(&scope_lock); scope = current; ReleaseSRWLockShared(&scope_lock);
    const auto now = GetTickCount64();
    if ((!scope.enabled && !scope.world_enabled) || !Recent(scope.updated_ms, now)) { received_ms = 0; return {}; }
    if (!SameScope(scope, previous_scope)) {
        received_ms = 0; previous_scope = scope;
    }
    const bool fresh = reader.Read(scope.mc_session, scope.sifu_session, scope.world, scope.epoch, &latest);
    if (fresh && scope.enabled) { received_ms = now; ++uploads; }
    Com<ID3D11Texture2D> backbuffer;
    HRESULT hr = chain->GetBuffer(0, __uuidof(ID3D11Texture2D), reinterpret_cast<void**>(backbuffer.put()));
    if (FAILED(hr)) return {};
    D3D11_TEXTURE2D_DESC desc{}; backbuffer->GetDesc(&desc);
    reader.SetViewport(desc.Width, desc.Height);
    Com<ID3D11Device> device;
    if (FAILED(chain->GetDevice(__uuidof(ID3D11Device), reinterpret_cast<void**>(device.put())))) return {};
    depth_probe::DrawWorld(device.get(), backbuffer.get(), WorldScope(scope), now);
    if (!scope.enabled) { received_ms = 0; return {}; }
    if (!Recent(received_ms, now)) { ++expired; return {}; }
    hr = compositor.Draw(device.get(), backbuffer.get(), latest, fresh);
    if (FAILED(hr)) {
        ++failures;
        if (!last_error_logged) SC_LOG("[overlay] draw failed HRESULT=0x%08lX", static_cast<unsigned long>(hr));
        last_error_logged = true;
        compositor.Reset(); received_ms = 0;
        return {};
    }
    if (draws.fetch_add(1) == 0) SC_LOG("[overlay] first draw: source=%ux%u backbuffer=%ux%u format=%u frame=%llu",
        latest.header.width, latest.header.height, desc.Width, desc.Height, desc.Format,
        static_cast<unsigned long long>(latest.header.frame_id));
    if (capture.exchange(false)) {
        const bool saved = SaveCapture(device.get(), backbuffer.get());
        SC_LOG("[overlay] diagnostic capture %s", saved ? "saved to SifuCraft/overlay.bmp" : "failed");
    }
    return {scope, received_ms, 0};
}

HRESULT WINAPI PresentHook(IDXGISwapChain* chain, UINT interval, UINT flags) {
    active.fetch_add(1);
    ++presents;
    Presentation candidate;
    bool attempted = false;
    if (!exiting.load() && drawing.load() && chain == target.load() && !(flags & DXGI_PRESENT_TEST) &&
        TryAcquireSRWLockExclusive(&render_lock)) {
        try {
            // DXGI may synchronously send window messages. Keep its COM calls
            // on the game's Present thread, never the Runtime discovery worker.
            DXGI_SWAP_CHAIN_DESC desc{};
            const auto window=target_window.load();
            if(window&&SUCCEEDED(chain->GetDesc(&desc))&&desc.OutputWindow==window&&chain==target.load()){
                attempted=true;depth_probe::Tick(chain);candidate=Paint(chain);
            }
        }
        catch (...) { ++failures; received_ms = 0; }
        ReleaseSRWLockExclusive(&render_lock);
    }
    const auto forward = original.load();
    const HRESULT hr = forward ? forward(chain, interval, flags) : DXGI_ERROR_INVALID_CALL;
    // Context-state isolation (and Present itself) may restore D3D11's own COM
    // dispatch table. Observe the NEXT game frame, after all our state swaps.
    if (attempted && !exiting.load() && drawing.load() && !depth_observer::Rearm()) ++failures;
    if (attempted) {
        // Occluded and failed Presents do not prove that the user has a replacement HUD.
        if (hr == S_OK && candidate.image_ms) candidate.presented_ms = GetTickCount64();
        else candidate = {};
        AcquireSRWLockExclusive(&presentation_lock); presented = candidate; ReleaseSRWLockExclusive(&presentation_lock);
    }
    active.fetch_sub(1);
    return hr;
}

IDXGISwapChain* FindSwapChain(std::uintptr_t base,HWND* expected_window) {
    const auto rhi = mem::ReadPtr(base + gen::rva::GD3D11RHI);
    if (!rhi || rhi != mem::ReadPtr(base + gen::rva::GDynamicRHI)) return nullptr;
    mem::RawArray viewports;
    if (!mem::ReadArray(rhi + gen::off::FD3D11DynamicRHI::Viewports, &viewports) || viewports.num > 8) return nullptr;
    for (int i = 0; i < viewports.num; ++i) {
        const auto viewport = mem::ReadPtr(viewports.data + i * sizeof(void*));
        const auto window = reinterpret_cast<HWND>(mem::ReadPtr(viewport + gen::off::FD3D11Viewport::WindowHandle));
        DWORD pid = 0; GetWindowThreadProcessId(window, &pid);
        if (pid != GetCurrentProcessId() || !IsWindowVisible(window)) continue;
        auto* chain = reinterpret_cast<IDXGISwapChain*>(mem::ReadPtr(viewport + gen::off::FD3D11Viewport::SwapChain));
        const auto vtable = mem::ReadPtr(reinterpret_cast<std::uintptr_t>(chain));
        const auto entry = mem::ReadPtr(vtable + 8 * sizeof(void*));
        if (!DxgiCode(entry) && entry != reinterpret_cast<std::uintptr_t>(&PresentHook)) continue;
        // Verify the later render-thread query without dispatching COM here:
        // even GetDesc can enter DXGI's window handling and deadlock the worker
        // against the game's message-pump thread during a mode/focus change.
        if (!DxgiCode(mem::ReadPtr(vtable + 12 * sizeof(void*)))) continue;
        *expected_window=window;
        pixel_color_space = mem::ReadOr<std::int32_t>(viewport + gen::off::FD3D11Viewport::PixelColorSpace, -1);
        return chain;
    }
    return nullptr;
}
}

void Maintain(std::uintptr_t image_base, const Scope& scope, int stage) {
    AcquireSRWLockExclusive(&scope_lock); current = scope; ReleaseSRWLockExclusive(&scope_lock);
    depth_probe::UpdateScope(WorldScope(scope));
    static std::uint64_t retry = 0;
    if (scope.updated_ms < retry || exiting.load()) return;
    retry = scope.updated_ms + 1000;
    const auto root = log::RootDirectory();
    const auto depth_request = root + L"\\depth-capture";
    const auto world_request = root + L"\\world-capture";
    if (GetFileAttributesW(world_request.c_str()) != INVALID_FILE_ATTRIBUTES && DeleteFileW(world_request.c_str())) depth_probe::RequestWorldCapture();
    const bool depth_capture = GetFileAttributesW(depth_request.c_str()) != INVALID_FILE_ATTRIBUTES && DeleteFileW(depth_request.c_str());
    depth_probe::Configure(image_base, GetFileAttributesW((root + L"\\depth-probe").c_str()) != INVALID_FILE_ATTRIBUTES, depth_capture,
        stage >= 9 && GetFileAttributesW((root + L"\\depth-bind-probe").c_str()) != INVALID_FILE_ATTRIBUTES,
        stage >= 9 && GetFileAttributesW((root + L"\\world-render").c_str()) != INVALID_FILE_ATTRIBUTES, WorldScope(scope));
    const auto off = root + L"\\overlay-off";
    if (GetFileAttributesW(off.c_str()) != INVALID_FILE_ATTRIBUTES) {
        depth_probe::Stop();
        // Keep H8 alive until its thread restores H9/H10 and releases retained depth references.
        if (depth_observer::Installed()) return;
        drawing = false;
        if (hook.installed() && hook.Uninstall()) {
            // The DLL stays mapped; any late caller that loaded the old slot simply forwards.
            const auto deadline = GetTickCount64() + 2000;
            while (active.load() && GetTickCount64() < deadline) Sleep(1);
            SC_LOG("[overlay] H8 disabled, slot restored; remaining callbacks=%llu",
                static_cast<unsigned long long>(active.load()));
        }
        return;
    }
    const auto request = root + L"\\overlay-capture";
    if (GetFileAttributesW(request.c_str()) != INVALID_FILE_ATTRIBUTES && DeleteFileW(request.c_str())) capture = true;
    const auto pattern_request = root + L"\\overlay-pattern";
    if (GetFileAttributesW(pattern_request.c_str()) != INVALID_FILE_ATTRIBUTES && DeleteFileW(pattern_request.c_str())) pattern = true;
    HWND window=nullptr;
    auto* chain = FindSwapChain(image_base,&window);
    if (!chain) { drawing = false; return; }
    const auto vtable = mem::ReadPtr(reinterpret_cast<std::uintptr_t>(chain));
    if (hook.installed()) {
        if (vtable == plan.vftable) {target_window=window;target=chain;}
        drawing = target.load() == chain;
        return;
    }
    const auto entry = mem::ReadPtr(vtable + 8 * sizeof(void*));
    // One immutable forwarding function for this DLL lifetime; never reinterpret a late caller
    // using another swap-chain implementation's signature/address after a device recreation.
    if (original.load() && original.load() != reinterpret_cast<Present>(entry)) return;
    plan.vftable = vtable; plan.original = entry;
    original = reinterpret_cast<Present>(entry);target_window=window;target=chain;
    if (!hook.Install(0, plan, reinterpret_cast<std::uintptr_t>(chain), reinterpret_cast<void*>(&PresentHook))) return;
    if (!hook.Uninstall() || !hook.Install(0, plan, reinterpret_cast<std::uintptr_t>(chain), reinterpret_cast<void*>(&PresentHook))) {
        drawing = false;
        SC_LOG("[overlay] H8 uninstall/reinstall self-test FAILED");
        return;
    }
    drawing = true;
    SC_LOG("[overlay] H8 uninstall/reinstall PASS; DXGI Present slot=8 swapchain=0x%llX schema=%u layout=%016llX",
        reinterpret_cast<unsigned long long>(chain), overlay::kVersion, static_cast<unsigned long long>(overlay::kLayoutHash));
}

std::string TakeStats() {
    return FormatString("H8=%s presents=%llu uploads=%llu draws=%llu stale=%llu failures=%llu", hook.installed() ? "on" : "off",
        static_cast<unsigned long long>(presents.load()), static_cast<unsigned long long>(uploads.load()),
        static_cast<unsigned long long>(draws.load()), static_cast<unsigned long long>(expired.load()),
        static_cast<unsigned long long>(failures.load())) + " | depth " + depth_probe::TakeStats();
}
Presentation LastPresentation() {
    if (exiting.load() || !drawing.load()) return {};
    Presentation shown;
    AcquireSRWLockShared(&presentation_lock); shown = presented; ReleaseSRWLockShared(&presentation_lock);
    return shown;
}
void OnProcessExit() { exiting = true; drawing = false; depth_observer::OnProcessExit(); }
}
