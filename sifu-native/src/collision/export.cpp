#include "collision/export.h"

#include <cmath>
#include <cstdio>
#include <memory>
#include <vector>

namespace sifucraft::collision {

namespace {

struct FileCloser {
    void operator()(std::FILE* f) const { std::fclose(f); }
};
using File = std::unique_ptr<std::FILE, FileCloser>;

File Open(const std::wstring& path) { return File(_wfopen(path.c_str(), L"wb")); }

template <typename T>
void Put(std::FILE* f, const T& value) {
    std::fwrite(&value, sizeof(T), 1, f);
}

class ObjWriter {
public:
    explicit ObjWriter(std::FILE* f) : f_(f) {}

    void Group(const char* name) { std::fprintf(f_, "g %s\n", name); }

    // A polygon given in Unreal space; written in reverse because the axis swap mirrors it.
    void Face(const std::vector<FVector>& polygon) {
        for (const FVector& v : polygon) std::fprintf(f_, "v %.4f %.4f %.4f\n", v.x / 100.0, v.z / 100.0, v.y / 100.0);
        std::fprintf(f_, "f");
        for (std::size_t i = polygon.size(); i > 0; --i) std::fprintf(f_, " %d", next_ + static_cast<int>(i) - 1);
        std::fprintf(f_, "\n");
        next_ += static_cast<int>(polygon.size());
    }

private:
    std::FILE* f_;
    int next_ = 1;
};

// The hull's faces as polygons.
void WriteHull(ObjWriter* obj, const std::vector<FVector>& points) {
    for (const HullFace& face : HullFaces(points, HullPlanes(points))) obj->Face(face.polygon);
}

// A capsule as the hull of its two end spheres' extreme points (an octagonal-ish prism).
std::vector<FVector> CapsulePoints(const CapsuleShape& c) {
    std::vector<FVector> points;
    const float r = c.radius;
    for (const FVector& end : {c.a, c.b}) {
        for (int i = 0; i < 8; ++i) {
            const float angle = static_cast<float>(i) * 0.785398f;
            points.push_back({end.x + r * std::cos(angle), end.y + r * std::sin(angle), end.z});
        }
        points.push_back({end.x, end.y, end.z + r});
        points.push_back({end.x, end.y, end.z - r});
    }
    return points;
}

}  // namespace

bool WriteVoxelFile(const std::wstring& path, const VoxelGrid& grid, const std::string& map, const FVector& pawn_feet) {
    const File f = Open(path);
    if (!f) return false;
    std::fwrite("SCVX", 1, 4, f.get());
    Put(f.get(), kVoxelFileVersion);
    Put(f.get(), kCellCm);
    Put(f.get(), static_cast<std::uint32_t>(kCellsPerBlock));
    Put(f.get(), pawn_feet);
    Put(f.get(), static_cast<std::uint32_t>(map.size()));
    std::fwrite(map.data(), 1, map.size(), f.get());
    Put(f.get(), static_cast<std::uint32_t>(grid.blocks().size()));
    for (const auto& [key, block] : grid.blocks()) {
        int bx, by, bz;
        VoxelGrid::Unkey(key, &bx, &by, &bz);
        const std::int32_t coords[3] = {bx, by, bz};
        Put(f.get(), coords);
        Put(f.get(), block.layers);
    }
    return std::ferror(f.get()) == 0;
}

bool WriteShapesObj(const std::wstring& path, const ShapeSet& shapes) {
    const File f = Open(path);
    if (!f) return false;
    std::fprintf(f.get(), "# SifuCraft collision shapes: Minecraft orientation, metres\n");
    ObjWriter obj(f.get());
    obj.Group("convex");
    for (const ConvexShape& shape : shapes.convexes) WriteHull(&obj, shape.vertices);
    obj.Group("capsule");
    for (const CapsuleShape& shape : shapes.capsules) WriteHull(&obj, CapsulePoints(shape));
    obj.Group("triangle_mesh");
    for (const TriangleShape& t : shapes.triangles) obj.Face({t.v[0], t.v[1], t.v[2]});
    return std::ferror(f.get()) == 0;
}

}  // namespace sifucraft::collision
