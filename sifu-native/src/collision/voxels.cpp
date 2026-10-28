#include "collision/voxels.h"

#include <windows.h>

#include <algorithm>
#include <bit>
#include <cmath>
#include <limits>

namespace sifucraft::collision {

namespace {

constexpr double kCell = kCellCm;
constexpr double kPlaneEpsilon = 0.25;           // cm: cooked hull faces are not perfectly planar
constexpr double kOnFaceCm = 0.5;                // hull points this close to a face plane are on it
constexpr double kMaxShapeExtentCm = 100000.0;   // 1 km: anything larger is not level geometry
constexpr double kColumnSlackCm = 1e-3;          // a centre line on a triangle's edge crosses it
constexpr double kMinSteepZ = 0.01;              // closer to vertical than this: a wall already
constexpr double kTerraceCm = 150.0;             // steep faces: wall height (Minecraft jumps 125 cm)
constexpr double kFloorHoverCm = 2.15;           // a walking UE character's capsule floats this far up
constexpr int kKeyBits = 21;
constexpr std::int64_t kKeyMask = (std::int64_t{1} << kKeyBits) - 1;
constexpr std::int64_t kKeyBias = std::int64_t{1} << (kKeyBits - 1);

double Dot(const double (&n)[3], const FVector& p) { return n[0] * p.x + n[1] * p.y + n[2] * p.z; }

FVector Sub(const FVector& a, const FVector& b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }

FVector Cross(const FVector& a, const FVector& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}

float DotF(const FVector& a, const FVector& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }

// Cells whose centre lies in [lo, hi].
int FirstCentre(double lo) { return static_cast<int>(std::ceil(lo / kCell - 0.5)); }
int LastCentre(double hi) { return static_cast<int>(std::floor(hi / kCell - 0.5)); }
// Cells that touch [lo, hi].
int CellOf(double v) { return static_cast<int>(std::floor(v / kCell)); }

double Millis(const LARGE_INTEGER& start) {
    LARGE_INTEGER now, frequency;
    QueryPerformanceCounter(&now);
    QueryPerformanceFrequency(&frequency);
    return 1000.0 * static_cast<double>(now.QuadPart - start.QuadPart) / static_cast<double>(frequency.QuadPart);
}

bool AddPlane(std::vector<Plane>* planes, double nx, double ny, double nz, const FVector& through,
              const std::vector<FVector>& points) {
    const double length = std::sqrt(nx * nx + ny * ny + nz * nz);
    if (length < 1e-9) return false;
    Plane p{{nx / length, ny / length, nz / length}, 0.0};
    p.d = -Dot(p.n, through);
    double lo = std::numeric_limits<double>::max(), hi = -lo;
    for (const FVector& v : points) {
        const double s = Dot(p.n, v) + p.d;
        lo = std::min(lo, s);
        hi = std::max(hi, s);
    }
    if (hi > kPlaneEpsilon && lo < -kPlaneEpsilon) return false;  // points on both sides: not a face
    if (hi > kPlaneEpsilon) {                                        // all on the positive side: flip
        for (double& c : p.n) c = -c;
        p.d = -p.d;
    }
    for (const Plane& q : *planes) {
        if (std::fabs(q.n[0] - p.n[0]) < 1e-6 && std::fabs(q.n[1] - p.n[1]) < 1e-6 &&
            std::fabs(q.n[2] - p.n[2]) < 1e-6 && std::fabs(q.d - p.d) < kPlaneEpsilon) {
            return true;
        }
    }
    planes->push_back(p);
    return true;
}

// How much to push each face outwards so that the shape is at least one cell deep behind it:
// a thin shape (plate, wall, zero-thickness polygon) then always contains a layer of cell
// centres, without growing in the directions it is already thick.
std::vector<double> FaceGrowth(const std::vector<Plane>& planes, const std::vector<FVector>& points) {
    std::vector<double> growth;
    growth.reserve(planes.size());
    for (const Plane& p : planes) {
        double depth = 0.0;
        for (const FVector& v : points) depth = std::max(depth, -(Dot(p.n, v) + p.d));
        growth.push_back(std::max(0.0, (kCell - depth) * 0.5));
    }
    return growth;
}

bool Oversized(float lo, float hi) { return !(hi - lo < kMaxShapeExtentCm) || !std::isfinite(lo) || !std::isfinite(hi); }

// Calls fn(cu, cv, along) for every column of cells along axis k (cu, cv: its cell indices on the
// other two axes, in order k + 1, k + 2) whose centre line crosses the triangle, with the
// triangle's coordinate along k there. Returns the number of columns.
template <typename Fn>
int ForEachColumn(const FVector (&tri)[3], int k, Fn&& fn) {
    const int u = (k + 1) % 3, v = (k + 2) % 3;
    double p[3][3];
    for (int i = 0; i < 3; ++i) {
        p[i][0] = tri[i].x;
        p[i][1] = tri[i].y;
        p[i][2] = tri[i].z;
    }
    const double e1[3] = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]};
    const double e2[3] = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
    const double n[3] = {e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]};
    if (std::fabs(n[k]) < 1e-9) return 0;
    const double d = -(n[0] * p[0][0] + n[1] * p[0][1] + n[2] * p[0][2]);
    const double lo_u = std::min({p[0][u], p[1][u], p[2][u]}), hi_u = std::max({p[0][u], p[1][u], p[2][u]});
    const double lo_k = std::min({p[0][k], p[1][k], p[2][k]}), hi_k = std::max({p[0][k], p[1][k], p[2][k]});
    int columns = 0;
    for (int cu = FirstCentre(lo_u - kColumnSlackCm); cu <= LastCentre(hi_u + kColumnSlackCm); ++cu) {
        const double at_u = std::clamp((cu + 0.5) * kCell, lo_u, hi_u);
        // Where the line u = at_u crosses the triangle: [v_lo, v_hi].
        double v_lo = std::numeric_limits<double>::max(), v_hi = -v_lo;
        for (int e = 0; e < 3; ++e) {
            const double* a = p[e];
            const double* b = p[(e + 1) % 3];
            const double au = a[u] - at_u, bu = b[u] - at_u;
            if (au * bu > 0.0) continue;
            if (au == bu) {  // the edge lies on the line
                v_lo = std::min({v_lo, a[v], b[v]});
                v_hi = std::max({v_hi, a[v], b[v]});
                continue;
            }
            const double at_v = a[v] + au / (au - bu) * (b[v] - a[v]);
            v_lo = std::min(v_lo, at_v);
            v_hi = std::max(v_hi, at_v);
        }
        if (v_lo > v_hi) continue;
        for (int cv = FirstCentre(v_lo - kColumnSlackCm); cv <= LastCentre(v_hi + kColumnSlackCm); ++cv) {
            const double at_v = std::clamp((cv + 0.5) * kCell, v_lo, v_hi);
            fn(cu, cv, std::clamp(-(d + n[u] * at_u + n[v] * at_v) / n[k], lo_k, hi_k));
            ++columns;
        }
    }
    return columns;
}

