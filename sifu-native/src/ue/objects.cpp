#include "ue/objects.h"

#include <unordered_map>

#include "core/safe_memory.h"
#include "ue/names.h"

namespace sifucraft::ue {

namespace {

namespace off = gen::off;

constexpr std::int32_t kElementsPerChunk = 64 * 1024;
constexpr int kMaxOuterDepth = 32;
constexpr int kMaxSuperDepth = 64;

std::uintptr_t g_array = 0;  // FChunkedFixedUObjectArray
std::uintptr_t g_chunks = 0;  // FUObjectItem** (allocated once, never moves)
std::unordered_map<std::string, std::uintptr_t> g_classes;

std::uintptr_t ItemAddress(std::int32_t index) {
    if (index < 0 || index >= ObjectCount()) return 0;
    const std::uintptr_t chunk = mem::ReadPtr(g_chunks + (index / kElementsPerChunk) * sizeof(std::uintptr_t));
    if (!chunk) return 0;
    return chunk + static_cast<std::uintptr_t>(index % kElementsPerChunk) * gen::size::FUObjectItem;
}

}  // namespace

bool InitObjects(std::uintptr_t image_base) {
    const std::uintptr_t array = image_base + gen::rva::GUObjectArray + off::FUObjectArray::ObjObjects;
    namespace chunked = off::FChunkedFixedUObjectArray;
    const auto chunks = mem::ReadPtr(array + chunked::Objects);
    const auto max_elements = mem::ReadOr<std::int32_t>(array + chunked::MaxElements, 0);
    const auto num_elements = mem::ReadOr<std::int32_t>(array + chunked::NumElements, 0);
    const auto max_chunks = mem::ReadOr<std::int32_t>(array + chunked::MaxChunks, 0);
    if (!chunks || num_elements <= 0 || num_elements > max_elements ||
        max_elements > static_cast<std::int64_t>(max_chunks) * kElementsPerChunk) {
        return false;
    }
    g_array = array;
    g_chunks = chunks;
    g_classes.clear();
    return true;
}

std::int32_t ObjectCount() {
    if (!g_array) return 0;
    return mem::ReadOr<std::int32_t>(g_array + off::FChunkedFixedUObjectArray::NumElements, 0);
}

std::uintptr_t ObjectAt(std::int32_t index, std::int32_t* internal_flags) {
    const std::uintptr_t item = ItemAddress(index);
    if (!item) return 0;
    if (internal_flags) *internal_flags = mem::ReadOr<std::int32_t>(item + off::FUObjectItem::Flags, 0);
    return mem::ReadPtr(item + off::FUObjectItem::Object);
}

std::uintptr_t ResolveWeak(std::uintptr_t weak) {
    const auto index = mem::ReadOr<std::int32_t>(weak + off::FWeakObjectPtr::ObjectIndex, -1);
    const auto serial = mem::ReadOr<std::int32_t>(weak + off::FWeakObjectPtr::ObjectSerialNumber, 0);
    const std::uintptr_t item = ItemAddress(index);
    if (!item || serial == 0) return 0;
    if (mem::ReadOr<std::int32_t>(item + off::FUObjectItem::SerialNumber, 0) != serial) return 0;
    return mem::ReadPtr(item + off::FUObjectItem::Object);
}

std::uintptr_t ClassOf(std::uintptr_t object) {
    return object ? mem::ReadPtr(object + off::UObjectBase::ClassPrivate) : 0;
}

ObjectIdentity IdentityOf(std::uintptr_t object) {
    if (!IsLive(object)) return {};
    const auto index = mem::ReadOr<std::int32_t>(object + off::UObjectBase::InternalIndex, -1);
    const auto item = ItemAddress(index);
    if (!item || mem::ReadPtr(item + off::FUObjectItem::Object) != object) return {};
    return {index, mem::ReadOr<std::int32_t>(item + off::FUObjectItem::SerialNumber, 0)};
}

std::uintptr_t OuterOf(std::uintptr_t object) {
    return object ? mem::ReadPtr(object + off::UObjectBase::OuterPrivate) : 0;
}

std::uintptr_t SuperOf(std::uintptr_t ustruct) {
    return ustruct ? mem::ReadPtr(ustruct + off::UStruct::SuperStruct) : 0;
}

std::uint32_t FlagsOf(std::uintptr_t object) {
    return object ? mem::ReadOr<std::uint32_t>(object + off::UObjectBase::ObjectFlags, 0) : 0;
}

FName NameOf(std::uintptr_t object) {
    return object ? mem::ReadOr<FName>(object + off::UObjectBase::NamePrivate, FName{}) : FName{};
}

std::string ObjectName(std::uintptr_t object) {
    if (!object) return "null";
    std::string name = NameToString(NameOf(object));
    return name.empty() ? "?" : name;
}

std::string ClassName(std::uintptr_t object) { return ObjectName(ClassOf(object)); }

std::string PathName(std::uintptr_t object) {
    std::string path = ObjectName(object);
    std::uintptr_t outer = OuterOf(object);
    for (int depth = 0; outer && depth < kMaxOuterDepth; ++depth) {
        path = ObjectName(outer) + "." + path;
        outer = OuterOf(outer);
    }
    return path;
}

bool IsLive(std::uintptr_t object) {
    if (!object) return false;
    if (FlagsOf(object) & (RF_ClassDefaultObject | RF_ArchetypeObject)) return false;
    const auto index = mem::ReadOr<std::int32_t>(object + off::UObjectBase::InternalIndex, -1);
    std::int32_t internal = 0;
    if (ObjectAt(index, &internal) != object) return false;
    return (internal & (kInternalPendingKill | kInternalUnreachable)) == 0;
}

bool IsChildOf(std::uintptr_t ustruct, std::uintptr_t base) {
    if (!base) return false;
    for (int depth = 0; ustruct && depth < kMaxSuperDepth; ++depth) {
        if (ustruct == base) return true;
        ustruct = SuperOf(ustruct);
    }
    return false;
}

bool IsA(std::uintptr_t object, std::uintptr_t ustruct) { return object && IsChildOf(ClassOf(object), ustruct); }

std::uintptr_t FindClass(std::string_view name) {
    const std::string key(name);
    if (auto it = g_classes.find(key); it != g_classes.end()) return it->second;
    const std::int32_t count = ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ObjectAt(i);
        if (!object || NameEntry(NameOf(object).comparison_index) != name) continue;
        if (ClassName(object) != "Class") continue;
        g_classes.emplace(key, object);
        return object;
    }
    return 0;
}

std::uintptr_t DefaultObject(std::uintptr_t cls) {
    if (!cls) return 0;
    const std::int32_t count = ObjectCount();
    for (std::int32_t i = 0; i < count; ++i) {
        const std::uintptr_t object = ObjectAt(i);
        if (object && ClassOf(object) == cls && (FlagsOf(object) & RF_ClassDefaultObject)) return object;
    }
    return 0;
}

}  // namespace sifucraft::ue
