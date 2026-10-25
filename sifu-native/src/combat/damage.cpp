#include "combat/damage.h"

#include <windows.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <deque>

#include "combat/damage_ledger.h"
#include "combat/light_impact.h"
#include "combat/shield_ledger.h"
#include "combat/impulse_ledger.h"
#include "combat/launch_params.h"
#include "combat/player_hits.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/game_hooks.h"
#include "hooks/vtable_hook.h"
#include "input/input_router.h"
#include "probe/combat_sample.h"
#include "probe/probe.h"
#include "ue/data_table.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/transform.h"
#include "ue/function_call.h"
#include "ue/reflection.h"

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
    std::vector<protocol::EnemyEntry> entries;
    std::uint64_t revision = 0;
    bool impulses_allowed = false;
};
struct Job {
    std::uint64_t session = 0, id = 0, queued_ms = 0;
    protocol::EnemyDamagePayload payload{};
    Enemies::Target target{};
    bool shield_feedback = false;
    bool impulse = false;
    std::uint64_t revision = 0;
    protocol::EnemyImpulsePayload push{};
    ImpulseLedger::Origin origin{};
};
SRWLOCK g_lock = SRWLOCK_INIT;
Context g_context;
std::deque<Job> g_jobs;
std::size_t g_inflight = 0;
std::vector<Reply> g_replies;
DamageLedger g_ledger;
ShieldLedger g_shield_ledger;
ImpulseLedger g_impulse_ledger;
std::atomic<bool> g_impulse_failed{false}, g_launch_ready{false};
std::atomic<std::uint64_t> g_impulse_received{0}, g_impulse_applied{0}, g_impulse_rejected{0};
ue::FunctionCall g_launch_character; // H1 only; shared base function, named reflection validation
ue::ObjectIdentity g_launch_identity;
LaunchParams g_launch_params;
int g_impulse_logged = 0;
std::atomic<bool> g_shield_failed{false};
hooks::VtableHook g_observer;
LaunchImpactFn g_launch = nullptr;
std::uintptr_t g_health_class = 0, g_hit_class = 0, g_settings_class = 0, g_settings = 0, g_table_class = 0;
std::atomic<bool> g_ready{false}, g_failed{false};
std::atomic<std::uint64_t> g_received{0}, g_applied{0}, g_rejected{0}, g_duplicate{0}, g_observed{0}, g_mismatch{0}, g_reply_overflow{0};
int g_logged = 0; // game thread only
int g_light_refusals = 0; // game thread only

bool LightDefaults(std::string* name, std::uint8_t* power) {
    if (!ue::IsRegistered(g_settings) || !ue::IsA(g_settings, g_settings_class)) return false;
    const auto handle = g_settings + off::USCGameSettings::m_DefaultHitbox;
    const auto table = mem::ReadPtr(handle + off::FDataTableRowHandle::DataTable);
    ue::FName key{};
    if (!ue::IsRegistered(table) || !ue::IsA(table, g_table_class) ||
        !mem::Read(handle + off::FDataTableRowHandle::RowName, &key)) return false;
    const auto row_struct = mem::ReadPtr(table + off::UDataTable::RowStruct);
    if (!ue::IsRegistered(row_struct) || ue::ObjectName(row_struct) != "HitboxDataRow" ||
        mem::ReadOr<std::uint32_t>(row_struct + off::UStruct::PropertiesSize, 0) != gen::size::FHitboxDataRow) return false;
    *name = ue::ObjectName(table) + ":" + ue::NameToString(key);
    const auto row = ue::DataTableRow(table, key);
    return row && mem::Read(row + off::FHitboxDataRow::m_eAttackPower, power) && *power == gen::value::EAttackPowers::Light;
}

struct Application { std::uintptr_t health; unsigned calls = 0; };
thread_local Application* g_application = nullptr;
using InternalDamageFn = void (*)(void*, void*);

