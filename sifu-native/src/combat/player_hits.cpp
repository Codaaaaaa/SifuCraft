#include "combat/player_hits.h"

#include <windows.h>

#include <atomic>
#include <algorithm>
#include <cmath>

#include "combat/damage.h"
#include "combat/attack_glow.h"
#include "combat/hit_sample.h"
#include "combat/shield_timing.h"
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
std::uintptr_t g_attack_class = 0, g_combo_class = 0, g_animation_class = 0, g_notify_class = 0, g_curve_class = 0;
ue::ObjectIdentity g_glow_layout;
std::int32_t g_glow_curve_offset = -1;
std::atomic<std::uintptr_t> g_settings_default{0};
std::uint64_t g_settings_retry_ms = 0; // runtime thread
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

std::uintptr_t RowHandle(std::uintptr_t handle, std::string* name) {
    const auto table = mem::ReadPtr(handle + off::FDataTableRowHandle::DataTable);
    ue::FName key{};
    if (!ue::IsRegistered(table) || !ue::IsA(table, g_table_class) ||
        !mem::Read(handle + off::FDataTableRowHandle::RowName, &key)) return 0;
    const auto row_struct = mem::ReadPtr(table + off::UDataTable::RowStruct);
    if (!ue::IsRegistered(row_struct) || ue::ObjectName(row_struct) != "HitboxDataRow" ||
        mem::ReadOr<std::uint32_t>(row_struct + off::UStruct::PropertiesSize, 0) != gen::size::FHitboxDataRow) return 0;
    *name = ue::ObjectName(table) + ":" + ue::NameToString(key);
    return ue::DataTableRow(table, key);
}

std::uintptr_t HitRow(std::uintptr_t request, std::string* name) {
    const auto box = request + off::FHitRequest::m_HitBox;
    const auto cached = mem::ReadOr<std::uint8_t>(box + off::FHitBox::m_bCachedRowSetup, 0xFF);
    if (cached == 1) { *name = "inline"; return box + off::FHitBox::m_CachedRow; }
    if (cached != 0) return 0;
    if (const auto row = RowHandle(box + off::FHitBox::m_HitRow, name)) return row;
    const auto settings = g_settings_default.load(std::memory_order_acquire);
    if (!ue::IsRegistered(settings)) return 0;
    const auto row = RowHandle(settings + off::USCGameSettings::m_DefaultHitbox, name);
    if (row) *name = "settings-default:" + *name;
    return row;
}

float DefaultDamage(std::uintptr_t pawn, std::uint8_t guard_type, std::uintptr_t* db) {
    const auto defense = mem::ReadPtr(pawn + off::AFightingCharacter::m_DefenseComponent);
    if (!ue::IsLive(defense)) return NAN;
    const auto weak = StaticGuardValue(defense);
    *db = weak ? ue::ResolveWeak(weak) : 0;
    if (!ue::IsRegistered(*db) || !ue::IsA(*db, g_guard_class)) return NAN;
    std::uint32_t at = off::UGuardDB::m_DefaultHitImpact;
    if (guard_type == gen::value::EGuardType::Guard) at = off::UGuardDB::m_DefaultGuardImpact;
    if (guard_type == gen::value::EGuardType::Deflect) at = off::UGuardDB::m_DefaultDeflectImpact;
    return mem::ReadOr<float>(*db + at + off::FHitImpact::m_fDamage, NAN);
}

std::uintptr_t AttackData(std::uintptr_t request) {
    static_assert(off::ComboAttackMap::Pairs == off::DataTableMap::Pairs &&
        gen::size::ComboAttackElement == gen::size::DataTableElement &&
        off::ComboAttackElement::Value == off::DataTableElement::Value &&
        off::ComboAttackPair::Key == off::DataTablePair::Key && off::ComboAttackPair::Value == off::DataTablePair::Value);
    const auto info = request + off::FHitRequest::m_AttackRequest;
    auto db = mem::ReadPtr(info + off::FAttackRequest::m_AttackDB);
    if (ue::IsRegistered(db) && ue::IsA(db, g_attack_class)) return db;
    const auto combo = mem::ReadPtr(info + off::FAttackRequest::m_Combo);
    ue::FName name{};
    if (!ue::IsRegistered(combo) || !ue::IsA(combo, g_combo_class) || !mem::Read(info + off::FAttackRequest::m_AttackName, &name)) return 0;
    db = ue::NamePointerMapValue(combo + off::UCombo::m_Attacks, name);
    return ue::IsRegistered(db) && ue::IsA(db, g_attack_class) ? db : 0;
}

std::uintptr_t GlowCurve(std::uintptr_t notify) {
    if (!ue::IsRegistered(notify) || !ue::IsA(notify, g_notify_class)) return 0;
    const auto cls = ue::ClassOf(notify);
    if (!ue::IsRegistered(cls) || ue::ObjectName(cls) != "AnimNotify_GlowLimbs_C") return 0;
    const auto identity = ue::IdentityOf(cls);
    if (identity != g_glow_layout) {
        g_glow_layout = identity;
        g_glow_curve_offset = -1;
        const auto size = mem::ReadOr<std::int32_t>(cls + off::UStruct::PropertiesSize, 0);
        for (const auto& p : ue::ListProperties(cls)) {
            if (p.name == "Effect Curve" && p.type == "ObjectProperty" && p.element_size == 8 && p.array_dim == 1 &&
                p.offset >= 0 && p.offset <= size - 8 && mem::ReadPtr(p.field + off::FObjectPropertyBase::PropertyClass) == g_curve_class) {
                g_glow_curve_offset = p.offset;
            }
        }
    }
    if (g_glow_curve_offset < 0) return 0;
    const auto curve = mem::ReadPtr(notify + g_glow_curve_offset);
    return ue::IsRegistered(curve) && ue::IsA(curve, g_curve_class) ? curve : 0;
}

