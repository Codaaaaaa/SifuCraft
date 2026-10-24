#include "collision/physx_mesh.h"

#include <algorithm>
#include <cmath>
#include <cstring>

#include "core/safe_memory.h"

namespace sifucraft::collision {

namespace {

constexpr std::uint32_t kMaxMeshVertices = 4'000'000;
constexpr std::uint32_t kMaxMeshTriangles = 8'000'000;
constexpr std::uint32_t kSampledTriangles = 2048;
constexpr int kScannedBytes = 192;
constexpr float kMaxCoordinate = 1e6f;

// Reads `count` triangles starting at `first` as 32-bit indices.
bool ReadIndices(std::uintptr_t data, bool wide, std::uint32_t first, std::uint32_t count, std::uint32_t* out) {
    const std::size_t n = static_cast<std::size_t>(count) * 3;
    if (wide) return mem::Copy(out, data + static_cast<std::uint64_t>(first) * 12, n * 4);
    std::vector<std::uint16_t> narrow(n);
    if (!mem::Copy(narrow.data(), data + static_cast<std::uint64_t>(first) * 6, n * 2)) return false;
    std::copy(narrow.begin(), narrow.end(), out);
    return true;
}

// The first triangles and the last one: a count that runs past the real array fails here.
bool SampledIndicesValid(std::uintptr_t data, std::uint32_t triangles, std::uint32_t vertices, bool wide) {
    const std::uint32_t sampled = std::min(triangles, kSampledTriangles);
    std::vector<std::uint32_t> indices((sampled + 1) * 3);
    if (!ReadIndices(data, wide, 0, sampled, indices.data())) return false;
    if (!ReadIndices(data, wide, triangles - 1, 1, indices.data() + sampled * 3)) return false;
    return std::all_of(indices.begin(), indices.end(), [&](std::uint32_t i) { return i < vertices; });
}

bool VerticesFinite(const std::vector<ue::FVector>& vertices) {
    return std::all_of(vertices.begin(), vertices.end(), [](const ue::FVector& v) {
        return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z) && std::fabs(v.x) < kMaxCoordinate &&
               std::fabs(v.y) < kMaxCoordinate && std::fabs(v.z) < kMaxCoordinate;
    });
}

}  // namespace

TriangleMeshInfo InspectTriangleMesh(std::uintptr_t px_mesh) {
    std::uint8_t raw[kScannedBytes];
    if (!px_mesh || !mem::Read(px_mesh, &raw)) return {};
    for (int at = 8; at + 24 <= kScannedBytes; at += 8) {
        TriangleMeshInfo m;
        std::memcpy(&m.vertices, raw + at, 4);
        std::memcpy(&m.triangles, raw + at + 4, 4);
        std::memcpy(&m.vertex_data, raw + at + 8, 8);
        std::memcpy(&m.index_data, raw + at + 16, 8);
        if (m.vertices < 3 || m.vertices > kMaxMeshVertices || m.triangles == 0 || m.triangles > kMaxMeshTriangles) {
            continue;
        }
        if (!m.vertex_data || !m.index_data || (m.vertex_data & 3) || (m.index_data & 1)) continue;
        if (SampledIndicesValid(m.index_data, m.triangles, m.vertices, true)) {
            m.wide = true;
        } else if (m.vertices <= 0x10000 && SampledIndicesValid(m.index_data, m.triangles, m.vertices, false)) {
            m.wide = false;
        } else {
            continue;
        }
        m.layout = at;
        m.found = true;
        return m;
    }
    return {};
}

bool ReadTriangleMesh(std::uintptr_t px_mesh, std::vector<ue::FVector>* vertices, std::vector<std::uint32_t>* indices) {
    const TriangleMeshInfo m = InspectTriangleMesh(px_mesh);
    if (!m.found) return false;
    vertices->resize(m.vertices);
    indices->resize(static_cast<std::size_t>(m.triangles) * 3);
    if (!mem::Copy(vertices->data(), m.vertex_data, vertices->size() * sizeof(ue::FVector))) return false;
    if (!ReadIndices(m.index_data, m.wide, 0, m.triangles, indices->data())) return false;
    if (!VerticesFinite(*vertices)) return false;
    return std::all_of(indices->begin(), indices->end(), [&](std::uint32_t i) { return i < m.vertices; });
}

}  // namespace sifucraft::collision
