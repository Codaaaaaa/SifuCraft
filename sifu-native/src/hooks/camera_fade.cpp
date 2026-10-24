#include "hooks/camera_fade.h"

#include <windows.h>

#include <atomic>
#include <cstring>
#include <string>

#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/function_call.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/reflection.h"

namespace sifucraft::hooks::camera_fade {

namespace {

namespace off = gen::off;

constexpr char kCollection[] = "MPC_GameplayGeneric";
constexpr const char* kParameters[] = {"ENV | CamFade", "PLAYER | Player Distance from Camera"};
constexpr int kCount = 2;
constexpr std::uint64_t kRetryMs = 5000;
constexpr std::size_t kMaxParams = 128;

struct Calls {
    std::uintptr_t library = 0;     // UKismetMaterialLibrary default object
    std::uintptr_t collection = 0;  // MPC_GameplayGeneric
    ue::FName names[kCount]{};
    ue::FunctionCall set, get;
    std::int32_t set_world = -1, set_collection = -1, set_name = -1, set_value = -1;
    std::int32_t get_world = -1, get_collection = -1, get_name = -1, get_return = -1;
};
Calls g_calls;
std::atomic<bool> g_ready{false};
bool g_failed = false;
std::uint64_t g_last_try_ms = 0;

// Game thread only.
bool g_overriding = false;
float g_saved[kCount]{};
alignas(16) std::uint8_t g_params[kMaxParams];

std::int32_t Param(const ue::FunctionCall& call, const char* name, std::size_t size) {
    const std::int32_t at = call.ParamOffset(name);
    return at >= 0 && at + static_cast<std::int32_t>(size) <= call.params_size() ? at : -1;
}

// The collection and the FNames of its parameters we hold.
bool FindCollection(Calls* c) {
    std::uintptr_t scalar_struct = 0;
    const std::int32_t count = ue::ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ue::ObjectAt(i);
        if (!object) continue;
        if (!c->collection && ue::ObjectName(object) == kCollection && ue::ClassName(object) == "MaterialParameterCollection" &&
            ue::IsLive(object)) {
            c->collection = object;
        } else if (!scalar_struct && ue::ObjectName(object) == "CollectionScalarParameter" &&
                   ue::ClassName(object) == "ScriptStruct") {
            scalar_struct = object;
        }
    }
    if (!c->collection || !scalar_struct) return false;
    const std::int32_t stride = mem::ReadOr<std::int32_t>(scalar_struct + off::UStruct::PropertiesSize, 0);
    const std::int32_t name_at = ue::FindPropertyOffset(scalar_struct, "ParameterName");
    const std::int32_t array_at = ue::FindPropertyOffset(ue::ClassOf(c->collection), "ScalarParameters");
    mem::RawArray a;
    if (stride <= 0 || name_at < 0 || array_at < 0 || !mem::ReadArray(c->collection + array_at, &a)) return false;
    int found = 0;
    for (std::int32_t p = 0; p < a.num; ++p) {
        const auto name = mem::ReadOr<ue::FName>(a.data + static_cast<std::uintptr_t>(p) * stride + name_at, {});
        const std::string text = ue::NameToString(name);
        for (int k = 0; k < kCount; ++k) {
            if (text == kParameters[k]) {
                c->names[k] = name;
                ++found;
            }
        }
    }
    return found == kCount;
}

void Clear(const ue::FunctionCall& call) { std::memset(g_params, 0, static_cast<std::size_t>(call.params_size())); }

template <typename T>
void Put(std::int32_t at, const T& value) {
    std::memcpy(g_params + at, &value, sizeof(T));
}

void Set(std::uintptr_t world_context, int k, float value) {
    const Calls& c = g_calls;
    Clear(c.set);
    Put(c.set_world, world_context);
    Put(c.set_collection, c.collection);
    Put(c.set_name, c.names[k]);
    Put(c.set_value, value);
    c.set.Invoke(c.library, g_params);
}

float Get(std::uintptr_t world_context, int k) {
    const Calls& c = g_calls;
    Clear(c.get);
    Put(c.get_world, world_context);
    Put(c.get_collection, c.collection);
    Put(c.get_name, c.names[k]);
    c.get.Invoke(c.library, g_params);
    float value = 0.0f;
    std::memcpy(&value, g_params + c.get_return, sizeof(value));
    return value;
}

}  // namespace

void Resolve() {
    if (g_ready.load(std::memory_order_acquire) || g_failed) return;
    const std::uint64_t now = GetTickCount64();
    if (now - g_last_try_ms < kRetryMs) return;
    g_last_try_ms = now;
    Calls c;
    const std::uintptr_t library = ue::FindClass("KismetMaterialLibrary");
    c.library = ue::DefaultObject(library);
    if (!library || !c.library || !c.set.Resolve(library, "SetScalarParameterValue") ||
        !c.get.Resolve(library, "GetScalarParameterValue")) {
        SC_LOG("[camera-fade] REFUSED: material library functions not found");
        g_failed = true;
        return;
    }
    c.set_world = Param(c.set, "WorldContextObject", 8);
    c.set_collection = Param(c.set, "Collection", 8);
    c.set_name = Param(c.set, "ParameterName", sizeof(ue::FName));
    c.set_value = Param(c.set, "ParameterValue", 4);
    c.get_world = Param(c.get, "WorldContextObject", 8);
    c.get_collection = Param(c.get, "Collection", 8);
    c.get_name = Param(c.get, "ParameterName", sizeof(ue::FName));
    c.get_return = Param(c.get, "ReturnValue", 4);
    if (c.set_world < 0 || c.set_collection < 0 || c.set_name < 0 || c.set_value < 0 || c.get_world < 0 ||
        c.get_collection < 0 || c.get_name < 0 || c.get_return < 0 || c.set.params_size() > static_cast<std::int32_t>(kMaxParams) ||
        c.get.params_size() > static_cast<std::int32_t>(kMaxParams)) {
        SC_LOG("[camera-fade] REFUSED: unexpected parameter layout");
        g_failed = true;
        return;
    }
    if (!FindCollection(&c)) return;  // not loaded yet: try again later
    g_calls = c;
    SC_LOG("[camera-fade] ready: %s holds \"%s\" and \"%s\" while Minecraft owns the view", kCollection, kParameters[0],
           kParameters[1]);
    g_ready.store(true, std::memory_order_release);
}

void AfterCameraUpdate(std::uintptr_t camera_manager, bool minecraft_view) {
    if (!g_ready.load(std::memory_order_acquire) || !ue::IsLive(g_calls.collection)) return;
    if (minecraft_view) {
        if (!g_overriding) {
            for (int k = 0; k < kCount; ++k) g_saved[k] = Get(camera_manager, k);
            g_overriding = true;
        }
        for (int k = 0; k < kCount; ++k) Set(camera_manager, k, 0.0f);
    } else if (g_overriding) {
        for (int k = 0; k < kCount; ++k) Set(camera_manager, k, g_saved[k]);
        g_overriding = false;
    }
}

}  // namespace sifucraft::hooks::camera_fade
