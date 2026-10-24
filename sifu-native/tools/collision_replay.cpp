// Re-runs the voxelization on shapes saved by the game (SifuCraft\collision\<map>.shapes) and
// scores it, without Sifu:
//  - against recorded walks (SifuCraft\debug\walk_<map>.csv): the in-game walk check, replayed;
//  - against the shapes themselves (--surface): the voxel floor over every walkable surface.
//
//   sifucraft_collision_replay <map.shapes> [walk.csv]... [--touch] [--no-steep] [--compare]
//                              [--surface] [--grid out.scvx] [--outliers N]
//     --touch:    triangles mark every cell they touch (the first rule)
//     --no-steep: no wall columns under steep faces
//     --compare:  also score the first rule (touch, no steep walls) for comparison
//     --grid:     write the grid (for tools/collision/voxview.py, or fake_sifu --collision)
//     --outliers: list up to N surface columns whose voxel floor is more than half a cell off

#include <windows.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

#include "collision/export.h"
#include "collision/shape_file.h"
#include "collision/voxels.h"
#include "collision/walk_check.h"
#include "core/log.h"

namespace {

using namespace sifucraft;
using namespace sifucraft::collision;

constexpr float kSearchCm = 25.0f;     // a voxel floor this far from the surface belongs to it
constexpr float kCoveredCm = 12.5f;    // higher than this above it: something stands on the surface
constexpr float kHoverCm = 2.15f;      // a walking UE character's capsule floats this far above the floor

std::wstring Widen(const char* text) {
    const int size = MultiByteToWideChar(CP_UTF8, 0, text, -1, nullptr, 0);
    std::wstring out(static_cast<std::size_t>(std::max(size, 1)) - 1, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text, -1, out.data(), size);
    return out;
}

// The voxel floor (top of the highest solid cell) within kSearchCm of `z` in the column at (x, y).
float FloorNear(const VoxelGrid& grid, float x, float y, float z) {
    const int to = CellIndex(z - kSearchCm);
    const int top = grid.TopCellBetween(CellIndex(x), CellIndex(y), CellIndex(z + kSearchCm), to);
    return top < to ? NAN : static_cast<float>((top + 1) * kCellCm);
}

struct SurfaceScore {
    long columns = 0, missing = 0, covered = 0, within_half_cell = 0;
    double sum = 0.0, sum_sq = 0.0;
    float min = 0.0f, max = 0.0f;
    long buckets[6] = {};  // same edges as the walk check

