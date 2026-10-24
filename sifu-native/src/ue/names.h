#pragma once

#include <cstdint>
#include <string>

#include "ue/types.h"

// Read-only decoding of the global FNamePool.
namespace sifucraft::ue {

// Succeeds once the pool is populated and entry 0 decodes to "None".
bool InitNames(std::uintptr_t image_base);

// Plain entry text for a comparison index; empty on failure. Results are cached.
const std::string& NameEntry(std::uint32_t index);

// Full FName text including the `_N` number suffix.
std::string NameToString(FName name);

}  // namespace sifucraft::ue
