#include "ue/function_call.h"

#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/reflection.h"

namespace sifucraft::ue {

namespace {

namespace off = gen::off;

constexpr int kMaxFieldsPerStruct = 8192;
constexpr int kMaxSuperDepth = 64;

}  // namespace

std::uintptr_t FindFunction(std::uintptr_t ustruct, std::string_view name) {
    for (int depth = 0; ustruct && depth < kMaxSuperDepth; ++depth, ustruct = SuperOf(ustruct)) {
        std::uintptr_t field = mem::ReadPtr(ustruct + off::UStruct::Children);
        for (int i = 0; field && i < kMaxFieldsPerStruct; ++i) {
            if (NameEntry(NameOf(field).comparison_index) == name && ClassName(field) == "Function") return field;
            field = mem::ReadPtr(field + off::UField::Next);
        }
    }
    return 0;
}

bool FunctionCall::Resolve(std::uintptr_t ustruct, std::string_view function_name) {
    function_ = FindFunction(ustruct, function_name);
    params_size_ = function_ ? mem::ReadOr<std::int32_t>(function_ + off::UStruct::PropertiesSize, -1) : -1;
    if (params_size_ < 0) function_ = 0;
    return function_ != 0;
}

std::int32_t FunctionCall::ParamOffset(std::string_view name) const {
    return function_ ? FindPropertyOffset(function_, name) : -1;
}

void FunctionCall::Invoke(std::uintptr_t object, void* params) const {
    using ProcessEventFn = void (*)(void* object, void* function, void* params);
    const auto vtable = *reinterpret_cast<const std::uintptr_t*>(object);
    const auto process_event =
        *reinterpret_cast<const ProcessEventFn*>(vtable + gen::slot::UObject::ProcessEvent);
    process_event(reinterpret_cast<void*>(object), reinterpret_cast<void*>(function_), params);
}

}  // namespace sifucraft::ue
