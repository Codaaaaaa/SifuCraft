#include "collision/shapes.h"

#include <algorithm>
#include <cmath>
#include <unordered_map>

#include "collision/components.h"
#include "collision/physx_mesh.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/objects.h"
#include "ue/transform.h"

namespace sifucraft::collision {

namespace {

namespace off = gen::off;
using ue::Affine;

constexpr std::int32_t kMaxElems = 4096;          // per shape array of one body setup
constexpr std::int32_t kMaxConvexVertices = 1024;
constexpr std::int32_t kMaxInstances = 65536;

struct MeshData {
    bool ok = false;
    std::vector<FVector> vertices;
    std::vector<std::uint32_t> indices;
};

class Extractor {
public:
    explicit Extractor(ShapeSet* out) : out_(out) {}

    void AddComponent(std::uintptr_t setup, const std::vector<Affine>& transforms, float walkable_z) {
        ++out_->components;
        walkable_z_ = walkable_z;
        mem::RawArray trimeshes;
        mem::ReadArray(setup + off::UBodySetupCore::TriMeshes, &trimeshes);
        if (UsesComplexAsSimple(setup) && trimeshes.num > 0) {
            for (std::int32_t m = 0; m < std::min(trimeshes.num, 8); ++m) {
                AddMesh(mem::ReadPtr(trimeshes.data + m * sizeof(std::uintptr_t)), transforms);
            }
            return;
        }
        const std::uintptr_t agg = setup + off::UBodySetup::AggGeom;
        for (const Affine& world : transforms) {
            ForEach(agg + off::FKAggregateGeom::BoxElems, gen::size::FKBoxElem,
                    [&](std::uintptr_t e) { AddBox(e, world); });
            ForEach(agg + off::FKAggregateGeom::SphereElems, gen::size::FKSphereElem,
                    [&](std::uintptr_t e) { AddSphere(e, world); });
            ForEach(agg + off::FKAggregateGeom::SphylElems, gen::size::FKSphylElem,
                    [&](std::uintptr_t e) {
                        namespace sphyl = off::FKSphylElem;
                        AddCapsule(e, world, sphyl::Radius, sphyl::Radius, sphyl::Length);
                    });
            ForEach(agg + off::FKAggregateGeom::TaperedCapsuleElems, gen::size::FKTaperedCapsuleElem,
                    [&](std::uintptr_t e) {
                        namespace tapered = off::FKTaperedCapsuleElem;
                        AddCapsule(e, world, tapered::Radius0, tapered::Radius1, tapered::Length);
                    });
            ForEach(agg + off::FKAggregateGeom::ConvexElems, gen::size::FKConvexElem,
                    [&](std::uintptr_t e) { AddConvex(e, world); });
        }
    }

private:
    template <typename Fn>
    void ForEach(std::uintptr_t array_address, std::size_t stride, Fn fn) {
        mem::RawArray a;
        if (!mem::ReadArray(array_address, &a)) return;
        if (a.num > kMaxElems) ++out_->capped;
        for (std::int32_t i = 0; i < std::min(a.num, kMaxElems); ++i) fn(a.data + i * stride);
    }

    void AddBox(std::uintptr_t e, const Affine& world) {
        namespace box = off::FKBoxElem;
        const auto center = mem::ReadOr<FVector>(e + box::Center, FVector{});
        const auto rotation = mem::ReadOr<ue::FRotator>(e + box::Rotation, ue::FRotator{});
        const float hx = mem::ReadOr<float>(e + box::X, 0) * 0.5f, hy = mem::ReadOr<float>(e + box::Y, 0) * 0.5f,
                    hz = mem::ReadOr<float>(e + box::Z, 0) * 0.5f;
        const Affine to_world = world * ue::AffineFromRotator(rotation, center);
        ConvexShape shape;
        shape.walkable_z = walkable_z_;
        for (int corner = 0; corner < 8; ++corner) {
            shape.vertices.push_back(to_world.Apply(
                {corner & 1 ? hx : -hx, corner & 2 ? hy : -hy, corner & 4 ? hz : -hz}));
        }
        out_->convexes.push_back(std::move(shape));
        ++out_->boxes;
    }

    void AddSphere(std::uintptr_t e, const Affine& world) {
        CapsuleShape shape;
        shape.a = shape.b = world.Apply(mem::ReadOr<FVector>(e + off::FKSphereElem::Center, FVector{}));
        shape.radius = mem::ReadOr<float>(e + off::FKSphereElem::Radius, 0) * world.MinAxisScale();
        out_->capsules.push_back(shape);
        ++out_->spheres;
    }

