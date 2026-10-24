#include "collision/shape_file.h"

#include <cstdio>
#include <cstring>
#include <memory>
#include <utility>

namespace sifucraft::collision {

namespace {

constexpr std::uint32_t kMaxCount = 1u << 24;  // sanity limit when reading

struct FileCloser {
    void operator()(std::FILE* f) const { std::fclose(f); }
};
using File = std::unique_ptr<std::FILE, FileCloser>;

template <typename T>
void Put(std::FILE* f, const T& value) {
    std::fwrite(&value, sizeof(T), 1, f);
}

template <typename T>
bool Get(std::FILE* f, T* value) {
    return std::fread(value, sizeof(T), 1, f) == 1;
}

bool GetCount(std::FILE* f, std::uint32_t* count) { return Get(f, count) && *count <= kMaxCount; }

}  // namespace

bool WriteShapeFile(const std::wstring& path, const ShapeSet& shapes, const std::string& map) {
    const File f(_wfopen(path.c_str(), L"wb"));
    if (!f) return false;
    std::fwrite("SCSH", 1, 4, f.get());
    Put(f.get(), kShapeFileVersion);
    Put(f.get(), shapes.pawn_walkable_z);
    Put(f.get(), static_cast<std::uint32_t>(map.size()));
    std::fwrite(map.data(), 1, map.size(), f.get());
    Put(f.get(), static_cast<std::uint32_t>(shapes.convexes.size()));
    for (const ConvexShape& c : shapes.convexes) {
        Put(f.get(), c.walkable_z);
        Put(f.get(), static_cast<std::uint32_t>(c.vertices.size()));
        std::fwrite(c.vertices.data(), sizeof(FVector), c.vertices.size(), f.get());
    }
    Put(f.get(), static_cast<std::uint32_t>(shapes.capsules.size()));
    for (const CapsuleShape& c : shapes.capsules) {
        Put(f.get(), c.a);
        Put(f.get(), c.b);
        Put(f.get(), c.radius);
    }
    Put(f.get(), static_cast<std::uint32_t>(shapes.triangles.size()));
    for (const TriangleShape& t : shapes.triangles) {
        Put(f.get(), t.v);
        Put(f.get(), t.walkable_z);
    }
    return std::ferror(f.get()) == 0;
}

bool ReadShapeFile(const std::wstring& path, ShapeSet* shapes, std::string* map) {
    const File f(_wfopen(path.c_str(), L"rb"));
    if (!f) return false;
    char magic[4];
    std::uint32_t version, count;
    if (std::fread(magic, 1, 4, f.get()) != 4 || std::memcmp(magic, "SCSH", 4) != 0 || !Get(f.get(), &version) ||
        version != kShapeFileVersion) {
        return false;
    }
    ShapeSet out;
    if (!Get(f.get(), &out.pawn_walkable_z) || !GetCount(f.get(), &count)) return false;
    map->assign(count, '\0');
    if (std::fread(map->data(), 1, count, f.get()) != count || !GetCount(f.get(), &count)) return false;
    out.convexes.resize(count);
    for (ConvexShape& c : out.convexes) {
        std::uint32_t vertices;
        if (!Get(f.get(), &c.walkable_z) || !GetCount(f.get(), &vertices)) return false;
        c.vertices.resize(vertices);
        if (std::fread(c.vertices.data(), sizeof(FVector), vertices, f.get()) != vertices) return false;
    }
    if (!GetCount(f.get(), &count)) return false;
    out.capsules.resize(count);
    for (CapsuleShape& c : out.capsules) {
        if (!Get(f.get(), &c.a) || !Get(f.get(), &c.b) || !Get(f.get(), &c.radius)) return false;
    }
    if (!GetCount(f.get(), &count)) return false;
    out.triangles.resize(count);
    for (TriangleShape& t : out.triangles) {
        if (!Get(f.get(), &t.v) || !Get(f.get(), &t.walkable_z)) return false;
    }
    *shapes = std::move(out);
    return true;
}

}  // namespace sifucraft::collision