void ObserveDamage(void* health, void* infos) {
    if (player_hits::BlockHealthDamage(reinterpret_cast<std::uintptr_t>(health), reinterpret_cast<std::uintptr_t>(infos))) return;
    // Enemy observation is filtered against the one health component whose MC event is being
    // applied. Local player protection above uses the separate ownership/lifecycle gate.
    if (g_application && reinterpret_cast<std::uintptr_t>(health) == g_application->health) {
        ++g_application->calls;
        g_observed.fetch_add(1, std::memory_order_relaxed);
    }
    const auto component = reinterpret_cast<std::uintptr_t>(health);
    const float before = mem::ReadOr<float>(component + off::UHealthComponent::m_fHealth, NAN);
    g_observer.original<InternalDamageFn>()(health, infos);
    player_hits::ObserveHealthDamage(component, reinterpret_cast<std::uintptr_t>(infos), before,
                                    mem::ReadOr<float>(component + off::UHealthComponent::m_fHealth, NAN));
}

protocol::DamageResultPayload Result(const Job& job, DamageOutcome outcome, float health = 0, float lost = 0) {
    return {job.id, job.payload.enemy_id, job.payload.spawn, static_cast<std::uint32_t>(outcome),
            lost, health, job.payload.world_generation};
}

const protocol::EnemyEntry* Entry(const Context& context, std::uint32_t id, std::uint32_t spawn) {
    for (const auto& e : context.entries) if (e.id == id && e.spawn == spawn) return &e;
    return nullptr;
}
protocol::Vec3 Feet(const protocol::EnemyEntry& e) { return {e.location.x, e.location.y, e.location.z - e.capsule_half_height}; }
bool NamedBase(std::uintptr_t object, const char* name) {
    auto cls = ue::ClassOf(object);
    for (int i = 0; i < 64 && ue::IsRegistered(cls); ++i, cls = ue::SuperOf(cls)) {
        if (ue::ObjectName(cls) == name) return true;
        if (!ue::SuperOf(cls)) break;
    }
    return false;
}
bool ResolveLaunch(std::uintptr_t actor) {
    if (!ue::IsLive(actor) || !NamedBase(actor, "Character")) return false;
    const auto cls = ue::ClassOf(actor), function = ue::FindFunction(cls, "LaunchCharacter");
    if (ue::IsRegistered(function) && function == g_launch_character.function() && ue::IdentityOf(function) == g_launch_identity && g_launch_params.valid()) return true;
    g_launch_ready.store(false);
    if (!g_launch_character.Resolve(cls, "LaunchCharacter") || !ue::IsRegistered(function) ||
        !(mem::ReadOr<std::uint32_t>(function + off::UFunction::FunctionFlags, 0) & 0x400u)) return false;
    LaunchParams p; p.size = g_launch_character.params_size();
    const auto properties = ue::ListProperties(function);
    if (properties.size() != 3) return false;
    for (const auto& property : properties) {
        if (property.array_dim != 1 || property.offset < 0 || property.element_size <= 0 ||
            property.offset > p.size - property.element_size) return false;
        if (property.name == "LaunchVelocity" && property.type == "StructProperty" && property.detail == "Vector" && property.element_size == 12) p.vector = property.offset;
        else if ((property.name == "bXYOverride" || property.name == "bZOverride") && property.type == "BoolProperty" && property.element_size == 1) {
            const auto byte = mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::ByteOffset, 0xFF);
            const auto mask = mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::ByteMask, 0);
            if (mem::ReadOr<std::uint8_t>(property.field + off::FBoolProperty::FieldSize, 0) != 1 || byte != 0) return false;
            if (property.name == "bXYOverride") { p.xy = property.offset + byte; p.xy_mask = mask; }
            else { p.z = property.offset + byte; p.z_mask = mask; }
        } else return false;
    }
    if (!p.valid()) return false;
    g_launch_params = p; g_launch_identity = ue::IdentityOf(function); g_launch_ready.store(true);
    if (g_impulse_logged++ < 80) SC_LOG("[mace] LaunchCharacter selfcheck PASS params=%d velocity=%d xy=%d/%u z=%d/%u cap=600cm/s",
        p.size, p.vector, p.xy, p.xy_mask, p.z, p.z_mask);
    return true;
}

