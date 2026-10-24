#pragma once

#include <cstdint>
#include <string_view>

// Calling reflected (Blueprint-callable) functions through UObject::ProcessEvent. The parameter
// block layout comes from the UFunction's reflected properties, never from hard-coded offsets.
namespace sifucraft::ue {

// UFunction named `name` declared on `ustruct` or one of its supers; 0 if none.
std::uintptr_t FindFunction(std::uintptr_t ustruct, std::string_view name);

class FunctionCall {
public:
    // Finds `function_name` on `ustruct` (or a super) and reads its parameter block size.
    bool Resolve(std::uintptr_t ustruct, std::string_view function_name);

    bool resolved() const { return function_ != 0; }
    std::uintptr_t function() const { return function_; }
    std::int32_t params_size() const { return params_size_; }

    // Offset of a parameter in the block, or -1.
    std::int32_t ParamOffset(std::string_view name) const;

    // Game thread only. `params` must hold params_size() bytes laid out per ParamOffset().
    void Invoke(std::uintptr_t object, void* params) const;

private:
    std::uintptr_t function_ = 0;
    std::int32_t params_size_ = 0;
};

}  // namespace sifucraft::ue
