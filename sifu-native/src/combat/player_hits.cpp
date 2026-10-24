#include "combat/player_hits.h"

#include <windows.h>

#include <atomic>
#include <algorithm>
#include <cmath>

#include "combat/damage.h"
#include "combat/hit_sample.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/vtable_hook.h"
#include "hooks/game_hooks.h"
#include "input/input_router.h"
#include "probe/probe.h"
#include "ue/function_call.h"
#include "ue/objects.h"
#include "ue/data_table.h"
#include "ue/names.h"
#include "ue/reflection.h"

namespace sifucraft::combat::player_hits {
namespace {
namespace off = gen::off;
hooks::VtableHook g_observer;
std::atomic<bool> g_ready{false};
bool g_failed = false; // runtime thread only
std::uintptr_t g_base = 0, g_guard_class = 0, g_table_class = 0;
std::atomic<std::uint64_t> g_hits{0}, g_resolved{0}, g_unresolved{0}, g_health_calls{0};
std::atomic<std::uint64_t> g_forwarded{0}, g_cancelled{0}, g_dropped{0}, g_blocked{0};
SRWLOCK g_lock = SRWLOCK_INIT;
struct Context {
    std::uintptr_t world = 0, pawn = 0;
    ue::ObjectIdentity identity;
    std::uint32_t generation = 0, epoch = 0;
    std::uint64_t session = 0, updated_ms = 0;
    bool active = false, can_forward = false;
    std::vector<Enemies::Target> targets;
};
Context g_context;
std::vector<Hit> g_pending;
std::uint64_t g_request_id = 0, g_last_ms = 0; // game thread only; nearest request is a hint, not correlation
unsigned g_hit_logs = 0, g_health_logs = 0, g_block_logs = 0;
using ValidateFn = bool (*)(void*, const void*, const void*);

std::uintptr_t CurrentPawn() {
    if (!g_ready.load(std::memory_order_acquire)) return 0;
    const auto world = mem::ReadPtr(g_base + gen::rva::GWorld);
    return world ? probe::FindPlayerObjects(world).pawn : 0;
}

bool Active(std::uintptr_t pawn, Context* context) {
    if (!pawn || input::Router().owner() != protocol::InputOwner::Minecraft) return false;
    AcquireSRWLockShared(&g_lock);
    *context = g_context;
    ReleaseSRWLockShared(&g_lock);
    return context->active && context->session && context->epoch && context->generation &&
        context->pawn == pawn && context->identity == ue::IdentityOf(pawn) &&
        context->world == mem::ReadPtr(g_base + gen::rva::GWorld) && GetTickCount64() - context->updated_ms < 2000;
}

std::uintptr_t HitRow(std::uintptr_t request, std::string* name) {
    const auto box = request + off::FHitRequest::m_HitBox;
    const auto cached = mem::ReadOr<std::uint8_t>(box + off::FHitBox::m_bCachedRowSetup, 0xFF);
    if (cached == 1) { *name = "inline"; return box + off::FHitBox::m_CachedRow; }
    if (cached != 0) return 0;
    const auto handle = box + off::FHitBox::m_HitRow;
    const auto table = mem::ReadPtr(handle + off::FDataTableRowHandle::DataTable);
    ue::FName key{};
    if (!ue::IsLive(table) || !ue::IsA(table, g_table_class) ||
        !mem::Read(handle + off::FDataTableRowHandle::RowName, &key)) return 0;
    const auto row_struct = mem::ReadPtr(table + off::UDataTable::RowStruct);
    if (!ue::IsLive(row_struct) || ue::ObjectName(row_struct) != "HitboxDataRow" ||
        mem::ReadOr<int>(row_struct + off::UStruct::PropertiesSize, 0) != gen::size::FHitboxDataRow) return 0;
    *name = ue::ObjectName(table) + ":" + ue::NameToString(key);
    return ue::DataTableRow(table, key);
}

float DefaultDamage(std::uintptr_t pawn, std::uint8_t guard_type, std::uintptr_t* db) {
    const auto defense = mem::ReadPtr(pawn + off::AFightingCharacter::m_DefenseComponent);
    if (!ue::IsLive(defense)) return NAN;
    const auto weak = StaticGuardValue(defense);
    *db = weak ? ue::ResolveWeak(weak) : 0;
    if (!ue::IsLive(*db) || !ue::IsA(*db, g_guard_class)) return NAN;
    std::uint32_t at = off::UGuardDB::m_DefaultHitImpact;
    if (guard_type == gen::value::EGuardType::Guard) at = off::UGuardDB::m_DefaultGuardImpact;
    if (guard_type == gen::value::EGuardType::Deflect) at = off::UGuardDB::m_DefaultDeflectImpact;
    return mem::ReadOr<float>(*db + at + off::FHitImpact::m_fDamage, NAN);
}

bool ObserveHit(void* component, const void* result, const void* request_ptr) {
    const auto pawn = CurrentPawn();
    if (ue::IsLive(pawn) && reinterpret_cast<std::uintptr_t>(component) == mem::ReadPtr(pawn + off::AFightingCharacter::m_HitComponent)) {
        const auto request = reinterpret_cast<std::uintptr_t>(request_ptr);
        HitRequestSample hit;
        std::string row_name;
        const bool parsed = ReadHitRequest(request, HitRow(request, &row_name), &hit);
        std::uintptr_t db = 0;
        const float default_damage = parsed ? DefaultDamage(pawn, hit.guard_type, &db) : NAN;
        float resolved = NAN;
        const bool valid = parsed && ResolveHitDamage(hit, default_damage, &resolved);
        g_hits.fetch_add(1, std::memory_order_relaxed);
        (valid ? g_resolved : g_unresolved).fetch_add(1, std::memory_order_relaxed);
        ++g_request_id;
        g_last_ms = GetTickCount64();
        Context context;
        const bool active = Active(pawn, &context);
        const auto attacker = ue::ResolveWeak(request + off::FHitRequest::m_Instigator);
        const auto identity = ue::IdentityOf(attacker);
        if (g_hit_logs++ < 200) {
            const auto box = request + off::FHitRequest::m_HitBox;
            const auto cached = mem::ReadOr<std::uint8_t>(box + off::FHitBox::m_bCachedRowSetup, 0xFF);
            SC_LOG("[player-hits] H6 request=%llu pawn=%s attacker=%s id=%d/%d cached=%u parsed=%d "
                   "use_damage=%u raw=%.3f default=%.3f guard_db=%s base=%.3f resolved=%d guard=%u power=%u type=%u "
                   "behavior=0x%X defend=%u parriable=%u lethal=%u dir=(%.3f,%.3f,%.3f) row=%s mode=%s",
                   static_cast<unsigned long long>(g_request_id), ue::ObjectName(pawn).c_str(), ue::ObjectName(attacker).c_str(),
                   identity.index, identity.serial, cached, parsed, hit.use_damage, hit.raw_damage, default_damage,
                   ue::ObjectName(db).c_str(), resolved, valid, hit.guard_type, hit.attack_power, hit.hit_type,
                   hit.behavior, hit.defend, hit.parriable, hit.lethal, hit.direction.x, hit.direction.y, hit.direction.z,
                   row_name.c_str(), active ? "MC_CANCEL" : "OBSERVE_ONLY");
        }
        if (active) {
            g_cancelled.fetch_add(1, std::memory_order_relaxed);
            if (context.can_forward && hooks::PoseActive() && valid && resolved > 0 && resolved <= 1000000 && (hit.behavior & 1)) {
                protocol::PlayerHitPayload payload{};
                const auto target = std::find_if(context.targets.begin(), context.targets.end(), [&](const auto& t) {
                    return t.actor == attacker && t.identity == identity;
                });
                if (target != context.targets.end()) { payload.attacker_id = target->id; payload.spawn = target->spawn; }
                payload.sifu_damage = resolved;
                payload.flags = (hit.defend ? protocol::kHitCanDefend : 0) | (hit.parriable ? protocol::kHitParriable : 0) |
                    (hit.lethal ? protocol::kHitCanBeLethal : 0) | (static_cast<std::uint32_t>(hit.attack_power) << 8);
                payload.world_generation = context.generation;
                payload.anchor_epoch = context.epoch;
                payload.dir_x = hit.direction.x; payload.dir_y = hit.direction.y;
                AcquireSRWLockExclusive(&g_lock);
                if (g_pending.size() < 128) {
                    g_pending.push_back({context.session, GetTickCount64(), payload});
                    g_forwarded.fetch_add(1, std::memory_order_relaxed);
                } else g_dropped.fetch_add(1, std::memory_order_relaxed);
                ReleaseSRWLockExclusive(&g_lock);
            } else if (!valid) {
                g_dropped.fetch_add(1, std::memory_order_relaxed);
            }
            return false; // no Sifu reaction order, health loss or structure damage under MC ownership
        }
    }
    return g_observer.original<ValidateFn>()(component, result, request_ptr);
}
}  // namespace

void InstallObserver(std::uintptr_t base, std::uintptr_t pawn) {
    if (g_ready.load() || g_failed || !ue::IsLive(pawn)) return;
    const auto hit = mem::ReadPtr(pawn + off::AFightingCharacter::m_HitComponent);
    if (!ue::IsLive(hit) || !hooks::Resolve(base, gen::hook::ValidateHit, hit).ready()) return;
    const auto native = ue::FindClass("HitComponent");
    const auto function = ue::FindFunction(ue::ClassOf(hit), "BPE_ValidateHit");
    const auto flags = mem::ReadOr<std::uint32_t>(function + off::UFunction::FunctionFlags, 0);
    g_guard_class = ue::FindClass("GuardDB");
    g_table_class = ue::FindClass("DataTable");
    if (!native || !g_guard_class || !g_table_class || !function || !damage::ObserverReady()) return;
    if (ue::OuterOf(function) != native || !(flags & 0x400)) {
        SC_LOG("[player-hits] REFUSE H6: effective validation has a blueprint override");
        g_failed = true;
        return;
    }
    g_base = base;
    if (!g_observer.Install(base, gen::hook::ValidateHit, hit, reinterpret_cast<void*>(&ObserveHit))) return;
    const bool restored = g_observer.Uninstall() && mem::ReadPtr(g_observer.slot_address()) == base + gen::hook::ValidateHit.original;
    const bool reinstalled = restored && g_observer.Install(base, gen::hook::ValidateHit, hit, reinterpret_cast<void*>(&ObserveHit));
    SC_LOG("[player-hits] H6 uninstall/reinstall -> %s; forwarding=MC_ONLY (native control remains observable)", reinstalled ? "PASS" : "FAIL");
    g_failed = !reinstalled;
    g_ready.store(reinstalled, std::memory_order_release);
}

bool UninstallObserver() {
    g_ready.store(false, std::memory_order_release);
    return !g_observer.installed() || g_observer.Uninstall();
}

bool OwnsObserver(std::uintptr_t value) {
    return g_ready.load(std::memory_order_acquire) && value == reinterpret_cast<std::uintptr_t>(&ObserveHit);
}

void ObserveHealthDamage(std::uintptr_t health, std::uintptr_t infos, float before, float after) {
    const auto pawn = CurrentPawn();
    if (!ue::IsLive(pawn) || health != mem::ReadPtr(pawn + off::AFightingCharacter::m_HealthComponent)) return;
    g_health_calls.fetch_add(1, std::memory_order_relaxed);
    if (g_health_logs++ >= 200) return;
    const auto damage = mem::ReadOr<float>(infos + off::FDamageInfos::m_fDamage, NAN);
    const auto behavior = mem::ReadOr<std::uint32_t>(infos + off::FDamageInfos::m_uiApplyDamageBehavior, 0);
    const auto instigator = ue::ResolveWeak(infos + off::FDamageInfos::m_Instigator);
    SC_LOG("[player-hits] H7 native player damage=%.3f behavior=0x%X HP=%.3f->%.3f instigator=%s "
           "nearest_request=%llu age_ms=%llu (time hint only; original executed)", damage, behavior, before, after,
           ue::ObjectName(instigator).c_str(), static_cast<unsigned long long>(g_request_id),
           static_cast<unsigned long long>(g_last_ms ? GetTickCount64() - g_last_ms : 0));
}

void UpdateContext(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, bool active, bool can_forward,
                   std::uint32_t epoch, std::uint64_t session, const std::vector<Enemies::Target>& targets) {
    const auto identity = ue::IdentityOf(pawn);
    AcquireSRWLockExclusive(&g_lock);
    g_context = {world, pawn, identity, generation, epoch, session, GetTickCount64(), active && identity.valid(), can_forward, targets};
    ReleaseSRWLockExclusive(&g_lock);
}

void DrainHits(std::vector<Hit>* hits) {
    AcquireSRWLockExclusive(&g_lock);
    hits->clear(); hits->swap(g_pending);
    ReleaseSRWLockExclusive(&g_lock);
}

bool BlockHealthDamage(std::uintptr_t health, std::uintptr_t infos) {
    const auto pawn = CurrentPawn();
    if (!ue::IsLive(pawn) || health != mem::ReadPtr(pawn + off::AFightingCharacter::m_HealthComponent)) return false;
    Context context;
    if (!Active(pawn, &context)) return false;
    g_blocked.fetch_add(1, std::memory_order_relaxed);
    if (g_block_logs++ < 20) SC_LOG("[player-hits] H7 discarded native player damage=%.3f behavior=0x%X world=%u epoch=%u",
        mem::ReadOr<float>(infos + off::FDamageInfos::m_fDamage, NAN),
        mem::ReadOr<std::uint32_t>(infos + off::FDamageInfos::m_uiApplyDamageBehavior, 0), context.generation, context.epoch);
    return true;
}

std::string TakeStats() {
    const auto take = [](std::atomic<std::uint64_t>& n) { return static_cast<unsigned long long>(n.exchange(0)); };
    return FormatString("H6=%s forwarding=MC_ONLY hits=%llu resolved=%llu unresolved=%llu native_player_damage=%llu "
                        "cancelled=%llu queued_hits=%llu dropped=%llu H7_discarded=%llu",
                        g_ready.load() ? "on" : "off", take(g_hits), take(g_resolved), take(g_unresolved), take(g_health_calls),
                        take(g_cancelled), take(g_forwarded), take(g_dropped), take(g_blocked));
}
}  // namespace sifucraft::combat::player_hits