protocol::DamageResultPayload ApplyImpulse(const Context& context, const Job& job, std::uintptr_t actor,
                                         std::uintptr_t health_component, const probe::HealthSample& health) {
    if (!context.impulses_allowed || context.revision != job.revision || g_impulse_failed.load() ||
        GetTickCount64() < job.origin.created || GetTickCount64() - job.origin.created > 2000) return Result(job, DamageOutcome::Stale);
    const auto entry = Entry(context, job.payload.enemy_id, job.payload.spawn);
    if (!entry || !InImpulseRange(job.origin.feet, Feet(*entry))) return Result(job, DamageOutcome::Stale);
    const auto movement = mem::ReadPtr(actor + off::ACharacter::CharacterMovement);
    if (!ue::IsLive(movement) || ue::OuterOf(movement) != actor || !NamedBase(movement, "CharacterMovementComponent") ||
        ue::FindPropertyOffset(ue::ClassOf(movement), "Velocity") != off::UMovementComponent::Velocity || !ResolveLaunch(actor)) return Result(job, DamageOutcome::Unavailable);
    protocol::Vec3 current{}, velocity{};
    if (!mem::Read(movement + off::UMovementComponent::Velocity, &current) || !ImpulseVelocity(job.push, current, &velocity)) return Result(job, DamageOutcome::Invalid);
    std::vector<std::uint8_t> params(g_launch_params.size);
    if (!g_launch_params.Encode(params, velocity)) return Result(job, DamageOutcome::Unavailable);
    g_launch_character.Invoke(actor, params.data());
    const float after = mem::ReadOr<float>(health_component + off::UHealthComponent::m_fHealth, NAN);
    if (!std::isfinite(after) || after != health.health) g_impulse_failed.store(true);
    if (g_impulse_logged++ < 200) SC_LOG("[mace] impulse original=%llu target=%u/%u world=%u epoch=%u velocity=(%.2f,%.2f,%.2f) HP=%.3f->%.3f%s",
        static_cast<unsigned long long>(job.push.origin_damage_id), job.payload.enemy_id, job.payload.spawn,
        context.generation, context.epoch, velocity.x, velocity.y, velocity.z, health.health, after,
        g_impulse_failed.load() ? " DISABLED: health changed" : "");
    if (g_impulse_failed.load()) return Result(job, DamageOutcome::Unavailable);
    g_impulse_applied.fetch_add(1); return Result(job, DamageOutcome::Applied, after);
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
    if (job.shield_feedback && g_shield_failed.load()) return Result(job, DamageOutcome::Unavailable);
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
    if (job.impulse) return ApplyImpulse(context, job, actor, health_component, health);
    const auto hit_component = mem::ReadPtr(actor + off::AFightingCharacter::m_HitComponent);
    if (!ue::IsLive(hit_component) || !ue::IsA(hit_component, g_hit_class) ||
        ue::OuterOf(hit_component) != actor) return Result(job, DamageOutcome::Stale);
    std::string row_name;
    std::uint8_t power = 0xFF;
    if (!LightDefaults(&row_name, &power)) {
        if (g_light_refusals++ < 8) SC_LOG("[combat] REFUSE light impact: default_row=%s power=%u (requires Light)",
                                         row_name.c_str(), power);
        return Result(job, DamageOutcome::Unavailable);
    }
    ue::FVector location{};
    if (!ue::ReadWorldLocation(mem::ReadPtr(actor + off::AActor::RootComponent), &location) ||
        !std::isfinite(location.x) || !std::isfinite(location.y) || !std::isfinite(location.z)) {
        return Result(job, DamageOutcome::Stale);
    }
    const float requested = job.payload.mc_damage * protocol::kEnemySifuHpPerMcHp;
    Application observation{health_component};
    g_application = job.shield_feedback ? nullptr : &observation;
    LaunchLightImpact(g_launch, reinterpret_cast<void*>(hit_component), location,
                      ImpactDamage(requested, health.health, health.can_die));
    g_application = nullptr;
    const float after = mem::ReadOr<float>(health_component + off::UHealthComponent::m_fHealth, NAN);
    const float lost = std::isfinite(after) ? std::max(0.0f, health.health - after) : 0;
    if (!job.shield_feedback && (!std::isfinite(after) || observation.calls > 1 || (lost > 0 && observation.calls != 1))) {
        g_mismatch.fetch_add(1, std::memory_order_relaxed);
        g_failed.store(true); // no further game calls until the mismatch has been investigated
    }
    const auto outcome = job.shield_feedback ? (after == health.health ? DamageOutcome::Applied : DamageOutcome::Unavailable)
                                            : (lost > 0 ? DamageOutcome::Applied : DamageOutcome::Invincible);
    if (!job.shield_feedback && outcome == DamageOutcome::Applied && (job.payload.flags & protocol::kDamageSmash)) {
        if (const auto entry = Entry(context, job.payload.enemy_id, job.payload.spawn)) {
            AcquireSRWLockExclusive(&g_lock);
            if (g_context.revision == context.revision && g_context.impulses_allowed)
                g_impulse_ledger.Record(job.id, job.session, job.payload, Feet(*entry), job.queued_ms);
            ReleaseSRWLockExclusive(&g_lock);
        }
    }
    if (!job.shield_feedback && outcome == DamageOutcome::Applied) g_applied.fetch_add(1, std::memory_order_relaxed);
    if (job.shield_feedback) {
        if (!std::isfinite(after) || after != health.health) g_shield_failed.store(true);
        if (g_logged++ < 200) SC_LOG("[shield] perfect feedback event=%llu enemy=%u/%u HP=%.3f->%.3f stun=%.3f outcome=%u%s",
            static_cast<unsigned long long>(job.id), job.payload.enemy_id, job.payload.spawn, health.health, after,
            kLightStunSeconds, static_cast<unsigned>(outcome), g_shield_failed.load() ? " DISABLED: unexpected health change" : "");
    } else if (g_logged++ < 200) {
        SC_LOG("[combat] damage event=%llu session=%016llX enemy=%u/%u world=%u mc=%.3f requested=%.3f "
               "HP=%.3f->%.3f observed=%u outcome=%u impact=%u power=%u stun=%.3f row=%s%s", static_cast<unsigned long long>(job.id),
               static_cast<unsigned long long>(job.session), job.payload.enemy_id, job.payload.spawn,
               job.payload.world_generation, job.payload.mc_damage, requested, health.health, after,
               observation.calls, static_cast<unsigned>(outcome), gen::value::EImpactType::Hit, power, kLightStunSeconds,
               row_name.c_str(), g_failed.load() ? " LEDGER MISMATCH: disabled" : "");
    }
    return Result(job, outcome, std::isfinite(after) ? after : health.health, lost);
}
}  // namespace

