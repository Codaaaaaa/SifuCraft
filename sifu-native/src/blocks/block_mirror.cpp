#include "blocks/block_mirror.h"
#include "blocks/owned_serial.h"
#include "blocks/shape_plan.h"

#include <windows.h>

#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <functional>
#include <iterator>
#include <array>
#include <map>
#include <memory>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <utility>
#include <vector>

#include "collision/components.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/function_call.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/reflection.h"
#include "ue/transform.h"
#include "ue/types.h"

namespace sifucraft::blocks {

namespace {

namespace off = gen::off;
using protocol::BlockEntry;

constexpr int kSpawnsPerFrame = 8;
constexpr int kDestroysPerFrame = 16;
// Reusing an owned box takes two reflected calls; it must not consume the
// much smaller actor-construction budget during a fleet of moving pistons.
constexpr int kShapeUpdatesPerFrame = 128;
constexpr auto kShapeUpdateMinBudget = std::chrono::microseconds(2000);
constexpr auto kShapeUpdateMaxBudget = std::chrono::microseconds(8000);
constexpr float kBlockCm = 100.0f;
constexpr std::uint8_t kAlwaysSpawn = 1;       // ESpawnActorCollisionHandlingMethod
constexpr std::uint8_t kMovable = 2;           // EComponentMobility
constexpr std::uint8_t kLevelObjectType = 18;  // the channel Sifu's level geometry uses: every capsule blocks it
constexpr std::uint8_t kBlock = 2;             // ECollisionResponse
constexpr std::uint8_t kQueryAndPhysics = 3;   // ECollisionEnabled
constexpr std::size_t kMaxParams = 256;
constexpr std::uint64_t kAssetRetryMs = 5000;
constexpr int kSpawnsLogged = 6;
constexpr char kCubePath[] = "/Engine/BasicShapes/Cube.Cube";  // 100 cm, centred on its origin
// The first of these loaded in the world is used: stone, then concrete, then the engine's plain one.
// World-space (WS) materials tile by world position, which suits a cube of any size; the others
// are laid out for their own meshes.
constexpr const char* kMaterials[] = {"MI_Archi_WS_Stone_06_A_FULLYROUGH", "M_MasterArchi_WS_Stone_01_A_FULLYROUGH",
                                      "MI_Props_VP_Stone_02_D_FULLYROUGH", "M_MasterArchi_WS_Concrete_01_A",
                                      "BasicShapeMaterial"};

// Minecraft-textured blocks: material instances of the first of these loaded (UV-mapped, with a
// "Base Color | Texture" parameter), one per face texture Minecraft exported.
constexpr const char* kTextureBases[] = {"M_MasterSimple_DiffSpe_Wood_01_A", "M_MasterSimple_DiffSpe_Wood_01_A_FULLYROUGH",
                                         "MI_Simple_Wood_05_A", "MI_Simple_Wood_02_A_FULLYROUGH",
                                         "MI_Simple_Wood_04_A_FULLYROUGH", "MI_Simple_Concrete_02_A"};
constexpr char kBaseTexture[] = "Base Color | Texture";
constexpr char kBaseTiling[] = "Base Color | Texture Tiling";
constexpr char kPlanePath[] = "/Engine/BasicShapes/Plane.Plane";  // 100 x 100 cm, facing +Z, centred
constexpr int kMaterialsPerFrame = 2;  // each imports a PNG
constexpr std::uint8_t kNoCollision = 0;
constexpr int kFaces = 6;

// A palette entry: the texture file of each face, Unreal +X, -X, +Y, -Y, +Z, -Z.
struct Look {
    std::wstring faces[kFaces];
};

// Where each face's plane goes on a block whose faces differ: its outward normal (the plane mesh's
// +Z) and the direction the texture's "down" runs along (taken as the mesh's +Y; its +X follows).
// Side faces keep the texture upright, as Minecraft draws them.
struct FaceFrame {
    ue::FVector normal, down;
};
constexpr FaceFrame kFaceFrames[kFaces] = {
    {{1, 0, 0}, {0, 0, -1}},   // +X
    {{-1, 0, 0}, {0, 0, -1}},  // -X
    {{0, 1, 0}, {0, 0, -1}},   // +Y
    {{0, -1, 0}, {0, 0, -1}},  // -Y
    {{0, 0, 1}, {0, 1, 0}},    // +Z (top)
    {{0, 0, -1}, {0, -1, 0}},  // -Z (bottom)
};

// The rotation (x, y, z, w) taking the plane mesh's axes X, Y, Z to right = down x normal, down,
// normal: a proper rotation whatever the frame.
void FaceRotation(const FaceFrame& f, float (&q)[4]) {
    const ue::FVector& b = f.down;
    const ue::FVector& c = f.normal;
    const ue::FVector a{b.y * c.z - b.z * c.y, b.z * c.x - b.x * c.z, b.x * c.y - b.y * c.x};
    // Rotation matrix with columns a, b, c; standard matrix -> quaternion.
    const float m00 = a.x, m01 = b.x, m02 = c.x, m10 = a.y, m11 = b.y, m12 = c.y, m20 = a.z, m21 = b.z, m22 = c.z;
    const float trace = m00 + m11 + m22;
    if (trace > 0.0f) {
        const float s = std::sqrt(trace + 1.0f) * 2.0f;
        q[3] = 0.25f * s;
        q[0] = (m21 - m12) / s;
        q[1] = (m02 - m20) / s;
        q[2] = (m10 - m01) / s;
    } else if (m00 > m11 && m00 > m22) {
        const float s = std::sqrt(1.0f + m00 - m11 - m22) * 2.0f;
        q[3] = (m21 - m12) / s;
        q[0] = 0.25f * s;
        q[1] = (m01 + m10) / s;
        q[2] = (m02 + m20) / s;
    } else if (m11 > m22) {
        const float s = std::sqrt(1.0f + m11 - m00 - m22) * 2.0f;
        q[3] = (m02 - m20) / s;
        q[0] = (m01 + m10) / s;
        q[1] = 0.25f * s;
        q[2] = (m12 + m21) / s;
    } else {
        const float s = std::sqrt(1.0f + m22 - m00 - m11) * 2.0f;
        q[3] = (m10 - m01) / s;
        q[0] = (m02 + m20) / s;
        q[1] = (m12 + m21) / s;
        q[2] = 0.25f * s;
    }
}

std::int64_t Key(const BlockEntry& e) {
    constexpr std::int64_t kMask = (std::int64_t{1} << 21) - 1;
    return (static_cast<std::int64_t>(e.x) & kMask) | ((static_cast<std::int64_t>(e.y) & kMask) << 21) |
           ((static_cast<std::int64_t>(e.z) & kMask) << 42);
}

// Reflected functions and their parameter offsets, resolved once on the runtime thread.
struct Calls {
    std::uintptr_t statics = 0;      // UGameplayStatics default object: static functions are called on it
    std::uintptr_t actor_class = 0;  // AStaticMeshActor
    std::int32_t component = -1;     // AStaticMeshActor::StaticMeshComponent
    ue::FunctionCall begin, finish, mobility, mesh, material, object_type, responses, enabled, destroy;
    // Optional: a navigation modifier cutting the block out of the navigation mesh, which is what
    // the enemies follow. Left out if any piece is missing.
    bool nav = false;
    std::uintptr_t modifier_class = 0, null_area_class = 0;
    ue::FunctionCall add_component, finish_component, set_area;
    std::int32_t add_class = -1, add_manual = -1, add_transform = -1, add_deferred = -1, add_return = -1;
    std::int32_t finish_component_component = -1, finish_component_manual = -1, finish_component_transform = -1;
    std::int32_t set_area_value = -1;
    bool components = false;  // AddComponentByClass / FinishAddComponent usable
    // Optional: Minecraft's textures (an imported PNG on a material instance per face texture).
    bool textured = false;
    std::uintptr_t rendering = 0, material_library = 0, mesh_component_class = 0;
    ue::FunctionCall import, create_mid, set_texture, set_scalar, visibility;
    std::int32_t import_world = -1, import_file = -1, import_return = -1;
    std::int32_t mid_world = -1, mid_parent = -1, mid_return = -1;
    std::int32_t texture_name = -1, texture_value = -1, scalar_name = -1, scalar_value = -1;
    std::int32_t visibility_value = -1;
    std::int32_t begin_world = -1, begin_class = -1, begin_transform = -1, begin_collision = -1, begin_return = -1;
    std::int32_t finish_actor = -1, finish_transform = -1;
    std::int32_t mobility_value = -1, mesh_value = -1, material_index = -1, material_value = -1;
    std::int32_t object_type_value = -1, responses_value = -1, enabled_value = -1;
};
Calls g_calls;
std::atomic<bool> g_ready{false};
bool g_failed = false;

// What blocks are built from, found among the loaded objects (runtime thread).
struct Assets {
    std::uintptr_t cube = 0;
    std::uintptr_t material = 0;  // untextured blocks
    // Textured blocks: the base material, its parameters and the plane mesh (0 if not loaded).
    std::uintptr_t base = 0, plane = 0;
    ue::FName base_texture{}, base_tiling{};
    bool has_tiling = false;
};

// What should exist: written by the runtime thread, read by the game thread.
struct Desired {
    std::uintptr_t world = 0;
    std::uint64_t version = 0;
    Assets assets;
    std::vector<Look> palette;  // kind - 1
    std::vector<BlockEntry> blocks;
    std::shared_ptr<const shape_data::Frame> shapes;
    std::uint64_t shape_scope = 0;
};
SRWLOCK g_lock = SRWLOCK_INIT;
Desired g_desired;

struct Stats {
    std::atomic<std::uint64_t> spawned{0};
    std::atomic<std::uint64_t> destroyed{0};
    std::atomic<std::uint64_t> failed{0};
    std::atomic<std::uint64_t> shown{0};
    std::atomic<std::uint64_t> nav_modifiers{0};
    std::atomic<std::uint64_t> shapes{0}, shapes_created{0}, shapes_removed{0},shapes_updated{0};
};
Stats g_stats;

// A block in Sifu: its actor (0: its spawn failed) and what it was made to look like.
struct Shown {
    std::uintptr_t actor = 0;
    std::size_t look = 0;  // hash of its palette entry (0: plain)
};

// Game thread only.
std::uintptr_t g_world = 0;
std::uint64_t g_version = 0;
Assets g_assets;
std::vector<Look> g_palette;
std::unordered_map<std::int64_t, Shown> g_actors;
std::vector<BlockEntry> g_to_spawn;
std::vector<std::int64_t> g_to_destroy;
// Material instances per texture file (0: it could not be made), kept alive in the material
// slots of a hidden keeper actor: nothing else may hold them once their blocks are gone.
std::unordered_map<std::wstring, std::uintptr_t> g_materials;
std::uintptr_t g_keeper = 0;  // the keeper's mesh component
std::int32_t g_keeper_slots = 0;
int g_materials_this_frame = 0;
int g_logged_spawns = 0;
int g_logged_failures = 0;
alignas(16) std::uint8_t g_params[kMaxParams];

// Runtime thread only.
std::uintptr_t g_assets_world = 0;
std::uint64_t g_assets_ms = 0;
Assets g_found;
std::uint32_t g_palette_generation = ~0u;
std::vector<Look> g_palette_read;
std::uintptr_t g_published_world = 0;
std::uintptr_t g_published_cube = 0;
std::uint32_t g_published_palette = ~0u;
std::uint64_t g_published_seq = ~0ull;
std::string g_published_map;
std::uint64_t g_next_version = 1;
std::uint64_t g_published_shape_revision = 0, g_published_shape_mc = 0;
std::uint64_t g_published_shape_save = 0, g_shape_resolve_at = 0;
std::uint32_t g_published_shape_world = 0;
std::atomic<std::uint64_t> g_shape_requested{0}, g_shape_applied{0};
std::atomic<std::uint64_t> g_shape_presentation_scope{0}, g_shape_requested_scope{0};
std::uint64_t g_shape_scope_counter = 0, g_shape_game_scope = 0;
std::atomic<std::uint64_t> g_shape_revision_done{0};
std::uint64_t g_shape_active_revision = 0;

struct ShapeCalls {
    bool ready = false;
    std::uintptr_t box_class = 0;
    ue::FunctionCall extent, move, remove;
    std::int32_t extent_value = -1, overlap = -1, location = -1, rotation = -1, sweep = -1, hit = -1, teleport = -1, object = -1;
};
ShapeCalls g_shape_calls;
std::uint64_t g_shape_retry_at = 0;
int g_shape_logged = 0;
int g_shape_update_logged=0;
struct OwnedObject {
    std::uintptr_t pointer = 0;
    mutable ue::ObjectIdentity identity;
    ue::FName name{};
};
struct ShapeShown { OwnedObject actor, component; shape_data::Box box{};std::uint64_t geometry_version=0; };
std::map<ShapeKey,ShapeShown> g_shape_actors;
std::unordered_map<std::uintptr_t,ShapeKey> g_shape_components;
std::uint64_t g_shape_geometry_version=0;
std::vector<ShapeWanted> g_shape_spawns;
std::vector<ShapeUpdate> g_shape_updates;
std::vector<ShapeKey> g_shape_destroys;
bool g_shape_mode = false;

OwnedObject Capture(std::uintptr_t p) { return {p,ue::IdentityOf(p),ue::NameOf(p)}; }
bool SameOwned(const OwnedObject& o) {
    if (!o.pointer || o.identity.index < 0 || ue::ObjectAt(o.identity.index) != o.pointer || !ue::IsLive(o.pointer)) return false;
    auto name = ue::NameOf(o.pointer);
    const auto identity = ue::IdentityOf(o.pointer);
    return name.comparison_index == o.name.comparison_index && name.number == o.name.number &&
        MatchOwnedSerial(o.identity.index,o.identity.serial,identity.index,identity.serial);
}
bool OwnedShape(const ShapeShown& s) {
    return SameOwned(s.actor) && SameOwned(s.component) && ue::ClassOf(s.actor.pointer) == g_calls.actor_class &&
        ue::ClassOf(s.component.pointer) == g_shape_calls.box_class && ue::OuterOf(s.component.pointer) == s.actor.pointer &&
        mem::ReadPtr(ue::OuterOf(s.actor.pointer) + off::ULevel::OwningWorld) == g_world;
}
bool OwnedActor(const OwnedObject& actor) {
    return SameOwned(actor) && ue::ClassOf(actor.pointer) == g_calls.actor_class &&
        mem::ReadPtr(ue::OuterOf(actor.pointer)+off::ULevel::OwningWorld) == g_world;
}

std::size_t LookHash(const Look* look) {
    if (!look) return 0;
    std::size_t hash = 1469598103934665603ull;
    for (const std::wstring& face : look->faces) hash = (hash ^ std::hash<std::wstring>{}(face)) * 1099511628211ull;
    return hash | 1;
}

const Look* LookOf(const BlockEntry& e) {
    return e.kind >= 1 && e.kind <= g_palette.size() && !g_palette[e.kind - 1].faces[0].empty() ? &g_palette[e.kind - 1]
                                                                                               : nullptr;
}

// The offset of a parameter that fits `size` bytes in the function's block, or -1.
std::int32_t Param(const ue::FunctionCall& call, const char* name, std::size_t size) {
    const std::int32_t at = call.ParamOffset(name);
    return at >= 0 && at + static_cast<std::int32_t>(size) <= call.params_size() ? at : -1;
}

bool ShapeFunction(ue::FunctionCall& call, std::uintptr_t cls, const char* name, std::size_t count) {
    return cls && call.Resolve(cls,name) && ue::IsRegistered(call.function()) && ue::OuterOf(call.function()) == cls &&
        (mem::ReadOr<std::uint32_t>(call.function()+off::UFunction::FunctionFlags,0)&0x400u) &&
        call.params_size() > 0 && call.params_size() <= static_cast<int>(kMaxParams) &&
        ue::ListProperties(call.function()).size() == count;
}
bool ShapeParam(const ue::FunctionCall& call, const char* name, const char* type, const char* detail,
                int size, std::int32_t* at) {
    for (const auto& p : ue::ListProperties(call.function())) if (p.name == name) {
        if (p.type != type || (detail && p.detail != detail) || p.array_dim != 1 || p.element_size <= 0 ||
            (size && p.element_size != size) || p.offset < 0 || p.offset > call.params_size()-p.element_size) return false;
        if (p.type == "BoolProperty" && (mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteOffset,255) != 0 ||
            mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteMask,0) != 1)) return false;
        *at = p.offset; return true;
    }
    return false;
}
bool ResolveShapes() {
    auto& c = g_shape_calls;
    if (c.ready) return ue::IsRegistered(c.extent.function()) && ue::IsRegistered(c.move.function()) && ue::IsRegistered(c.remove.function());
    const auto now = GetTickCount64();
    if (now < g_shape_resolve_at) return false;
    g_shape_resolve_at = now+5000;
    auto scene = ue::FindClass("SceneComponent"), component = ue::FindClass("ActorComponent");
    c.box_class = ue::FindClass("BoxComponent");
    c.ready = g_calls.components && g_calls.nav &&
        ShapeFunction(c.extent,c.box_class,"SetBoxExtent",2) &&
        ShapeFunction(c.move,scene,"K2_SetRelativeLocationAndRotation",5) &&
        ShapeFunction(c.remove,component,"K2_DestroyComponent",1) &&
        ShapeParam(c.extent,"InBoxExtent","StructProperty","Vector",12,&c.extent_value) &&
        ShapeParam(c.extent,"bUpdateOverlaps","BoolProperty",nullptr,1,&c.overlap) &&
        ShapeParam(c.move,"NewLocation","StructProperty","Vector",12,&c.location) &&
        ShapeParam(c.move,"NewRotation","StructProperty","Rotator",12,&c.rotation) &&
        ShapeParam(c.move,"bSweep","BoolProperty",nullptr,1,&c.sweep) &&
        ShapeParam(c.move,"SweepHitResult","StructProperty","HitResult",0,&c.hit) &&
        ShapeParam(c.move,"bTeleport","BoolProperty",nullptr,1,&c.teleport) &&
        ShapeParam(c.remove,"Object","ObjectProperty","Object",8,&c.object);
    bool extent_property = false;
    if (c.ready) for (const auto& p : ue::ListProperties(c.box_class)) {
        if (p.name == "BoxExtent") extent_property = p.type == "StructProperty" && p.detail == "Vector" &&
            p.array_dim == 1 && p.element_size == 12 && p.offset == static_cast<int>(gen::off::UBoxComponent::BoxExtent);
    }
    c.ready = c.ready && extent_property;
    SC_LOG("[block-shapes] reflection %s extent=%d move=%d remove=%d; self-owned BoxComponent only",c.ready?"PASS":"REFUSE",
        c.extent.params_size(),c.move.params_size(),c.remove.params_size());
    return c.ready;
}

