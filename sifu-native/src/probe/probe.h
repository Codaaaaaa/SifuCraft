#pragma once

#include <cstdint>
#include <vector>

#include "bridge/sifu_bridge.h"

// Read-only runtime probe: reads world / player / camera / FightingCharacter state through the
// generated offsets. It never writes game memory, calls game functions or installs hooks.
namespace sifucraft::probe {

// Blocks until the name pool and object array are usable; opens reflection.txt.
void WaitForEngine(std::uintptr_t image_base);

// Resolves native classes and verifies the offset table against UE reflection. Call once a
// world exists.
void Prepare();

// The local player's objects; any of them may be 0.
struct PlayerObjects {
    std::uintptr_t game_instance = 0;
    std::uintptr_t controller = 0;
    std::uintptr_t pawn = 0;
    std::uintptr_t camera = 0;
    std::uintptr_t movement = 0;  // the pawn's CharacterMovement component
};
PlayerObjects FindPlayerObjects(std::uintptr_t world);

// AActor::bHidden.
bool ActorHidden(std::uintptr_t actor);

// Live character impostors (Sifu's stand-in actors that can show a character's body), as of the
// last LogWorld scan. Runtime thread only.
const std::vector<std::uintptr_t>& Impostors();

// Cheap per-tick read of the local player and camera into `frame` (leaves world fields alone).
void CapturePlayer(std::uintptr_t world, bridge::PlayerFrame* frame);

// Detailed log of the player, camera and every FightingCharacter; dumps newly seen classes.
void LogWorld(std::uintptr_t world, const bridge::PlayerFrame& frame);

}  // namespace sifucraft::probe
