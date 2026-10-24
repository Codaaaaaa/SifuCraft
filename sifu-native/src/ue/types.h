#pragma once

#include <cstddef>
#include <cstdint>

#include "generated/sifu_build.g.h"

// Minimal value types with the exact layout of their UE4.26 counterparts.
namespace sifucraft::ue {

struct FName {
    std::uint32_t comparison_index;
    std::uint32_t number;
};
static_assert(sizeof(FName) == gen::size::FName);

struct FVector {
    float x, y, z;
};

struct FRotator {
    float pitch, yaw, roll;
};

struct FBoxSphereBounds {
    FVector origin;
    FVector extent;
    float sphere_radius;
};
static_assert(offsetof(FBoxSphereBounds, extent) == gen::off::FBoxSphereBounds::BoxExtent);

// EObjectFlags / EInternalObjectFlags bits the probe cares about.
inline constexpr std::uint32_t RF_ClassDefaultObject = 0x10;
inline constexpr std::uint32_t RF_ArchetypeObject = 0x20;
inline constexpr std::int32_t kInternalUnreachable = 1 << 28;
inline constexpr std::int32_t kInternalPendingKill = 1 << 29;

}  // namespace sifucraft::ue