// An up-facing face (unit normal `n`) too steep to walk on.
bool Steep(const double (&n)[3], float walkable_z) { return n[2] > kMinSteepZ && n[2] < walkable_z; }

// Fills each column under the triangle from the surface up to the next terrace level (at most the
// face's `top`), so that a steep face is a flight of walls taller than Minecraft jumps rather than
// a staircase of cells it walks up; going down it still works, as sliding down does in Sifu.
void RaiseSteep(const FVector (&tri)[3], double top, VoxelGrid* grid) {
    ForEachColumn(tri, 2, [&](int x, int y, double z) {
        const double level = std::min(top, std::ceil(z / kTerraceCm) * kTerraceCm);
        for (int cz = LastCentre(z); cz <= LastCentre(level); ++cz) grid->Set(x, y, cz);
    });
}

void RaiseSteepFaces(const ConvexShape& shape, const std::vector<Plane>& planes, VoxelGrid* grid, VoxelStats* stats) {
    for (const HullFace& face : HullFaces(shape.vertices, planes)) {
        if (!Steep(face.plane.n, shape.walkable_z)) continue;
        double top = face.polygon[0].z;
        for (const FVector& v : face.polygon) top = std::max<double>(top, v.z);
        for (std::size_t i = 1; i + 1 < face.polygon.size(); ++i) {
            const FVector fan[3] = {face.polygon[0], face.polygon[i], face.polygon[i + 1]};
            RaiseSteep(fan, top, grid);
        }
        ++stats->steep_faces;
    }
}

