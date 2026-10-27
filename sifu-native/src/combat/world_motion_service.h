#pragma once
#include "bridge/world_motion_mailbox.h"
#include "combat/motion_probe.h"
namespace sifucraft::world_motion {
struct Context {
    combat::motion::Context actors;
    std::uint64_t save=0,map=0;
    bool enabled=false;
};
// Runtime owns the mapping; H1 owns all actor bindings, leases and engine operations.
void Exchange(Context context,Mailbox& mailbox);
void ApplyOnGameThread(std::uintptr_t base);
bool Skip(std::uintptr_t component,combat::motion::Part part);
void AfterOriginal(std::uintptr_t component,combat::motion::Part part);
void PoseOwnedMovement(std::uintptr_t component);
void BeforeOriginalMovement(std::uintptr_t component);
struct PublishedTargets {std::vector<std::uint64_t> active,retained;};
PublishedTargets RetainedTargets(std::uint32_t world,std::uint64_t now);
}