void Resolve() {
    Calls& c = g_calls;
    const std::uintptr_t statics = ue::FindClass("GameplayStatics");
    const std::uintptr_t scene = ue::FindClass("SceneComponent");
    const std::uintptr_t primitive = ue::FindClass("PrimitiveComponent");
    const std::uintptr_t static_mesh = ue::FindClass("StaticMeshComponent");
    const std::uintptr_t actor = ue::FindClass("Actor");
    c.actor_class = ue::FindClass("StaticMeshActor");
    c.statics = statics ? ue::DefaultObject(statics) : 0;
    c.component = c.actor_class ? ue::FindPropertyOffset(c.actor_class, "StaticMeshComponent") : -1;
    const bool found = c.statics && c.actor_class && c.component > 0 && scene && primitive && static_mesh && actor &&
                       c.begin.Resolve(statics, "BeginDeferredActorSpawnFromClass") &&
                       c.finish.Resolve(statics, "FinishSpawningActor") && c.mobility.Resolve(scene, "SetMobility") &&
                       c.mesh.Resolve(static_mesh, "SetStaticMesh") && c.material.Resolve(primitive, "SetMaterial") &&
                       c.object_type.Resolve(primitive, "SetCollisionObjectType") &&
                       c.responses.Resolve(primitive, "SetCollisionResponseToAllChannels") &&
                       c.enabled.Resolve(primitive, "SetCollisionEnabled") && c.destroy.Resolve(actor, "K2_DestroyActor");
    if (found) {
        c.begin_world = Param(c.begin, "WorldContextObject", 8);
        c.begin_class = Param(c.begin, "ActorClass", 8);
        c.begin_transform = Param(c.begin, "SpawnTransform", gen::size::FTransform);
        c.begin_collision = Param(c.begin, "CollisionHandlingOverride", 1);
        c.begin_return = Param(c.begin, "ReturnValue", 8);
        c.finish_actor = Param(c.finish, "Actor", 8);
        c.finish_transform = Param(c.finish, "SpawnTransform", gen::size::FTransform);
        c.mobility_value = Param(c.mobility, "NewMobility", 1);
        c.mesh_value = Param(c.mesh, "NewMesh", 8);
        c.material_index = Param(c.material, "ElementIndex", 4);
        c.material_value = Param(c.material, "Material", 8);
        c.object_type_value = Param(c.object_type, "Channel", 1);
        c.responses_value = Param(c.responses, "NewResponse", 1);
        c.enabled_value = Param(c.enabled, "NewType", 1);
    }
    const std::int32_t offsets[] = {c.begin_world, c.begin_class, c.begin_transform, c.begin_collision,
                                    c.begin_return, c.finish_actor, c.finish_transform, c.mobility_value,
                                    c.mesh_value, c.material_index, c.material_value, c.object_type_value,
                                    c.responses_value, c.enabled_value};
    bool usable = found && c.begin.params_size() <= static_cast<std::int32_t>(kMaxParams) &&
                  c.finish.params_size() <= static_cast<std::int32_t>(kMaxParams) &&
                  c.begin_transform % 16 == 0 && c.finish_transform % 16 == 0;
    for (std::int32_t at : offsets) usable = usable && at >= 0;
    if (!usable) {
        SC_LOG("[blocks] REFUSED: block functions not usable (found=%d, offsets %d %d %d %d %d / %d %d / %d %d %d %d "
               "%d %d %d)",
               found, c.begin_world, c.begin_class, c.begin_transform, c.begin_collision, c.begin_return,
               c.finish_actor, c.finish_transform, c.mobility_value, c.mesh_value, c.material_index,
               c.material_value, c.object_type_value, c.responses_value, c.enabled_value);
        g_failed = true;
        return;
    }
    if (c.add_component.Resolve(actor, "AddComponentByClass") && c.finish_component.Resolve(actor, "FinishAddComponent")) {
        c.add_class = Param(c.add_component, "Class", 8);
        c.add_manual = Param(c.add_component, "bManualAttachment", 1);
        c.add_transform = Param(c.add_component, "RelativeTransform", gen::size::FTransform);
        c.add_deferred = Param(c.add_component, "bDeferredFinish", 1);
        c.add_return = Param(c.add_component, "ReturnValue", 8);
        c.finish_component_component = Param(c.finish_component, "Component", 8);
        c.finish_component_manual = Param(c.finish_component, "bManualAttachment", 1);
        c.finish_component_transform = Param(c.finish_component, "RelativeTransform", gen::size::FTransform);
        c.components = c.add_class >= 0 && c.add_manual >= 0 && c.add_transform >= 0 && c.add_transform % 16 == 0 &&
                       c.add_deferred >= 0 && c.add_return >= 0 && c.finish_component_component >= 0 &&
                       c.finish_component_manual >= 0 && c.finish_component_transform >= 0 &&
                       c.finish_component_transform % 16 == 0 &&
                       c.add_component.params_size() <= static_cast<std::int32_t>(kMaxParams) &&
                       c.finish_component.params_size() <= static_cast<std::int32_t>(kMaxParams);
    }
    c.modifier_class = ue::FindClass("NavModifierComponent");
    c.null_area_class = ue::FindClass("NavArea_Null");
    if (c.components && c.modifier_class && c.null_area_class && c.set_area.Resolve(c.modifier_class, "SetAreaClass")) {
        c.set_area_value = Param(c.set_area, "NewAreaClass", 8);
        c.nav = c.set_area_value >= 0;
    }
    const std::uintptr_t rendering = ue::FindClass("KismetRenderingLibrary");
    const std::uintptr_t material_library = ue::FindClass("KismetMaterialLibrary");
    const std::uintptr_t dynamic_material = ue::FindClass("MaterialInstanceDynamic");
    c.mesh_component_class = static_mesh;
    c.rendering = ue::DefaultObject(rendering);
    c.material_library = ue::DefaultObject(material_library);
    if (c.components && c.rendering && c.material_library && dynamic_material &&
        c.import.Resolve(rendering, "ImportFileAsTexture2D") &&
        c.create_mid.Resolve(material_library, "CreateDynamicMaterialInstance") &&
        c.set_texture.Resolve(dynamic_material, "SetTextureParameterValue") &&
        c.set_scalar.Resolve(dynamic_material, "SetScalarParameterValue") && c.visibility.Resolve(scene, "SetVisibility")) {
        c.import_world = Param(c.import, "WorldContextObject", 8);
        c.import_file = Param(c.import, "Filename", 16);
        c.import_return = Param(c.import, "ReturnValue", 8);
        c.mid_world = Param(c.create_mid, "WorldContextObject", 8);
        c.mid_parent = Param(c.create_mid, "Parent", 8);
        c.mid_return = Param(c.create_mid, "ReturnValue", 8);
        c.texture_name = Param(c.set_texture, "ParameterName", sizeof(ue::FName));
        c.texture_value = Param(c.set_texture, "Value", 8);
        c.scalar_name = Param(c.set_scalar, "ParameterName", sizeof(ue::FName));
        c.scalar_value = Param(c.set_scalar, "Value", 4);
        c.visibility_value = Param(c.visibility, "bNewVisibility", 1);
        c.textured = c.import_world >= 0 && c.import_file >= 0 && c.import_return >= 0 && c.mid_world >= 0 &&
                     c.mid_parent >= 0 && c.mid_return >= 0 && c.texture_name >= 0 && c.texture_value >= 0 &&
                     c.scalar_name >= 0 && c.scalar_value >= 0 && c.visibility_value >= 0;
    }
    SC_LOG("[blocks] ready: BeginDeferredActorSpawnFromClass params=%d (transform@%d return@%d), "
           "FinishSpawningActor params=%d, StaticMeshComponent @%d; navigation modifiers %s; Minecraft textures %s",
           c.begin.params_size(), c.begin_transform, c.begin_return, c.finish.params_size(), c.component,
           c.nav ? "on (NavArea_Null)" : "OFF (not available)", c.textured ? "on" : "OFF (not available)");
    g_ready.store(true, std::memory_order_release);
}