void VoxelizeConvex(const ConvexShape& shape, VoxelGrid* grid, VoxelStats* stats, const VoxelOptions& options) {
    const std::vector<Plane> planes = HullPlanes(shape.vertices);
    if (planes.size() < 4) {
        ++stats->degenerate;
        return;
    }
    std::vector<double> growth = FaceGrowth(planes, shape.vertices);
    // Walkable tops are raised to where the pawn's feet are on them, like triangle floors.
    for (std::size_t i = 0; i < planes.size(); ++i) {
        if (planes[i].n[2] >= shape.walkable_z) growth[i] += kFloorHoverCm * planes[i].n[2];
    }
    const double grow = *std::max_element(growth.begin(), growth.end());
    float lo[3] = {shape.vertices[0].x, shape.vertices[0].y, shape.vertices[0].z}, hi[3] = {lo[0], lo[1], lo[2]};
    for (const FVector& v : shape.vertices) {
        const float p[3] = {v.x, v.y, v.z};
        for (int k = 0; k < 3; ++k) {
            lo[k] = std::min(lo[k], p[k]);
            hi[k] = std::max(hi[k], p[k]);
        }
    }
    for (int k = 0; k < 3; ++k) {
        if (Oversized(lo[k], hi[k])) {
            ++stats->oversized;
            return;
        }
    }
    ++stats->convexes;
    for (int z = FirstCentre(lo[2] - grow); z <= LastCentre(hi[2] + grow); ++z) {
        const double cz = (z + 0.5) * kCell;
        for (int y = FirstCentre(lo[1] - grow); y <= LastCentre(hi[1] + grow); ++y) {
            const double cy = (y + 0.5) * kCell;
            // Where the row's line is inside every plane.
            double x_lo = lo[0] - grow, x_hi = hi[0] + grow;
            for (std::size_t i = 0; i < planes.size(); ++i) {
                const Plane& p = planes[i];
                const double rest = -(p.n[1] * cy + p.n[2] * cz + p.d) + growth[i];
                if (std::fabs(p.n[0]) < 1e-12) {
                    if (rest < 0.0) x_hi = x_lo - 1.0;
                } else if (p.n[0] > 0.0) {
                    x_hi = std::min(x_hi, rest / p.n[0]);
                } else {
                    x_lo = std::max(x_lo, rest / p.n[0]);
                }
                if (x_lo > x_hi) break;
            }
            if (x_lo > x_hi) continue;
            const int x0 = FirstCentre(x_lo), x1 = LastCentre(x_hi);
            if (x0 <= x1) grid->SetRow(x0, x1, y, z);
        }
    }
    if (options.steep_walls) RaiseSteepFaces(shape, planes, grid, stats);
}

void VoxelizeCapsule(const CapsuleShape& shape, VoxelGrid* grid, VoxelStats* stats) {
    const double radius = std::max<double>(shape.radius, kCell * 0.5);
    const FVector axis = Sub(shape.b, shape.a);
    const double axis_length2 = DotF(axis, axis);
    const float lo[3] = {std::min(shape.a.x, shape.b.x), std::min(shape.a.y, shape.b.y), std::min(shape.a.z, shape.b.z)};
    const float hi[3] = {std::max(shape.a.x, shape.b.x), std::max(shape.a.y, shape.b.y), std::max(shape.a.z, shape.b.z)};
    for (int k = 0; k < 3; ++k) {
        if (Oversized(lo[k] - static_cast<float>(radius), hi[k] + static_cast<float>(radius))) {
            ++stats->oversized;
            return;
        }
    }
    ++stats->capsules;
    for (int z = FirstCentre(lo[2] - radius); z <= LastCentre(hi[2] + radius); ++z) {
        for (int y = FirstCentre(lo[1] - radius); y <= LastCentre(hi[1] + radius); ++y) {
            for (int x = FirstCentre(lo[0] - radius); x <= LastCentre(hi[0] + radius); ++x) {
                const FVector c{static_cast<float>((x + 0.5) * kCell), static_cast<float>((y + 0.5) * kCell),
                                static_cast<float>((z + 0.5) * kCell)};
                const FVector to_c = Sub(c, shape.a);
                double t = axis_length2 > 0.0 ? DotF(to_c, axis) / axis_length2 : 0.0;
                t = std::clamp(t, 0.0, 1.0);
                const double dx = to_c.x - t * axis.x, dy = to_c.y - t * axis.y, dz = to_c.z - t * axis.z;
                if (dx * dx + dy * dy + dz * dz <= radius * radius) grid->Set(x, y, z);
            }
        }
    }
}

