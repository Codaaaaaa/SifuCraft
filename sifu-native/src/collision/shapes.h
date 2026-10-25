#pragma once

#include <cstdint>
#include <vector>

#include "collision/components.h"
#include "ue/types.h"

// The collision the player pawn stands on and bumps into, copied out of the game in world space
// (Unreal units: cm, Z up). Read-only.
namespace sifucraft::collision {

using ue::FVector;

// UCharacterMovementComponent's default WalkableFloorZ (a 44.765 degree slope).
inline constexpr float kDefaultWalkableFloorZ = 0.71f;

// Boxes and convex hulls: the hull of these points.
struct ConvexShape {
    std::vector<FVector> vertices;
    // Up-facing faces whose normal's Z is below this are too steep for the pawn to walk on (the
    // pawn's WalkableFloorZ as modified by the component's walkable slope override).
    float walkable_z = kDefaultWalkableFloorZ;
};

// Capsules; a sphere has a == b.
struct CapsuleShape {
    FVector a{}, b{};
    float radius = 0.0f;
};

// Triangle meshes the pawn's sweeps use as simple collision: surfaces, not solids. The winding
// gives the outward normal, (v1 - v0) x (v2 - v0).
struct TriangleShape {
    FVector v[3]{};
    float walkable_z = kDefaultWalkableFloorZ;
};

struct ShapeSet {
    std::vector<ConvexShape> convexes;
    std::vector<CapsuleShape> capsules;
    std::vector<TriangleShape> triangles;

    float pawn_walkable_z = kDefaultWalkableFloorZ;  // the pawn's own WalkableFloorZ

    // What went in, for the log.
    int components = 0;
    int boxes = 0, spheres = 0, capsule_elems = 0, convex_elems = 0, triangle_meshes = 0, instances = 0;
    int unreadable_meshes = 0;   // triangle meshes whose layout did not validate (left out)
    int skipped_movable = 0;     // blocking but movable: doors, props (not imported in P3)
    int no_body_setup = 0;
    int capped = 0;              // arrays larger than the sanity limits (left out)
    int slope_overrides = 0;     // components whose walkable slope override changes the pawn's
};

// Every static / stationary primitive component in a visible level of `world` that blocks a pawn
// with collision filtering `pawn` and walkable floor Z `pawn_walkable_z`. Runtime thread; reads
// memory only.
ShapeSet ExtractShapes(std::uintptr_t world, const Responses& pawn, float pawn_walkable_z);
bool DynamicSceneComponent(std::uintptr_t component);
std::vector<std::uintptr_t> DynamicSceneComponents(std::uintptr_t world);
ShapeSet ExtractComponents(const std::vector<std::uintptr_t>& components, const Responses& pawn, float walkable_z);

}  // namespace sifucraft::collision
