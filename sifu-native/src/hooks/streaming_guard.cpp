#include "hooks/streaming_guard.h"
#include <windows.h>
#include <atomic>
#include <array>
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/streaming_lease.h"
#include "input/input_router.h"
#include "probe/probe.h"
#include "ue/function_call.h"
#include "ue/names.h"
#include "ue/reflection.h"

namespace sifucraft::hooks::streaming_guard {
namespace {
namespace off = gen::off;
struct Calls {
    std::uintptr_t library = 0;
    ue::FunctionCall set;
    ue::ObjectIdentity library_id, function_id;
    int offset = -1;
    std::uint8_t mask = 0;
};
Calls g_calls;
std::atomic<bool> g_ready{false}, g_failed{false};
std::uint64_t g_retry_ms = 0;
SRWLOCK g_lock = SRWLOCK_INIT;
Context g_context;
std::atomic<std::uint64_t> g_freezes{0}, g_restores{0}, g_refused{0};
// Game thread only.
StreamingLease g_lease;
std::uintptr_t g_manager = 0, g_world = 0;
ue::ObjectIdentity g_world_id;
unsigned g_logged = 0;

bool Set(bool enabled) {
    if (!ue::IsRegistered(g_calls.library) || ue::IdentityOf(g_calls.library) != g_calls.library_id ||
        !ue::IsRegistered(g_calls.set.function()) || ue::IdentityOf(g_calls.set.function()) != g_calls.function_id) return false;
    std::array<std::uint8_t, 16> params{};
    if (enabled) params[g_calls.offset] = g_calls.mask;
    g_calls.set.Invoke(g_calls.library, params.data());
    const auto actual = mem::ReadOr<std::uint8_t>(g_manager + off::FSCLevelStreamingManager::m_bStreamingVolumesEnabled, 0xFF);
    if (actual != static_cast<std::uint8_t>(enabled)) return false;
    (enabled ? g_restores : g_freezes).fetch_add(1);
    if (g_logged++ < 80) SC_LOG("[streaming] volumes=%s world=%s (temporary MC airborne override)",
        enabled ? "enabled/restored" : "frozen", ue::ObjectName(g_world).c_str());
    return true;
}
}

void Resolve() {
    if (g_ready.load(std::memory_order_acquire) || g_failed.load()) return;
    const auto now = GetTickCount64();
    if (now - g_retry_ms < 5000) return;
    g_retry_ms = now;
    const auto cls = ue::FindClass("SCLevelStreamingManagerHelpers");
    Calls calls;
    calls.library = cls ? ue::DefaultObject(cls) : 0;
    if (!calls.library || !calls.set.Resolve(cls, "BPF_SetStreamingVolumesEnabled")) return;
    const auto function = calls.set.function();
    const auto flags = mem::ReadOr<std::uint32_t>(function + off::UFunction::FunctionFlags, 0);
    const auto properties = ue::ListProperties(function);
    bool valid = ue::OuterOf(function) == cls && (flags & 0x2400u) == 0x2400u &&
        calls.set.params_size() > 0 && calls.set.params_size() <= 16 && properties.size() == 1;
    if (valid) {
        const auto& property = properties.front();
        calls.offset = property.offset;
        calls.mask = mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::ByteMask, 0);
        valid = property.name == "_bEnabled" && property.type == "BoolProperty" && property.array_dim == 1 &&
            property.element_size == 1 && property.offset >= 0 && property.offset < calls.set.params_size() &&
            mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::FieldSize, 0) == 1 &&
            mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::ByteOffset, 0xFF) == 0 &&
            calls.mask && (calls.mask == 0xFF || (calls.mask & (calls.mask - 1)) == 0);
    }
    if (!valid) {
        g_failed.store(true);
        SC_LOG("[streaming] REFUSED: streaming helper reflected signature mismatch");
        return;
    }
    calls.library_id = ue::IdentityOf(calls.library);
    calls.function_id = ue::IdentityOf(function);
    g_calls = calls;
    SC_LOG("[streaming] selfcheck PASS: BPF_SetStreamingVolumesEnabled params=%d bool@%d mask=%u; no new hook/direct write",
        calls.set.params_size(), calls.offset, calls.mask);
    g_ready.store(true, std::memory_order_release);
}

void UpdateContext(const Context& context) {
    AcquireSRWLockExclusive(&g_lock);
    g_context = context;
    ReleaseSRWLockExclusive(&g_lock);
}

void ApplyOnGameThread(std::uintptr_t base, int stage) {
    if (!g_ready.load(std::memory_order_acquire)) return;
    Context context;
    AcquireSRWLockShared(&g_lock);
    context = g_context;
    ReleaseSRWLockShared(&g_lock);
    const auto manager = mem::ReadPtr(base + gen::rva::LevelStreamingManager);
    const auto world = manager ? ue::ResolveWeak(manager + off::FSCLevelStreamingManager::m_World) : 0;
    const auto world_id = ue::IdentityOf(world);
    // A new manager/world owns its own state; never restore a previous world's saved value into it.
    if (manager != g_manager || world != g_world || world_id != g_world_id) {
        g_lease.Discard(); g_manager = manager; g_world = world; g_world_id = world_id;
    }
    if (!manager || !ue::IsLive(world)) return; // never create a manager through the static helper
    const auto enabled = mem::ReadOr<std::uint8_t>(manager + off::FSCLevelStreamingManager::m_bStreamingVolumesEnabled, 0xFF);
    if (enabled > 1) { g_refused.fetch_add(1); return; }
    const auto now = GetTickCount64();
    const bool active = stage >= 8 && !g_failed.load() && context.active && context.world == world &&
        context.world_identity == world_id && context.pawn_identity.valid() &&
        input::Router().owner() == protocol::InputOwner::Minecraft &&
        mem::ReadPtr(base + gen::rva::GWorld) == world && now >= context.updated_ms && now - context.updated_ms < 500 &&
        ue::IsLive(context.pawn) && ue::IdentityOf(context.pawn) == context.pawn_identity &&
        probe::FindPlayerObjects(world).pawn == context.pawn;
    const auto change = g_lease.Update(active && context.freeze, enabled != 0);
    if (change && !Set(*change)) {
        g_refused.fetch_add(1); g_failed.store(true);
        SC_LOG("[streaming] REFUSED: helper invocation/readback failed; protection disabled");
    }
}

std::string TakeStats() {
    return FormatString("ready=%d disabled=%d freeze=%llu restore=%llu refused=%llu", g_ready.load(), g_failed.load(),
        static_cast<unsigned long long>(g_freezes.load()), static_cast<unsigned long long>(g_restores.load()),
        static_cast<unsigned long long>(g_refused.load()));
}
}
