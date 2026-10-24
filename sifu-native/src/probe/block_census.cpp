#include "probe/block_census.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <map>
#include <string>
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

namespace sifucraft::probe {

namespace {

namespace off = gen::off;

constexpr std::size_t kMaxListed = 25;

struct FunctionWanted {
    const char* owner;
    const char* name;
};

constexpr FunctionWanted kFunctions[] = {
    {"GameplayStatics", "BeginDeferredActorSpawnFromClass"},
    {"GameplayStatics", "FinishSpawningActor"},
    {"SceneComponent", "SetMobility"},
    {"StaticMeshComponent", "SetStaticMesh"},
    {"PrimitiveComponent", "SetMaterial"},
    {"PrimitiveComponent", "SetCollisionEnabled"},
    {"PrimitiveComponent", "SetCollisionObjectType"},
    {"PrimitiveComponent", "SetCollisionResponseToAllChannels"},
    {"Actor", "K2_DestroyActor"},
};

std::string Lower(std::string text) {
    std::transform(text.begin(), text.end(), text.begin(), [](unsigned char c) { return std::tolower(c); });
    return text;
}

bool ContainsAny(const std::string& text, std::initializer_list<const char*> words) {
    return std::any_of(words.begin(), words.end(), [&](const char* w) { return text.find(w) != std::string::npos; });
}

void LogFunctions() {
    for (const FunctionWanted& f : kFunctions) {
        const std::uintptr_t owner = ue::FindClass(f.owner);
        ue::FunctionCall call;
        if (!owner || !call.Resolve(owner, f.name)) {
            SC_LOG("[blocks] %s::%s NOT FOUND", f.owner, f.name);
            continue;
        }
        std::string params;
        for (const ue::PropertyInfo& p : ue::ListProperties(call.function())) {
            params += FormatString(" %s:%s%s%s@%d(%d)", p.name.c_str(), p.type.c_str(), p.detail.empty() ? "" : "/",
                                   p.detail.c_str(), p.offset, p.element_size);
        }
        SC_LOG("[blocks] %s::%s params=%d |%s", f.owner, f.name, call.params_size(), params.c_str());
    }
}

void LogClasses() {
    const std::uintptr_t actor_class = ue::FindClass("StaticMeshActor");
    const std::uintptr_t statics = ue::FindClass("GameplayStatics");
    SC_LOG("[blocks] StaticMeshActor class 0x%llX (StaticMeshComponent @%d) | GameplayStatics class 0x%llX, default "
           "object 0x%llX",
           static_cast<unsigned long long>(actor_class),
           actor_class ? ue::FindPropertyOffset(actor_class, "StaticMeshComponent") : -1,
           static_cast<unsigned long long>(statics), static_cast<unsigned long long>(statics ? ue::DefaultObject(statics) : 0));
}

// Loaded cube meshes and stone-like materials, by path.
void LogAssets() {
    std::vector<std::string> cubes, materials;
    int meshes = 0, material_count = 0;
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object || (ue::FlagsOf(object) & ue::RF_ClassDefaultObject)) continue;
        const std::string cls = ue::ClassName(object);
        if (cls == "StaticMesh") {
            ++meshes;
            const std::string name = Lower(ue::ObjectName(object));
            if (cubes.size() < kMaxListed && ContainsAny(name, {"cube", "box", "block"})) {
                cubes.push_back(ue::PathName(object));
            }
        } else if (cls == "Material" || cls == "MaterialInstanceConstant") {
            ++material_count;
            const std::string name = Lower(ue::ObjectName(object));
            if (materials.size() < kMaxListed &&
                ContainsAny(name, {"stone", "rock", "concrete", "brick", "cement", "granite", "cobble", "basicshape"})) {
                materials.push_back(cls == "Material" ? ue::PathName(object) : ue::PathName(object) + " (instance)");
            }
        }
    }
    SC_LOG("[blocks] %d static meshes loaded; cube-like:", meshes);
    for (const std::string& path : cubes) SC_LOG("[blocks]   mesh %s", path.c_str());
    SC_LOG("[blocks] %d materials loaded; stone-like:", material_count);
    for (const std::string& path : materials) SC_LOG("[blocks]   material %s", path.c_str());
}

std::string Channels(const collision::Responses& r, std::uint8_t response) {
    std::string out;
    for (int c = 0; c < 32; ++c) {
        if (r.to_channel[c] == response) out += (out.empty() ? "" : ",") + std::to_string(c);
    }
    return out;
}

