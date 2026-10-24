#pragma once

#include <algorithm>
#include <cstdint>

#include "ue/types.h"

namespace sifucraft::combat {

inline constexpr float kLightStunSeconds = 0.15f;

// Exact x64 signature from the matched PDB. The four enum parameters are byte-sized;
// the float and bool stack arguments must retain the order used by LaunchFakeImpact.
using LaunchImpactFn = void (*)(void*, std::uint8_t, std::uint8_t, std::uint8_t, std::uint8_t,
                               float, float, const ue::FVector*, float, float, bool, bool,
                               void*, const ue::FVector*);

inline float ImpactDamage(float requested, float health, bool can_die) {
    // Preserve BPF_LaunchImpact's lethal path: it passes remaining HP to the same callee.
    return can_die ? std::min(requested, health) : requested;
}

inline void LaunchLightImpact(LaunchImpactFn launch, void* component, const ue::FVector& location, float damage) {
    const ue::FVector direction{};
    launch(component, gen::value::EImpactType::Hit, gen::value::EQuadrantTypes::FrontLeft,
           gen::value::ESCCardinalPoints::North, gen::value::EHeight::Mid,
           0.0f, kLightStunSeconds, &location, damage, 0.0f, false, false, nullptr, &direction);
}

}  // namespace sifucraft::combat
