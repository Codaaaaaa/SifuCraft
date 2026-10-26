#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "combat/enemies.h"
#include "combat/motion_lease.h"
#include "collision/voxels.h"

namespace sifucraft::combat::motion {
// Opt-in diagnostic only. No piston producer is enabled by this module.
struct Context {
    Scope scope;
    std::uintptr_t pawn = 0;
    ue::ObjectIdentity pawn_identity;
    std::uint64_t updated_ms = 0;
    bool enabled = false;
    std::vector<Enemies::Target> targets;
    std::shared_ptr<const collision::VoxelGrid> grid;
};
void Configure(bool enabled);
void UpdateContext(Context context); // runtime thread, includes local diagnostic file polling
void ApplyOnGameThread(std::uintptr_t image_base);
bool SkipMovement(std::uintptr_t component); // existing H5, never suppress on another thread
void AfterOriginalMovement(std::uintptr_t component);
}  // namespace sifucraft::combat::motion