// Calls mark(cell) for every cell the triangle touches (normal n, dominant axis k, bounds lo..hi).
template <typename Fn>
void TouchTriangle(const TriangleShape& tri, const FVector& n, int k, const float (&lo)[3], const float (&hi)[3],
                   Fn&& mark) {
    // Walk the cells of the two axes the triangle spreads over most; along the third, only the
    // few cells the triangle's plane passes through in that column.
    const int u = (k + 1) % 3, v = (k + 2) % 3;
    const double nn[3] = {n.x, n.y, n.z};
    const double d = -(nn[0] * tri.v[0].x + nn[1] * tri.v[0].y + nn[2] * tri.v[0].z);
    // Level geometry is often snapped to whole metres, i.e. exactly onto cell boundaries. Such a
    // surface marks only the cells behind it (against its normal, the solid side), so a floor at
    // z = 300 yields solid cells whose top is exactly 300: each tested box is shrunk a little and
    // nudged along the normal by twice that, so the cell behind clearly reaches the surface and
    // the one in front clearly does not.
    constexpr float kShrink = 0.01f;
    const float length = std::sqrt(n.x * n.x + n.y * n.y + n.z * n.z) / (2.0f * kShrink);
    const FVector nudge{n.x / length, n.y / length, n.z / length};
    const float half = static_cast<float>(kCell * 0.5) - kShrink;
    for (int cu = CellOf(lo[u]); cu <= CellOf(hi[u]); ++cu) {
        for (int cv = CellOf(lo[v]); cv <= CellOf(hi[v]); ++cv) {
            double k_lo = std::numeric_limits<double>::max(), k_hi = -k_lo;
            for (int corner = 0; corner < 4; ++corner) {
                const double pu = (cu + (corner & 1)) * kCell, pv = (cv + (corner >> 1)) * kCell;
                const double pk = -(d + nn[u] * pu + nn[v] * pv) / nn[k];
                k_lo = std::min(k_lo, pk);
                k_hi = std::max(k_hi, pk);
            }
            k_lo = std::max<double>(k_lo, lo[k]);
            k_hi = std::min<double>(k_hi, hi[k]);
            if (k_lo > k_hi) continue;
            // Widened a little: a surface exactly on a boundary must test the cells on both sides.
            for (int ck = CellOf(k_lo - 0.05); ck <= CellOf(k_hi + 0.05); ++ck) {
                int cell[3];
                cell[u] = cu;
                cell[v] = cv;
                cell[k] = ck;
                const FVector centre{static_cast<float>((cell[0] + 0.5) * kCell) + nudge.x,
                                     static_cast<float>((cell[1] + 0.5) * kCell) + nudge.y,
                                     static_cast<float>((cell[2] + 0.5) * kCell) + nudge.z};
                if (TriangleOverlapsBox(centre, half, tri.v[0], tri.v[1], tri.v[2])) mark(cell);
            }
        }
    }
}

// A triangle's normal (not normalized), the axis it is closest to and its bounds; false for a
// degenerate or oversized one (counted).
bool Measure(const TriangleShape& tri, FVector* n, int* k, float (&lo)[3], float (&hi)[3], VoxelStats* stats) {
    *n = Cross(Sub(tri.v[1], tri.v[0]), Sub(tri.v[2], tri.v[0]));
    const float an[3] = {std::fabs(n->x), std::fabs(n->y), std::fabs(n->z)};
    if (an[0] + an[1] + an[2] < 1e-6f) {
        if (stats) ++stats->degenerate;
        return false;
    }
    for (int axis = 0; axis < 3; ++axis) {
        const float c[3] = {(&tri.v[0].x)[axis], (&tri.v[1].x)[axis], (&tri.v[2].x)[axis]};
        lo[axis] = std::min({c[0], c[1], c[2]});
        hi[axis] = std::max({c[0], c[1], c[2]});
        if (Oversized(lo[axis], hi[axis])) {
            if (stats) ++stats->oversized;
            return false;
        }
    }
    *k = an[0] >= an[1] && an[0] >= an[2] ? 0 : (an[1] >= an[2] ? 1 : 2);
    return true;
}

