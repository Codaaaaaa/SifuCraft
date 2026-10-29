#pragma once
#include "combat/motion_probe.h"
namespace sifucraft::world_motion::base_sync {
void Context(std::uintptr_t base,const combat::motion::Context* context,bool enabled);
void PoseOwned(std::uintptr_t movement);
void TranslatePlayerPose(std::uintptr_t actor,float before_z,float after_z);
void BeforeOriginal(std::uintptr_t movement);
}
