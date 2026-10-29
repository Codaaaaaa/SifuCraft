#include "interaction/native_interaction.h"
#include <windows.h>
#include <deque>
#include <cstring>
#include <atomic>
#include <cmath>
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/game_hooks.h"
#include "input/input_router.h"
#include "interaction/intent.h"
#include "interaction/door_motion.h"
#include "collision/components.h"
#include "ue/transform.h"
#include "ue/names.h"

namespace sifucraft::interaction {
namespace {
namespace off = gen::off;
SRWLOCK g_lock = SRWLOCK_INIT;
Context g_context;
protocol::InteractionSnapshot g_snapshot{};
struct Job { protocol::Event event; std::uint64_t at; };
std::deque<Job> g_jobs;
std::vector<Reply> g_replies;
Requests g_requests;
DoorMotionWindow g_door_motion;
std::atomic<std::uint64_t> g_door_starts{0}, g_door_corrections{0};
std::atomic<std::uint64_t> g_sent{0}, g_rejected{0}, g_releases{0};
// Game-thread state.
Context g_held;
bool g_down = false;
std::uint64_t g_revision = 0, g_frame = 0;
std::uint32_t g_last_target = 0, g_last_serial = 0, g_last_world = 0, g_last_epoch = 0;
unsigned g_logs = 0;
MotionScope ScopeOf(const Context& c) {
    return {c.session, c.generation, c.epoch, c.pawn_id.index, c.pawn_id.serial, c.controller_id.index, c.controller_id.serial};
}
std::uintptr_t Current(const Context& c) {
    const auto component = ue::ResolveWeak(c.controller + off::AFightingPlayerController::m_CurrentInteractionObjectComponent);
    if (!ue::IsLive(component) || !collision::VisibleLevelOf(component, c.world)) return 0;
    auto cls = ue::ClassOf(component);
    for (int i = 0; cls && i < 64; ++i, cls = ue::SuperOf(cls))
        if (ue::ObjectName(cls) == "InteractionObjectComponent") return component;
    return 0;
}
bool Active(std::uintptr_t base, int stage, const Context& c) {
    const auto now = GetTickCount64();
    return stage >= 8 && c.active && c.session && c.epoch && c.generation && now >= c.updated_ms && now - c.updated_ms < 500 &&
        input::Router().owner() == protocol::InputOwner::Minecraft && hooks::PoseActive() && hooks::InputHooksInstalled() &&
        hooks::InteractionKeyReady() &&
        c.world == mem::ReadPtr(base + gen::rva::GWorld) && ue::IsLive(c.controller) && ue::IsLive(c.pawn) &&
        ue::IdentityOf(c.controller) == c.controller_id && ue::IdentityOf(c.pawn) == c.pawn_id;
}
void Result(const Job& job, const protocol::InteractPayload& p, unsigned outcome) {
    AcquireSRWLockExclusive(&g_lock);
    if (g_replies.size() < 64) g_replies.push_back({job.event.source_session,
        {job.event.id, p.revision, outcome, p.target, p.world, p.epoch}});
    ReleaseSRWLockExclusive(&g_lock);
    (outcome == 0 ? g_sent : g_rejected).fetch_add(1);
}
}
void UpdateContext(const Context& c) {
    AcquireSRWLockExclusive(&g_lock);
    g_context = c;
    g_door_motion.UpdateScope(ScopeOf(c), c.pose_owned);
    ReleaseSRWLockExclusive(&g_lock);
}
bool PreserveDoorView(std::uintptr_t pawn, float correction_cm) {
    const auto now = GetTickCount64();
    const auto identity = ue::IdentityOf(pawn);
    AcquireSRWLockExclusive(&g_lock);
    const auto& c = g_context;
    const bool fresh = c.pose_owned && c.pawn == pawn && c.pawn_id == identity &&
        now >= c.updated_ms && now - c.updated_ms < 500;
    g_door_motion.UpdateScope(ScopeOf(c), fresh);
    const bool keep = fresh && g_door_motion.Observe(ScopeOf(c), now, correction_cm);
    ReleaseSRWLockExclusive(&g_lock);
    if (keep) g_door_corrections.fetch_add(1, std::memory_order_relaxed);
    return keep;
}
void Submit(const protocol::Event& event) {
    if (event.size != sizeof(protocol::InteractPayload)) return;
    AcquireSRWLockExclusive(&g_lock);
    if (g_jobs.size() < 8) g_jobs.push_back({event, GetTickCount64()});
    else g_rejected.fetch_add(1);
    ReleaseSRWLockExclusive(&g_lock);
}
void BeforeTick(std::uintptr_t base, int stage) {
    Context c; Job job{}; protocol::InteractionSnapshot target{}; bool have = false;
    AcquireSRWLockExclusive(&g_lock);
    c = g_context; target = g_snapshot;
    if (!g_jobs.empty()) { job = g_jobs.front(); g_jobs.pop_front(); have = true; }
    ReleaseSRWLockExclusive(&g_lock);
    if (!have) return;
    protocol::InteractPayload p{}; std::memcpy(&p, job.event.payload, sizeof(p));
    if (!Active(base, stage, c) || c.session != job.event.source_session ||
        !g_requests.Claim(job.event.source_session, job.event.id) || GetTickCount64() - job.at > 250 ||
        p.flags || p.reserved || p.world != c.generation || p.epoch != c.epoch) { Result(job, p, 1); return; }
    const auto component = Current(c);
    const auto id = ue::IdentityOf(component);
    const auto owner = ue::OuterOf(component);
    const auto owner_id = ue::IdentityOf(owner);
    if (!(target.flags & protocol::kInteractAvailable) || p.revision != target.revision ||
        p.target != target.target || p.serial != target.serial || static_cast<std::uint32_t>(id.index) != p.target ||
        static_cast<std::uint32_t>(id.serial) != p.serial || !ue::IsLive(owner) ||
        static_cast<std::uint32_t>(owner_id.index) != target.owner || static_cast<std::uint32_t>(owner_id.serial) != target.owner_serial ||
        !InRange(c.eye, target)) { Result(job, p, 2); return; }
    if (g_down || !hooks::SendInteractionKey(c.controller, true)) { Result(job, p, 3); return; }
    const bool door = IsDoorClass(ue::ClassName(owner));
    AcquireSRWLockExclusive(&g_lock);
    if (ScopeOf(g_context) == ScopeOf(c) && g_context.pose_owned)
        g_door_motion.Arm(ScopeOf(c), GetTickCount64(), door);
    ReleaseSRWLockExclusive(&g_lock);
    if (door) {
        g_door_starts.fetch_add(1, std::memory_order_relaxed);
        if (g_logs++ < 100) SC_LOG("[interaction] keep MC view for door animation: %s, <=%llu ms, <=%.0f cm/frame",
            ue::ClassName(owner).c_str(), static_cast<unsigned long long>(DoorMotionWindow::kDurationMs), DoorMotionWindow::kMaxCorrectionCm);
    }
    g_down = true; g_held = c;
    Result(job, p, 0);
    if (g_logs++ < 100) SC_LOG("[interaction] dispatched Q request=%llu target=%s world=%u epoch=%u (native validation decides outcome)",
        static_cast<unsigned long long>(job.event.id), ue::ObjectName(component).c_str(), c.generation, c.epoch);
}
void AfterTick(std::uintptr_t base, int stage) {
    if (g_down) {
        if (g_held.world == mem::ReadPtr(base + gen::rva::GWorld) &&
            ue::IsLive(g_held.controller) && ue::IdentityOf(g_held.controller) == g_held.controller_id &&
            hooks::SendInteractionKey(g_held.controller, false)) g_releases.fetch_add(1);
        g_down = false;
    }
    Context c;
    AcquireSRWLockShared(&g_lock); c = g_context; ReleaseSRWLockShared(&g_lock);
    protocol::InteractionSnapshot s{}; s.frame = ++g_frame; s.world = c.generation; s.epoch = c.epoch;
    const auto component = Active(base, stage, c) ? Current(c) : 0;
    const auto owner = ue::OuterOf(component);
    if (component && ue::IsLive(owner)) {
        const auto id = ue::IdentityOf(component), owner_id = ue::IdentityOf(owner);
        auto visual = ue::ResolveWeak(component + off::UInteractionObjectComponent::m_VisualComponent);
        if (!ue::IsLive(visual)) visual = ue::ResolveWeak(component + off::UInteractionObjectComponent::m_DetectionComponent);
        if (!ue::IsLive(visual)) visual = mem::ReadPtr(owner + off::AActor::RootComponent);
        ue::FVector point{}, extent{35, 35, 90};
        if (ue::IsLive(visual) && ue::ReadWorldLocation(visual, &point)) {
            const auto bounds = visual + off::USceneComponent::Bounds;
            ue::FVector origin{}, size{};
            if (mem::Read(bounds + off::FBoxSphereBounds::Origin, &origin) && mem::Read(bounds + off::FBoxSphereBounds::BoxExtent, &size) &&
                std::isfinite(size.x) && std::isfinite(size.y) && std::isfinite(size.z) && size.x > 0 && size.y > 0 && size.z > 0 &&
                size.x <= 500 && size.y <= 500 && size.z <= 500) { point = origin; extent = size; }
            s.target = id.index; s.serial = id.serial; s.owner = owner_id.index; s.owner_serial = owner_id.serial;
            s.location = {point.x, point.y, point.z};
            s.minimum = {point.x - extent.x, point.y - extent.y, point.z - extent.z};
            s.maximum = {point.x + extent.x, point.y + extent.y, point.z + extent.z};
            s.range_cm = 300;
            if (mem::ReadOr<std::uint8_t>(component + off::UInteractionObjectComponent::m_bUseCustomWalkInteractionDist, 0)) {
                const float distance = mem::ReadOr<float>(component + off::UInteractionObjectComponent::m_fCustomWalkInteractionDist, 0);
                if (std::isfinite(distance) && distance > 0 && distance <= 1000) s.range_cm = distance;
            }
            if (id.valid() && owner_id.index >= 0 && InRange(c.eye, s)) s.flags = protocol::kInteractAvailable;
            const auto name = ue::ClassName(owner); std::memcpy(s.label, name.data(), std::min(name.size(), sizeof(s.label) - 1));
        }
    }
    if (s.target != g_last_target || s.serial != g_last_serial || s.world != g_last_world || s.epoch != g_last_epoch) {
        if (++g_revision == 0) ++g_revision;
        g_last_target = s.target; g_last_serial = s.serial; g_last_world = s.world; g_last_epoch = s.epoch;
        if (s.target && g_logs++ < 100) SC_LOG("[interaction] candidate=%s owner=%s id=%u/%u range=%.0f", s.label,
            ue::ObjectName(owner).c_str(), s.target, s.serial, s.range_cm);
    }
    s.revision = g_revision;
    AcquireSRWLockExclusive(&g_lock); g_snapshot = s; ReleaseSRWLockExclusive(&g_lock);
}
protocol::InteractionSnapshot Snapshot() {
    AcquireSRWLockShared(&g_lock); auto s = g_snapshot; ReleaseSRWLockShared(&g_lock); return s;
}
void DrainReplies(std::vector<Reply>* out) {
    AcquireSRWLockExclusive(&g_lock); out->clear(); out->swap(g_replies); ReleaseSRWLockExclusive(&g_lock);
}
std::string TakeStats() {
    return FormatString("dispatched=%llu released=%llu rejected=%llu door_starts=%llu door_corrections=%llu", static_cast<unsigned long long>(g_sent.load()),
        static_cast<unsigned long long>(g_releases.load()), static_cast<unsigned long long>(g_rejected.load()),
        static_cast<unsigned long long>(g_door_starts.load()), static_cast<unsigned long long>(g_door_corrections.load()));
}
}