// How characters collide: the object type a block needs so that every capsule blocks it.
void LogCharacterCollision(std::uintptr_t world, std::uintptr_t pawn) {
    constexpr std::uint8_t kBlock = 2;
    const std::uintptr_t character_class = ue::FindClass("Character");
    std::map<std::string, int> kinds;  // "class type=N blocks=[...]" -> count
    mem::RawArray levels;
    if (!character_class || !mem::ReadArray(world + off::UWorld::Levels, &levels)) return;
    for (std::int32_t l = 0; l < levels.num; ++l) {
        const std::uintptr_t level = mem::ReadPtr(levels.data + l * sizeof(std::uintptr_t));
        mem::RawArray actors;
        if (!level || !mem::ReadArray(level + off::ULevel::Actors, &actors)) continue;
        for (std::int32_t a = 0; a < actors.num; ++a) {
            const std::uintptr_t actor = mem::ReadPtr(actors.data + a * sizeof(std::uintptr_t));
            if (!actor || !ue::IsA(actor, character_class) || !ue::IsLive(actor)) continue;
            const std::uintptr_t capsule = mem::ReadPtr(actor + off::AActor::RootComponent);
            if (!capsule) continue;
            const collision::Responses r = collision::ReadResponses(capsule);
            ++kinds[FormatString("%s%s type=%u blocks=[%s]", actor == pawn ? "PLAYER " : "", ue::ClassName(actor).c_str(),
                                 r.object_type, Channels(r, kBlock).c_str())];
        }
    }
    for (const auto& [kind, n] : kinds) SC_LOG("[blocks] capsule %s x%d", kind.c_str(), n);
}

// The bool / byte / enum / int / float properties of `object` named in `names` (all if empty) and
// their values.
std::string Scalars(std::uintptr_t object, std::initializer_list<const char*> names) {
    std::string out;
    for (const ue::PropertyInfo& p : ue::ListProperties(ue::ClassOf(object))) {
        if (names.size() && std::none_of(names.begin(), names.end(), [&](const char* n) { return p.name == n; })) {
            continue;
        }
        if (p.type == "BoolProperty") {
            namespace boolean = off::FBoolProperty;
            const auto byte = mem::ReadOr<std::uint8_t>(p.field + boolean::ByteOffset, 0);
            const auto mask = mem::ReadOr<std::uint8_t>(p.field + boolean::ByteMask, 0);
            out += FormatString(" %s=%d", p.name.c_str(), (mem::ReadOr<std::uint8_t>(object + p.offset + byte, 0) & mask) != 0);
        } else if (p.type == "ByteProperty" || p.type == "EnumProperty") {
            out += FormatString(" %s=%u", p.name.c_str(), mem::ReadOr<std::uint8_t>(object + p.offset, 0xFF));
        } else if (p.type == "IntProperty") {
            out += FormatString(" %s=%d", p.name.c_str(), mem::ReadOr<std::int32_t>(object + p.offset, -1));
        } else if (p.type == "FloatProperty") {
            out += FormatString(" %s=%g", p.name.c_str(), mem::ReadOr<float>(object + p.offset, NAN));
        }
    }
    return out;
}

// Whether the navigation mesh can change at runtime (ERuntimeGenerationType: 0 static, 1 dynamic
// modifiers only, 2 dynamic) and how the navigation system is set up.
void LogNavigation() {
    const std::int32_t count = ue::ObjectCount();
    int meshes = 0, systems = 0;
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object || !ue::IsLive(object)) continue;
        const std::string cls = ue::ClassName(object);
        if (cls == "RecastNavMesh" && meshes < 4) {
            ++meshes;
            SC_LOG("[blocks] nav %s:%s", ue::PathName(object).c_str(),
                   Scalars(object, {"RuntimeGeneration", "bForceRebuildOnLoad", "AgentRadius", "AgentHeight", "CellSize",
                                    "TileSizeUU", "bCanBeMainNavData"})
                       .c_str());
        } else if (cls == "NavigationSystemV1" && systems < 2) {
            ++systems;
            SC_LOG("[blocks] nav system %s (%s):%s", ue::PathName(object).c_str(), cls.c_str(),
                   Scalars(object, {}).c_str());
        }
    }
}

