#pragma once

#include <cstdint>

// P4 research: what spawning a block in Sifu needs, read-only. Resolves the reflected functions a
// block actor is created, set up and destroyed with and logs their parameter layouts; lists the
// loaded cube meshes and stone-like materials to build it from; and logs how characters collide
// (which object type a block must have to stop the player and the enemies). It never writes game
// memory or calls game functions.
namespace sifucraft::probe {

void LogBlockCensus(std::uintptr_t world, std::uintptr_t pawn);

}  // namespace sifucraft::probe
