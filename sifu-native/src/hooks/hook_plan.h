#pragma once

#include <cstdint>

#include "hooks/vtable_hook.h"

namespace sifucraft::hooks {

// Resolves every planned hook against the current world's objects and logs the result without
// writing anything. Returns the number of targets that would be safe to patch.
int DryRun(std::uintptr_t image_base, std::uintptr_t world);

}  // namespace sifucraft::hooks
