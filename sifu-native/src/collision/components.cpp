#include "collision/components.h"

#include <algorithm>
#include <cmath>

#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/objects.h"
#include "ue/reflection.h"
#include "ue/types.h"

namespace sifucraft::collision {

namespace {

namespace off = gen::off;

// ECollisionEnabled, ECollisionResponse, ECollisionTraceFlag and EComponentMobility values.
constexpr std::uint8_t kQueryOnly = 1;
constexpr std::uint8_t kQueryAndPhysics = 3;
constexpr std::uint8_t kBlock = 2;
constexpr std::uint8_t kUseDefault = 0;
constexpr std::uint8_t kComplexAsSimple = 3;
constexpr std::uint8_t kMovable = 2;
// EWalkableSlopeBehavior.
constexpr std::uint8_t kSlopeIncrease = 1;
constexpr std::uint8_t kSlopeDecrease = 2;
constexpr std::uint8_t kSlopeUnwalkable = 3;
constexpr float kUnwalkableZ = 2.0f;  // above any normal's Z, as UE uses it

int ReadDefaultShapeComplexity() {
    const std::uintptr_t cls = ue::FindClass("PhysicsSettings");
    if (!cls) return -1;
    const std::int32_t offset = ue::FindPropertyOffset(cls, "DefaultShapeComplexity");
    if (offset < 0) return -2;
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (object && ue::ClassOf(object) == cls && (ue::FlagsOf(object) & ue::RF_ClassDefaultObject)) {
            return mem::ReadOr<std::uint8_t>(object + offset, 0xFF);
        }
    }
    return -3;
}

}  // namespace

Responses ReadResponses(std::uintptr_t component) {
    const std::uintptr_t body = component + off::UPrimitiveComponent::BodyInstance;
    Responses r;
    r.object_type = mem::ReadOr<std::uint8_t>(body + off::FBodyInstance::ObjectType, 0xFF);
    mem::Read(body + off::FBodyInstance::CollisionResponses + off::FCollisionResponse::ResponseToChannels,
              &r.to_channel);
    return r;
}

bool Blocks(std::uintptr_t component, const Responses& pawn) {
    const std::uintptr_t body = component + off::UPrimitiveComponent::BodyInstance;
    const auto enabled = mem::ReadOr<std::uint8_t>(body + off::FBodyInstance::CollisionEnabled, 0);
    if (enabled != kQueryOnly && enabled != kQueryAndPhysics) return false;
    const Responses own = ReadResponses(component);
    return pawn.object_type < 32 && own.object_type < 32 && own.to_channel[pawn.object_type] == kBlock &&
           pawn.to_channel[own.object_type] == kBlock;
}

bool IsFixed(std::uintptr_t component) {
    return mem::ReadOr<std::uint8_t>(component + off::USceneComponent::Mobility, kMovable) != kMovable;
}

std::uintptr_t BodySetupOf(std::uintptr_t component) {
    static const std::uintptr_t static_mesh_class = ue::FindClass("StaticMeshComponent");
    const std::uintptr_t setup =
        ue::ResolveWeak(component + off::UPrimitiveComponent::BodyInstance + off::FBodyInstanceCore::BodySetup);
    if (setup || !ue::IsA(component, static_mesh_class)) return setup;
    const std::uintptr_t mesh = mem::ReadPtr(component + off::UStaticMeshComponent::StaticMesh);
    return mesh ? mem::ReadPtr(mesh + off::UStaticMesh::BodySetup) : 0;
}

float WalkableFloorZOn(std::uintptr_t component, float pawn_walkable_z) {
    namespace bits = gen::bits::FBodyInstance;
    namespace slope = off::FWalkableSlopeOverride;
    const std::uintptr_t body = component + off::UPrimitiveComponent::BodyInstance;
    const bool on_instance =
        (mem::ReadOr<std::uint8_t>(body + bits::bOverrideWalkableSlopeOnInstance_Offset, 0) >>
         bits::bOverrideWalkableSlopeOnInstance_Shift) & 1;
    const std::uintptr_t weak_setup = ue::ResolveWeak(body + off::FBodyInstanceCore::BodySetup);
    const std::uintptr_t source = on_instance || !weak_setup ? body + off::FBodyInstance::WalkableSlopeOverride
                                                             : weak_setup + off::UBodySetup::WalkableSlopeOverride;
    const auto behavior = mem::ReadOr<std::uint8_t>(source + slope::WalkableSlopeBehavior, 0);
    const float angle = mem::ReadOr<float>(source + slope::WalkableSlopeAngle, 0.0f);
    const float slope_z = std::cos(std::clamp(angle, 0.0f, 90.0f) * 0.0174532925f);
    switch (behavior) {
        case kSlopeIncrease: return std::min(pawn_walkable_z, slope_z);
        case kSlopeDecrease: return std::max(pawn_walkable_z, slope_z);
        case kSlopeUnwalkable: return kUnwalkableZ;
        default: return pawn_walkable_z;
    }
}

bool UsesComplexAsSimple(std::uintptr_t body_setup) {
    const auto trace = mem::ReadOr<std::uint8_t>(body_setup + off::UBodySetupCore::CollisionTraceFlag, 0);
    return trace == kComplexAsSimple || (trace == kUseDefault && ProjectDefaultShapeComplexity() == kComplexAsSimple);
}

int ProjectDefaultShapeComplexity() {
    static int cached = -100;
    if (cached < 0) cached = ReadDefaultShapeComplexity();
    return cached;
}

bool LevelVisible(std::uintptr_t level) {
    namespace bits = gen::bits::ULevel;
    return (mem::ReadOr<std::uint8_t>(level + bits::bIsVisible_Offset, 0) >> bits::bIsVisible_Shift) & 1;
}

int VisibleLevelCount(std::uintptr_t world) {
    mem::RawArray levels;
    if (!world || !mem::ReadArray(world + off::UWorld::Levels, &levels)) return 0;
    int visible = 0;
    for (std::int32_t i = 0; i < levels.num; ++i) {
        const std::uintptr_t level = mem::ReadPtr(levels.data + i * sizeof(std::uintptr_t));
        if (level && LevelVisible(level)) ++visible;
    }
    return visible;
}

std::uintptr_t VisibleLevelOf(std::uintptr_t component, std::uintptr_t world) {
    const std::uintptr_t level = ue::OuterOf(ue::OuterOf(component));
    if (!level || mem::ReadPtr(level + off::ULevel::OwningWorld) != world || !LevelVisible(level)) return 0;
    return level;
}

}  // namespace sifucraft::collision
