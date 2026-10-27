#pragma once
#include <cstdint>
#include "combat/motion_probe.h"

namespace sifucraft::world_motion::player_idle {
// H1 only. Clears only the ambient fidget timer while this module controls the
// player's physical pose; ordinary commands and their eligibility checks remain.
void Update(std::uintptr_t base,const combat::motion::Context& context,bool controlled);
}