// The FName of the parameter called `wanted` in a material instance's `array` of parameter values
// (each starting with its FMaterialParameterInfo, whose first member is the name).
bool FindParameter(std::uintptr_t instance, const char* array, const char* value_struct, const char* wanted,
                   ue::FName* name) {
    static std::unordered_map<std::string, std::int32_t> strides;
    auto stride = strides.find(value_struct);
    if (stride == strides.end()) {
        std::int32_t size = 0;
        const std::int32_t count = ue::ObjectCount();
        for (std::int32_t i = 0; i < count && !size; ++i) {
            const std::uintptr_t object = ue::ObjectAt(i);
            if (object && ue::ObjectName(object) == value_struct && ue::ClassName(object) == "ScriptStruct") {
                size = mem::ReadOr<std::int32_t>(object + off::UStruct::PropertiesSize, 0);
            }
        }
        stride = strides.emplace(value_struct, size).first;
    }
    const std::int32_t at = ue::FindPropertyOffset(ue::ClassOf(instance), array);
    mem::RawArray values;
    if (stride->second <= 0 || at < 0 || !mem::ReadArray(instance + at, &values)) return false;
    for (std::int32_t v = 0; v < values.num; ++v) {
        const auto parameter =
            mem::ReadOr<ue::FName>(values.data + static_cast<std::uintptr_t>(v) * stride->second, ue::FName{});
        if (ue::NameToString(parameter) == wanted) {
            *name = parameter;
            return true;
        }
    }
    return false;
}

