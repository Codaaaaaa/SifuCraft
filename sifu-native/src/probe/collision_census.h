#pragma once

#include <cstdint>

// P3 research: a read-only survey of the collision in the current world. For every primitive
// component in a visible level it works out whether it blocks the player pawn and in which form
// its collision exists (simple shapes, a triangle mesh used as simple collision, a landscape
// heightfield), and logs a summary. It never writes game memory or calls game functions.
namespace sifucraft::probe {

void LogCollisionCensus(std::uintptr_t world, std::uintptr_t pawn);

}  // namespace sifucraft::probe
