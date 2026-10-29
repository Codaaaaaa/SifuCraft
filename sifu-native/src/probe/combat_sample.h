#pragma once

#include <cmath>
#include <cstdint>
#include <limits>

#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"

// Plain, read-only samples, also exercised against synthetic buffers by the offline tests.
namespace sifucraft::probe {

struct HealthSample {
    float health = 0;
    float base_max = 0;
    float multipliers[3]{};
    bool can_be_damaged = false;
    bool can_die = false;
    bool down = false;
};

inline bool ReadHealthSample(std::uintptr_t component, HealthSample* out) {
    namespace off = gen::off;
    HealthSample sample;
    std::uint8_t damageable = 0, mortal = 0, down = 0;
    if (!component || !mem::Read(component + off::UHealthComponent::m_fHealth, &sample.health) ||
        !mem::Read(component + off::UHealthComponent::m_fMaxHealth, &sample.base_max) ||
        !mem::Read(component + off::UCharacterHealthComponent::m_fMaxHealthMultiplier, &sample.multipliers) ||
        !mem::Read(component + off::UHealthComponent::m_bCanBeDamaged, &damageable) ||
        !mem::Read(component + off::UHealthComponent::m_bCanDieByDamage, &mortal) ||
        !mem::Read(component + off::UCharacterHealthComponent::m_bIsDown, &down)) return false;
    if (!std::isfinite(sample.health) || !std::isfinite(sample.base_max) || sample.base_max <= 0 ||
        damageable > 1 || mortal > 1 || down > 1) return false;
    for (float multiplier : sample.multipliers) {
        if (!std::isfinite(multiplier) || multiplier <= 0) return false;
    }
    sample.can_be_damaged = damageable != 0;
    sample.can_die = mortal != 0;
    sample.down = down != 0;
    *out = sample;
    return true;
}

// An unknown difficulty stays unknown: never present the base maximum as the real maximum.
inline float EffectiveMaxHealth(const HealthSample& sample, int difficulty) {
    if (difficulty < 0) return std::numeric_limits<float>::quiet_NaN();
    const float value = sample.base_max * (difficulty < 3 ? sample.multipliers[difficulty] : 1.0f);
    return std::isfinite(value) ? value : std::numeric_limits<float>::quiet_NaN();
}

struct HitSample {
    float final_damage = 0;
    float base_damage = 0;
    bool use_damage = false;
    bool can_be_defended = false;
    bool lethal = false;
    std::uint8_t attack_power = 0;
    std::uint8_t hit_type = 0;
};

// The last hit description is persistent state, not a stream of all hits. A cached row must be
// initialized and the result valid before its damage fields mean anything.
inline bool ReadHitSample(std::uintptr_t description, HitSample* out) {
    namespace off = gen::off;
    if (!description) return false;
    const auto request = description + off::FHitDescription::m_Request;
    const auto box = request + off::FHitRequest::m_HitBox;
    const auto row = box + off::FHitBox::m_CachedRow;
    const auto impact = row + off::FHitboxDataRow::m_OnHitImpact;
    const auto result = description + off::FHitDescription::m_ImpactResult;
    std::uint8_t valid = 0, cached = 0, use = 0, defend = 0, lethal = 0;
    HitSample sample;
    if (!mem::Read(result + off::FImpactResult::m_bIsValid, &valid) || valid != 1 ||
        !mem::Read(box + off::FHitBox::m_bCachedRowSetup, &cached) || cached != 1 ||
        !mem::Read(impact + off::FHitImpact::m_bUseDamage, &use) || use > 1 ||
        !mem::Read(request + off::FHitRequest::m_bCanBeDefended, &defend) || defend > 1 ||
        !mem::Read(result + off::FImpactResult::m_bLethal, &lethal) || lethal > 1 ||
        !mem::Read(result + off::FImpactResult::m_fDamage, &sample.final_damage) ||
        !mem::Read(impact + off::FHitImpact::m_fDamage, &sample.base_damage) ||
        !mem::Read(row + off::FHitboxDataRow::m_eAttackPower, &sample.attack_power) ||
        !mem::Read(row + off::FHitboxDataRow::m_eHitType, &sample.hit_type)) return false;
    if (!std::isfinite(sample.final_damage) || sample.final_damage < 0 ||
        (use && (!std::isfinite(sample.base_damage) || sample.base_damage < 0))) return false;
    sample.use_damage = use != 0;
    sample.can_be_defended = defend != 0;
    sample.lethal = lethal != 0;
    *out = sample;
    return true;
}

}  // namespace sifucraft::probe
