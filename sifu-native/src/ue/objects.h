#pragma once

#include <cstdint>
#include <string>
#include <string_view>

#include "ue/types.h"

// Read-only access to GUObjectArray and basic UObject fields.
namespace sifucraft::ue {

// Succeeds once GUObjectArray holds objects and its chunk table looks sane.
bool InitObjects(std::uintptr_t image_base);

std::int32_t ObjectCount();

// UObject* at `index` (0 if empty); `internal_flags` receives FUObjectItem::Flags.
std::uintptr_t ObjectAt(std::int32_t index, std::int32_t* internal_flags = nullptr);

// The object a TWeakObjectPtr / FWeakObjectPtr at `weak` refers to, or 0 if it is stale or unset.
std::uintptr_t ResolveWeak(std::uintptr_t weak);

struct ObjectIdentity {
    std::int32_t index = -1;
    std::int32_t serial = 0;
    bool valid() const { return index >= 0 && serial > 0; }
    bool operator==(const ObjectIdentity&) const = default;
};
// GUObjectArray serial distinguishes a new object that reuses the same address/internal index.
ObjectIdentity IdentityOf(std::uintptr_t object);

std::uintptr_t ClassOf(std::uintptr_t object);
std::uintptr_t OuterOf(std::uintptr_t object);
std::uintptr_t SuperOf(std::uintptr_t ustruct);
std::uint32_t FlagsOf(std::uintptr_t object);
FName NameOf(std::uintptr_t object);

std::string ObjectName(std::uintptr_t object);
std::string ClassName(std::uintptr_t object);
// Outer chain joined with '.', e.g. "/Script/Engine.Actor".
std::string PathName(std::uintptr_t object);

// False for CDOs/archetypes and objects that are pending kill or unreachable.
bool IsLive(std::uintptr_t object);

bool IsChildOf(std::uintptr_t ustruct, std::uintptr_t base);
bool IsA(std::uintptr_t object, std::uintptr_t ustruct);

// Native UClass by short name (e.g. "Actor"); cached after the first full scan hit.
std::uintptr_t FindClass(std::string_view name);

// The class default object of `cls` (static functions of a library class are called on it); 0 if
// there is none. Scans the object array.
std::uintptr_t DefaultObject(std::uintptr_t cls);

}  // namespace sifucraft::ue