// A sliver marks the cells it touches, except where the solid already lies right behind such a
// cell: there it is part of a surface the other shapes made (marking the cell would put a bump
// in it), elsewhere it is thin geometry of its own, such as a slim post, that must not vanish.
void TouchSliver(const TriangleShape& tri, VoxelGrid* grid) {
    FVector n;
    int k;
    float lo[3], hi[3];
    if (!Measure(tri, &n, &k, lo, hi, nullptr)) return;
    const int behind = (&n.x)[k] > 0.0f ? -1 : 1;
    TouchTriangle(tri, n, k, lo, hi, [&](int (&cell)[3]) {
        int back[3] = {cell[0], cell[1], cell[2]};
        back[k] += behind;
        if (!grid->Get(back[0], back[1], back[2])) grid->Set(cell[0], cell[1], cell[2]);
    });
}

// Called twice for every triangle: the first pass takes the faces whose solid lies below them
// along their axis (floors, and walls facing +x / +y), the second the faces whose solid lies above
// (ceilings, undersides, walls facing -x / -y). A solid thinner than a cell (a slab, a thin wall,
// a plane with both sides) has its two sides' cells next to each other; the second side then
// leaves its cell out where the first side's cell is right in front of it, so the solid is one
// cell thick and its top ends at the cell boundary nearest to its top surface.
void VoxelizeTriangle(const TriangleShape& tri, VoxelGrid* grid, VoxelStats* stats, const VoxelOptions& options,
                      bool second_pass, std::vector<const TriangleShape*>* slivers) {
    FVector n;
    int k;
    float lo[3], hi[3];
    if (!Measure(tri, &n, &k, lo, hi, second_pass ? nullptr : stats)) return;
    // The cell behind the surface: below it along k if the normal points to +k.
    const bool behind_is_below = (&n.x)[k] > 0.0f;
    const double length = std::sqrt(DotF(n, n));
    const double unit[3] = {n.x / length, n.y / length, n.z / length};
    if (options.touch_triangles) {
        if (second_pass) return;
        ++stats->triangles;
        TouchTriangle(tri, n, k, lo, hi, [&](int (&cell)[3]) { grid->Set(cell[0], cell[1], cell[2]); });
    } else {
        if (behind_is_below == second_pass) return;
        ++stats->triangles;
        // A floor ends at the cell boundary nearest to where the pawn's feet are on it.
        const double lift = k == 2 && unit[2] >= tri.walkable_z ? kFloorHoverCm : 0.0;
        const int u = (k + 1) % 3, v = (k + 2) % 3;
        const int columns = ForEachColumn(tri.v, k, [&](int cu, int cv, double along) {
            int cell[3];
            cell[u] = cu;
            cell[v] = cv;
            cell[k] = behind_is_below ? LastCentre(along + lift) : FirstCentre(along);
            if (second_pass) {
                int front[3] = {cell[0], cell[1], cell[2]};
                --front[k];
                if (grid->Get(front[0], front[1], front[2])) return;
            }
            grid->Set(cell[0], cell[1], cell[2]);
        });
        if (columns == 0) {
            ++stats->slivers;
            slivers->push_back(&tri);
        }
    }
    if (options.steep_walls && Steep(unit, tri.walkable_z)) {
        RaiseSteep(tri.v, hi[2], grid);
        ++stats->steep_faces;
    }
}

}  // namespace

int FloorDiv(int value, int divisor) {
    const int q = value / divisor;
    return (value % divisor != 0 && (value < 0) != (divisor < 0)) ? q - 1 : q;
}

int CellIndex(float cm) { return static_cast<int>(std::floor(cm / kCellCm)); }

