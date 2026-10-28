#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "collision/components.h"
#include "ue/objects.h"

namespace sifucraft::collision::coverage {
struct Component {std::uintptr_t pointer=0;ue::ObjectIdentity identity;};
struct Sample {
    std::uintptr_t world=0;std::uint32_t generation=0;std::uint64_t at_ms=0;
    ue::FVector center{},extent{};
    std::vector<Component> components;
    unsigned characters=0,derived=0,blocking=0,missing_setup=0;
};
// Bounded read-only preflight, once per native world, on H1's game thread.
// It does not yet mark any voxel or region as known-empty.
void Probe(std::uintptr_t base,std::uintptr_t world,std::uint32_t generation,std::uintptr_t pawn,const Responses& reference,std::uint64_t now);
std::shared_ptr<const Sample> Latest();
}
