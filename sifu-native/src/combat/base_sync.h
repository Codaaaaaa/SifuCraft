#pragma once
#include "combat/motion_probe.h"
namespace sifucraft::world_motion::base_sync {
void Context(std::uintptr_t base,const combat::motion::Context* context,bool enabled);
void PoseOwned(std::uintptr_t movement);
void BeforeOriginal(std::uintptr_t movement);
}