std::int64_t VoxelGrid::Key(int bx, int by, int bz) {
    return ((bx + kKeyBias) & kKeyMask) | (((by + kKeyBias) & kKeyMask) << kKeyBits) |
           (((bz + kKeyBias) & kKeyMask) << (2 * kKeyBits));
}

void VoxelGrid::Unkey(std::int64_t key, int* bx, int* by, int* bz) {
    *bx = static_cast<int>((key & kKeyMask) - kKeyBias);
    *by = static_cast<int>(((key >> kKeyBits) & kKeyMask) - kKeyBias);
    *bz = static_cast<int>(((key >> (2 * kKeyBits)) & kKeyMask) - kKeyBias);
}

void VoxelGrid::Set(int x, int y, int z) {
    if(clip_&&!clip_->contains(x,y,z))return;
    Block& block = blocks_[Key(FloorDiv(x, kCellsPerBlock), FloorDiv(y, kCellsPerBlock), FloorDiv(z, kCellsPerBlock))];
    const int lx = x - FloorDiv(x, kCellsPerBlock) * kCellsPerBlock;
    const int ly = y - FloorDiv(y, kCellsPerBlock) * kCellsPerBlock;
    const int lz = z - FloorDiv(z, kCellsPerBlock) * kCellsPerBlock;
    block.layers[lz] |= std::uint64_t{1} << (lx + kCellsPerBlock * ly);
}

void VoxelGrid::SetRow(int x0, int x1, int y, int z) {
    if(clip_){if(y<clip_->min[1]||y>=clip_->max[1]||z<clip_->min[2]||z>=clip_->max[2])return;x0=std::max(x0,clip_->min[0]);x1=std::min(x1,clip_->max[0]-1);}
    const int by = FloorDiv(y, kCellsPerBlock), bz = FloorDiv(z, kCellsPerBlock);
    const int ly = y - by * kCellsPerBlock, lz = z - bz * kCellsPerBlock;
    for (int x = x0; x <= x1;) {
        const int bx = FloorDiv(x, kCellsPerBlock);
        const int first = x - bx * kCellsPerBlock;
        const int last = std::min(kCellsPerBlock - 1, x1 - bx * kCellsPerBlock);
        const std::uint64_t run = ((std::uint64_t{1} << (last - first + 1)) - 1) << first;
        blocks_[Key(bx, by, bz)].layers[lz] |= run << (kCellsPerBlock * ly);
        x = (bx + 1) * kCellsPerBlock;
    }
}

void VoxelGrid::ReplaceRegion(const VoxelGrid& source,const CellRegion& region){
    for(auto at=blocks_.begin();at!=blocks_.end();){
        int x,y,z;Unkey(at->first,&x,&y,&z);x*=8;y*=8;z*=8;
        if(x+8<=region.min[0]||x>=region.max[0]||y+8<=region.min[1]||y>=region.max[1]||z+8<=region.min[2]||z>=region.max[2]){++at;continue;}
        if(x>=region.min[0]&&x+8<=region.max[0]&&y>=region.min[1]&&y+8<=region.max[1]&&z>=region.min[2]&&z+8<=region.max[2])at=blocks_.erase(at);
        else {for(int dz=0;dz<8;dz++)for(int dy=0;dy<8;dy++)for(int dx=0;dx<8;dx++)if(region.contains(x+dx,y+dy,z+dz))at->second.layers[dz]&=~(std::uint64_t{1}<<(dx+8*dy));if(std::all_of(std::begin(at->second.layers),std::end(at->second.layers),[](auto bits){return bits==0;}))at=blocks_.erase(at);else ++at;}
    }
    for(const auto& [key,block]:source.blocks_){int x,y,z;Unkey(key,&x,&y,&z);x*=8;y*=8;z*=8;
        for(int dz=0;dz<8;dz++)for(int dy=0;dy<8;dy++)for(int dx=0;dx<8;dx++)if(region.contains(x+dx,y+dy,z+dz)&&((block.layers[dz]>>(dx+8*dy))&1))Set(x+dx,y+dy,z+dz);
    }
}

