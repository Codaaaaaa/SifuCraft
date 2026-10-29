#pragma once

#include <cstdint>
#include <unordered_map>
#include <vector>
#include <optional>
#include "collision/coverage.h"

#include "collision/shapes.h"

// The collision as a sparse grid of 1/8-block cells (12.5 cm) in Unreal space. Blocks are 1 m
// cubes aligned to the Unreal origin, so with whole-block offsets the cells land exactly on
// Minecraft's 1/8 grid. Cell (x, y, z) of a block is bit x + 8y of 64-bit layer z.
namespace sifucraft::collision {

inline constexpr float kBlockCm = 100.0f;
inline constexpr int kCellsPerBlock = 8;
inline constexpr float kCellCm = kBlockCm / kCellsPerBlock;

int FloorDiv(int value, int divisor);
// The cell a coordinate (cm) falls in.
int CellIndex(float cm);

class VoxelGrid {
public:
    struct Block {
        std::uint64_t layers[kCellsPerBlock]{};
        bool operator==(const Block&) const = default;
    };

    bool operator==(const VoxelGrid&) const = default;
    void Clip(CellRegion region){clip_=region;}
    void ReplaceRegion(const VoxelGrid& source,const CellRegion& region);

    // Global cell coordinates (cell = floor(position / kCellCm)).
    void Set(int x, int y, int z);
    // Cells x0..x1 (inclusive) of one row.
    void SetRow(int x0, int x1, int y, int z);
    bool Get(int x, int y, int z) const;
    // The highest solid cell of column (x, y) between z_from and z_to (inclusive, z_from >= z_to),
    // or z_to - 1 if there is none.
    int TopCellBetween(int x, int y, int z_from, int z_to) const;

    static std::int64_t Key(int bx, int by, int bz);
    static void Unkey(std::int64_t key, int* bx, int* by, int* bz);

    const std::unordered_map<std::int64_t, Block>& blocks() const { return blocks_; }
    std::uint64_t CellCount() const;

private:
    std::unordered_map<std::int64_t, Block> blocks_;
    std::optional<CellRegion> clip_;
};

// The top (cm) of the highest solid cell in the column under (x, y), searched from a metre above
// `feet_z` to three metres below it; NAN if there is none.
float FloorUnder(const VoxelGrid& grid, float x, float y, float feet_z);
// Nearby original-scene support only, not a floor many metres below a Minecraft sky platform.
bool HasFloorSupport(const VoxelGrid& grid, const FVector& feet);

// A plane n.p + d = 0; inside is n.p + d <= 0.
struct Plane {
    double n[3];
    double d;
};

// The faces of the convex hull of `points` (brute force over point triples; hull inputs are
// small). Coplanar inputs get their two faces plus the side planes of the polygon's edges.
std::vector<Plane> HullPlanes(const std::vector<FVector>& points);

// A hull face: its outward plane and the points on it, counter-clockwise about the normal.
struct HullFace {
    Plane plane;
    std::vector<FVector> polygon;
};
// The faces among `planes` (the HullPlanes of `points`) with at least three distinct points.
std::vector<HullFace> HullFaces(const std::vector<FVector>& points, const std::vector<Plane>& planes);

// Separating-axis test of a triangle against an axis-aligned box.
bool TriangleOverlapsBox(const FVector& center, float half, const FVector& a, const FVector& b, const FVector& c);

struct VoxelOptions {
    // Triangles mark every cell they touch (the first rule, floors up to a cell too high) instead
    // of one cell behind them per column. For comparing the two offline.
    bool touch_triangles = false;
    // Up-facing faces too steep to walk on become wall columns up to their top.
    bool steep_walls = true;
};

struct VoxelStats {
    std::vector<UnknownBounds> rejected;
    bool rejected_bounds=false;
    std::uint64_t cells = 0;
    std::size_t blocks = 0;
    int convexes = 0, capsules = 0, triangles = 0;
    int degenerate = 0;   // shapes that produced no hull / zero-area triangles
    int oversized = 0;    // shapes larger than the sanity limit (left out)
    int slivers = 0;      // triangles too thin to hold a column centre: marked by touch instead
    int steep_faces = 0;  // faces raised into wall columns
    double ms_convex = 0, ms_capsule = 0, ms_triangle = 0;
};

// Solid shapes fill the cells whose centre is inside them (thin ones are grown to one cell so they
// cannot fall between centres). A triangle is a surface with the solid behind it: along the axis
// its normal is closest to, each column whose centre line crosses it gets the one cell behind the
// surface whose centre is within a cell of it, so the solid ends at the cell boundary nearest to
// the surface (at most half a cell off). Walkable floors, of triangles and of solid shapes alike,
// end at the boundary nearest to where a walking pawn's feet are, 2.15 cm above them (UE keeps the
// capsule floating that far), so Minecraft stands the player where Sifu stands the pawn. A
// triangle too thin to hold any column centre marks the
// cells it touches instead, unless they lie on a surface already made. Up-facing faces steeper
// than their shape's walkable floor are filled from the surface up to the next 1.5 m terrace
// level (at most the face's top): walls taller than Minecraft jumps, where a staircase of cells
// would let its step-up climb what the pawn cannot.
void Voxelize(const ShapeSet& shapes, VoxelGrid* grid, VoxelStats* stats, const VoxelOptions& options = {});

}  // namespace sifucraft::collision
