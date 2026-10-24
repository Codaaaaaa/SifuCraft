#pragma once

#include <string>

#include "collision/shapes.h"
#include "collision/voxels.h"

// Debug output for checking the collision outside the game.
//  - .scvx: the voxel grid (read by tools/collision/voxview.py, which renders it to OBJ / PNG);
//  - .obj:  the source shapes, for comparing against the voxels in any 3D viewer.
// OBJ files use Minecraft's orientation in metres: (x, y, z) = (UE.x, UE.z, UE.y) / 100.
namespace sifucraft::collision {

// Layout (little endian): "SCVX", u32 version, f32 cell cm, u32 cells per block, f32[3] pawn feet
// (UE cm), u32 map name length, map name bytes, u32 block count, then per block i32 bx, by, bz and
// u64 layers[8] (cell x + 8y of layer z).
inline constexpr std::uint32_t kVoxelFileVersion = 1;

bool WriteVoxelFile(const std::wstring& path, const VoxelGrid& grid, const std::string& map, const FVector& pawn_feet);
bool WriteShapesObj(const std::wstring& path, const ShapeSet& shapes);

}  // namespace sifucraft::collision
