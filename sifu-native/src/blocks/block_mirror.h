#pragma once

#include <cstdint>
#include <string>

#include "bridge/sifu_bridge.h"
#include "bridge/shape_reader.h"

// P4: the blocks Minecraft has placed in the map Sifu shows, as actors in Sifu's world. Each block
// is an AStaticMeshActor holding the engine's 1 m cube, colliding like level geometry (object type
// 18, blocking every channel) and cut out of the navigation mesh, so it stops the player and the
// enemies alike. It looks like its Minecraft block: Minecraft exports each face's texture and a
// palette (SifuCraft\textures), and each texture becomes a material instance of a level material
// with a base color texture; a block whose faces differ shows six textured planes instead of the
// cube. Without those, blocks fall back to a stone material.
//
// The runtime thread works out which blocks should exist (Update); the game thread, from the engine
// tick hook, spawns and destroys a few per frame (ApplyOnGameThread) through reflected functions
// called with ProcessEvent. Actors die with their world: a new world starts from none.
namespace sifucraft::blocks {

// Runtime thread: the blocks of `table` when it is for `map`, none otherwise.
void Update(std::uintptr_t world, const std::string& map, const bridge::BlockList& table,
            const shape_data::Frame* shapes = nullptr);
bool ShapesReady();
bool ShapesSettled(); // exact latest desired revision, with no partially applied box queue
std::uint64_t ShapesAppliedRevision();

// Game thread (engine tick, stage 6).
void ApplyOnGameThread(std::uintptr_t image_base);

// One-line statistics since the previous call.
std::string TakeStats();

}  // namespace sifucraft::blocks
