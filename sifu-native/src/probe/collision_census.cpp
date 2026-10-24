#include "probe/collision_census.h"

#include <windows.h>

#include <algorithm>
#include <cfloat>
#include <cstdio>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include "collision/components.h"
#include "collision/physx_mesh.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/objects.h"
#include "ue/types.h"

namespace sifucraft::probe {

namespace {

namespace off = gen::off;
using collision::Responses;

constexpr std::uint8_t kBlock = 2;  // ECollisionResponse
constexpr std::uint8_t kOverlap = 1;
constexpr std::int32_t kMaxConvexElems = 4096;
constexpr std::size_t kMaxClassesLogged = 30;
constexpr std::size_t kMaxSamplesLogged = 15;
constexpr std::size_t kMaxMeshesLogged = 40;

std::int32_t ArrayNum(std::uintptr_t address) {
    mem::RawArray a;
    return mem::ReadArray(address, &a) ? a.num : 0;
}

struct Tally {
    int components = 0;
    int blocking = 0;           // query collision that blocks the pawn and is blocked by it
    int blocking_movable = 0;
    int simple = 0;             // blocking, with simple shapes (what the pawn's sweeps hit)
    int complex_as_simple = 0;  // blocking, its triangle mesh is used for simple queries
    int no_simple = 0;          // blocking response but nothing simple: the pawn walks through
    int no_body_setup = 0;
    int boxes = 0, spheres = 0, capsules = 0, convexes = 0, empty_convexes = 0, trimesh_sets = 0;
    long long convex_vertices = 0;
    long long instances = 0;    // instanced static mesh instances

