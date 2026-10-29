#pragma once

#include <cstdint>

// Which primitive components the player pawn collides with, and where their collision lives.
// Read-only; mirrors how UE decides it for the pawn's movement sweeps.
namespace sifucraft::collision {

// FBodyInstance collision filtering of one component.
struct Responses {
    std::uint8_t object_type = 0xFF;  // ECollisionChannel
    std::uint8_t to_channel[32]{};    // ECollisionResponse per channel
};
Responses ReadResponses(std::uintptr_t component);

// Query collision on, and each side blocks the other's object type.
bool Blocks(std::uintptr_t component, const Responses& pawn);

// Static or stationary (not EComponentMobility::Movable).
bool IsFixed(std::uintptr_t component);

// The body setup the component's collision was built from (0 if none).
std::uintptr_t BodySetupOf(std::uintptr_t component);

// The walkable floor Z the pawn's movement uses on this component: `pawn_walkable_z` as changed by
// the component's walkable slope override (its body instance's own, or its body setup's).
float WalkableFloorZOn(std::uintptr_t component, float pawn_walkable_z);

// Whether the pawn's (simple) sweeps hit this body setup's triangle meshes instead of its simple
// shapes: CTF_UseComplexAsSimple, or CTF_UseDefault in a project whose default is that.
bool UsesComplexAsSimple(std::uintptr_t body_setup);

// UPhysicsSettings::DefaultShapeComplexity (cached; negative if it could not be read).
int ProjectDefaultShapeComplexity();

// Loaded levels that are visible, i.e. whose collision is in the physics scene.
int VisibleLevelCount(std::uintptr_t world);
std::uint64_t VisibleLevelSignature(std::uintptr_t world);
bool LevelVisible(std::uintptr_t level);

// The visible level of `world` that owns `component` through its actor, or 0.
std::uintptr_t VisibleLevelOf(std::uintptr_t component, std::uintptr_t world);

}  // namespace sifucraft::collision