void InstallObserver(std::uintptr_t base, std::uintptr_t pawn) {
    if (g_ready.load() || g_failed.load() || !ue::IsLive(pawn)) return;
    g_health_class = ue::FindClass("CharacterHealthComponent");
    g_hit_class = ue::FindClass("HitComponent");
    g_settings_class = ue::FindClass("SCGameSettings");
    g_table_class = ue::FindClass("DataTable");
    g_settings = g_settings_class ? ue::DefaultObject(g_settings_class) : 0;
    if (!g_health_class || !g_hit_class || !g_table_class || !ue::IsRegistered(g_settings)) return;
    // The module's build guard has already matched the generated EXE/PDB fingerprint.
    g_launch = reinterpret_cast<LaunchImpactFn>(base + gen::fn::LaunchImpactInternal);
    const auto health = mem::ReadPtr(pawn + off::AFightingCharacter::m_HealthComponent);
    if (!ue::IsLive(health) || !hooks::Resolve(base, gen::hook::InternalApplyDamage, health).ready()) return;
    if (!g_observer.Install(base, gen::hook::InternalApplyDamage, health, reinterpret_cast<void*>(&ObserveDamage))) return;
    // Verify restoration before allowing damage calls, while there are no queued applications.
    const bool restored = g_observer.Uninstall() && mem::ReadPtr(g_observer.slot_address()) == base + gen::hook::InternalApplyDamage.original;
    const bool reinstalled = restored && g_observer.Install(base, gen::hook::InternalApplyDamage, health,
                                                           reinterpret_cast<void*>(&ObserveDamage));
    std::string row_name;
    std::uint8_t power = 0xFF;
    const bool light = LightDefaults(&row_name, &power);
    SC_LOG("[combat] H7 observer uninstall/reinstall -> %s; LaunchImpactInternal rva=0x%llX impact=%u "
           "stun=%.3f default_row=%s power=%u light=%d", reinstalled ? "PASS" : "FAIL",
           static_cast<unsigned long long>(gen::fn::LaunchImpactInternal), gen::value::EImpactType::Hit,
           kLightStunSeconds, row_name.c_str(), power, light);
    if (!reinstalled) g_failed.store(true);
    g_ready.store(reinstalled, std::memory_order_release);
}