bool VoxelGrid::Get(int x, int y, int z) const {
    const int bx = FloorDiv(x, kCellsPerBlock), by = FloorDiv(y, kCellsPerBlock), bz = FloorDiv(z, kCellsPerBlock);
    const auto it = blocks_.find(Key(bx, by, bz));
    if (it == blocks_.end()) return false;
    const int lx = x - bx * kCellsPerBlock, ly = y - by * kCellsPerBlock, lz = z - bz * kCellsPerBlock;
    return (it->second.layers[lz] >> (lx + kCellsPerBlock * ly)) & 1;
}

int VoxelGrid::TopCellBetween(int x, int y, int z_from, int z_to) const {
    for (int z = z_from; z >= z_to; --z) {
        if (Get(x, y, z)) return z;
    }
    return z_to - 1;
}

std::uint64_t VoxelGrid::CellCount() const {
    std::uint64_t cells = 0;
    for (const auto& [key, block] : blocks_) {
        for (std::uint64_t layer : block.layers) cells += static_cast<std::uint64_t>(std::popcount(layer));
    }
    return cells;
}

bool HasFloorSupport(const VoxelGrid& grid, const FVector& feet) {
    if (!std::isfinite(feet.x) || !std::isfinite(feet.y) || !std::isfinite(feet.z)) return false;
    const float offsets[][2] = {{0, 0}, {25, 0}, {-25, 0}, {0, 25}, {0, -25}};
    const int from = CellIndex(feet.z + kCellCm), to = CellIndex(feet.z - 25);
    for (const auto& offset : offsets) {
        const int top = grid.TopCellBetween(CellIndex(feet.x + offset[0]), CellIndex(feet.y + offset[1]), from, to);
        if (top >= to && std::fabs((top + 1) * kCellCm - feet.z) <= 25) return true;
    }
    return false;
}

float FloorUnder(const VoxelGrid& grid, float x, float y, float feet_z) {
    constexpr float kAboveCm = 100.0f, kBelowCm = 300.0f;
    const int to = CellIndex(feet_z - kBelowCm);
    const int top = grid.TopCellBetween(CellIndex(x), CellIndex(y), CellIndex(feet_z + kAboveCm), to);
    return top < to ? NAN : static_cast<float>((top + 1) * kCellCm);
}

std::vector<Plane> HullPlanes(const std::vector<FVector>& points) {
    std::vector<Plane> planes;
    const std::size_t n = points.size();
    for (std::size_t i = 0; i < n; ++i) {
        for (std::size_t j = i + 1; j < n; ++j) {
            for (std::size_t k = j + 1; k < n; ++k) {
                const FVector c = Cross(Sub(points[j], points[i]), Sub(points[k], points[i]));
                AddPlane(&planes, c.x, c.y, c.z, points[i], points);
            }
        }
    }
    // All points on one plane: keep that plane in both orientations, and bound the polygon
    // sideways with a plane through each edge, perpendicular to the polygon.
    const bool flat = !planes.empty() && std::all_of(points.begin(), points.end(), [&](const FVector& v) {
        return std::fabs(Dot(planes[0].n, v) + planes[0].d) <= kPlaneEpsilon;
    });
    if (flat) {
        const Plane front = planes[0];
        const Plane back{{-front.n[0], -front.n[1], -front.n[2]}, -front.d};
        planes = {front, back};
        const FVector normal{static_cast<float>(front.n[0]), static_cast<float>(front.n[1]),
                             static_cast<float>(front.n[2])};
        for (std::size_t i = 0; i < n; ++i) {
            for (std::size_t j = i + 1; j < n; ++j) {
                const FVector side = Cross(normal, Sub(points[j], points[i]));
                AddPlane(&planes, side.x, side.y, side.z, points[i], points);
            }
        }
    }
    return planes;
}

