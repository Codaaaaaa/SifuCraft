#pragma once

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace sifucraft {
class TextFile;
}

// Walks UStruct::ChildProperties (FField/FProperty) to read reflected layouts.
namespace sifucraft::ue {

struct PropertyInfo {
    std::string owner;   // struct that declares the property
    std::string name;
    std::string type;    // FFieldClass name, e.g. "FloatProperty"
    std::string detail;  // referenced class/struct/inner type, if any
    std::int32_t offset = 0;
    std::int32_t element_size = 0;
    std::int32_t array_dim = 0;
    std::uintptr_t field = 0;  // the FProperty itself
};

// Properties of `ustruct`, declared ones first, then each super struct's.
std::vector<PropertyInfo> ListProperties(std::uintptr_t ustruct);

// Offset of a reflected property (searching supers), or -1.
std::int32_t FindPropertyOffset(std::uintptr_t ustruct, std::string_view name);

// Writes a human-readable layout of `ustruct` and its supers.
void DumpStruct(TextFile& file, std::uintptr_t ustruct);

}  // namespace sifucraft::ue
