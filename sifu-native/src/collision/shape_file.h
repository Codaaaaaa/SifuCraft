#pragma once

#include <string>

#include "collision/shapes.h"

// The extracted shapes saved as they came out of the game, so that the voxelization can be re-run
// and measured offline (tools/collision_replay.cpp) without Sifu.
namespace sifucraft::collision {

// Layout (little endian): "SCSH", u32 version, f32 pawn walkable Z, u32 map name length, map name
// bytes; u32 convex count, per convex f32 walkable Z, u32 vertex count, f32[3] per vertex (UE cm);
// u32 capsule count, per capsule f32[3] a, f32[3] b, f32 radius; u32 triangle count, per triangle
// f32[3] x 3 vertices, f32 walkable Z.
inline constexpr std::uint32_t kShapeFileVersion = 1;

bool WriteShapeFile(const std::wstring& path, const ShapeSet& shapes, const std::string& map);
// Only the shapes and walkable Z are restored, not the extraction counters.
bool ReadShapeFile(const std::wstring& path, ShapeSet* shapes, std::string* map);

}  // namespace sifucraft::collision