std::vector<HullFace> HullFaces(const std::vector<FVector>& points, const std::vector<Plane>& planes) {
    std::vector<HullFace> faces;
    for (const Plane& p : planes) {
        std::vector<FVector> face;
        for (const FVector& v : points) {
            if (std::fabs(Dot(p.n, v) + p.d) > kOnFaceCm) continue;
            const bool duplicate = std::any_of(face.begin(), face.end(), [&](const FVector& f) {
                return std::fabs(f.x - v.x) + std::fabs(f.y - v.y) + std::fabs(f.z - v.z) < 0.01f;
            });
            if (!duplicate) face.push_back(v);
        }
        if (face.size() < 3) continue;
        FVector centre{};
        for (const FVector& v : face) {
            centre.x += v.x / face.size();
            centre.y += v.y / face.size();
            centre.z += v.z / face.size();
        }
        // An in-plane basis (u, w = n x u) to sort the points by angle, counter-clockwise about n.
        const double ref[3] = {face[0].x - centre.x, face[0].y - centre.y, face[0].z - centre.z};
        const double un = std::sqrt(ref[0] * ref[0] + ref[1] * ref[1] + ref[2] * ref[2]);
        if (un < 1e-6) continue;
        const double u[3] = {ref[0] / un, ref[1] / un, ref[2] / un};
        const double w[3] = {p.n[1] * u[2] - p.n[2] * u[1], p.n[2] * u[0] - p.n[0] * u[2], p.n[0] * u[1] - p.n[1] * u[0]};
        const auto angle = [&](const FVector& v) {
            const double d[3] = {v.x - centre.x, v.y - centre.y, v.z - centre.z};
            return std::atan2(d[0] * w[0] + d[1] * w[1] + d[2] * w[2], d[0] * u[0] + d[1] * u[1] + d[2] * u[2]);
        };
        std::sort(face.begin(), face.end(), [&](const FVector& a, const FVector& b) { return angle(a) < angle(b); });
        faces.push_back({p, std::move(face)});
    }
    return faces;
}

bool TriangleOverlapsBox(const FVector& center, float half, const FVector& a, const FVector& b, const FVector& c) {
    const FVector v[3] = {Sub(a, center), Sub(b, center), Sub(c, center)};
    const FVector e[3] = {Sub(v[1], v[0]), Sub(v[2], v[1]), Sub(v[0], v[2])};
    // The box's face normals.
    for (int k = 0; k < 3; ++k) {
        const float p[3] = {(&v[0].x)[k], (&v[1].x)[k], (&v[2].x)[k]};
        if (std::min({p[0], p[1], p[2]}) > half || std::max({p[0], p[1], p[2]}) < -half) return false;
    }
    // The triangle's normal.
    const FVector normal = Cross(e[0], e[1]);
    const float radius_n = half * (std::fabs(normal.x) + std::fabs(normal.y) + std::fabs(normal.z));
    if (std::fabs(DotF(normal, v[0])) > radius_n) return false;
    // Edge x box-axis cross products.
    const FVector axes[3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    for (const FVector& edge : e) {
        for (const FVector& box_axis : axes) {
            const FVector axis = Cross(edge, box_axis);
            const float radius = half * (std::fabs(axis.x) + std::fabs(axis.y) + std::fabs(axis.z));
            const float p0 = DotF(axis, v[0]), p1 = DotF(axis, v[1]), p2 = DotF(axis, v[2]);
            if (std::min({p0, p1, p2}) > radius || std::max({p0, p1, p2}) < -radius) return false;
        }
    }
    return true;
}

void Voxelize(const ShapeSet& shapes, VoxelGrid* grid, VoxelStats* stats, const VoxelOptions& options) {
    LARGE_INTEGER start;
    QueryPerformanceCounter(&start);
    for (const ConvexShape& shape : shapes.convexes) VoxelizeConvex(shape, grid, stats, options);
    stats->ms_convex = Millis(start);
    QueryPerformanceCounter(&start);
    for (const CapsuleShape& shape : shapes.capsules) VoxelizeCapsule(shape, grid, stats);
    stats->ms_capsule = Millis(start);
    QueryPerformanceCounter(&start);
    std::vector<const TriangleShape*> slivers;
    for (const bool second_pass : {false, true}) {
        for (const TriangleShape& shape : shapes.triangles) {
            VoxelizeTriangle(shape, grid, stats, options, second_pass, &slivers);
        }
    }
    // Last, so that they see every surface the other shapes made.
    for (const TriangleShape* sliver : slivers) TouchSliver(*sliver, grid);
    stats->ms_triangle = Millis(start);
    stats->cells = grid->CellCount();
    stats->blocks = grid->blocks().size();
}

}  // namespace sifucraft::collision