bool UninstallObserver() {
    g_ready.store(false, std::memory_order_release);
    return !g_observer.installed() || g_observer.Uninstall();
}

bool OwnsObserver(std::uintptr_t value) {
    return g_ready.load(std::memory_order_acquire) && value == reinterpret_cast<std::uintptr_t>(&ObserveDamage);
}

bool ObserverReady() { return g_ready.load(std::memory_order_acquire); }

void UpdateContext(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, bool active,
                   std::uint32_t epoch, std::uint64_t peer_session, const std::vector<Enemies::Target>& targets,
                   const std::vector<protocol::EnemyEntry>& entries, bool impulses_allowed) {
    const auto identity = ue::IdentityOf(pawn);
    AcquireSRWLockExclusive(&g_lock);
    if (g_context.world != world || g_context.generation != generation || g_context.pawn != pawn || g_context.pawn_identity != identity || g_context.epoch != epoch ||
        g_context.session != peer_session || g_context.active != (active && identity.valid()) || g_context.impulses_allowed != impulses_allowed) {
        g_shield_ledger.Reset(); g_impulse_ledger.Reset(); ++g_context.revision;
    }
    const auto revision = g_context.revision;
    g_context = {world, pawn, identity, generation, epoch, peer_session, active && identity.valid(), targets, entries, revision, impulses_allowed};
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

void RecordPlayerHit(std::uint64_t id, std::uint64_t peer_session, const protocol::PlayerHitPayload& hit, std::uint64_t created_ms) {
    AcquireSRWLockExclusive(&g_lock);
    if (g_context.active && g_context.session == peer_session && g_context.generation == hit.world_generation && g_context.epoch == hit.anchor_epoch)
        g_shield_ledger.Record(id, peer_session, hit, created_ms);
    ReleaseSRWLockExclusive(&g_lock);
}

void SubmitBlocked(const protocol::Event& event) {
    protocol::PlayerBlockedPayload reply{};
    if (event.size != sizeof(reply)) return;
    std::memcpy(&reply, event.payload, sizeof(reply));
    AcquireSRWLockExclusive(&g_lock);
    std::uint64_t created_ms = 0;
    bool accepted = g_context.active && event.source_session == g_context.session && reply.world_generation == g_context.generation &&
        reply.anchor_epoch == g_context.epoch && g_shield_ledger.Consume(event.source_session, reply, GetTickCount64(), &created_ms);
    const auto target = std::find_if(g_context.targets.begin(), g_context.targets.end(), [&](const auto& value) {
        return value.id == reply.attacker_id && value.spawn == reply.spawn;
    });
    accepted = accepted && target != g_context.targets.end();
    bool queued = false;
    if (accepted && (reply.flags & protocol::kBlockedPerfect) && !g_shield_failed.load() && g_jobs.size() < kMaxJobs) {
        Job job;
        job.session = event.source_session; job.id = event.id; job.queued_ms = created_ms;
        job.payload = {reply.attacker_id, reply.spawn, 0, kLightStunSeconds, 0, reply.world_generation, reply.anchor_epoch, 0};
        job.target = *target; job.shield_feedback = true;
        g_jobs.push_back(job);
        queued = true;
    }
    ReleaseSRWLockExclusive(&g_lock);
    static unsigned logged = 0;
    if (logged++ < 200) SC_LOG("[shield] reply original=%llu enemy=%u/%u blocked=%.3f perfect=%d accepted=%d queued=%d",
        static_cast<unsigned long long>(reply.origin_hit_id), reply.attacker_id, reply.spawn, reply.blocked_damage,
        (reply.flags & protocol::kBlockedPerfect) != 0, accepted, queued);
}

void SubmitImpulse(const protocol::Event& event) {
    Job job; job.session = event.source_session; job.id = event.id; job.impulse = true;
    if (event.size != sizeof(job.push)) return;
    std::memcpy(&job.push, event.payload, sizeof(job.push));
    g_impulse_received.fetch_add(1);
    AcquireSRWLockExclusive(&g_lock);
    const auto target = std::find_if(g_context.targets.begin(), g_context.targets.end(), [&](const auto& t) {
        return t.id == job.push.enemy_id && t.spawn == job.push.spawn;
    });
    const auto entry = Entry(g_context, job.push.enemy_id, job.push.spawn);
    const bool accepted = g_context.active && g_context.impulses_allowed && !g_impulse_failed.load() &&
        g_context.session == job.session && target != g_context.targets.end() && entry && g_jobs.size() < kMaxJobs &&
        g_jobs.size() + g_replies.size() + g_inflight < kMaxReplies &&
        g_impulse_ledger.Consume(job.session, job.push, Feet(*entry), GetTickCount64(), &job.origin);
    if (accepted) {
        job.target = *target; job.revision = g_context.revision; job.queued_ms = job.origin.created;
        job.payload = {job.push.enemy_id, job.push.spawn, 0, 0, 0, job.push.world_generation, job.push.anchor_epoch, 0};
        g_jobs.push_back(job);
    } else g_impulse_rejected.fetch_add(1);
    ReleaseSRWLockExclusive(&g_lock);
}

void ApplyOnGameThread(std::uintptr_t base) {
    AcquireSRWLockShared(&g_lock);
    const auto schema_pawn = g_context.impulses_allowed ? g_context.pawn : 0;
    ReleaseSRWLockShared(&g_lock);
    if (!g_launch_ready.load() && schema_pawn) ResolveLaunch(schema_pawn); // metadata-only preflight
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
        if (job.shield_feedback || job.impulse) {
            --g_inflight;
            if (job.impulse && result.result != static_cast<std::uint32_t>(DamageOutcome::Applied)) g_impulse_rejected.fetch_add(1);
        }
        else FinishLocked(job, result, true);
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
        "ledger_mismatch=%llu reply_overflow=%llu queued=%zu replies=%zu mace_ready=%d mace_received=%llu mace_applied=%llu mace_rejected=%llu mace_disabled=%d", g_ready.load() ? "on" : "off",
        g_failed.load() ? "DISABLED" : "ready", take(g_received), take(g_applied), take(g_rejected), take(g_duplicate),
        take(g_observed), take(g_mismatch), take(g_reply_overflow), queued, replies, g_launch_ready.load(),
        take(g_impulse_received), take(g_impulse_applied), take(g_impulse_rejected), g_impulse_failed.load());
}
}  // namespace sifucraft::combat::damage
