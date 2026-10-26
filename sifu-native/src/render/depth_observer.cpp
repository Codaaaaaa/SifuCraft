#include "render/depth_observer.h"
#include <windows.h>
#include <atomic>
#include <cwchar>
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/vtable_hook.h"

namespace sifucraft::render::depth_observer {
namespace {
using Setter = void(STDMETHODCALLTYPE*)(ID3D11DeviceContext*, UINT, UINT, ID3D11ShaderResourceView* const*);
constexpr std::size_t kVariants = 8;
struct Forwarders { std::atomic<Setter> ps{nullptr}, cs{nullptr}; } forwarders[kVariants];
std::atomic<std::uint64_t> rearms{0}, rearm_failures{0};
std::atomic<ID3D11DeviceContext*> target{nullptr};
std::atomic<bool> enabled{false}, installed{false};
std::atomic<std::uint64_t> parameters_until{0};
std::atomic<unsigned> parameters_stream{0};
std::atomic<std::uint64_t> calls_ps{0}, calls_cs{0}, matched{0}, captured{0}, busy{0}, stale{0}, rejected{0};
thread_local bool observing = false;
struct State {
    std::size_t variant = kVariants;
    hooks::VtableHook ps, cs;
    gen::hook::Target ps_plan{"DepthPSBind", 0, 8 * sizeof(void*), 0};
    gen::hook::Target cs_plan{"DepthCSBind", 0, 67 * sizeof(void*), 0};
    Com<ID3D11DeviceContext> context;
    SRWLOCK lock = SRWLOCK_INIT;
    Scope scope;
    DepthIdentity expected;
    std::atomic<std::uintptr_t> expected_srv{0};
    HeldDepth pending;
    BoundParameters parameters;
} &state = *new State;
bool D3d11Code(std::uintptr_t address) {
    MEMORY_BASIC_INFORMATION page{};
    if (!VirtualQuery(reinterpret_cast<void*>(address), &page, sizeof(page)) || page.State != MEM_COMMIT ||
        !(page.Protect & (PAGE_EXECUTE | PAGE_EXECUTE_READ | PAGE_EXECUTE_READWRITE | PAGE_EXECUTE_WRITECOPY))) return false;
    HMODULE module = nullptr;
    if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
            reinterpret_cast<LPCWSTR>(address), &module)) return false;
    wchar_t path[MAX_PATH]{};
    if (!GetModuleFileNameW(module, path, MAX_PATH)) return false;
    const auto* leaf = std::wcsrchr(path, L'\\');
    return _wcsicmp(leaf ? leaf + 1 : path, L"d3d11.dll") == 0;
}
void Observe(ID3D11DeviceContext* context, UINT start, UINT count, ID3D11ShaderResourceView* const* views, bool compute) {
    if (!enabled.load(std::memory_order_relaxed) || context != target.load(std::memory_order_relaxed) || observing || !views ||
        count > D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT || start > D3D11_COMMONSHADER_INPUT_RESOURCE_SLOT_COUNT - count) return;
    const auto wanted = state.expected_srv.load(std::memory_order_relaxed);
    if (!wanted) return;
    for (UINT i = 0; i < count; ++i) {
        if (reinterpret_cast<std::uintptr_t>(views[i]) != wanted) continue;
        ++matched;
        if (!TryAcquireSRWLockExclusive(&state.lock)) { ++busy; return; }
        observing = true;
        const auto now = GetTickCount64();
        if (enabled.load(std::memory_order_relaxed) && context == target.load(std::memory_order_relaxed) &&
            state.expected.srv == wanted && Recent(state.scope.updated_ms, now)) {
            Com<ID3D11ShaderResourceView> held;
            if (compute) context->CSGetShaderResources(start + i, 1, held.put());
            else context->PSGetShaderResources(start + i, 1, held.put());
            if (held && reinterpret_cast<std::uintptr_t>(held.get()) == wanted) {
                Com<ID3D11Resource> resource; held->GetResource(resource.put());
                if (reinterpret_cast<std::uintptr_t>(resource.get()) == state.expected.resource) {
                    state.pending = {std::move(held), state.expected, state.scope, now, GetCurrentThreadId(),
                        compute ? DepthBinding::CS : DepthBinding::PS, start + i};
                    ++captured;
                    const auto parameters_deadline = parameters_until.exchange(0);
                    const bool discover = parameters_deadline && now <= parameters_deadline;
                    const auto stream = parameters_stream.load(std::memory_order_relaxed);
                    const auto stage = compute ? DepthBinding::CS : DepthBinding::PS;
                    const bool selected = stream && (stream >> 8) == unsigned(stage) && !state.parameters.full_snapshot;
                    if (discover || selected) {
                        BoundParameters sample;
                        sample.scope = state.scope; sample.identity = state.expected; sample.observed_ms = now;
                        sample.thread_id = GetCurrentThreadId(); sample.stage = stage; sample.full_snapshot = discover;
                        if (discover) {
                            ID3D11Buffer* buffers[D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT]{};
                            if (compute) context->CSGetConstantBuffers(0, D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT, buffers);
                            else context->PSGetConstantBuffers(0, D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT, buffers);
                            for (UINT slot = 0; slot < sample.buffers.size(); ++slot) sample.buffers[slot].reset(buffers[slot]);
                            UINT count = 1; context->RSGetViewports(&count, &sample.viewport);
                            if (count != 1) sample.viewport = {};
                        } else {
                            const auto slot = (stream & 255u) - 1;
                            if (compute) context->CSGetConstantBuffers(slot, 1, sample.buffers[slot].put());
                            else context->PSGetConstantBuffers(slot, 1, sample.buffers[slot].put());
                            // Later passes may bind depth before their constants, or with
                            // no View buffer at all. Do not erase a valid reference from an
                            // earlier pass in this same frame with an empty slot.
                            if (!sample.buffers[slot]) {
                                observing = false; ReleaseSRWLockExclusive(&state.lock); return;
                            }
                        }
                        state.parameters = std::move(sample);
                    }
                }
            }
        } else ++rejected;
        observing = false;
        ReleaseSRWLockExclusive(&state.lock);
        return;
    }
}
template<std::size_t Variant> void STDMETHODCALLTYPE PixelHook(ID3D11DeviceContext* context, UINT start, UINT count, ID3D11ShaderResourceView* const* views) {
    ++calls_ps;
    const auto forward = forwarders[Variant].ps.load(std::memory_order_acquire);
    if (forward) forward(context, start, count, views);
    Observe(context, start, count, views, false);
}
template<std::size_t Variant> void STDMETHODCALLTYPE ComputeHook(ID3D11DeviceContext* context, UINT start, UINT count, ID3D11ShaderResourceView* const* views) {
    ++calls_cs;
    const auto forward = forwarders[Variant].cs.load(std::memory_order_acquire);
    if (forward) forward(context, start, count, views);
    Observe(context, start, count, views, true);
}
constexpr Setter pixel_hooks[] = {PixelHook<0>,PixelHook<1>,PixelHook<2>,PixelHook<3>,PixelHook<4>,PixelHook<5>,PixelHook<6>,PixelHook<7>};
constexpr Setter compute_hooks[] = {ComputeHook<0>,ComputeHook<1>,ComputeHook<2>,ComputeHook<3>,ComputeHook<4>,ComputeHook<5>,ComputeHook<6>,ComputeHook<7>};
bool RestoreSlot(hooks::VtableHook& hook) {
    if (!hook.installed()) return true;
    const auto actual = mem::ReadPtr(hook.slot_address());
    // D3D11 has already replaced our slot with its own implementation. Never
    // write the old implementation over a newer runtime-selected one.
    if (D3d11Code(actual)) return hook.RetireRestored(actual);
    return hook.Uninstall(); // CAS still refuses any unknown third-party entry.
}
bool RestoreSlots() {
    enabled = false;
    const bool ps = RestoreSlot(state.ps), cs = RestoreSlot(state.cs);
    installed = state.ps.installed() || state.cs.installed();
    return ps && cs;
}
bool Live(ID3D11DeviceContext* context) {
    if (state.variant >= kVariants) return false;
    const auto table = mem::ReadPtr(reinterpret_cast<std::uintptr_t>(context));
    return table + state.ps_plan.slot == state.ps.slot_address() && table + state.cs_plan.slot == state.cs.slot_address() &&
        mem::ReadPtr(table + state.ps_plan.slot) == reinterpret_cast<std::uintptr_t>(pixel_hooks[state.variant]) &&
        mem::ReadPtr(table + state.cs_plan.slot) == reinterpret_cast<std::uintptr_t>(compute_hooks[state.variant]);
}
}
bool Installed() { return installed.load(std::memory_order_acquire); }
bool Disable() {
    enabled = false; state.expected_srv = 0;
    parameters_stream = 0;
    // Immutable forwarders remain available to a late caller that loaded an old slot.
    if (!RestoreSlots()) return false;
    if (!TryAcquireSRWLockExclusive(&state.lock)) return false;
    target = nullptr; state.pending = {}; state.parameters = {}; state.expected = {}; state.scope = {}; state.context.reset();
    ReleaseSRWLockExclusive(&state.lock);
    installed = false;
    return true;
}
bool Install(ID3D11DeviceContext* context) {
    if (!context || context->GetType() != D3D11_DEVICE_CONTEXT_IMMEDIATE) return false;
    if (Installed() && enabled.load() && target.load() == context && Live(context)) return true;
    const bool same_context = state.context.get() == context;
    if (same_context ? !RestoreSlots() : !Disable()) return false;
    const auto object = reinterpret_cast<std::uintptr_t>(context);
    const auto vtable = mem::ReadPtr(object);
    const auto ps = mem::ReadPtr(vtable + state.ps_plan.slot), cs = mem::ReadPtr(vtable + state.cs_plan.slot);
    if (!D3d11Code(ps) || !D3d11Code(cs)) return false;
    std::size_t variant = 0;
    for (; variant < kVariants; ++variant) {
        if (!forwarders[variant].ps.load()) {
            forwarders[variant].ps = reinterpret_cast<Setter>(ps); forwarders[variant].cs = reinterpret_cast<Setter>(cs);
            SC_LOG("[depth] binding implementation variant=%zu context=%p ps=%p cs=%p", variant, context, reinterpret_cast<void*>(ps), reinterpret_cast<void*>(cs));
            break;
        }
        if (forwarders[variant].ps.load() == reinterpret_cast<Setter>(ps) && forwarders[variant].cs.load() == reinterpret_cast<Setter>(cs)) break;
    }
    if (variant == kVariants) return false;
    state.variant = variant;
    state.ps_plan.vftable = state.cs_plan.vftable = vtable;
    state.ps_plan.original = ps; state.cs_plan.original = cs;
    if (!same_context) { context->AddRef(); state.context.reset(context); }
    target = context;
    if (!state.ps.Install(0, state.ps_plan, object, reinterpret_cast<void*>(pixel_hooks[variant]), false) ||
        !state.cs.Install(0, state.cs_plan, object, reinterpret_cast<void*>(compute_hooks[variant]), false)) { Disable(); return false; }
    installed = true; enabled = true; ++rearms;
    return true;
}
bool Rearm() {
    if (!enabled.load() || !state.context) return true;
    if (Install(state.context.get())) return true;
    ++rearm_failures; return false;
}
void SetTarget(const Scope& scope, const DepthIdentity& identity) {
    if (!TryAcquireSRWLockExclusive(&state.lock)) { ++busy; return; }
    if (!SameScope(scope, state.scope) || identity != state.expected) { state.pending = {}; state.parameters = {}; }
    state.scope = scope; state.expected = identity;
    state.expected_srv = identity.resource && identity.width && identity.height && scope.world && scope.sifu_session ? identity.srv : 0;
    ReleaseSRWLockExclusive(&state.lock);
}
HeldDepth Take(ID3D11DeviceContext* context, const Scope& scope, const DepthIdentity& identity, std::uint64_t now_ms) {
    HeldDepth held;
    if (!TryAcquireSRWLockExclusive(&state.lock)) { ++busy; return held; }
    if (state.pending.view) {
        if (enabled.load() && context == target.load() && SameScope(scope, state.pending.scope) && identity == state.pending.identity &&
            Recent(state.pending.observed_ms, now_ms) && Recent(state.pending.scope.updated_ms, now_ms)) held = std::move(state.pending);
        else { ++stale; state.pending = {}; }
    }
    ReleaseSRWLockExclusive(&state.lock);
    return held;
}
void RequestParameters(bool requested) { parameters_until = requested ? GetTickCount64() + 10000 : 0; }
void StreamParameters(DepthBinding::Stage stage, UINT slot) {
    parameters_stream = (stage == DepthBinding::PS || stage == DepthBinding::CS) && slot < D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT
        ? (unsigned(stage) << 8) | (slot + 1) : 0;
}
BoundParameters TakeParameters(ID3D11DeviceContext* context, const Scope& scope, const DepthIdentity& identity, std::uint64_t now_ms) {
    BoundParameters sample;
    if (!TryAcquireSRWLockExclusive(&state.lock)) { ++busy; return sample; }
    if (state.parameters.observed_ms) {
        if (enabled.load() && context == target.load() && SameScope(scope, state.parameters.scope) && identity == state.parameters.identity &&
            Recent(state.parameters.observed_ms, now_ms) && Recent(state.parameters.scope.updated_ms, now_ms)) sample = std::move(state.parameters);
        state.parameters = {};
    }
    ReleaseSRWLockExclusive(&state.lock);
    return sample;
}
std::string TakeStats() {
    return FormatString("H9/H10=%s ps=%llu cs=%llu matched=%llu held=%llu busy=%llu stale=%llu rejected=%llu rearms=%llu rearm_failures=%llu", Installed() ? "on" : "off",
        static_cast<unsigned long long>(calls_ps.load()), static_cast<unsigned long long>(calls_cs.load()),
        static_cast<unsigned long long>(matched.load()), static_cast<unsigned long long>(captured.load()),
        static_cast<unsigned long long>(busy.load()), static_cast<unsigned long long>(stale.load()), static_cast<unsigned long long>(rejected.load()),
        static_cast<unsigned long long>(rearms.load()), static_cast<unsigned long long>(rearm_failures.load()));
}
void OnProcessExit() { enabled = false; }
}
