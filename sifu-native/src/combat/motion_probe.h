#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "combat/enemies.h"
#include "combat/motion_lease.h"
#include "collision/voxels.h"
#include "ue/types.h"

namespace sifucraft::combat::motion {
// Opt-in diagnostic only. No piston producer is enabled by this module.
struct Context {
    Scope scope;
    std::uintptr_t pawn = 0;
    ue::ObjectIdentity pawn_identity;
    std::uint64_t updated_ms = 0;
    std::uint64_t native_frame = 0;
    bool enabled = false;
    std::vector<Enemies::Target> targets;
    std::shared_ptr<const collision::VoxelGrid> grid;
};
// Shared, read-only qualifications and the fingerprint/reflection checked engine sweep.
// Production world motion keeps its own lease and never uses the diagnostic budgets.
struct BodySample {
    std::uintptr_t root=0,health=0;
    ue::FVector position{},velocity{};
    ue::FRotator rotation{};
    float hp=0,radius=0,half=0;
    int mode=-1;
};
struct SweepResult { BodySample body;bool invoked=false,returned=false,blocking=false,penetrating=false; };
const char* CheckWorld(std::uintptr_t base,const Context& context,std::uint64_t now);
const char* CheckBody(const Context& context,const Target& target,BodySample* sample,bool geometry,bool ground=true);
const char* CheckPeers(std::uintptr_t base,const Context& context,const Target& target,Peers* peers,bool idle);
bool SweepBody(const Context& context,const Target& target,const BodySample& before,const ue::FVector& delta,SweepResult* result);
void Configure(bool enabled,bool ai_enabled=false);
void UpdateContext(Context context); // runtime thread, includes local diagnostic file polling
void ApplyOnGameThread(std::uintptr_t image_base);
bool SkipMovement(std::uintptr_t component); // existing H5, never suppress on another thread
void AfterOriginalMovement(std::uintptr_t component);
bool SkipDecision(std::uintptr_t component,Part part);
void AfterOriginalDecision(std::uintptr_t component,Part part);
}  // namespace sifucraft::combat::motion