    void Add(const Tally& t) {
        components += t.components;
        blocking += t.blocking;
        blocking_movable += t.blocking_movable;
        simple += t.simple;
        complex_as_simple += t.complex_as_simple;
        no_simple += t.no_simple;
        no_body_setup += t.no_body_setup;
        boxes += t.boxes;
        spheres += t.spheres;
        capsules += t.capsules;
        convexes += t.convexes;
        empty_convexes += t.empty_convexes;
        trimesh_sets += t.trimesh_sets;
        convex_vertices += t.convex_vertices;
        instances += t.instances;
    }
};

struct Classes {
    std::uintptr_t primitive = 0, instanced = 0, landscape = 0;
};

Classes FindClasses() {
    Classes c;
    c.primitive = ue::FindClass("PrimitiveComponent");
    c.instanced = ue::FindClass("InstancedStaticMeshComponent");
    c.landscape = ue::FindClass("LandscapeHeightfieldCollisionComponent");
    return c;
}

std::string ChannelList(const std::uint8_t (&responses)[32], std::uint8_t wanted) {
    std::string out;
    for (int i = 0; i < 32; ++i) {
        if (responses[i] != wanted) continue;
        if (!out.empty()) out += ',';
        out += std::to_string(i);
    }
    return out.empty() ? "-" : out;
}

void LogTally(const char* name, const Tally& t) {
    SC_LOG("[census] %-40s n=%d blocking=%d (movable %d) simple=%d complex_as_simple=%d no_simple=%d "
           "no_body_setup=%d | boxes=%d spheres=%d capsules=%d convex=%d (verts %lld, empty %d) trimesh_sets=%d "
           "instances=%lld",
           name, t.components, t.blocking, t.blocking_movable, t.simple, t.complex_as_simple, t.no_simple,
           t.no_body_setup, t.boxes, t.spheres, t.capsules, t.convexes, t.convex_vertices, t.empty_convexes,
           t.trimesh_sets, t.instances);
}

void LogTriangleMeshes(const std::map<std::uintptr_t, std::string>& meshes) {
    long long vertices = 0, triangles = 0;
    int read = 0;
    std::size_t logged = 0;
    for (const auto& [px, user] : meshes) {
        const collision::TriangleMeshInfo m = collision::InspectTriangleMesh(px);
        if (m.found) {
            ++read;
            vertices += m.vertices;
            triangles += m.triangles;
        }
        if (logged++ >= kMaxMeshesLogged) continue;
        if (!m.found) {
            std::uint32_t head[24] = {};
            mem::Read(px, &head);
            std::string hex;
            char word[12];
            for (std::uint32_t w : head) {
                std::snprintf(word, sizeof(word), " %08X", w);
                hex += word;
            }
            SC_LOG("[census] trimesh %s: layout NOT found; first 96 bytes:%s", user.c_str(), hex.c_str());
            continue;
        }
        SC_LOG("[census] trimesh %s: counts@+0x%X verts=%u tris=%u idx=%s", user.c_str(), m.layout, m.vertices,
               m.triangles, m.wide ? "32" : "16");
    }
    SC_LOG("[census] complex-as-simple triangle meshes: %d unique, %d read, %lld vertices, %lld triangles",
           static_cast<int>(meshes.size()), read, vertices, triangles);
}

}  // namespace

void LogCollisionCensus(std::uintptr_t world, std::uintptr_t pawn) {
    const ULONGLONG start = GetTickCount64();
    static const Classes classes = FindClasses();
    if (!world || !pawn || !classes.primitive) {
        SC_LOG("[census] skipped: world=%d pawn=%d PrimitiveComponent class=%d", world != 0, pawn != 0,
               classes.primitive != 0);
        return;
    }
    const Responses pawn_responses = collision::ReadResponses(mem::ReadPtr(pawn + off::AActor::RootComponent));

    std::map<std::string, Tally> by_class;
    std::map<int, int> blocking_object_types;
    int trace_flags[4] = {};
    std::map<std::uintptr_t, std::string> complex_meshes;  // PxTriangleMesh -> its first user
    std::vector<std::string> complex_samples;
    int in_world = 0, hidden_level = 0, landscape = 0, landscape_blocking = 0;
    float lo[3] = {FLT_MAX, FLT_MAX, FLT_MAX}, hi[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};

    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t component = ue::ObjectAt(i);
        if (!component || !ue::IsA(component, classes.primitive) || !ue::IsLive(component)) continue;
        const std::uintptr_t actor = ue::OuterOf(component);
        const std::uintptr_t level = ue::OuterOf(actor);
        if (!level || mem::ReadPtr(level + off::ULevel::OwningWorld) != world) continue;
        ++in_world;
        if (!collision::LevelVisible(level)) {
            ++hidden_level;
            continue;
        }

        Tally& t = by_class[ue::ClassName(component)];
        ++t.components;
        const bool blocking = collision::Blocks(component, pawn_responses);
        if (ue::IsA(component, classes.landscape)) {
            ++landscape;
            if (blocking) ++landscape_blocking;
        }
        if (!blocking) continue;
        ++t.blocking;
        ++blocking_object_types[collision::ReadResponses(component).object_type];
        const bool fixed = collision::IsFixed(component);
        if (!fixed) ++t.blocking_movable;
        if (ue::IsA(component, classes.instanced)) {
            t.instances += ArrayNum(component + off::UInstancedStaticMeshComponent::PerInstanceSMData);
        }
        const std::uintptr_t setup = collision::BodySetupOf(component);
        if (!setup) {
            ++t.no_body_setup;
            continue;
        }

        const std::uintptr_t agg = setup + off::UBodySetup::AggGeom;
        const int boxes = ArrayNum(agg + off::FKAggregateGeom::BoxElems);
        const int spheres = ArrayNum(agg + off::FKAggregateGeom::SphereElems);
        const int capsules = ArrayNum(agg + off::FKAggregateGeom::SphylElems) +
                             ArrayNum(agg + off::FKAggregateGeom::TaperedCapsuleElems);
        mem::RawArray convex;
        mem::ReadArray(agg + off::FKAggregateGeom::ConvexElems, &convex);
        mem::RawArray trimeshes;
        mem::ReadArray(setup + off::UBodySetupCore::TriMeshes, &trimeshes);
        const auto trace = mem::ReadOr<std::uint8_t>(setup + off::UBodySetupCore::CollisionTraceFlag, 0);
        if (trace < 4) ++trace_flags[trace];
        t.boxes += boxes;
        t.spheres += spheres;
        t.capsules += capsules;
        t.convexes += convex.num;
        if (trimeshes.num > 0) ++t.trimesh_sets;
        for (std::int32_t e = 0; e < std::min(convex.num, kMaxConvexElems); ++e) {
            const int vertices = ArrayNum(convex.data + e * gen::size::FKConvexElem + off::FKConvexElem::VertexData);
            t.convex_vertices += vertices;
            if (vertices == 0) ++t.empty_convexes;
        }

        if (collision::UsesComplexAsSimple(setup) && trimeshes.num > 0) {
            ++t.complex_as_simple;
            for (std::int32_t m = 0; m < trimeshes.num && m < 8; ++m) {
                const std::uintptr_t px = mem::ReadPtr(trimeshes.data + m * sizeof(std::uintptr_t));
                if (px) complex_meshes.emplace(px, ue::ObjectName(actor) + "." + ue::ObjectName(component));
            }
            if (complex_samples.size() < kMaxSamplesLogged) {
                complex_samples.push_back(ue::ObjectName(actor) + "." + ue::ObjectName(component));
            }
        } else if (boxes + spheres + capsules + convex.num > 0) {
            ++t.simple;
        } else {
            ++t.no_simple;
            continue;
        }
        if (fixed) {
            ue::FBoxSphereBounds bounds{};
            if (mem::Read(component + off::USceneComponent::Bounds, &bounds)) {
                const float o[3] = {bounds.origin.x, bounds.origin.y, bounds.origin.z};
                const float e[3] = {bounds.extent.x, bounds.extent.y, bounds.extent.z};
                for (int k = 0; k < 3; ++k) {
                    lo[k] = std::min(lo[k], o[k] - e[k]);
                    hi[k] = std::max(hi[k], o[k] + e[k]);
                }
            }
        }
    }

