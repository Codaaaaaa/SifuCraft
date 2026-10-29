#pragma once
#include <cstdint>
#include <string>

namespace sifucraft::ue {
// Read-only, PDB-bound access to the engine's UI language; no engine function calls.
std::string ReadLanguage(std::uintptr_t image_base);
}