    void Add(float error) {
        if (error > kCoveredCm) {
            ++covered;
            return;
        }
        if (columns++ == 0) min = max = error;
        min = std::min(min, error);
        max = std::max(max, error);
        sum += error;
        sum_sq += static_cast<double>(error) * error;
        if (std::fabs(error) <= kCellCm * 0.5f + 0.01f) ++within_half_cell;
        ++buckets[error < -10 ? 0 : error < -5 ? 1 : error < 0 ? 2 : error < 5 ? 3 : error < 10 ? 4 : 5];
    }
};

// Walkable surface heights per column (cell x, y), with what made them.
struct Surface {
    float z;
    bool convex;
    bool operator<(const Surface& other) const { return z < other.z; }
};
using Surfaces = std::unordered_map<std::int64_t, std::vector<Surface>>;

std::int64_t ColumnKey(int x, int y) { return (static_cast<std::int64_t>(x) << 32) ^ static_cast<std::uint32_t>(y); }

// Adds the triangle's height at every column centre over it, if it is a walkable up-facing face.
void AddSurface(const FVector (&t)[3], float walkable_z, bool convex, Surfaces* surfaces) {
    const double ax = t[1].x - t[0].x, ay = t[1].y - t[0].y, az = t[1].z - t[0].z;
    const double bx = t[2].x - t[0].x, by = t[2].y - t[0].y, bz = t[2].z - t[0].z;
    const double nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
    const double length = std::sqrt(nx * nx + ny * ny + nz * nz);
    if (length < 1e-6 || nz / length < walkable_z) return;
    const float lo_x = std::min({t[0].x, t[1].x, t[2].x}), hi_x = std::max({t[0].x, t[1].x, t[2].x});
    const float lo_y = std::min({t[0].y, t[1].y, t[2].y}), hi_y = std::max({t[0].y, t[1].y, t[2].y});
    for (int cx = CellIndex(lo_x); cx <= CellIndex(hi_x); ++cx) {
        const double x = (cx + 0.5) * kCellCm;
        for (int cy = CellIndex(lo_y); cy <= CellIndex(hi_y); ++cy) {
            const double y = (cy + 0.5) * kCellCm;
            // Barycentric coordinates of (x, y) in the triangle's projection.
            const double px = x - t[0].x, py = y - t[0].y;
            const double u = (px * by - py * bx) / nz, v = (ax * py - ay * px) / nz;
            if (u < 0.0 || v < 0.0 || u + v > 1.0) continue;
            (*surfaces)[ColumnKey(cx, cy)].push_back({static_cast<float>(t[0].z + u * az + v * bz), convex});
        }
    }
}

Surfaces CollectSurfaces(const ShapeSet& shapes) {
    Surfaces surfaces;
    for (const TriangleShape& t : shapes.triangles) AddSurface(t.v, t.walkable_z, false, &surfaces);
    for (const ConvexShape& c : shapes.convexes) {
        for (const HullFace& face : HullFaces(c.vertices, HullPlanes(c.vertices))) {
            for (std::size_t i = 1; i + 1 < face.polygon.size(); ++i) {
                const FVector fan[3] = {face.polygon[0], face.polygon[i], face.polygon[i + 1]};
                AddSurface(fan, c.walkable_z, true, &surfaces);
            }
        }
    }
    return surfaces;
}

// The voxel floor against the walkable surfaces: in each column, surfaces less than a cell apart
// (a plank on a floor, shapes overlapping) count as one floor at the highest of them.
void PrintSurface(const VoxelGrid& grid, const Surfaces& surfaces, int outliers) {
    SurfaceScore score;
    long off_by_source[2] = {};
    for (const auto& [key, unsorted] : surfaces) {
        const int x = static_cast<int>(key >> 32), y = static_cast<int>(static_cast<std::uint32_t>(key));
        std::vector<Surface> heights = unsorted;
        std::sort(heights.begin(), heights.end());
        for (std::size_t i = 0; i < heights.size(); ++i) {
            if (i + 1 < heights.size() && heights[i + 1].z - heights[i].z < kCellCm) continue;
            const float z = heights[i].z;
            const float cx = (x + 0.5f) * kCellCm, cy = (y + 0.5f) * kCellCm;
            const float floor = FloorNear(grid, cx, cy, z);
            if (std::isnan(floor)) {
                ++score.missing;
                continue;
            }
            const float error = floor - (z + kHoverCm);  // against where the feet are on it
            score.Add(error);
            if (std::fabs(error) <= kCellCm * 0.5f + 0.01f || error > kCoveredCm) continue;
            ++off_by_source[heights[i].convex];
            if (outliers-- > 0) {
                std::printf("    off by %+.1f cm at (%.0f, %.0f, %.1f) on a %s surface\n", error, cx, cy, z,
                            heights[i].convex ? "convex" : "triangle mesh");
            }
        }
    }
    const double mean = score.columns ? score.sum / score.columns : 0.0;
    const double sd = score.columns ? std::sqrt(std::max(0.0, score.sum_sq / score.columns - mean * mean)) : 0.0;
    std::printf("  surface: %ld walkable columns | voxel floor - (surface + 2.15 cm hover): mean %+.2f cm, sd %.2f, min %+.2f, max %+.2f "
                "| within half a cell: %.1f%% | <-10:%ld -10..-5:%ld -5..0:%ld 0..5:%ld 5..10:%ld >=10:%ld "
                "| covered by other shapes: %ld | no voxel floor: %ld\n",
                score.columns, mean, sd, score.min, score.max,
                score.columns ? 100.0 * score.within_half_cell / score.columns : 0.0, score.buckets[0],
                score.buckets[1], score.buckets[2], score.buckets[3], score.buckets[4], score.buckets[5], score.covered,
                score.missing);
    std::printf("  surface: more than half a cell off: %ld on triangle meshes, %ld on convex shapes\n",
                off_by_source[0], off_by_source[1]);
}

// Walk samples whose floor (under the capsule's centre) is more than half a cell from the feet:
// with the highest floor anywhere under the capsule, which tells a pawn
// standing on an edge (its capsule's rim on a step, its centre over the lower floor) from a floor
// at the wrong height.
void PrintWalkOutliers(const VoxelGrid& grid, const std::vector<WalkSample>& walk, int outliers) {
    if (outliers <= 0) return;
    for (const WalkSample& s : walk) {
        const float floor = FloorUnder(grid, s.feet.x, s.feet.y, s.feet.z);
        const float error = floor - s.feet.z;
        if (std::isnan(floor) || std::fabs(error) <= kCellCm * 0.5f) continue;
        float under_capsule = -1e9f;
        const float r = s.capsule_radius;
        for (int y = CellIndex(s.feet.y - r); y <= CellIndex(s.feet.y + r); ++y) {
            for (int x = CellIndex(s.feet.x - r); x <= CellIndex(s.feet.x + r); ++x) {
                const float cx = (x + 0.5f) * kCellCm - s.feet.x, cy = (y + 0.5f) * kCellCm - s.feet.y;
                if (cx * cx + cy * cy > r * r) continue;
                const float f = FloorUnder(grid, (x + 0.5f) * kCellCm, (y + 0.5f) * kCellCm, s.feet.z);
                if (!std::isnan(f)) under_capsule = std::max(under_capsule, f);
            }
        }
        std::printf("    floor %+.1f cm off at feet (%.0f, %.0f, %.1f); highest floor under the capsule %+.1f\n", error,
                    s.feet.x, s.feet.y, s.feet.z, under_capsule - s.feet.z);
        if (--outliers == 0) break;
    }
}

// The walkable surfaces and the solid cells in the column at (x, y).
void PrintColumn(const VoxelGrid& grid, const Surfaces& surfaces, float x, float y) {
    const int cx = CellIndex(x), cy = CellIndex(y);
    std::printf("column (%d, %d) at (%.0f, %.0f):\n  surfaces:", cx, cy, x, y);
    const auto it = surfaces.find(ColumnKey(cx, cy));
    if (it != surfaces.end()) {
        std::vector<Surface> sorted = it->second;
        std::sort(sorted.begin(), sorted.end());
        for (const Surface& s : sorted) std::printf(" %.2f%s", s.z, s.convex ? " (convex)" : "");
    }
    std::printf("\n  solid cells (bottom..top cm):");
    int run_start = 0;
    bool in_run = false;
    for (int z = -4000; z <= 4000; ++z) {
        const bool solid = z < 4000 && grid.Get(cx, cy, z);
        if (solid && !in_run) run_start = z;
        if (!solid && in_run) std::printf(" %.1f..%.1f", run_start * kCellCm, z * kCellCm);
        in_run = solid;
    }
    std::printf("\n");
}

// Holes: points on every triangle that is not a walkable floor (walls, ceilings, steep faces),
// one per 12.5 cm along each edge direction, should have a solid cell within one cell of them.
void PrintHoles(const VoxelGrid& grid, const ShapeSet& shapes, int outliers) {
    long points = 0, holes = 0;
    for (const TriangleShape& t : shapes.triangles) {
        const FVector e1{t.v[1].x - t.v[0].x, t.v[1].y - t.v[0].y, t.v[1].z - t.v[0].z};
        const FVector e2{t.v[2].x - t.v[0].x, t.v[2].y - t.v[0].y, t.v[2].z - t.v[0].z};
        const double nx = e1.y * e2.z - e1.z * e2.y, ny = e1.z * e2.x - e1.x * e2.z, nz = e1.x * e2.y - e1.y * e2.x;
        const double length = std::sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1e-6 || nz / length >= t.walkable_z) continue;
        const int steps1 = static_cast<int>(std::ceil(std::sqrt(e1.x * e1.x + e1.y * e1.y + e1.z * e1.z) / kCellCm));
        const int steps2 = static_cast<int>(std::ceil(std::sqrt(e2.x * e2.x + e2.y * e2.y + e2.z * e2.z) / kCellCm));
        for (int i = 0; i <= steps1; ++i) {
            for (int j = 0; i * steps2 + j * steps1 <= steps1 * steps2; ++j) {
                const float a = steps1 ? static_cast<float>(i) / steps1 : 0.0f, b = steps2 ? static_cast<float>(j) / steps2 : 0.0f;
                const float px = t.v[0].x + a * e1.x + b * e2.x, py = t.v[0].y + a * e1.y + b * e2.y,
                            pz = t.v[0].z + a * e1.z + b * e2.z;
                ++points;
                bool solid = false;
                for (int dz = -1; dz <= 1 && !solid; ++dz) {
                    for (int dy = -1; dy <= 1 && !solid; ++dy) {
                        for (int dx = -1; dx <= 1 && !solid; ++dx) {
                            solid = grid.Get(CellIndex(px) + dx, CellIndex(py) + dy, CellIndex(pz) + dz);
                        }
                    }
                }
                if (solid) continue;
                if (holes++ < outliers) std::printf("    hole at (%.0f, %.0f, %.0f)\n", px, py, pz);
            }
        }
    }
    std::printf("  walls: %ld points on non-floor triangles, %ld with no solid cell within a cell\n", points, holes);
}