// What to build blocks from, among the objects loaded now.
Assets FindAssets() {
    Assets a;
    std::size_t best = std::size(kMaterials), best_base = std::size(kTextureBases);
    std::uintptr_t bases[std::size(kTextureBases)] = {};
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object || !ue::IsLive(object)) continue;
        const std::string cls = ue::ClassName(object);
        if (cls == "StaticMesh") {
            const std::string name = ue::ObjectName(object);
            if (!a.cube && name == "Cube" && ue::PathName(object) == kCubePath) a.cube = object;
            if (!a.plane && name == "Plane" && ue::PathName(object) == kPlanePath) a.plane = object;
        } else if (cls == "Material" || cls == "MaterialInstanceConstant") {
            const std::string name = ue::ObjectName(object);
            for (std::size_t m = 0; m < best; ++m) {
                if (name == kMaterials[m]) {
                    best = m;
                    a.material = object;
                    break;
                }
            }
            for (std::size_t b = 0; b < std::size(kTextureBases); ++b) {
                if (name == kTextureBases[b] && !bases[b]) bases[b] = object;
            }
        }
    }
    for (std::size_t b = 0; b < std::size(kTextureBases) && !a.base; ++b) {
        if (bases[b] && FindParameter(bases[b], "TextureParameterValues", "TextureParameterValue", kBaseTexture,
                                      &a.base_texture)) {
            a.base = bases[b];
            best_base = b;
            a.has_tiling = FindParameter(bases[b], "ScalarParameterValues", "ScalarParameterValue", kBaseTiling,
                                         &a.base_tiling);
        }
    }
    SC_LOG("[blocks] assets: cube %s, plane %s, material %s, texture base %s%s", a.cube ? "found" : "NOT LOADED",
           a.plane ? "found" : "not loaded", a.material ? kMaterials[best] : "none (engine default)",
           a.base ? kTextureBases[best_base] : "none (blocks keep the material above)",
           a.base && !a.has_tiling ? " (no tiling parameter)" : "");
    return a;
}

// The palette Minecraft wrote: "<kind> <+X> <-X> <+Y> <-Y> <+Z> <-Z>" per line, texture files in the
// same folder.
std::vector<Look> ReadPalette() {
    std::vector<Look> palette;
    const std::wstring folder = log::RootDirectory() + L"\\textures\\";
    std::FILE* file = _wfopen((folder + L"palette.txt").c_str(), L"rb");
    if (!file) return palette;
    char line[2048];
    while (std::fgets(line, sizeof(line), file)) {
        char faces[kFaces][320];
        unsigned kind = 0;
        if (std::sscanf(line, "%u %319s %319s %319s %319s %319s %319s", &kind, faces[0], faces[1], faces[2], faces[3],
                        faces[4], faces[5]) != 7 ||
            kind == 0 || kind > 4096) {
            continue;
        }
        if (palette.size() < kind) palette.resize(kind);
        for (int f = 0; f < kFaces; ++f) {
            palette[kind - 1].faces[f] = folder + std::wstring(faces[f], faces[f] + std::strlen(faces[f]));
        }
    }
    std::fclose(file);
    return palette;
}

void Invoke(const ue::FunctionCall& call, std::uintptr_t object) { call.Invoke(object, g_params); }

void Clear(const ue::FunctionCall& call) { std::memset(g_params, 0, static_cast<std::size_t>(call.params_size())); }

template <typename T>
void Put(std::int32_t at, const T& value) {
    std::memcpy(g_params + at, &value, sizeof(T));
}

