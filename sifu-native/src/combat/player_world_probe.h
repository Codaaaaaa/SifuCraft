#pragma once
#include "combat/motion_probe.h"

namespace sifucraft::world_motion::player_probe {
// Existing H5 only. Reads the current local player's original movement, never filters it.
void Before(std::uintptr_t base,const combat::motion::Context& context,std::uintptr_t component,bool enabled);
void After(std::uintptr_t base,const combat::motion::Context& context,std::uintptr_t component,bool enabled);
}