VoxelGrid Run(const char* label, const ShapeSet& shapes, const std::string& map, const VoxelOptions& options,
              const std::vector<WalkSample>& walk, const Surfaces* surfaces, int outliers) {
    VoxelGrid grid;
    VoxelStats stats;
    Voxelize(shapes, &grid, &stats, options);
    std::printf("%s: %llu cells in %zu blocks | %d convex %d capsules %d triangles (%d slivers, %d degenerate) | "
                "%d steep faces made walls | %.0f + %.0f + %.0f ms\n",
                label, static_cast<unsigned long long>(stats.cells), stats.blocks, stats.convexes, stats.capsules,
                stats.triangles, stats.slivers, stats.degenerate, stats.steep_faces, stats.ms_convex, stats.ms_capsule,
                stats.ms_triangle);
    if (!walk.empty()) {
        WalkCheck check;
        for (const WalkSample& s : walk) check.Sample(grid, s);
        for (const std::string& line : check.Report(map)) std::printf("  walk %s\n", line.c_str());
        PrintWalkOutliers(grid, walk, outliers);
    }
    if (surfaces) {
        PrintSurface(grid, *surfaces, outliers);
        PrintHoles(grid, shapes, outliers);
    }
    return grid;
}

}  // namespace

int main(int argc, char** argv) {
    const char* shapes_path = nullptr;
    const char* grid_path = nullptr;
    std::vector<WalkSample> walk;
    VoxelOptions options;
    bool compare = false, surface = false;
    int outliers = 0;
    std::vector<std::pair<float, float>> probes;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--touch") == 0) {
            options.touch_triangles = true;
        } else if (std::strcmp(argv[i], "--no-steep") == 0) {
            options.steep_walls = false;
        } else if (std::strcmp(argv[i], "--compare") == 0) {
            compare = true;
        } else if (std::strcmp(argv[i], "--surface") == 0) {
            surface = true;
        } else if (std::strcmp(argv[i], "--outliers") == 0 && i + 1 < argc) {
            outliers = std::atoi(argv[++i]);
            surface = true;
        } else if (std::strcmp(argv[i], "--at") == 0 && i + 2 < argc) {
            const float x = static_cast<float>(std::atof(argv[++i]));
            probes.emplace_back(x, static_cast<float>(std::atof(argv[++i])));
            surface = true;
        } else if (std::strcmp(argv[i], "--grid") == 0 && i + 1 < argc) {
            grid_path = argv[++i];
        } else if (!shapes_path) {
            shapes_path = argv[i];
        } else if (!ReadWalkSamples(Widen(argv[i]), &walk)) {
            std::fprintf(stderr, "cannot read %s\n", argv[i]);
            return 1;
        }
    }
    if (!shapes_path) {
        std::fprintf(stderr, "usage: %s <map.shapes> [walk.csv]... [--touch] [--no-steep] [--compare] [--surface] "
                             "[--grid out.scvx] [--outliers N] [--at X Y]...\n", argv[0]);
        return 2;
    }
    ShapeSet shapes;
    std::string map;
    if (!ReadShapeFile(Widen(shapes_path), &shapes, &map)) {
        std::fprintf(stderr, "%s is not a readable shapes file\n", shapes_path);
        return 1;
    }
    std::printf("%s: %zu convex %zu capsules %zu triangles, pawn walkable floor Z %.4f, %zu walk samples\n",
                map.c_str(), shapes.convexes.size(), shapes.capsules.size(), shapes.triangles.size(),
                shapes.pawn_walkable_z, walk.size());
    const Surfaces surfaces = surface ? CollectSurfaces(shapes) : Surfaces{};
    const Surfaces* scored = surface ? &surfaces : nullptr;
    if (compare) Run("first rule (touch, no steep walls)", shapes, map, {true, false}, walk, scored, 0);
    const VoxelGrid grid = Run(options.touch_triangles || !options.steep_walls ? "options" : "current rule", shapes,
                               map, options, walk, scored, outliers);
    for (const auto& [x, y] : probes) PrintColumn(grid, surfaces, x, y);
    if (grid_path) {
        FVector feet = walk.empty() ? FVector{} : walk.front().feet;
        if (!WriteVoxelFile(Widen(grid_path), grid, map, feet)) {
            std::fprintf(stderr, "cannot write %s\n", grid_path);
            return 1;
        }
        std::printf("wrote %s\n", grid_path);
    }
    return 0;
}
