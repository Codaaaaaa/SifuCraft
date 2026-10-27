#include "render/depth_probe.h"
#include <windows.h>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>
#include <chrono>
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "render/depth_copy.h"
#include "render/depth_observer.h"
#include "render/view_parameters.h"
#include "render/world_compositor.h"

namespace sifucraft::render::depth_probe {
namespace {
SRWLOCK config_lock = SRWLOCK_INIT;
struct Configuration { std::uintptr_t base = 0; bool enabled = false, observe = false, world = false; Scope scope; } configuration;
std::atomic<std::uint64_t> capture_until{0}, polls{0}, metadata_ok{0}, found{0}, missing{0}, failures{0}, saved{0}, stale{0};
std::atomic<bool> parameters_capture{false};
std::atomic<bool> world_capture{false};
std::atomic<std::uint64_t> world_frames{0}, world_copies{0}, world_draws{0}, world_errors{0}, world_pixels{0}, world_no_depth{0};
std::atomic<std::uint64_t> world_peak_pixels{0}, world_peak_no_depth{0};
std::atomic<std::uint64_t> world_patches{0};
struct CpuTime {
    std::atomic<std::uint64_t> samples{0}, total_us{0}, peak_us{0};
    void Record(std::chrono::steady_clock::time_point start) {
        const auto us = static_cast<std::uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start).count());
        total_us.fetch_add(us, std::memory_order_relaxed);
        auto peak = peak_us.load(std::memory_order_relaxed);
        while (us > peak && !peak_us.compare_exchange_weak(peak, us, std::memory_order_relaxed)) {}
        samples.fetch_add(1, std::memory_order_relaxed);
    }
    std::uint64_t Average() const { const auto count = samples.load(); return count ? total_us.load() / count : 0; }
} prepare_cpu, submit_cpu;
struct VerifiedBinding {
    DepthBinding::Stage stage = DepthBinding::None;
    UINT slot = 0, bytes = 0;
    explicit operator bool() const { return stage != DepthBinding::None; }
};
struct NativeDepth {
    std::uintptr_t pool = 0, rhi = 0;
    DepthIdentity identity;
    std::uint32_t format = 0;
    bool operator==(const NativeDepth&) const = default;
};
struct ParameterReadback {
    struct Buffer {
        UINT slot = 0, width = 0;
        Com<ID3D11Buffer> staging;
        std::vector<std::uint8_t> bytes;
    };
    std::vector<Buffer> buffers;
    depth_observer::BoundParameters sample;
    Com<ID3D11Device> device;
    std::uint64_t started = 0;
    bool diagnostic = true;
    std::uint64_t discovery_log_ms = 0;
    void Reset() { buffers.clear(); sample = {}; device.reset(); started = 0; }
    void Begin(ID3D11Device* current_device, ID3D11DeviceContext* context, depth_observer::BoundParameters&& bound, bool write_files = true) {
        Reset();
        if (!bound.observed_ms) return;
        diagnostic = write_files;
        sample = std::move(bound); current_device->AddRef(); device.reset(current_device); started = GetTickCount64();
        for (UINT slot = 0; slot < sample.buffers.size(); ++slot) {
            auto& source = sample.buffers[slot];
            if (!source) continue;
            D3D11_BUFFER_DESC desc{}; source->GetDesc(&desc);
            Com<ID3D11Device> owner; source->GetDevice(owner.put());
            if (owner.get() != current_device || !(desc.BindFlags & D3D11_BIND_CONSTANT_BUFFER) ||
                !desc.ByteWidth || desc.ByteWidth > D3D11_REQ_CONSTANT_BUFFER_ELEMENT_COUNT * 16) continue;
            Buffer b; b.slot = slot; b.width = desc.ByteWidth;
            desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = desc.MiscFlags = desc.StructureByteStride = 0;
            desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
            if (FAILED(current_device->CreateBuffer(&desc, nullptr, b.staging.put()))) continue;
            context->CopyResource(b.staging.get(), source.get());
            buffers.push_back(std::move(b));
        }
        // Strong source references were needed only through the copy submission.
        for (auto& source : sample.buffers) source.reset();
    }
    bool Save() {
        const auto root = log::RootDirectory();
        for (const auto& b : buffers) {
            const auto path = root + L"\\depth-view-slot" + std::to_wstring(b.slot) + L".bin";
            FILE* file = _wfopen(path.c_str(), L"wb");
            if (!file) return false;
            const bool written = std::fwrite(b.bytes.data(), 1, b.bytes.size(), file) == b.bytes.size();
            const bool closed = std::fclose(file) == 0;
            if (!written || !closed) return false;
        }
        FILE* file = _wfopen((root + L"\\depth-view.json").c_str(), L"wb");
        if (!file) return false;
        const auto& v = sample.viewport;
        std::fprintf(file, "{\n  \"world\":%u, \"epoch\":%u, \"stage\":\"%s\", \"thread\":%lu,\n"
            "  \"depth_width\":%u, \"depth_height\":%u,\n"
            "  \"raster_viewport\":[%.9g,%.9g,%.9g,%.9g,%.9g,%.9g],\n  \"buffers\":[",
            sample.scope.world, sample.scope.epoch, DepthStageName(sample.stage), sample.thread_id,
            sample.identity.width, sample.identity.height, v.TopLeftX, v.TopLeftY, v.Width, v.Height, v.MinDepth, v.MaxDepth);
        bool first = true;
        for (const auto& b : buffers) {
            std::fprintf(file, "%s{\"slot\":%u,\"bytes\":%u,\"file\":\"depth-view-slot%u.bin\"}", first ? "" : ",", b.slot, b.width, b.slot);
            first = false;
        }
        std::fprintf(file, "],\n  \"coherent_view_candidates\":[");
        first = true;
        for (const auto& b : buffers) {
            ViewParameters view;
            if (!ParseViewParameters(b.bytes, sample.identity.width, sample.identity.height, view)) continue;
            std::fprintf(file, "%s{\"slot\":%u,\"frame\":%u,\"view_rect\":[%u,%u,%u,%u],\"buffer_size\":[%u,%u],"
                "\"near_cm\":%.9g,\"inverse_device_z\":[%.9g,%.9g,%.9g,%.9g],\"camera_cm\":[%.9g,%.9g,%.9g],"
                "\"jitter\":[%.9g,%.9g,%.9g,%.9g]}",
                first ? "" : ",", b.slot, view.frame, view.x, view.y, view.width, view.height, view.buffer_width, view.buffer_height,
                view.near_cm, view.inverse_device_z[0], view.inverse_device_z[1], view.inverse_device_z[2], view.inverse_device_z[3],
                view.camera_cm[0], view.camera_cm[1], view.camera_cm[2], view.jitter[0], view.jitter[1], view.jitter[2], view.jitter[3]);
            first = false;
            SC_LOG("[depth] coherent view slot=%u frame=%u rect=(%u,%u,%u,%u) allocation=%ux%u near_cm=%.9g invz=(%.9g,%.9g,%.9g,%.9g)",
                b.slot, view.frame, view.x, view.y, view.width, view.height, view.buffer_width, view.buffer_height, view.near_cm,
                view.inverse_device_z[0], view.inverse_device_z[1], view.inverse_device_z[2], view.inverse_device_z[3]);
        }
        std::fprintf(file, "]\n}\n");
        const bool written = !std::ferror(file); const bool closed = std::fclose(file) == 0;
        SC_LOG("[depth] view parameters %s stage=%s buffers=%zu viewport=%.0fx%.0f (diagnostic; not a verified ViewRect)",
            written && closed ? "PASS" : "FAIL", DepthStageName(sample.stage), buffers.size(), v.Width, v.Height);
        return written && closed;
    }
    void Poll(ID3D11Device* current_device, ID3D11DeviceContext* context, const Scope& scope, const DepthIdentity& identity, std::uint64_t now, VerifiedBinding* verified) {
        if (!started) return;
        if (current_device != device.get() || !SameScope(scope, sample.scope) || identity != sample.identity || now - started > 2000) {
            ++stale; Reset(); return;
        }
        bool ready = true;
        for (auto& b : buffers) {
            if (!b.staging) continue;
            D3D11_MAPPED_SUBRESOURCE mapped{};
            const auto hr = context->Map(b.staging.get(), 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
            if (hr == DXGI_ERROR_WAS_STILL_DRAWING) { ready = false; continue; }
            if (FAILED(hr)) { ++failures; Reset(); return; }
            const auto* bytes = static_cast<const std::uint8_t*>(mapped.pData);
            b.bytes.assign(bytes, bytes + b.width); context->Unmap(b.staging.get(), 0); b.staging.reset();
        }
        if (ready) {
            std::vector<ViewParameters> views;std::vector<VerifiedBinding> bindings;
            for (const auto& b : buffers) {
                ViewParameters view;
                if (ParseViewParameters(b.bytes, sample.identity.width, sample.identity.height, view)) {
                    views.push_back(view);bindings.push_back({sample.stage,b.slot,b.width});
                }
            }
            const auto& vp=sample.viewport;
            const int selected=SelectViewParameters(views,{vp.TopLeftX,vp.TopLeftY,vp.Width,vp.Height},sample.stage==DepthBinding::PS);
            *verified=selected>=0?bindings[selected]:VerifiedBinding{};
            if (diagnostic) { if (!Save()) ++failures; }
            else if(now>=discovery_log_ms){
                discovery_log_ms=now+5000;
                SC_LOG("[world-image] view discovery candidates=%zu stage=%s selected=%d slot=%u bytes=%u",views.size(),DepthStageName(verified->stage),selected,verified->slot,verified->bytes);
            }
            Reset();
        }
    }
};
NativeDepth Locate(std::uintptr_t base) {
    namespace off = gen::off;
    NativeDepth d;
    d.pool = mem::ReadPtr(base + gen::rva::SceneRenderTargets + off::FSceneRenderTargets::SceneDepthZ);
    if (!d.pool) return {};
    const auto item = d.pool + off::IPooledRenderTarget::RenderTargetItem;
    d.rhi = mem::ReadPtr(item + off::FSceneRenderTargetItem::ShaderResourceTexture);
    if (!d.rhi) d.rhi = mem::ReadPtr(item + off::FSceneRenderTargetItem::TargetableTexture);
    if (!d.rhi || mem::ReadPtr(d.rhi) != base + gen::rva::DepthTextureVtable ||
        mem::ReadOr<std::uint32_t>(d.rhi + off::FRHITexture::NumSamples, 0) != 1) return {};
    d.identity = {mem::ReadPtr(d.rhi + off::D3D11Texture2D::Resource), mem::ReadPtr(d.rhi + off::D3D11Texture2D::ShaderResourceView),
        mem::ReadOr<UINT>(d.rhi + off::FRHITexture2D::SizeX, 0), mem::ReadOr<UINT>(d.rhi + off::FRHITexture2D::SizeY, 0)};
    d.format = mem::ReadOr<std::uint32_t>(d.rhi + off::FRHITexture::Format, 0);
    if (!d.identity.resource || !d.identity.width || !d.identity.height || d.identity.width > 8192 || d.identity.height > 8192 ||
        std::uint64_t(d.identity.width) * d.identity.height > 16 * 1024 * 1024) return {};
    return d;
}
// Never destroy COM objects under CRT/DllMain teardown.
struct Probe {
    DepthCopy copy;
    VerifiedBinding verified;
    DepthIdentity parameter_identity;
    WorldScene world_scene;
    WorldCompositor world_renderer;
    world_image::Reader world_reader;
    world_image::Frame world_frame;
    std::uint64_t scene_ms = 0, verified_last_ms = 0, world_retry_ms = 0, world_error_log_ms = 0, world_wait_log_ms = 0, world_gpu_log_ms = 0;
    bool world_active = false;
    ParameterReadback parameters;
    Com<ID3D11Device> pending_device;
    Com<ID3D11Texture2D> staging;
    D3D11_TEXTURE2D_DESC staged_desc{};
    std::uint64_t next = 0, next_log = 0, pending_since = 0;
    Scope pending_scope;
    Scope live_scope;
    unsigned logs = 0;
    void CancelCapture() { staging.reset(); pending_device.reset(); pending_since = 0; }
    void ResetWorld() {
        world_scene.Reset(); world_renderer.Reset(); world_reader.Close(); world_frame = {}; scene_ms = world_retry_ms = 0; world_active = false;
        world_pixels = world_no_depth = world_patches = 0;
    }
    void Reset() { CancelCapture(); parameters.Reset(); copy.Reset(); verified = {}; parameter_identity = {}; ResetWorld(); }
    bool Save(const D3D11_MAPPED_SUBRESOURCE& mapped) {
        const auto& d = staged_desc;
        if (std::uint64_t(d.Width) * d.Height > 9 * 1024 * 1024) return false;
        std::vector<float> pixels(std::size_t(d.Width) * d.Height);
        float low = 1, high = 0; std::size_t nonzero = 0;
        for (UINT y = 0; y < d.Height; ++y) {
            const auto* row = static_cast<const std::uint8_t*>(mapped.pData) + y * mapped.RowPitch;
            for (UINT x = 0; x < d.Width; ++x) {
                float depth = 0;
                switch (DepthReadFormat(d.Format)) {
                    case DXGI_FORMAT_R24_UNORM_X8_TYPELESS: {
                        std::uint32_t packed; std::memcpy(&packed, row + x * 4, 4); depth = float(packed & 0xFFFFFF) / 16777215.0f; break;
                    }
                    case DXGI_FORMAT_R16_UNORM: {
                        std::uint16_t packed; std::memcpy(&packed, row + x * 2, 2); depth = float(packed) / 65535.0f; break;
                    }
                    case DXGI_FORMAT_R32_FLOAT: std::memcpy(&depth, row + x * 4, 4); break;
                    case DXGI_FORMAT_R32_FLOAT_X8X24_TYPELESS: std::memcpy(&depth, row + x * 8, 4); break;
                    default: return false;
                }
                if (!std::isfinite(depth) || depth < 0 || depth > 1) return false;
                low = std::min(low, depth); high = std::max(high, depth); nonzero += depth > 0;
                pixels[std::size_t(d.Height - 1 - y) * d.Width + x] = depth;
            }
        }
        FILE* file = _wfopen((log::RootDirectory() + L"\\depth-probe.pfm").c_str(), L"wb");
        if (!file) return false;
        std::fprintf(file, "Pf\n%u %u\n-1.0\n", d.Width, d.Height);
        const bool written = std::fwrite(pixels.data(), sizeof(float), pixels.size(), file) == pixels.size();
        const bool closed = std::fclose(file) == 0;
        SC_LOG("[depth] capture %s world=%u epoch=%u size=%ux%u dxgi=%u min=%.9g max=%.9g nonzero=%zu/%zu -> depth-probe.pfm",
            written && closed ? "PASS" : "FAIL", pending_scope.world, pending_scope.epoch, d.Width, d.Height, d.Format, low, high, nonzero, pixels.size());
        return written && closed;
    }
    void Poll(ID3D11Device* device, ID3D11DeviceContext* context, const Scope& scope, std::uint64_t now) {
        if (!staging) return;
        if (device != pending_device.get() || !SameScope(scope, pending_scope) || now - pending_since > 2000) {
            ++stale; CancelCapture(); return;
        }
        D3D11_MAPPED_SUBRESOURCE mapped{};
        const auto hr = context->Map(staging.get(), 0, D3D11_MAP_READ, D3D11_MAP_FLAG_DO_NOT_WAIT, &mapped);
        if (hr == DXGI_ERROR_WAS_STILL_DRAWING) return;
        if (FAILED(hr)) { ++failures; CancelCapture(); return; }
        const bool ok = Save(mapped);
        context->Unmap(staging.get(), 0);
        if (ok) { ++saved; capture_until = 0; } else ++failures;
        CancelCapture();
    }
    void Capture(ID3D11Device* device, ID3D11DeviceContext* context, const Scope& scope, std::uint64_t now) {
        if (staging || !copy.texture() || !capture_until.load()) return;
        auto desc = copy.description();
        desc.Usage = D3D11_USAGE_STAGING; desc.BindFlags = desc.MiscFlags = 0; desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(device->CreateTexture2D(&desc, nullptr, staging.put()))) { ++failures; return; }
        device->AddRef(); pending_device.reset(device);
        staged_desc = desc; pending_scope = scope; pending_since = now;
        context->CopyResource(staging.get(), copy.texture());
    }
} &probe = *new Probe;
}
void Configure(std::uintptr_t image_base, bool enabled, bool capture, bool observe_bindings, bool world_render, const Scope& scope) {
    AcquireSRWLockExclusive(&config_lock); configuration = {image_base, enabled, observe_bindings, world_render && observe_bindings, scope}; ReleaseSRWLockExclusive(&config_lock);
    if (capture) { capture_until = GetTickCount64() + 10000; parameters_capture = observe_bindings; if (observe_bindings) depth_observer::RequestParameters(); }
}
void Stop() {
    AcquireSRWLockExclusive(&config_lock); configuration.enabled = configuration.observe = configuration.world = false; ReleaseSRWLockExclusive(&config_lock);
    capture_until = 0;
    parameters_capture = false;
    depth_observer::RequestParameters(false);
    depth_observer::StreamParameters();
}
void UpdateScope(const Scope& scope) {
    AcquireSRWLockExclusive(&config_lock); configuration.scope = scope; ReleaseSRWLockExclusive(&config_lock);
}
void RequestWorldCapture() { world_capture = true; }
void Tick(IDXGISwapChain* chain) {
    Configuration c;
    AcquireSRWLockShared(&config_lock); c = configuration; ReleaseSRWLockShared(&config_lock);
    const auto now = GetTickCount64();
    const auto deadline = capture_until.load();
    if (deadline && now >= deadline) { capture_until = 0; parameters_capture = false; SC_LOG("[depth] capture deadline: no safely held complete depth copy within 10 seconds"); }
    if ((!c.enabled && !c.observe && !capture_until.load()) || !c.base || !c.scope.world || !Recent(c.scope.updated_ms, now)) {
        depth_observer::RequestParameters(false);
        depth_observer::StreamParameters();
        if (depth_observer::Installed()) depth_observer::Disable();
        probe.Reset(); return;
    }
    if (!SameScope(c.scope, probe.live_scope)) {
        probe.Reset(); probe.live_scope = c.scope; probe.next = 0;
    }
    Com<ID3D11Device> device;
    if (FAILED(chain->GetDevice(__uuidof(ID3D11Device), reinterpret_cast<void**>(device.put())))) { ++failures; return; }
    Com<ID3D11DeviceContext> context; device->GetImmediateContext(context.put());
    const auto rhi = mem::ReadPtr(c.base + gen::rva::GD3D11RHI);
    const bool correct_context = rhi && rhi == mem::ReadPtr(c.base + gen::rva::GDynamicRHI) &&
        mem::ReadPtr(rhi + gen::off::FD3D11DynamicRHI::Direct3DDeviceIMContext) == reinterpret_cast<std::uintptr_t>(context.get()) &&
        mem::ReadPtr(rhi + gen::off::FD3D11DynamicRHI::Direct3DDevice) == reinterpret_cast<std::uintptr_t>(device.get());
    if (c.observe && correct_context) {
        if (!depth_observer::Install(context.get())) ++failures;
    } else if (depth_observer::Installed()) depth_observer::Disable();
    probe.Poll(device.get(), context.get(), c.scope, now);
    const auto expected = Locate(c.base);
    if (expected.identity != probe.parameter_identity) {
        probe.parameters.Reset(); probe.verified = {}; probe.parameter_identity = expected.identity;
        probe.world_scene.Reset(); depth_observer::StreamParameters();
    }
    const bool had_binding = bool(probe.verified);
    probe.parameters.Poll(device.get(), context.get(), c.scope, expected.identity, now, &probe.verified);
    if (!had_binding && probe.verified) probe.verified_last_ms = now;
    if (c.observe && depth_observer::Installed()) depth_observer::SetTarget(c.scope, expected.identity);
    auto bound = depth_observer::TakeParameters(context.get(), c.scope, expected.identity, now);
    const bool world_active = c.world && c.scope.enabled && c.scope.mc_session && c.scope.sifu_session && c.scope.epoch;
    probe.world_scene.Invalidate(); probe.scene_ms = 0;
    if (world_active) {
        probe.world_active = true;
        if (probe.world_reader.Read(c.scope.mc_session, c.scope.sifu_session, c.scope.world, c.scope.epoch, now, &probe.world_frame)) {
            ++world_frames;
            const auto started = std::chrono::steady_clock::now();
            if (FAILED(probe.world_renderer.Prepare(probe.world_frame))) { ++world_errors; probe.world_frame = {}; }
            prepare_cpu.Record(started);
            world_pixels = probe.world_renderer.pixels(); world_no_depth = probe.world_renderer.missing_depth();
            world_patches = probe.world_renderer.patches();
            world_peak_pixels = std::max(world_peak_pixels.load(), world_pixels.load());
            world_peak_no_depth = std::max(world_peak_no_depth.load(), world_no_depth.load());
            if (world_pixels.load() && world_capture.exchange(false)) {
                FILE* file = _wfopen((log::RootDirectory() + L"\\world-frame.bin").c_str(), L"wb");
                bool ok = false;
                if (file) {
                    const auto& frame = probe.world_frame;
                    const bool written = std::fwrite(&frame.header,sizeof(frame.header),1,file)==1 &&
                        std::fwrite(frame.pixels.data(),1,frame.pixels.size(),file)==frame.pixels.size();
                    const bool closed = std::fclose(file)==0; ok=written&&closed;
                }
                SC_LOG("[world-image] frame capture %s -> world-frame.bin", ok ? "PASS" : "FAIL");
            }
        }
        const bool drawable = world_image::ValidFrame(probe.world_frame.header, now) && probe.world_renderer.pixels() &&
            probe.world_frame.header.world_generation == c.scope.world && probe.world_frame.header.anchor_epoch == c.scope.epoch;
        if (drawable && correct_context && depth_observer::Installed() && expected.identity.resource) {
            if (now >= probe.world_wait_log_ms) {
                probe.world_wait_log_ms = now + 3000;
                SC_LOG("[world-image] drawable pixels=%llu verified=%s slot=%u bytes=%u bound=%s has_buffer=%d",
                    static_cast<unsigned long long>(world_pixels.load()), DepthStageName(probe.verified.stage), probe.verified.slot, probe.verified.bytes,
                    DepthStageName(bound.stage), probe.verified && bool(bound.buffers[probe.verified.slot]));
            }
            if (probe.verified && bound.stage == probe.verified.stage && bound.buffers[probe.verified.slot]) {
                auto held = depth_observer::Take(context.get(), c.scope, expected.identity, now);
                if (held.view) {
                    auto result = probe.world_scene.Capture(context.get(), expected.identity, held.view.get(),
                        bound.buffers[probe.verified.slot].get(), probe.verified.bytes);
                    if (result == S_OK && expected == Locate(c.base)) {
                        probe.scene_ms = probe.verified_last_ms = now; ++world_copies;
                    } else {
                        probe.world_scene.Invalidate();
                        if (FAILED(result)) { ++world_errors; probe.verified = {}; }
                    }
                    // Explicit diagnostic captures may share this safely held reference.
                    if (capture_until.load() && probe.copy.CaptureHeld(context.get(), expected.identity, held.view.get()) == S_OK)
                        probe.Capture(device.get(), context.get(), c.scope, now);
                }
            }
            if (probe.verified && now - probe.verified_last_ms > 250) probe.verified = {};
            if (probe.verified) depth_observer::StreamParameters(probe.verified.stage, probe.verified.slot);
            else {
                depth_observer::StreamParameters();
                if (!probe.parameters.started) {
                    if (bound.observed_ms) probe.parameters.Begin(device.get(), context.get(), std::move(bound), parameters_capture.exchange(false));
                    else depth_observer::RequestParameters();
                }
            }
        } else depth_observer::StreamParameters();
    } else {
        depth_observer::StreamParameters();
        if (probe.world_active) { probe.ResetWorld(); probe.verified = {}; probe.parameters.Reset(); }
    }
    if (parameters_capture.load() && bound.observed_ms && !probe.parameters.started)
        probe.parameters.Begin(device.get(), context.get(), std::move(bound), parameters_capture.exchange(false));
    if (world_active && probe.scene_ms == now) return; // Production already copied the current scene; do not rescan empty bindings.
    if (now < probe.next) return;
    probe.next = now + 250; ++polls;
    if (!expected.identity.resource) { ++missing; return; }
    ++metadata_ok;
    DepthBinding binding;
    auto hr = probe.copy.Capture(context.get(), expected.identity, &binding);
    DWORD observer_thread = 0;
    if (hr == S_FALSE && c.observe && depth_observer::Installed()) {
        auto held = depth_observer::Take(context.get(), c.scope, expected.identity, now);
        if (held.view) {
            hr = probe.copy.CaptureHeld(context.get(), expected.identity, held.view.get());
            binding.stage = held.stage; binding.slot = held.slot; observer_thread = held.thread_id;
        }
    }
    if (hr == S_OK && expected != Locate(c.base)) { ++stale; probe.copy.Invalidate(); return; }
    if (hr == S_OK) { ++found; probe.Capture(device.get(), context.get(), c.scope, now); }
    else if (hr == S_FALSE) ++missing;
    else ++failures;
    if (now >= probe.next_log && probe.logs++ < 120) {
        probe.next_log = now + 2000;
        SC_LOG("[depth] H8 sample world=%u source=%ux%u pf=%u bound=%s slot=%u views=%u hr=0x%08lX copied=%llu missing=%llu observer_tid=%lu present_tid=%lu context_ok=%d",
            c.scope.world, expected.identity.width, expected.identity.height, expected.format, DepthStageName(binding.stage), binding.slot,
            binding.views_seen, static_cast<unsigned long>(hr), static_cast<unsigned long long>(found.load()), static_cast<unsigned long long>(missing.load()),
            observer_thread, GetCurrentThreadId(), correct_context);
    }
}
void DrawWorld(ID3D11Device* device, ID3D11Texture2D* backbuffer, const Scope& scope, std::uint64_t now) {
    if (!probe.world_active || !scope.enabled || !SameScope(scope, probe.live_scope) || !Recent(scope.updated_ms, now) ||
        !Recent(probe.scene_ms, now) || !world_image::ValidFrame(probe.world_frame.header, now) || !probe.world_scene.depth() || now < probe.world_retry_ms) return;
    const auto started = std::chrono::steady_clock::now();
    const auto result = probe.world_renderer.Draw(device, backbuffer, probe.world_scene);
    submit_cpu.Record(started);
    if (result == S_OK) {
        if (world_draws.fetch_add(1) == 0) SC_LOG("[world-image] first native draw world=%u epoch=%u source=%ux%u visible=%llu no_depth=%llu",
            scope.world, scope.epoch, probe.world_frame.header.width, probe.world_frame.header.height,
            static_cast<unsigned long long>(world_pixels.load()), static_cast<unsigned long long>(world_no_depth.load()));
        if(now>=probe.world_gpu_log_ms){
            probe.world_gpu_log_ms=now+2000;const auto gpu=probe.world_renderer.gpuStats();
            SC_LOG("[world-gpu] frame=%llu pixels=%llu patches=%llu n=%llu total_ns=%llu peak_ns=%llu disjoint=%llu skipped=%llu errors=%llu prepare_n=%llu prepare_total_us=%llu submit_n=%llu submit_total_us=%llu",
                probe.world_frame.header.frame_id,world_pixels.load(),world_patches.load(),gpu.samples,gpu.total_ns,gpu.peak_ns,gpu.disjoint,gpu.skipped,gpu.errors,
                prepare_cpu.samples.load(),prepare_cpu.total_us.load(),submit_cpu.samples.load(),submit_cpu.total_us.load());
        }
    } else if (FAILED(result)) {
        ++world_errors; probe.world_retry_ms = now + 1000;
        if (now >= probe.world_error_log_ms) {
            probe.world_error_log_ms = now + 10000;
            SC_LOG("[world-image] draw failure HRESULT=0x%08lX; retry after one second", static_cast<unsigned long>(result));
        }
        probe.world_scene.Invalidate();
    }
}
std::string TakeStats() {
    const auto gpu=probe.world_renderer.gpuStats();
    return FormatString("polls=%llu metadata=%llu copied=%llu missing=%llu failures=%llu stale=%llu saved=%llu",
        static_cast<unsigned long long>(polls.load()), static_cast<unsigned long long>(metadata_ok.load()), static_cast<unsigned long long>(found.load()),
        static_cast<unsigned long long>(missing.load()), static_cast<unsigned long long>(failures.load()), static_cast<unsigned long long>(stale.load()), static_cast<unsigned long long>(saved.load())) +
        " | " + depth_observer::TakeStats() + FormatString(" | world frames=%llu copies=%llu draws=%llu pixels=%llu no_depth=%llu errors=%llu peak_pixels=%llu peak_no_depth=%llu",
            static_cast<unsigned long long>(world_frames.load()), static_cast<unsigned long long>(world_copies.load()),
            static_cast<unsigned long long>(world_draws.load()), static_cast<unsigned long long>(world_pixels.load()),
            static_cast<unsigned long long>(world_no_depth.load()), static_cast<unsigned long long>(world_errors.load()),
            static_cast<unsigned long long>(world_peak_pixels.load()), static_cast<unsigned long long>(world_peak_no_depth.load())) +
        FormatString(" | world_cpu prepare_n=%llu prepare_avg_us=%llu prepare_peak_us=%llu submit_n=%llu submit_avg_us=%llu submit_peak_us=%llu patches=%llu",
            static_cast<unsigned long long>(prepare_cpu.samples.load()), static_cast<unsigned long long>(prepare_cpu.Average()),
            static_cast<unsigned long long>(prepare_cpu.peak_us.load()), static_cast<unsigned long long>(submit_cpu.samples.load()),
            static_cast<unsigned long long>(submit_cpu.Average()), static_cast<unsigned long long>(submit_cpu.peak_us.load()),static_cast<unsigned long long>(world_patches.load()))+
        FormatString(" | world_gpu_draw n=%llu avg_us=%llu peak_us=%llu disjoint=%llu skipped=%llu errors=%llu",
            static_cast<unsigned long long>(gpu.samples),static_cast<unsigned long long>(gpu.samples?gpu.total_ns/gpu.samples/1000:0),static_cast<unsigned long long>(gpu.peak_ns/1000),
            static_cast<unsigned long long>(gpu.disjoint),static_cast<unsigned long long>(gpu.skipped),static_cast<unsigned long long>(gpu.errors));
}
}