// How the enemies move: a character in NavWalking follows the navigation mesh without sweeping
// its capsule through the world, so only the navigation mesh can stop it.
void LogCharacterMovement(std::uintptr_t world, std::uintptr_t pawn) {
    const std::uintptr_t character_class = ue::FindClass("Character");
    std::map<std::string, int> kinds;
    mem::RawArray levels;
    if (!character_class || !mem::ReadArray(world + off::UWorld::Levels, &levels)) return;
    for (std::int32_t l = 0; l < levels.num; ++l) {
        const std::uintptr_t level = mem::ReadPtr(levels.data + l * sizeof(std::uintptr_t));
        mem::RawArray actors;
        if (!level || !mem::ReadArray(level + off::ULevel::Actors, &actors)) continue;
        for (std::int32_t a = 0; a < actors.num; ++a) {
            const std::uintptr_t actor = mem::ReadPtr(actors.data + a * sizeof(std::uintptr_t));
            if (!actor || actor == pawn || !ue::IsA(actor, character_class) || !ue::IsLive(actor)) continue;
            const std::uintptr_t movement = mem::ReadPtr(actor + off::ACharacter::CharacterMovement);
            if (!movement) continue;
            ++kinds[FormatString("%s mode=%u%s", ue::ClassName(movement).c_str(),
                                 mem::ReadOr<std::uint8_t>(movement + off::UCharacterMovementComponent::MovementMode, 0xFF),
                                 Scalars(movement, {"DefaultLandMovementMode", "GroundMovementMode",
                                                    "bProjectNavMeshWalking", "bProjectNavMeshOnBothWorldChannels",
                                                    "NavMeshProjectionInterval", "bUseRVOAvoidance"})
                                     .c_str())];
        }
    }
    for (const auto& [kind, n] : kinds) SC_LOG("[blocks] enemy movement %s x%d", kind.c_str(), n);
}

// Global material parameters (a camera fade would be driven from one) and Sifu's own classes that
// sound like hiding what is near the camera.
void LogCameraFade() {
    const std::int32_t count = ue::ObjectCount();
    std::uintptr_t scalar_struct = 0;
    for (std::int32_t i = 0; i < count && !scalar_struct; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (object && ue::ObjectName(object) == "CollectionScalarParameter" && ue::ClassName(object) == "ScriptStruct") {
            scalar_struct = object;
        }
    }
    const std::int32_t stride = scalar_struct ? mem::ReadOr<std::int32_t>(scalar_struct + off::UStruct::PropertiesSize, 0) : 0;
    const std::int32_t name_at = scalar_struct ? ue::FindPropertyOffset(scalar_struct, "ParameterName") : -1;
    const std::int32_t value_at = scalar_struct ? ue::FindPropertyOffset(scalar_struct, "DefaultValue") : -1;
    std::vector<std::string> classes;
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object) continue;
        const std::string cls = ue::ClassName(object);
        if (cls == "MaterialParameterCollection" && !(ue::FlagsOf(object) & ue::RF_ClassDefaultObject)) {
            std::string params;
            const std::int32_t array_at = ue::FindPropertyOffset(ue::ClassOf(object), "ScalarParameters");
            mem::RawArray a;
            if (stride > 0 && name_at >= 0 && value_at >= 0 && array_at >= 0 && mem::ReadArray(object + array_at, &a)) {
                for (std::int32_t p = 0; p < a.num && p < 60; ++p) {
                    const std::uintptr_t entry = a.data + static_cast<std::uintptr_t>(p) * stride;
                    params += FormatString(" %s=%g", ue::NameToString(mem::ReadOr<ue::FName>(entry + name_at, {})).c_str(),
                                           mem::ReadOr<float>(entry + value_at, NAN));
                }
            }
            SC_LOG("[blocks] parameter collection %s:%s", ue::PathName(object).c_str(), params.c_str());
        } else if (cls == "Class" || cls == "BlueprintGeneratedClass") {
            const std::string name = Lower(ue::ObjectName(object));
            if (classes.size() < kMaxListed &&
                ContainsAny(name, {"occlu", "fade", "dither", "hidecamera", "camerahide", "transparen", "seethrough"})) {
                classes.push_back(ue::PathName(object));
            }
        }
    }
    for (const std::string& path : classes) SC_LOG("[blocks] class %s", path.c_str());
}

// World-space (WS) materials: they tile by world position, so they suit any cube.
void LogWorldSpaceMaterials() {
    std::vector<std::string> found;
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count && found.size() < kMaxListed; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object || (ue::FlagsOf(object) & ue::RF_ClassDefaultObject)) continue;
        const std::string cls = ue::ClassName(object);
        if (cls != "Material" && cls != "MaterialInstanceConstant") continue;
        const std::string name = ue::ObjectName(object);
        const std::string lower = Lower(name);
        if (name.find("_WS") != std::string::npos &&
            ContainsAny(lower, {"stone", "rock", "brick", "cobble", "paving", "tile", "concrete"})) {
            found.push_back(name);
        }
    }
    for (const std::string& name : found) SC_LOG("[blocks] world-space material %s", name.c_str());
}

