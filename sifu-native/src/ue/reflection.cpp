#include "ue/reflection.h"

#include "core/log.h"
#include "core/safe_memory.h"
#include "ue/names.h"
#include "ue/objects.h"

namespace sifucraft::ue {

namespace {

namespace off = gen::off;

constexpr int kMaxFieldsPerStruct = 4096;
constexpr int kMaxSuperDepth = 64;

std::string FieldName(std::uintptr_t field) {
    return NameToString(mem::ReadOr<FName>(field + off::FField::NamePrivate, FName{}));
}

std::string FieldClassName(std::uintptr_t field) {
    const std::uintptr_t field_class = mem::ReadPtr(field + off::FField::ClassPrivate);
    if (!field_class) return "?";
    return NameToString(mem::ReadOr<FName>(field_class + off::FFieldClass::Name, FName{}));
}

std::string Describe(std::uintptr_t property, const std::string& type, int depth = 0) {
    if (type == "ObjectProperty" || type == "ClassProperty" || type == "WeakObjectProperty" ||
        type == "LazyObjectProperty" || type == "SoftObjectProperty" || type == "SoftClassProperty") {
        return ObjectName(mem::ReadPtr(property + off::FObjectPropertyBase::PropertyClass));
    }
    if (type == "StructProperty") return ObjectName(mem::ReadPtr(property + off::FStructProperty::Struct));
    if (type == "ArrayProperty" && depth < 4) {
        const std::uintptr_t inner = mem::ReadPtr(property + off::FArrayProperty::Inner);
        if (!inner) return "?";
        const std::string inner_type = FieldClassName(inner);
        const std::string inner_detail = Describe(inner, inner_type, depth + 1);
        return inner_detail.empty() ? inner_type : inner_type + "<" + inner_detail + ">";
    }
    return {};
}

template <typename Visit>
void ForEachDeclared(std::uintptr_t ustruct, Visit&& visit) {
    std::uintptr_t field = mem::ReadPtr(ustruct + off::UStruct::ChildProperties);
    for (int i = 0; field && i < kMaxFieldsPerStruct; ++i) {
        if (!visit(field)) return;
        field = mem::ReadPtr(field + off::FField::Next);
    }
}

}  // namespace

std::vector<PropertyInfo> ListProperties(std::uintptr_t ustruct) {
    std::vector<PropertyInfo> result;
    for (int depth = 0; ustruct && depth < kMaxSuperDepth; ++depth, ustruct = SuperOf(ustruct)) {
        const std::string owner = ObjectName(ustruct);
        ForEachDeclared(ustruct, [&](std::uintptr_t field) {
            PropertyInfo info;
            info.owner = owner;
            info.name = FieldName(field);
            info.type = FieldClassName(field);
            info.offset = mem::ReadOr<std::int32_t>(field + off::FProperty::Offset_Internal, -1);
            info.element_size = mem::ReadOr<std::int32_t>(field + off::FProperty::ElementSize, 0);
            info.array_dim = mem::ReadOr<std::int32_t>(field + off::FProperty::ArrayDim, 0);
            info.detail = Describe(field, info.type);
            info.field = field;
            result.push_back(std::move(info));
            return true;
        });
    }
    return result;
}

std::int32_t FindPropertyOffset(std::uintptr_t ustruct, std::string_view name) {
    std::int32_t found = -1;
    for (int depth = 0; ustruct && depth < kMaxSuperDepth && found < 0; ++depth, ustruct = SuperOf(ustruct)) {
        ForEachDeclared(ustruct, [&](std::uintptr_t field) {
            if (FieldName(field) != name) return true;
            found = mem::ReadOr<std::int32_t>(field + off::FProperty::Offset_Internal, -1);
            return false;
        });
    }
    return found;
}

void DumpStruct(TextFile& file, std::uintptr_t ustruct) {
    std::string chain;
    std::uintptr_t s = ustruct;
    for (int depth = 0; s && depth < kMaxSuperDepth; ++depth, s = SuperOf(s)) {
        if (!chain.empty()) chain += " <- ";
        chain += ObjectName(s);
    }
    const auto size = mem::ReadOr<std::int32_t>(ustruct + off::UStruct::PropertiesSize, 0);
    std::string text = FormatString("=== %s (size 0x%X)\n    %s\n", PathName(ustruct).c_str(), size, chain.c_str());
    std::string owner;
    for (const PropertyInfo& p : ListProperties(ustruct)) {
        if (p.owner != owner) {
            owner = p.owner;
            text += "  [" + owner + "]\n";
        }
        text += FormatString("    0x%04X  %4d%s  %-22s %s%s%s\n", p.offset, p.element_size,
                             p.array_dim > 1 ? FormatString("x%d", p.array_dim).c_str() : "", p.type.c_str(),
                             p.name.c_str(), p.detail.empty() ? "" : " : ", p.detail.c_str());
    }
    file.Write(text + "\n");
}

}  // namespace sifucraft::ue