// An FTransform with unit scale (rotation as a quaternion x, y, z, w).
void PutTransform(std::int32_t at, const ue::FVector& translation, const float (&rotation)[4]) {
    const ue::FVector scale{1.0f, 1.0f, 1.0f};
    std::memcpy(g_params + at + off::FTransform::Rotation, rotation, sizeof(rotation));
    std::memcpy(g_params + at + off::FTransform::Translation, &translation, sizeof(translation));
    std::memcpy(g_params + at + off::FTransform::Scale3D, &scale, sizeof(scale));
}

// An FTransform with no rotation and unit scale.
void PutTransform(std::int32_t at, const ue::FVector& translation) {
    const float rotation[4] = {0.0f, 0.0f, 0.0f, 1.0f};
    const ue::FVector scale{1.0f, 1.0f, 1.0f};
    std::memcpy(g_params + at + off::FTransform::Rotation, rotation, sizeof(rotation));
    std::memcpy(g_params + at + off::FTransform::Translation, &translation, sizeof(translation));
    std::memcpy(g_params + at + off::FTransform::Scale3D, &scale, sizeof(scale));
}

// A NavModifierComponent with the null area on the block: the navigation mesh (if it is rebuilt at
// runtime) leaves the block's bounds out, so the enemies, which follow it, go around.
bool AddNavModifier(std::uintptr_t actor) {
    const Calls& c = g_calls;
    Clear(c.add_component);
    Put(c.add_class, c.modifier_class);
    PutTransform(c.add_transform, ue::FVector{});
    g_params[c.add_deferred] = 1;
    Invoke(c.add_component, actor);
    std::uintptr_t component = 0;
    std::memcpy(&component, g_params + c.add_return, sizeof(component));
    if (!component) return false;
    Clear(c.set_area);
    Put(c.set_area_value, c.null_area_class);
    Invoke(c.set_area, component);
    Clear(c.finish_component);
    Put(c.finish_component_component, component);
    PutTransform(c.finish_component_transform, ue::FVector{});
    Invoke(c.finish_component, actor);
    return true;
}

void SetByte(const ue::FunctionCall& call, std::int32_t at, std::uint8_t value, std::uintptr_t object) {
    Clear(call);
    g_params[at] = value;
    Invoke(call, object);
}

void SetMesh(std::uintptr_t component, std::uintptr_t mesh) {
    Clear(g_calls.mesh);
    Put(g_calls.mesh_value, mesh);
    Invoke(g_calls.mesh, component);
}

void SetMaterialOn(std::uintptr_t component, std::int32_t slot, std::uintptr_t material) {
    Clear(g_calls.material);
    Put(g_calls.material_index, slot);
    Put(g_calls.material_value, material);
    Invoke(g_calls.material, component);
}

// A StaticMeshActor spawned deferred at `at`: its mesh component (0 if none) is handed to `setup`
// before the actor is finished and registered.
template <typename Fn>
std::uintptr_t SpawnActor(const ue::FVector& at, Fn&& setup) {
    const Calls& c = g_calls;
    Clear(c.begin);
    Put(c.begin_world, g_world);
    Put(c.begin_class, c.actor_class);
    PutTransform(c.begin_transform, at);
    g_params[c.begin_collision] = kAlwaysSpawn;
    Invoke(c.begin, c.statics);
    std::uintptr_t actor = 0;
    std::memcpy(&actor, g_params + c.begin_return, sizeof(actor));
    if (!actor) return 0;
    const std::uintptr_t component = mem::ReadPtr(actor + c.component);
    if (component) setup(component);
    Clear(c.finish);
    Put(c.finish_actor, actor);
    PutTransform(c.finish_transform, at);
    Invoke(c.finish, c.statics);
    return actor;
}

// The keeper: a hidden actor far below the level, without collision, whose mesh component's
// material slots hold every material instance made in this world.
std::uintptr_t Keeper() {
    if (g_keeper) return g_keeper;
    std::uintptr_t component = 0;
    SpawnActor(ue::FVector{0.0f, 0.0f, -100000.0f}, [&](std::uintptr_t mesh_component) {
        SetByte(g_calls.mobility, g_calls.mobility_value, kMovable, mesh_component);
        SetByte(g_calls.enabled, g_calls.enabled_value, kNoCollision, mesh_component);
        component = mesh_component;
    });
    g_keeper = component;
    return g_keeper;
}

// The material instance showing one texture file: made on first use (the PNG imported, the base
// material instanced with it) and kept; 0 if it cannot be made.
std::uintptr_t MaterialFor(const std::wstring& file) {
    const auto known = g_materials.find(file);
    if (known != g_materials.end()) return known->second;
    const Calls& c = g_calls;
    std::uintptr_t material = 0, texture = 0;
    const std::uintptr_t keeper = Keeper();
    if (keeper) {
        ++g_materials_this_frame;
        struct {
            const wchar_t* data;
            std::int32_t num, max;
        } path{file.c_str(), static_cast<std::int32_t>(file.size() + 1), static_cast<std::int32_t>(file.size() + 1)};
        static_assert(sizeof(path) == 16);  // FString: TArray<TCHAR>
        Clear(c.import);
        Put(c.import_world, g_world);
        Put(c.import_file, path);
        Invoke(c.import, c.rendering);
        std::memcpy(&texture, g_params + c.import_return, sizeof(texture));
        if (texture) {
            Clear(c.create_mid);
            Put(c.mid_world, g_world);
            Put(c.mid_parent, g_assets.base);
            Invoke(c.create_mid, c.material_library);
            std::memcpy(&material, g_params + c.mid_return, sizeof(material));
        }
        if (material) {
            Clear(c.set_texture);
            Put(c.texture_name, g_assets.base_texture);
            Put(c.texture_value, texture);
            Invoke(c.set_texture, material);
            if (g_assets.has_tiling) {
                Clear(c.set_scalar);
                Put(c.scalar_name, g_assets.base_tiling);
                Put(c.scalar_value, 1.0f);
                Invoke(c.set_scalar, material);
            }
            SetMaterialOn(keeper, g_keeper_slots++, material);
        }
    }
    if (!material && g_logged_failures++ < kSpawnsLogged) {
        SC_LOG("[blocks] no material for %ls (%s)", file.c_str(),
               !keeper ? "no keeper" : !texture ? "texture not imported" : "material instance not made");
    }
    g_materials[file] = material;  // a failure is not retried in this world
    return material;
}

// The new material instances a block still needs.
int MissingMaterials(const Look* look) {
    if (!look) return 0;
    int missing = 0;
    for (int f = 0; f < kFaces; ++f) {
        if (g_materials.count(look->faces[f])) continue;
        bool counted = false;
        for (int g = 0; g < f; ++g) counted = counted || look->faces[g] == look->faces[f];
        if (!counted) ++missing;
    }
    return missing;
}

// One plane per face on the block, each with its face's material; true when all six are there.
bool AddFacePlanes(std::uintptr_t actor, const std::uintptr_t (&faces)[kFaces]) {
    const Calls& c = g_calls;
    int added = 0;
    for (int f = 0; f < kFaces; ++f) {
        float rotation[4];
        FaceRotation(kFaceFrames[f], rotation);
        const ue::FVector& n = kFaceFrames[f].normal;
        const ue::FVector at{n.x * kBlockCm * 0.5f, n.y * kBlockCm * 0.5f, n.z * kBlockCm * 0.5f};
        Clear(c.add_component);
        Put(c.add_class, c.mesh_component_class);
        PutTransform(c.add_transform, at, rotation);
        g_params[c.add_deferred] = 1;
        Invoke(c.add_component, actor);
        std::uintptr_t plane = 0;
        std::memcpy(&plane, g_params + c.add_return, sizeof(plane));
        if (!plane) continue;
        SetByte(c.mobility, c.mobility_value, kMovable, plane);
        SetMesh(plane, g_assets.plane);
        SetMaterialOn(plane, 0, faces[f] ? faces[f] : g_assets.material);
        SetByte(c.enabled, c.enabled_value, kNoCollision, plane);
        Clear(c.finish_component);
        Put(c.finish_component_component, plane);
        PutTransform(c.finish_component_transform, at, rotation);
        Invoke(c.finish_component, actor);
        ++added;
    }
    return added == kFaces;
}