// For textured blocks: the functions that load an image and make material instances, the engine's
// plane mesh, and material instances with texture parameters (a base to put Minecraft's textures on).
void LogTexturing() {
    const FunctionWanted functions[] = {
        {"KismetRenderingLibrary", "ImportFileAsTexture2D"},
        {"KismetMaterialLibrary", "CreateDynamicMaterialInstance"},
        {"MaterialInstanceDynamic", "SetTextureParameterValue"},
        {"MaterialInstanceDynamic", "SetScalarParameterValue"},
        {"SceneComponent", "SetVisibility"},
    };
    for (const FunctionWanted& f : functions) {
        const std::uintptr_t owner = ue::FindClass(f.owner);
        ue::FunctionCall call;
        if (!owner || !call.Resolve(owner, f.name)) {
            SC_LOG("[blocks] %s::%s NOT FOUND", f.owner, f.name);
            continue;
        }
        std::string params;
        for (const ue::PropertyInfo& p : ue::ListProperties(call.function())) {
            params += FormatString(" %s:%s@%d(%d)", p.name.c_str(), p.type.c_str(), p.offset, p.element_size);
        }
        SC_LOG("[blocks] %s::%s params=%d |%s", f.owner, f.name, call.params_size(), params.c_str());
    }
    const std::int32_t count = ue::ObjectCount();
    std::uintptr_t texture_struct = 0, scalar_struct = 0;
    std::vector<std::string> planes;
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object) continue;
        const std::string name = ue::ObjectName(object);
        if (name == "TextureParameterValue" && ue::ClassName(object) == "ScriptStruct") texture_struct = object;
        if (name == "ScalarParameterValue" && ue::ClassName(object) == "ScriptStruct") scalar_struct = object;
        if ((name == "Plane" || name == "Cube") && ue::ClassName(object) == "StaticMesh") planes.push_back(ue::PathName(object));
    }
    for (const std::string& path : planes) SC_LOG("[blocks] engine mesh %s", path.c_str());
    if (!texture_struct) return;
    const std::int32_t stride = mem::ReadOr<std::int32_t>(texture_struct + off::UStruct::PropertiesSize, 0);
    const std::int32_t info_at = ue::FindPropertyOffset(texture_struct, "ParameterInfo");
    const std::int32_t value_at = ue::FindPropertyOffset(texture_struct, "ParameterValue");
    const std::int32_t scalar_stride = scalar_struct ? mem::ReadOr<std::int32_t>(scalar_struct + off::UStruct::PropertiesSize, 0) : 0;
    int listed = 0;
    for (std::int32_t i = 0; i < count && listed < 40; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object || !ue::IsLive(object) || ue::ClassName(object) != "MaterialInstanceConstant") continue;
        const std::string name = Lower(ue::ObjectName(object));
        if (!ContainsAny(name, {"simple", "placeholder", "basic", "generic", "default", "unlit", "debug", "prototype",
                                "blockout", "grid"})) {
            continue;
        }
        const std::uintptr_t cls = ue::ClassOf(object);
        const std::int32_t textures_at = ue::FindPropertyOffset(cls, "TextureParameterValues");
        const std::int32_t scalars_at = ue::FindPropertyOffset(cls, "ScalarParameterValues");
        const std::int32_t parent_at = ue::FindPropertyOffset(cls, "Parent");
        mem::RawArray textures, scalars;
        if (textures_at < 0 || !mem::ReadArray(object + textures_at, &textures) || textures.num == 0) continue;
        std::string params;
        for (std::int32_t t = 0; t < textures.num && t < 12; ++t) {
            const std::uintptr_t entry = textures.data + static_cast<std::uintptr_t>(t) * stride;
            const std::uintptr_t texture = mem::ReadPtr(entry + value_at);
            params += FormatString(" %s=%s", ue::NameToString(mem::ReadOr<ue::FName>(entry + info_at, {})).c_str(),
                                   texture ? ue::ObjectName(texture).c_str() : "none");
        }
        std::string scalar_names;
        if (scalar_stride > 0 && scalars_at >= 0 && mem::ReadArray(object + scalars_at, &scalars)) {
            for (std::int32_t t = 0; t < scalars.num && t < 12; ++t) {
                scalar_names += " " + ue::NameToString(mem::ReadOr<ue::FName>(
                                          scalars.data + static_cast<std::uintptr_t>(t) * scalar_stride + info_at, {}));
            }
        }
        const std::uintptr_t parent = parent_at >= 0 ? mem::ReadPtr(object + parent_at) : 0;
        SC_LOG("[blocks] base candidate %s (parent %s): textures%s | scalars%s", ue::ObjectName(object).c_str(),
               parent ? ue::ObjectName(parent).c_str() : "-", params.c_str(), scalar_names.c_str());
        ++listed;
    }
}

}  // namespace

void LogBlockCensus(std::uintptr_t world, std::uintptr_t pawn) {
    static bool logged_once = false;
    if (!logged_once) {
        logged_once = true;
        LogFunctions();
        LogClasses();
    }
    LogAssets();
    LogCharacterCollision(world, pawn);
    LogNavigation();
    LogCharacterMovement(world, pawn);
    LogCameraFade();
    LogWorldSpaceMaterials();
    LogTexturing();
}

}  // namespace sifucraft::probe
