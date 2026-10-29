#pragma once
#include "combat/world_motion_service.h"
namespace sifucraft::npc_debug {
void Configure(bool enabled);
void Exchange(const world_motion::Context& context); // runtime IO; never calls UE
void ApplyOnGameThread(std::uintptr_t base); // existing H1; no new hook
}
