#pragma once

#include <cstdint>

// Sifu fades whatever stands between its third-person camera and the player: the environment's
// materials read MPC_GameplayGeneric ("ENV | CamFade", "PLAYER | Player Distance from Camera") and
// dither away what is that close to the camera. With Minecraft's first-person view that eats into
// walls and blocks right in front of the player, so while Minecraft owns the view both are held at
// 0 (written after Sifu's own camera update every frame), and Sifu's values are put back when it
// takes the view again.
namespace sifucraft::hooks::camera_fade {

// Runtime thread: finds the parameter collection and the material library's functions; retried
// until they are loaded.
void Resolve();

// Game thread, right after Sifu's camera update.
void AfterCameraUpdate(std::uintptr_t camera_manager, bool minecraft_view);

}  // namespace sifucraft::hooks::camera_fade
