#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "ue/types.h"

// The P2 vtable hooks. Installation, self-tests and statistics run on the runtime thread; hook
// bodies run on the game thread and only touch atomics and the hooked game objects.
namespace sifucraft::hooks {

void Configure(std::uintptr_t image_base, int stage);

// H1: installs the engine-tick hook once GEngine matches the plan (stage >= 1). Safe to call
// repeatedly; returns whether the hook is installed.
bool InstallEngineHook();

// The local player's objects, refreshed by the runtime thread every tick (all 0 while loading).
// Hooks act only on these instances and pass every other object straight to the original.
void SetLocalPlayer(std::uintptr_t controller, std::uintptr_t camera, std::uintptr_t pawn, std::uintptr_t movement);

// H2/H3 (stage >= 2) and H4 (stage >= 4): installs the player hooks once the local controller /
// camera manager match the plan.
void InstallPlayerHooks();
bool InputHooksInstalled();
// Game thread, current local controller only. Uses the original input path and an owned FKey copy.
bool SendInteractionKey(std::uintptr_t controller, bool down);
bool InteractionKeyReady(); // H1-only balanced copy/destruction self-check, no gameplay input

// The view H4 writes over Sifu's camera after Sifu computed its own (Unreal space).
struct CameraOverride {
    bool active = false;
    ue::FVector location{};
    ue::FRotator rotation{};
    float fov = 0.0f;  // horizontal, degrees
};
void SetCameraOverride(const CameraOverride& view);

// The pawn pose H1 writes every frame (stage >= 5); while active, H5 suppresses the local
// player's own movement tick and the body is kept hidden: the pawn, plus the given impostor
// actors (Sifu's stand-ins that can show the character's body next to it).
struct PoseOverride {
    bool active = false;
    ue::FVector location{};  // actor location: capsule centre
    float yaw = 0.0f;
};
void SetPoseOverride(const PoseOverride& pose, const std::vector<std::uintptr_t>& impostors);
// H1, H5 and the reflected pose functions are all in place.
bool PoseHooksReady();
bool PoseActive();
// Frames in a row on which Sifu itself moved the pawn after our pose write (0 when idle).
std::uint32_t ConsecutiveDisplacements();

// Runtime-thread housekeeping, e.g. the uninstall/reinstall self-test.
void Maintain(std::uint64_t now_ms);

// One-line statistics since the previous call.
std::string TakeStats();

}  // namespace sifucraft::hooks