bool ObserveHit(void* component, const void* result, const void* request_ptr) {
    const auto pawn = CurrentPawn();
    if (ue::IsLive(pawn) && reinterpret_cast<std::uintptr_t>(component) == mem::ReadPtr(pawn + off::AFightingCharacter::m_HitComponent)) {
        const auto request = reinterpret_cast<std::uintptr_t>(request_ptr);
        HitRequestSample hit;
        std::string row_name;
        const bool parsed = ReadHitRequest(request, HitRow(request, &row_name), &hit);
        const auto attack_db = parsed ? AttackData(request) : 0;
        const auto attack = attack_db ? attack_db + off::UAttackDB::m_Attack : 0;
        const auto animation = attack ? mem::ReadPtr(attack + off::FComboAttack::m_Animation) : 0;
        const bool guard_breaker = ue::IsRegistered(animation) && ue::IsA(animation, g_animation_class) &&
            AttackHasGlow(request, attack, animation, GlowCurve);
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
                   "behavior=0x%X defend=%u parriable=%u guard_breaker=%d lethal=%u dir=(%.3f,%.3f,%.3f) row=%s attack=%s anim=%s mode=%s",
                   static_cast<unsigned long long>(g_request_id), ue::ObjectName(pawn).c_str(), ue::ObjectName(attacker).c_str(),
                   identity.index, identity.serial, cached, parsed, hit.use_damage, hit.raw_damage, default_damage,
                   ue::ObjectName(db).c_str(), resolved, valid, hit.guard_type, hit.attack_power, hit.hit_type,
                   hit.behavior, hit.defend, hit.parriable, guard_breaker, hit.lethal, hit.direction.x, hit.direction.y, hit.direction.z,
                   row_name.c_str(), ue::ObjectName(attack_db).c_str(), ue::ObjectName(animation).c_str(), active ? "MC_CANCEL" : "OBSERVE_ONLY");
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
                    (hit.lethal ? protocol::kHitCanBeLethal : 0) | (guard_breaker ? protocol::kHitGuardBreaker : 0) |
                    (static_cast<std::uint32_t>(hit.attack_power) << 8);
                const auto use_age = input::Router().UseAge(GameSeconds(g_base));
                if (hit.defend && InParryWindow(db, use_age))
                    payload.flags |= protocol::kHitPerfectWindow;
                if (g_hit_logs <= 200 && std::isfinite(use_age)) SC_LOG("[shield] timing candidate age_ms=%.2f perfect_window=%d attacker=%u/%u",
                    use_age * 1000, (payload.flags & protocol::kHitPerfectWindow) != 0, payload.attacker_id, payload.spawn);
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
    if (!g_settings_default.load(std::memory_order_acquire) && GetTickCount64() >= g_settings_retry_ms) {
        g_settings_retry_ms = GetTickCount64() + 1000;
        const auto cls = ue::FindClass("SCGameSettings");
        const auto defaults = cls ? ue::DefaultObject(cls) : 0;
        if (ue::IsRegistered(defaults)) g_settings_default.store(defaults, std::memory_order_release);
    }
    if (g_ready.load() || g_failed || !ue::IsLive(pawn)) return;
    const auto hit = mem::ReadPtr(pawn + off::AFightingCharacter::m_HitComponent);
    if (!ue::IsLive(hit) || !hooks::Resolve(base, gen::hook::ValidateHit, hit).ready()) return;
    const auto native = ue::FindClass("HitComponent");
    const auto function = ue::FindFunction(ue::ClassOf(hit), "BPE_ValidateHit");
    const auto flags = mem::ReadOr<std::uint32_t>(function + off::UFunction::FunctionFlags, 0);
    g_guard_class = ue::FindClass("GuardDB");
    g_table_class = ue::FindClass("DataTable");
    g_attack_class = ue::FindClass("AttackDB"); g_combo_class = ue::FindClass("Combo");
    g_animation_class = ue::FindClass("AnimSequenceBase"); g_notify_class = ue::FindClass("AnimNotify");
    g_curve_class = ue::FindClass("CurveFloat");
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
    if (reinstalled) {
        std::uintptr_t db = 0;
        const float hit = DefaultDamage(pawn, gen::value::EGuardType::None, &db);
        const float guard = DefaultDamage(pawn, gen::value::EGuardType::Guard, &db);
        const float deflect = DefaultDamage(pawn, gen::value::EGuardType::Deflect, &db);
        std::string row_name;
        const auto settings = g_settings_default.load(std::memory_order_acquire);
        const auto row = ue::IsRegistered(settings) ? RowHandle(settings + off::USCGameSettings::m_DefaultHitbox, &row_name) : 0;
        SC_LOG("[player-hits] read-only defaults: GuardDB=%s flags=0x%X damage(hit/guard/deflect)=%.3f/%.3f/%.3f "
               "settings_hitbox=%s resolved=%d", ue::ObjectName(db).c_str(), ue::FlagsOf(db), hit, guard, deflect,
               row_name.c_str(), row != 0);
    }
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
    const bool changed = g_context.world != world || g_context.generation != generation || g_context.pawn != pawn || g_context.identity != identity || g_context.epoch != epoch ||
        g_context.session != session || g_context.active != active || g_context.can_forward != can_forward;
    g_context = {world, pawn, identity, generation, epoch, session, GetTickCount64(), active && identity.valid(), can_forward, targets};
    ReleaseSRWLockExclusive(&g_lock);
    if (changed) input::Router().ResetUse();
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