// A block: the engine cube colliding like level geometry, showing its look. All faces alike: the
// cube wears that texture; faces that differ (grass, logs): textured planes on an invisible cube.
std::uintptr_t Spawn(const BlockEntry& e, const Look* look) {
    const Calls& c = g_calls;
    std::uintptr_t faces[kFaces] = {};
    bool uniform = true;
    if (look) {
        for (int f = 0; f < kFaces; ++f) {
            faces[f] = MaterialFor(look->faces[f]);
            uniform = uniform && look->faces[f] == look->faces[0];
        }
    }
    const bool planes = look && !uniform && g_assets.plane && c.components;
    const std::uintptr_t cube_material = look && faces[0] && !planes ? faces[0] : g_assets.material;
    std::uintptr_t cube = 0;
    const std::uintptr_t actor = SpawnActor(
        ue::FVector{(e.x + 0.5f) * kBlockCm, (e.y + 0.5f) * kBlockCm, (e.z + 0.5f) * kBlockCm},
        [&](std::uintptr_t component) {
            cube = component;
            SetByte(c.mobility, c.mobility_value, kMovable, component);
            SetMesh(component, g_assets.cube);
            if (cube_material) SetMaterialOn(component, 0, cube_material);
            SetByte(c.object_type, c.object_type_value, kLevelObjectType, component);
            SetByte(c.responses, c.responses_value, kBlock, component);
            SetByte(c.enabled, c.enabled_value, kQueryAndPhysics, component);
        });
    if (!actor) return 0;
    if (planes && cube && AddFacePlanes(actor, faces)) SetByte(c.visibility, c.visibility_value, 0, cube);
    if (c.nav && AddNavModifier(actor)) g_stats.nav_modifiers.fetch_add(1, std::memory_order_relaxed);
    return actor;
}

// Where a new block ended up and how it collides, for the first few of each world (read only).
void LogSpawned(const BlockEntry& e, std::uintptr_t actor) {
    if (g_logged_spawns >= kSpawnsLogged) return;
    ++g_logged_spawns;
    const std::uintptr_t root = mem::ReadPtr(actor + off::AActor::RootComponent);
    ue::FVector at{};
    const bool placed = root && ue::ReadWorldLocation(root, &at);
    const collision::Responses r = collision::ReadResponses(root);
    int blocked = 0;
    for (std::uint8_t response : r.to_channel) blocked += response == kBlock;
    SC_LOG("[blocks] block (%d, %d, %d): actor at (%.0f, %.0f, %.0f)%s, object type %u, blocks %d channels", e.x, e.y,
           e.z, at.x, at.y, at.z, placed ? "" : " (no location)", r.object_type, blocked);
}

void Destroy(std::uintptr_t actor) {
    if (!actor || !ue::IsLive(actor) || ue::ClassOf(actor) != g_calls.actor_class) return;
    Invoke(g_calls.destroy, actor);
}

void DestroyShape(const ShapeShown& s) {
    g_shape_components.erase(s.component.pointer);
    if (!OwnedActor(s.actor)) return;
    if (OwnedShape(s)) {
        Clear(g_shape_calls.remove); Put(g_shape_calls.object,s.actor.pointer);
        Invoke(g_shape_calls.remove,s.component.pointer);
    }
    // Component destruction cannot authorize a different actor after an engine callback.
    if (OwnedActor(s.actor)) { Destroy(s.actor.pointer); g_stats.shapes_removed.fetch_add(1); }
}
ShapeShown SpawnShape(const ShapeWanted& wanted) {
    auto& c = g_shape_calls; const auto& box = wanted.box;
    ShapeShown shown; shown.box = box;
    const ue::FVector center{(box.max[0]+box.min[0])*0.5f,(box.max[1]+box.min[1])*0.5f,(box.max[2]+box.min[2])*0.5f};
    const auto actor = SpawnActor({wanted.key[0]*100.0f+center.x,wanted.key[1]*100.0f+center.y,wanted.key[2]*100.0f+center.z},[&](std::uintptr_t root) {
        SetByte(g_calls.mobility,g_calls.mobility_value,kMovable,root);
        SetByte(g_calls.enabled,g_calls.enabled_value,kNoCollision,root);
        SetMesh(root,0);
    });
    if (!actor) return shown;
    shown.actor = Capture(actor);
    Clear(g_calls.add_component); Put(g_calls.add_class,c.box_class); PutTransform(g_calls.add_transform,ue::FVector{});
    g_params[g_calls.add_deferred] = 1; Invoke(g_calls.add_component,actor);
    std::uintptr_t component = 0; std::memcpy(&component,g_params+g_calls.add_return,8);
    shown.component = Capture(component);
    if (!OwnedShape(shown)) { if (SameOwned(shown.actor)) Destroy(actor); return {}; }
    SetByte(g_calls.mobility,g_calls.mobility_value,kMovable,component);
    const ue::FVector extent{(box.max[0]-box.min[0])*0.5f,(box.max[1]-box.min[1])*0.5f,(box.max[2]-box.min[2])*0.5f};
    Clear(c.extent); Put(c.extent_value,extent); Invoke(c.extent,component); // deferred, overlap update after registration
    Clear(c.move); Put(c.location,ue::FVector{}); g_params[c.teleport] = 1; Invoke(c.move,component);
    SetByte(g_calls.object_type,g_calls.object_type_value,kLevelObjectType,component);
    SetByte(g_calls.responses,g_calls.responses_value,kBlock,component);
    SetByte(g_calls.enabled,g_calls.enabled_value,kQueryAndPhysics,component);
    Clear(g_calls.finish_component); Put(g_calls.finish_component_component,component);
    PutTransform(g_calls.finish_component_transform,ue::FVector{}); Invoke(g_calls.finish_component,actor);
    ue::FVector actual_extent{}, actual_location{};
    const ue::FVector expected_location{wanted.key[0]*100.0f+center.x,wanted.key[1]*100.0f+center.y,wanted.key[2]*100.0f+center.z};
    const auto responses = collision::ReadResponses(component);
    collision::Responses pawn_response{}; pawn_response.object_type = 16; pawn_response.to_channel[18] = kBlock;
    bool valid = OwnedShape(shown) && mem::Read(component+off::UBoxComponent::BoxExtent,&actual_extent) &&
        ue::ReadWorldLocation(component,&actual_location) && responses.object_type == 18 && collision::Blocks(component,pawn_response);
    for (auto response : responses.to_channel) valid = valid && response == kBlock;
    const auto close = [](float a,float b) { return std::isfinite(a) && std::fabs(a-b) < 0.1f; };
    valid = valid && close(actual_extent.x,extent.x) && close(actual_extent.y,extent.y) && close(actual_extent.z,extent.z) &&
        close(actual_location.x,expected_location.x) && close(actual_location.y,expected_location.y) && close(actual_location.z,expected_location.z);
    if (g_shape_logged < 12) {
        ++g_shape_logged;
        SC_LOG("[block-shapes] box %s extent=(%.2f,%.2f,%.2f) world=(%.2f,%.2f,%.2f) object=%u",
            valid?"PASS":"REFUSE",actual_extent.x,actual_extent.y,actual_extent.z,actual_location.x,actual_location.y,actual_location.z,responses.object_type);
    }
    if (!valid || !AddNavModifier(actor)) { DestroyShape(shown); return {}; }
    g_stats.shapes_created.fetch_add(1); g_stats.nav_modifiers.fetch_add(1);
    shown.geometry_version=++g_shape_geometry_version;
    return shown;
}