    // FKSphylElem / FKTaperedCapsuleElem: a capsule along the element's local Z; a tapered one is
    // kept whole by using its larger radius.
    void AddCapsule(std::uintptr_t e, const Affine& world, std::uint32_t radius0, std::uint32_t radius1,
                    std::uint32_t length) {
        namespace sphyl = off::FKSphylElem;
        static_assert(off::FKTaperedCapsuleElem::Center == sphyl::Center &&
                      off::FKTaperedCapsuleElem::Rotation == sphyl::Rotation);
        const auto center = mem::ReadOr<FVector>(e + sphyl::Center, FVector{});
        const auto rotation = mem::ReadOr<ue::FRotator>(e + sphyl::Rotation, ue::FRotator{});
        const float radius = std::max(mem::ReadOr<float>(e + radius0, 0), mem::ReadOr<float>(e + radius1, 0));
        const float half_length = mem::ReadOr<float>(e + length, 0) * 0.5f;
        const Affine to_world = world * ue::AffineFromRotator(rotation, center);
        CapsuleShape shape;
        shape.a = to_world.Apply({0, 0, -half_length});
        shape.b = to_world.Apply({0, 0, half_length});
        // Radial scale: the larger of the element's local X / Y stretch.
        const FVector x = to_world.ApplyLinear({1, 0, 0}), y = to_world.ApplyLinear({0, 1, 0});
        const float sx = std::sqrt(x.x * x.x + x.y * x.y + x.z * x.z), sy = std::sqrt(y.x * y.x + y.y * y.y + y.z * y.z);
        shape.radius = radius * std::max(sx, sy);
        out_->capsules.push_back(shape);
        ++out_->capsule_elems;
    }

    void AddConvex(std::uintptr_t e, const Affine& world) {
        Affine element;
        mem::RawArray vertices;
        if (!ue::ReadTransformAt(e + off::FKConvexElem::Transform, &element) ||
            !mem::ReadArray(e + off::FKConvexElem::VertexData, &vertices) || vertices.num < 4) {
            return;
        }
        if (vertices.num > kMaxConvexVertices) {
            ++out_->capped;
            return;
        }
        std::vector<FVector> local(vertices.num);
        if (!mem::Copy(local.data(), vertices.data, local.size() * sizeof(FVector))) return;
        const Affine to_world = world * element;
        ConvexShape shape;
        shape.walkable_z = walkable_z_;
        shape.vertices.reserve(local.size());
        for (const FVector& v : local) shape.vertices.push_back(to_world.Apply(v));
        out_->convexes.push_back(std::move(shape));
        ++out_->convex_elems;
    }

    void AddMesh(std::uintptr_t px, const std::vector<Affine>& transforms) {
        if (!px) return;
        MeshData& mesh = meshes_[px];
        if (!mesh.ok && mesh.vertices.empty()) {
            mesh.ok = ReadTriangleMesh(px, &mesh.vertices, &mesh.indices);
            if (!mesh.ok) {
                mesh.vertices.assign(1, FVector{});  // remembered as unreadable
                ++out_->unreadable_meshes;
            }
        }
        if (!mesh.ok) return;
        ++out_->triangle_meshes;
        for (const Affine& world : transforms) {
            for (std::size_t i = 0; i + 2 < mesh.indices.size(); i += 3) {
                TriangleShape t;
                t.walkable_z = walkable_z_;
                for (int k = 0; k < 3; ++k) t.v[k] = world.Apply(mesh.vertices[mesh.indices[i + k]]);
                out_->triangles.push_back(t);
            }
        }
    }

    ShapeSet* out_;
    float walkable_z_ = kDefaultWalkableFloorZ;
    std::unordered_map<std::uintptr_t, MeshData> meshes_;
};

// The component's world transform, or one per instance of an instanced static mesh.
std::vector<Affine> WorldTransforms(std::uintptr_t component, std::uintptr_t instanced_class, ShapeSet* out) {
    Affine world;
    if (!ue::ReadComponentToWorld(component, &world)) return {};
    if (!ue::IsA(component, instanced_class)) return {world};
    mem::RawArray instances;
    if (!mem::ReadArray(component + off::UInstancedStaticMeshComponent::PerInstanceSMData, &instances)) return {};
    if (instances.num > kMaxInstances) ++out->capped;
    std::vector<Affine> transforms;
    for (std::int32_t i = 0; i < std::min(instances.num, kMaxInstances); ++i) {
        float matrix[4][4];
        if (!mem::Read(instances.data + i * gen::size::FInstancedStaticMeshInstanceData +
                           off::FInstancedStaticMeshInstanceData::Transform,
                       &matrix)) {
            continue;
        }
        transforms.push_back(world * ue::AffineFromRowMatrix(matrix));
    }
    out->instances += static_cast<int>(transforms.size());
    return transforms;
}

}  // namespace

ShapeSet ExtractShapes(std::uintptr_t world, const Responses& pawn_responses, float pawn_walkable_z) {
    ShapeSet out;
    out.pawn_walkable_z = pawn_walkable_z;
    static const std::uintptr_t primitive_class = ue::FindClass("PrimitiveComponent");
    static const std::uintptr_t instanced_class = ue::FindClass("InstancedStaticMeshComponent");
    if (!world || !primitive_class) return out;
    Extractor extractor(&out);
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t component = ue::ObjectAt(i);
        if (!component || !ue::IsA(component, primitive_class) || !ue::IsLive(component)) continue;
        if (!VisibleLevelOf(component, world) || !Blocks(component, pawn_responses)) continue;
        if (!IsFixed(component)) {
            ++out.skipped_movable;
            continue;
        }
        const std::uintptr_t setup = BodySetupOf(component);
        if (!setup) {
            ++out.no_body_setup;
            continue;
        }
        const std::vector<Affine> transforms = WorldTransforms(component, instanced_class, &out);
        if (transforms.empty()) continue;
        const float walkable_z = WalkableFloorZOn(component, pawn_walkable_z);
        if (walkable_z != pawn_walkable_z) ++out.slope_overrides;
        extractor.AddComponent(setup, transforms, walkable_z);
    }
    return out;
}

}  // namespace sifucraft::collision