    SC_LOG("[census] levels visible=%d | primitives in this world=%d (%d in hidden levels) | pawn capsule type=%u "
           "blocks=[%s] overlaps=[%s]",
           collision::VisibleLevelCount(world), in_world, hidden_level, pawn_responses.object_type,
           ChannelList(pawn_responses.to_channel, kBlock).c_str(),
           ChannelList(pawn_responses.to_channel, kOverlap).c_str());
    std::vector<std::pair<std::string, Tally>> rows(by_class.begin(), by_class.end());
    std::sort(rows.begin(), rows.end(), [](const auto& a, const auto& b) {
        return a.second.blocking != b.second.blocking ? a.second.blocking > b.second.blocking
                                                      : a.second.components > b.second.components;
    });
    Tally total;
    for (std::size_t r = 0; r < rows.size(); ++r) {
        total.Add(rows[r].second);
        if (r < kMaxClassesLogged) LogTally(rows[r].first.c_str(), rows[r].second);
    }
    LogTally("TOTAL", total);
    SC_LOG("[census] landscape components=%d (blocking %d)", landscape, landscape_blocking);
    std::string types;
    for (const auto& [type, n] : blocking_object_types) types += " " + std::to_string(type) + ":" + std::to_string(n);
    SC_LOG("[census] object types of blocking components:%s", types.empty() ? " -" : types.c_str());
    SC_LOG("[census] trace flags of blocking body setups: default=%d simple_and_complex=%d simple_as_complex=%d "
           "complex_as_simple=%d | project DefaultShapeComplexity=%d",
           trace_flags[0], trace_flags[1], trace_flags[2], trace_flags[3], collision::ProjectDefaultShapeComplexity());
    if (lo[0] <= hi[0]) {
        SC_LOG("[census] static blocking bounds (cm): min=(%.0f, %.0f, %.0f) max=(%.0f, %.0f, %.0f)", lo[0], lo[1],
               lo[2], hi[0], hi[1], hi[2]);
    }
    for (const std::string& sample : complex_samples) SC_LOG("[census] complex-as-simple: %s", sample.c_str());
    LogTriangleMeshes(complex_meshes);
    SC_LOG("[census] took %llu ms", static_cast<unsigned long long>(GetTickCount64() - start));
}

}  // namespace sifucraft::probe