bool UpdateShape(ShapeShown& shown,const ShapeWanted& wanted) {
    if(!OwnedShape(shown))return false;
    const auto root=mem::ReadPtr(shown.actor.pointer+off::AActor::RootComponent);
    ue::Affine transform;
    if(!ue::IsLive(root)||ue::OuterOf(root)!=shown.actor.pointer||ue::AttachParentOf(shown.component.pointer)!=root||!ue::ReadComponentToWorld(root,&transform))return false;
    // These roots are created with an identity linear transform by this module.
    // A foreign attachment/rotation/scale cannot authorize an unrelated relative move.
    for(int i=0;i<3;++i)for(int j=0;j<3;++j)if(!std::isfinite(transform.m[i][j])||std::fabs(transform.m[i][j]-(i==j?1.f:0.f))>0.0001f)return false;
    const auto& box=wanted.box;auto& calls=g_shape_calls;
    const ue::FVector center{wanted.key[0]*100.f+(box.min[0]+box.max[0])*.5f,
        wanted.key[1]*100.f+(box.min[1]+box.max[1])*.5f,wanted.key[2]*100.f+(box.min[2]+box.max[2])*.5f};
    const ue::FVector extent{(box.max[0]-box.min[0])*.5f,(box.max[1]-box.min[1])*.5f,(box.max[2]-box.min[2])*.5f};
    const ue::FVector relative{center.x-transform.t.x,center.y-transform.t.y,center.z-transform.t.z};
    if(!std::isfinite(relative.x)||!std::isfinite(relative.y)||!std::isfinite(relative.z))return false;
    Clear(calls.extent);Put(calls.extent_value,extent);Invoke(calls.extent,shown.component.pointer);
    if(!OwnedShape(shown))return false;
    Clear(calls.move);Put(calls.location,relative);g_params[calls.teleport]=1;Invoke(calls.move,shown.component.pointer);
    ue::FVector actual{},size{};
    if(!OwnedShape(shown)||!ue::ReadWorldLocation(shown.component.pointer,&actual)||
        !mem::Read(shown.component.pointer+off::UBoxComponent::BoxExtent,&size))return false;
    const auto close=[](float a,float b){return std::isfinite(a)&&std::fabs(a-b)<.1f;};
    if(!close(actual.x,center.x)||!close(actual.y,center.y)||!close(actual.z,center.z)||
       !close(size.x,extent.x)||!close(size.y,extent.y)||!close(size.z,extent.z))return false;
    shown.box=box;shown.geometry_version=++g_shape_geometry_version;g_stats.shapes_updated.fetch_add(1);
    if(g_shape_update_logged<12){
        ++g_shape_update_logged;
        SC_LOG("[block-shapes] update PASS source=%016llX actor=%d/%d component=%d/%d world=(%.2f,%.2f,%.2f) extent=(%.2f,%.2f,%.2f)",
            box.source_key,shown.actor.identity.index,shown.actor.identity.serial,shown.component.identity.index,shown.component.identity.serial,
            actual.x,actual.y,actual.z,size.x,size.y,size.z);
    }
    return true;
}

void PlanShapes(const std::shared_ptr<const shape_data::Frame>& frame) {
    g_shape_mode = static_cast<bool>(frame);
    g_shape_spawns.clear(); g_shape_updates.clear(); g_shape_destroys.clear();
    std::map<ShapeKey,shape_data::Box> wanted;
    if (frame) for (const auto& part : frame->parts) for (std::size_t i = 0; i < part.boxes.size(); ++i) {
        ShapeKey key{part.section.x,part.section.y,part.section.z,static_cast<std::int64_t>(part.boxes[i].source_key)};
        wanted.emplace(key,part.boxes[i]);
    }
    std::map<ShapeKey,ShapeExisting> existing;
    for(const auto& [key,shown]:g_shape_actors)existing.emplace(key,ShapeExisting{shown.box,OwnedShape(shown)});
    auto plan=PlanShapeWork(wanted,existing);
    g_shape_spawns=std::move(plan.spawns);g_shape_updates=std::move(plan.updates);g_shape_destroys=std::move(plan.destroys);
}

// Whether blocks are given Minecraft's textures in this world.
bool Textured() { return g_calls.textured && g_assets.base; }

// Takes a newer desired state: queues what to spawn and what to destroy. A block whose look
// changed (another block put in its place, a new palette) is destroyed and spawned again.
void Plan(const Desired& desired) {
    g_version = desired.version;
    g_assets = desired.assets;
    g_palette = desired.palette;
    std::unordered_set<std::int64_t> wanted;
    g_to_spawn.clear();
    g_to_destroy.clear();
    PlanShapes(desired.shapes);
    g_shape_game_scope = desired.shape_scope;
    g_shape_active_revision = desired.shapes ? desired.shapes->header.revision : 0;
    for (const BlockEntry& e : desired.blocks) {
        const std::int64_t key = Key(e);
        if (!wanted.insert(key).second) continue;
        const std::size_t look = Textured() ? LookHash(LookOf(e)) : 0;
        const auto it = g_actors.find(key);
        if (it == g_actors.end() || !it->second.actor || it->second.look != look) g_to_spawn.push_back(e);
    }
    for (const auto& [key, shown] : g_actors) {
        if (!wanted.count(key)) g_to_destroy.push_back(key);
    }
}

}  // namespace

void Update(std::uintptr_t world, const std::string& map, const bridge::BlockList& table, const shape_data::Frame* shapes) {
    if (!g_ready.load(std::memory_order_acquire) && !g_failed) Resolve();
    if (!g_ready.load(std::memory_order_acquire)) return;
    if (shapes && !ResolveShapes()) shapes = nullptr;
    const auto shape_revision = shapes ? shapes->header.revision : 0, shape_mc = shapes ? shapes->source_mc : 0;
    const auto shape_save = shapes ? shapes->header.save_key : 0;
    const auto shape_world = shapes ? shapes->header.world : 0;
    if (world != g_published_world || map != g_published_map || shape_mc != g_published_shape_mc || !shapes ||
        shape_save != g_published_shape_save || shape_world != g_published_shape_world) {
        g_shape_requested_scope.store(shapes ? ++g_shape_scope_counter : 0,std::memory_order_release);
    }
    // Assets come and go with levels: looked up for each world, and again while the cube is missing.
    const std::uint64_t now = GetTickCount64();
    if (world != g_assets_world || (!g_found.cube && now - g_assets_ms >= kAssetRetryMs)) {
        g_assets_world = world;
        g_assets_ms = now;
        g_found = FindAssets();
    }
    if (table.palette_generation != g_palette_generation) {
        g_palette_generation = table.palette_generation;
        g_palette_read = table.palette_generation ? ReadPalette() : std::vector<Look>{};
        SC_LOG("[blocks] palette %u: %zu block looks", g_palette_generation, g_palette_read.size());
    }
    if (world == g_published_world && table.seq == g_published_seq && map == g_published_map &&
        g_found.cube == g_published_cube && g_palette_generation == g_published_palette &&
        shape_revision == g_published_shape_revision && shape_mc == g_published_shape_mc &&
        shape_save == g_published_shape_save && shape_world == g_published_shape_world) {
        return;
    }
    g_published_world = world;
    g_published_seq = table.seq;
    g_published_map = map;
    g_published_cube = g_found.cube;
    g_published_palette = g_palette_generation;
    g_published_shape_revision = shape_revision; g_published_shape_mc = shape_mc;
    g_published_shape_save = shape_save; g_published_shape_world = shape_world;
    const bool ours = table.generation != 0 && table.map == map;
    Desired desired;
    desired.world = world;
    desired.version = g_next_version++;
    desired.assets = g_found;
    desired.palette = g_palette_read;
    if (shapes) desired.shapes = std::make_shared<shape_data::Frame>(*shapes);
    else if (ours && g_found.cube) desired.blocks = table.entries;
    g_shape_requested.store(shapes ? desired.version : 0,std::memory_order_release);
    desired.shape_scope = g_shape_requested_scope.load(std::memory_order_acquire);
    SC_LOG("[blocks] table %u for %s (%zu blocks); Sifu shows %s: %zu blocks wanted", table.generation,
           table.map.empty() ? "-" : table.map.c_str(), table.entries.size(), map.c_str(), desired.blocks.size());
    AcquireSRWLockExclusive(&g_lock);
    g_desired = std::move(desired);
    ReleaseSRWLockExclusive(&g_lock);
}

