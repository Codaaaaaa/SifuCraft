#pragma once

#include <cmath>
#include <cstdint>

#include "core/safe_memory.h"
#include "ue/types.h"

namespace sifucraft::combat {

// A live FHitRequest. Uncached requests need a separately validated data-table row; their inline
// cache is never used as a substitute for that row.
struct HitRequestSample {
    std::uint8_t cached = 0, use_damage = 0, guard_type = 0;
    std::uint8_t attack_power = 0, hit_type = 0, defend = 0, parriable = 0, lethal = 0;
    float raw_damage = NAN;
    std::uint32_t behavior = 0;
    ue::FVector direction{};
};

inline bool ReadHitRequest(std::uintptr_t request, std::uintptr_t row, HitRequestSample* out) {
    namespace off = gen::off;
    HitRequestSample hit;
    const auto box = request + off::FHitRequest::m_HitBox;
    const auto impact = row + off::FHitboxDataRow::m_OnHitImpact;
    if (!row || !mem::Read(box + off::FHitBox::m_bCachedRowSetup, &hit.cached) || hit.cached > 1 ||
        !mem::Read(impact + off::FHitImpact::m_bUseDamage, &hit.use_damage) || hit.use_damage > 1 ||
        !mem::Read(impact + off::FHitImpact::m_eGuardType, &hit.guard_type) || hit.guard_type >= gen::value::EGuardType::Count ||
        !mem::Read(impact + off::FHitImpact::m_fDamage, &hit.raw_damage) ||
        !mem::Read(row + off::FHitboxDataRow::m_eAttackPower, &hit.attack_power) ||
        !mem::Read(row + off::FHitboxDataRow::m_eHitType, &hit.hit_type) ||
        !mem::Read(row + off::FHitboxDataRow::m_bCanBeLethal, &hit.lethal) || hit.lethal > 1 ||
        !mem::Read(row + off::FHitboxDataRow::m_bParriable, &hit.parriable) || hit.parriable > 1 ||
        !mem::Read(request + off::FHitRequest::m_bCanBeDefended, &hit.defend) || hit.defend > 1 ||
        !mem::Read(request + off::FHitRequest::m_uiApplyDamageBehavior, &hit.behavior) ||
        !mem::Read(request + off::FHitRequest::m_vDamageDir, &hit.direction)) return false;
    if (hit.use_damage && (!std::isfinite(hit.raw_damage) || hit.raw_damage < 0)) return false;
    if (!std::isfinite(hit.direction.x) || !std::isfinite(hit.direction.y) || !std::isfinite(hit.direction.z)) return false;
    *out = hit;
    return true;
}

inline bool ReadCachedHitRequest(std::uintptr_t request, HitRequestSample* out) {
    namespace off = gen::off;
    const auto box = request + off::FHitRequest::m_HitBox;
    if (mem::ReadOr<std::uint8_t>(box + off::FHitBox::m_bCachedRowSetup, 0) != 1) return false;
    return ReadHitRequest(request, box + off::FHitBox::m_CachedRow, out);
}

inline bool ResolveHitDamage(const HitRequestSample& hit, float default_damage, float* out) {
    const float damage = hit.use_damage ? hit.raw_damage : default_damage;
    if (!std::isfinite(damage) || damage < 0) return false;
    *out = damage;
    return true;
}

// Read the current stack entry only if its value is static. Sifu may execute a TFunction to
// refresh dynamic values; that call is outside this read-only inspection's authorization.
inline std::uintptr_t StaticGuardValue(std::uintptr_t defense) {
    namespace off = gen::off;
    mem::RawArray stack;
    if (!mem::ReadArray(defense + off::UDefenseComponent::m_GuardDB + off::GuardStack::m_Stack, &stack) ||
        stack.num < 1 || stack.num > 64) return 0;
    const auto top = stack.data + static_cast<std::size_t>(stack.num - 1) * gen::size::GuardStackValue;
    std::uintptr_t callable = 0;
    if (!mem::Read(top + off::GuardStackValue::m_ValueGetter + off::GuardValueGetter::Callable, &callable) || callable) return 0;
    return top + off::GuardStackValue::m_Value; // address of a weak pointer, not an object
}

}  // namespace sifucraft::combat
