#include "combat/damage.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>

#include "combat/damage_ledger.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/game_hooks.h"
#include "hooks/vtable_hook.h"
#include "input/input_router.h"
#include "probe/combat_sample.h"
#include "probe/probe.h"
#include "ue/function_call.h"
#include "ue/objects.h"

namespace sifucraft::combat::damage {
namespace {
namespace off = gen::off;
using protocol::DamageOutcome;
constexpr std::size_t kMaxJobs = 128, kMaxReplies = 512;

struct Context {
    std::uintptr_t world = 0, pawn = 0;
    ue::ObjectIdentity pawn_identity;
    std::uint32_t generation = 0, epoch = 0;
    std::uint64_t session = 0;
    bool active = false;
    std::vector<Enemies::Target> targets;
};
struct Job {
    std::uint64_t session = 0, id = 0, queued_ms = 0;
    protocol::EnemyDamagePayload payload{};
    Enemies::Target target{};
};
SRWLOCK g_lock = SRWLOCK_INIT;
Context g_context;
std::deque<Job> g_jobs;
std::size_t g_inflight = 0;
std::vector<Reply> g_replies;
DamageLedger g_ledger;
hooks::VtableHook g_observer;
ue::FunctionCall g_launch;
int g_damage_at = -1, g_lethal_at = -1, g_stun_at = -1;
std::uintptr_t g_health_class = 0;
std::atomic<bool> g_ready{false}, g_failed{false};
std::atomic<std::uint64_t> g_received{0}, g_applied{0}, g_rejected{0}, g_duplicate{0}, g_observed{0}, g_mismatch{0}, g_reply_overflow{0};
int g_logged = 0; // game thread only

struct Application { std::uintptr_t health; unsigned calls = 0; };
thread_local Application* g_application = nullptr;
using InternalDamageFn = void (*)(void*, void*);

void ObserveDamage(void* health, void* infos) {
    // S2 never suppresses damage, especially the local player's. The shared vtable is filtered
    // against the one health component whose MC event is currently being applied.
    if (g_application && reinterpret_cast<std::uintptr_t>(health) == g_application->health) {
        ++g_application->calls;
        g_observed.fetch_add(1, std::memory_order_relaxed);
    }
    g_observer.original<InternalDamageFn>()(health, infos);
}

protocol::DamageResultPayload Result(const Job& job, DamageOutcome outcome, float health = 0, float lost = 0) {
    return {job.id, job.payload.enemy_id, job.payload.spawn, static_cast<std::uint32_t>(outcome),
            lost, health, job.payload.world_generation};
}

// Caller holds g_lock. Accepted commands have a reserved place in the bounded job queue.
void FinishLocked(const Job& job, const protocol::DamageResultPayload& result, bool application = false) {
    if (application) --g_inflight;
    g_ledger.Complete(job.session, job.id, result);
    if (g_replies.size() + g_jobs.size() + g_inflight < kMaxReplies) g_replies.push_back({job.session, result});
    else g_reply_overflow.fetch_add(1, std::memory_order_relaxed);
}

protocol::DamageResultPayload Apply(std::uintptr_t base, const Context& context, const Job& job) {
    if (!g_ready.load(std::memory_order_acquire) || g_failed.load()) return Result(job, DamageOutcome::Unavailable);
    if (!context.active || input::Router().owner() != protocol::InputOwner::Minecraft || !hooks::PoseActive() ||
        job.session != context.session || job.payload.anchor_epoch != context.epoch) return Result(job, DamageOutcome::NotOwned);
    const auto world = mem::ReadPtr(base + gen::rva::GWorld);
    if (!world || world != context.world || job.payload.world_generation != context.generation ||
        GetTickCount64() - job.queued_ms > 2000) return Result(job, DamageOutcome::Stale);
    const auto pawn = probe::FindPlayerObjects(world).pawn;
    if (pawn != context.pawn || ue::IdentityOf(pawn) != context.pawn_identity) return Result(job, DamageOutcome::Stale);
    const auto current = std::find_if(context.targets.begin(), context.targets.end(), [&](const auto& target) {
        return target.id == job.payload.enemy_id && target.spawn == job.payload.spawn && target.actor == job.target.actor &&
            target.identity == job.target.identity;
    });
    const auto actor = job.target.actor;
    if (current == context.targets.end() || actor == pawn || ue::IdentityOf(actor) != job.target.identity ||
        mem::ReadPtr(ue::OuterOf(actor) + off::ULevel::OwningWorld) != world) {
        return Result(job, DamageOutcome::Stale);
    }
    const auto pooled = mem::ReadOr<std::uint8_t>(actor + off::ASCCharacter::m_bIsPooled, 0xFF);
    const auto active = mem::ReadOr<std::uint8_t>(actor + off::ASCCharacter::m_bPooledActorActive, 0xFF);
    const auto faction = mem::ReadOr<std::uint8_t>(actor + off::AFightingCharacter::m_eFaction, 0xFF);
    const auto player_faction = mem::ReadOr<std::uint8_t>(pawn + off::AFightingCharacter::m_eFaction, 0xFF);
    if (pooled > 1 || active > 1 || (pooled && !active) || faction >= gen::value::EFactionsEnums::Count ||
        faction == gen::value::EFactionsEnums::TMP_Neutral || faction == player_faction) return Result(job, DamageOutcome::Stale);
    const auto health_component = mem::ReadPtr(actor + off::AFightingCharacter::m_HealthComponent);
    probe::HealthSample health;
    if (!ue::IsLive(health_component) || !ue::IsA(health_component, g_health_class) ||
        !probe::ReadHealthSample(health_component, &health)) return Result(job, DamageOutcome::Stale);
    if (health.health <= 0) return Result(job, DamageOutcome::Dead, health.health);
    if (!health.can_be_damaged) return Result(job, DamageOutcome::Invincible, health.health);
    const float requested = job.payload.mc_damage * protocol::kSifuHpPerMcHp;
    std::vector<std::uint8_t> params(static_cast<std::size_t>(g_launch.params_size()), 0);
    std::memcpy(params.data() + g_damage_at, &requested, sizeof(requested));
    params[g_lethal_at] = health.can_die && requested >= health.health ? 1 : 0;
    std::memcpy(params.data() + g_stun_at, &job.payload.stun_hint, sizeof(float));
    Application observation{health_component};
    g_application = &observation;
    g_launch.Invoke(actor, params.data());
    g_application = nullptr;
    const float after = mem::ReadOr<float>(health_component + off::UHealthComponent::m_fHealth, NAN);
    const float lost = std::isfinite(after) ? std::max(0.0f, health.health - after) : 0;
    if (!std::isfinite(after) || observation.calls > 1 || (lost > 0 && observation.calls != 1)) {
        g_mismatch.fetch_add(1, std::memory_order_relaxed);
        g_failed.store(true); // no further game calls until the mismatch has been investigated
    }
    const auto outcome = lost > 0 ? DamageOutcome::Applied : DamageOutcome::Invincible;
    if (outcome == DamageOutcome::Applied) g_applied.fetch_add(1, std::memory_order_relaxed);
    if (g_logged++ < 200) {
        SC_LOG("[combat] damage event=%llu session=%016llX enemy=%u/%u world=%u mc=%.3f requested=%.3f "
               "HP=%.3f->%.3f observed=%u outcome=%u%s", static_cast<unsigned long long>(job.id),
               static_cast<unsigned long long>(job.session), job.payload.enemy_id, job.payload.spawn,
               job.payload.world_generation, job.payload.mc_damage, requested, health.health, after,
               observation.calls, static_cast<unsigned>(outcome), g_failed.load() ? " LEDGER MISMATCH: disabled" : "");
    }
    return Result(job, outcome, std::isfinite(after) ? after : health.health, lost);
}
}  // namespace

void InstallObserver(std::uintptr_t base, std::uintptr_t pawn) {
    if (g_ready.load() || g_failed.load() || !ue::IsLive(pawn)) return;
    const auto fighting = ue::FindClass("FightingCharacter");
    g_health_class = ue::FindClass("CharacterHealthComponent");
    if (!fighting || !g_health_class || !g_launch.Resolve(fighting, "BPF_LaunchImpact")) return;
    g_damage_at = g_launch.ParamOffset("_fDamage");
    g_lethal_at = g_launch.ParamOffset("_bLethal");
    g_stun_at = g_launch.ParamOffset("_fStunTime");
    const int size = g_launch.params_size();
    const auto fits = [size](int at, int bytes) { return at >= 0 && at + bytes <= size; };
    if (size > 256 || !fits(g_damage_at, 4) || !fits(g_lethal_at, 1) || !fits(g_stun_at, 4)) {
        SC_LOG("[combat] REFUSE BPF_LaunchImpact parameter layout");
        g_failed.store(true);
        return;
    }
    const auto health = mem::ReadPtr(pawn + off::AFightingCharacter::m_HealthComponent);
    if (!ue::IsLive(health) || !hooks::Resolve(base, gen::hook::InternalApplyDamage, health).ready()) return;
    if (!g_observer.Install(base, gen::hook::InternalApplyDamage, health, reinterpret_cast<void*>(&ObserveDamage))) return;
    // Verify restoration before allowing damage calls, while there are no queued applications.
    const bool restored = g_observer.Uninstall() && mem::ReadPtr(g_observer.slot_address()) == base + gen::hook::InternalApplyDamage.original;
    const bool reinstalled = restored && g_observer.Install(base, gen::hook::InternalApplyDamage, health,
                                                           reinterpret_cast<void*>(&ObserveDamage));
    SC_LOG("[combat] H7 observer uninstall/reinstall -> %s; LaunchImpact params=%d damage=%d lethal=%d stun=%d",
           reinstalled ? "PASS" : "FAIL", size, g_damage_at, g_lethal_at, g_stun_at);
    if (!reinstalled) g_failed.store(true);
    g_ready.store(reinstalled, std::memory_order_release);
}

bool UninstallObserver() {
    g_ready.store(false, std::memory_order_release);
    return !g_observer.installed() || g_observer.Uninstall();
}

void UpdateContext(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, bool active,
                   std::uint32_t epoch, std::uint64_t peer_session, const std::vector<Enemies::Target>& targets) {
    const auto identity = ue::IdentityOf(pawn);
    AcquireSRWLockExclusive(&g_lock);
    g_context = {world, pawn, identity, generation, epoch, peer_session, active && identity.valid(), targets};
    g_ledger.SetSession(peer_session);
    ReleaseSRWLockExclusive(&g_lock);
}

void Submit(const protocol::Event& event) {
    Job job;
    job.session = event.source_session;
    job.id = event.id;
    job.queued_ms = GetTickCount64();
    if (event.size != sizeof(job.payload)) return;
    std::memcpy(&job.payload, event.payload, sizeof(job.payload));
    g_received.fetch_add(1, std::memory_order_relaxed);
    AcquireSRWLockExclusive(&g_lock);
    protocol::DamageResultPayload cached{};
    const auto claim = g_ledger.Begin(job.session, job.id, &cached);
    if (claim != DamageLedger::Claim::Fresh) {
        g_duplicate.fetch_add(1, std::memory_order_relaxed);
        if (claim == DamageLedger::Claim::Cached && g_replies.size() + g_jobs.size() + g_inflight < kMaxReplies) {
            g_replies.push_back({job.session, cached});
        }
        ReleaseSRWLockExclusive(&g_lock);
        return;
    }
    DamageOutcome rejection = DamageOutcome::Applied; // sentinel: accept when every gate passes
    if (!ValidDamage(job.payload)) rejection = DamageOutcome::Invalid;
    else if (!g_context.active || job.payload.anchor_epoch != g_context.epoch) rejection = DamageOutcome::NotOwned;
    else if (job.payload.world_generation != g_context.generation) rejection = DamageOutcome::Stale;
    else if (g_jobs.size() >= kMaxJobs || g_replies.size() + g_jobs.size() + g_inflight >= kMaxReplies) rejection = DamageOutcome::Busy;
    const auto target = std::find_if(g_context.targets.begin(), g_context.targets.end(), [&](const auto& t) {
        return t.id == job.payload.enemy_id && t.spawn == job.payload.spawn;
    });
    if (rejection == DamageOutcome::Applied && target == g_context.targets.end()) rejection = DamageOutcome::Stale;
    if (rejection == DamageOutcome::Applied) {
        job.target = *target;
        g_jobs.push_back(job);
    } else {
        g_rejected.fetch_add(1, std::memory_order_relaxed);
        FinishLocked(job, Result(job, rejection));
    }
    ReleaseSRWLockExclusive(&g_lock);
}

void DrainReplies(std::vector<Reply>* replies) {
    AcquireSRWLockExclusive(&g_lock);
    replies->clear();
    replies->swap(g_replies);
    ReleaseSRWLockExclusive(&g_lock);
}

void ApplyOnGameThread(std::uintptr_t base) {
    for (int i = 0; i < 8; ++i) {
        AcquireSRWLockExclusive(&g_lock);
        if (g_jobs.empty()) { ReleaseSRWLockExclusive(&g_lock); return; }
        const Job job = g_jobs.front();
        g_jobs.pop_front();
        ++g_inflight;
        const Context context = g_context;
        ReleaseSRWLockExclusive(&g_lock);
        const auto result = Apply(base, context, job);
        if (result.result != static_cast<std::uint32_t>(DamageOutcome::Applied)) g_rejected.fetch_add(1, std::memory_order_relaxed);
        AcquireSRWLockExclusive(&g_lock);
        FinishLocked(job, result, true);
        ReleaseSRWLockExclusive(&g_lock);
    }
}

std::string TakeStats() {
    AcquireSRWLockShared(&g_lock);
    const auto queued = g_jobs.size();
    const auto replies = g_replies.size();
    ReleaseSRWLockShared(&g_lock);
    const auto take = [](std::atomic<std::uint64_t>& n) { return static_cast<unsigned long long>(n.exchange(0)); };
    return FormatString("H7=%s calls=%s received=%llu applied=%llu rejected=%llu duplicate=%llu observed=%llu "
        "ledger_mismatch=%llu reply_overflow=%llu queued=%zu replies=%zu", g_ready.load() ? "on" : "off",
        g_failed.load() ? "DISABLED" : "ready", take(g_received), take(g_applied), take(g_rejected), take(g_duplicate),
        take(g_observed), take(g_mismatch), take(g_reply_overflow), queued, replies);
}
}  // namespace sifucraft::combat::damage