void ApplyOnGameThread(std::uintptr_t image_base) {
    if (!g_ready.load(std::memory_order_acquire)) return;
    const std::uintptr_t world = mem::ReadPtr(image_base + gen::rva::GWorld);
    if (world != g_world) {
        // The previous world destroyed its actors, the keeper and its materials with itself.
        g_actors.clear();
        g_shape_actors.clear();g_shape_components.clear(); g_shape_spawns.clear(); g_shape_updates.clear(); g_shape_destroys.clear();
        g_shape_applied.store(0,std::memory_order_release);
        g_shape_presentation_scope.store(0,std::memory_order_release);
        g_shape_logged = 0;g_shape_update_logged=0; g_shape_retry_at = 0;
        g_to_spawn.clear();
        g_to_destroy.clear();
        g_materials.clear();
        g_keeper = 0;
        g_keeper_slots = 0;
        g_version = 0;
        g_world = world;
        g_logged_spawns = 0;
        g_logged_failures = 0;
    }
    if (!world) return;
    AcquireSRWLockShared(&g_lock);
    if (g_desired.world == world && g_desired.version != g_version) Plan(g_desired);
    ReleaseSRWLockShared(&g_lock);

    int frame_destroys = 0;
    for (; frame_destroys < kDestroysPerFrame && !g_to_destroy.empty(); ++frame_destroys) {
        const auto it = g_actors.find(g_to_destroy.back());
        g_to_destroy.pop_back();
        if (it == g_actors.end()) continue;
        Destroy(it->second.actor);
        g_actors.erase(it);
        g_stats.destroyed.fetch_add(1, std::memory_order_relaxed);
    }
    g_materials_this_frame = 0;
    for (int n = 0; n < kSpawnsPerFrame && !g_to_spawn.empty(); ++n) {
        const BlockEntry e = g_to_spawn.back();
        const Look* look = Textured() ? LookOf(e) : nullptr;
        // New textures are imported a few per frame; a block needing more waits (but one always goes).
        if (g_materials_this_frame > 0 && g_materials_this_frame + MissingMaterials(look) > kMaterialsPerFrame) break;
        g_to_spawn.pop_back();
        const auto old = g_actors.find(Key(e));
        if (old != g_actors.end() && old->second.actor) {  // its look changed: replaced
            Destroy(old->second.actor);
            g_stats.destroyed.fetch_add(1, std::memory_order_relaxed);
        }
        const std::uintptr_t actor = g_assets.cube ? Spawn(e, look) : 0;
        g_actors[Key(e)] = Shown{actor, LookHash(look)};  // a failed one is not retried until the table changes
        if (actor) LogSpawned(e, actor);
        (actor ? g_stats.spawned : g_stats.failed).fetch_add(1, std::memory_order_relaxed);
    }
    g_stats.shown.store(g_actors.size(), std::memory_order_relaxed);
    // The legacy path spawns only outside shape mode; aggregate spawn/destroy budgets stay bounded.
    int destroys = kDestroysPerFrame-frame_destroys;
    while (destroys-- > 0 && !g_shape_destroys.empty()) {
        auto key = g_shape_destroys.back(); g_shape_destroys.pop_back();
        auto found = g_shape_actors.find(key);
        if (found != g_shape_actors.end()) { DestroyShape(found->second); g_shape_actors.erase(found); ++frame_destroys; }
    }
    if (g_shape_mode && g_actors.empty()) {
        const auto began = std::chrono::steady_clock::now();
        static auto previous = began;
        // Spend at most a quarter of the observed frame period, within 2..8ms.
        // A fixed 2ms budget cannot keep up with 20Hz animation when the renderer
        // itself runs at 25..30Hz. The count cap still bounds recovery after stalls.
        const auto budget=std::clamp(std::chrono::duration_cast<std::chrono::microseconds>((began-previous)/4),kShapeUpdateMinBudget,kShapeUpdateMaxBudget);
        previous=began;
        for (int n = 0; n < kShapeUpdatesPerFrame && !g_shape_updates.empty(); ++n) {
            if (n && std::chrono::steady_clock::now() - began >= budget) break;
            const auto update = g_shape_updates.back(); g_shape_updates.pop_back();
            const auto& wanted=update.wanted;
            auto old = g_shape_actors.find(update.from);
            if (old == g_shape_actors.end() || (update.from!=wanted.key&&g_shape_actors.count(wanted.key)) || !UpdateShape(old->second,wanted)) {
                g_shape_spawns.push_back(wanted);
                if(update.from!=wanted.key)g_shape_destroys.push_back(update.from);
            } else if(update.from!=wanted.key) {
                // UpdateShape verifies the new bounds and advances geometry_version,
                // invalidating motion proofs for the old key before it can be reused.
                auto node=g_shape_actors.extract(old);
                g_shape_components[node.mapped().component.pointer]=wanted.key;
                node.key()=wanted.key;g_shape_actors.insert(std::move(node));
            }
        }
    }
    if (g_shape_mode && g_actors.empty() && GetTickCount64() >= g_shape_retry_at) for (int n = 0; n < kSpawnsPerFrame && !g_shape_spawns.empty(); ++n) {
        const auto wanted = g_shape_spawns.back();
        auto old = g_shape_actors.find(wanted.key);
        if (old != g_shape_actors.end() && frame_destroys >= kDestroysPerFrame) break;
        // Build a replacement before removing this object's previous valid collision.
        ShapeShown shown = SpawnShape(wanted);
        if (!OwnedShape(shown)) { g_stats.failed.fetch_add(1); g_shape_retry_at=GetTickCount64()+1000; break; } // retry; never acknowledge a partial snapshot
        if (old != g_shape_actors.end()) { DestroyShape(old->second); ++frame_destroys; }
        g_shape_actors[wanted.key] = shown;g_shape_components[shown.component.pointer]=wanted.key; g_shape_spawns.pop_back();
    }
    g_stats.shapes.store(g_shape_actors.size());
    if (g_shape_mode && g_actors.empty() && g_shape_updates.empty() && g_shape_spawns.empty() && g_shape_destroys.empty()) {
        g_shape_applied.store(g_version,std::memory_order_release);
        g_shape_revision_done.store(g_shape_active_revision,std::memory_order_release);
        g_shape_presentation_scope.store(g_shape_game_scope,std::memory_order_release);
    }
}

bool ShapesReady() {
    const auto wanted = g_shape_requested_scope.load(std::memory_order_acquire);
    return wanted && wanted == g_shape_presentation_scope.load(std::memory_order_acquire);
}
bool ShapesSettled(){
    return ShapesReady()&&g_shape_requested.load(std::memory_order_acquire)==g_shape_applied.load(std::memory_order_acquire);
}
std::uint64_t OwnedBaseVersion(std::uintptr_t component){
    auto index=g_shape_components.find(component);if(index==g_shape_components.end())return 0;
    auto found=g_shape_actors.find(index->second);
    return found!=g_shape_actors.end()&&found->second.component.pointer==component&&OwnedShape(found->second)?found->second.geometry_version:0;
}
std::uint64_t ShapesAppliedRevision() { return g_shape_revision_done.load(std::memory_order_acquire); }

std::string TakeStats() {
    return FormatString("shown=%llu spawned=%llu destroyed=%llu failed=%llu nav_modifiers=%llu shapes_ready=%d shape_boxes=%llu shape_created=%llu shape_removed=%llu shape_updated=%llu",
                        static_cast<unsigned long long>(g_stats.shown.load()),
                        static_cast<unsigned long long>(g_stats.spawned.exchange(0)),
                        static_cast<unsigned long long>(g_stats.destroyed.exchange(0)),
                        static_cast<unsigned long long>(g_stats.failed.exchange(0)),
                        static_cast<unsigned long long>(g_stats.nav_modifiers.exchange(0)),ShapesReady(),
                        static_cast<unsigned long long>(g_stats.shapes.load()),
                        static_cast<unsigned long long>(g_stats.shapes_created.exchange(0)),
                        static_cast<unsigned long long>(g_stats.shapes_removed.exchange(0)),
                        static_cast<unsigned long long>(g_stats.shapes_updated.exchange(0)));
}

}  // namespace sifucraft::blocks
