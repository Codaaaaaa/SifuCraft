#pragma once
#include <cstdint>
#include <string>
#include "render/overlay_status.h"

namespace sifucraft::ui {
// Worker publishes context only. All reflected metadata and game calls stay on H1.
void UpdateHudContext(std::uintptr_t world, std::uintptr_t controller, std::uintptr_t pawn, const render::Scope& scope);
void ApplyHudOnGameThread(std::uintptr_t image_base, bool owns_input, const render::Presentation& presentation);
std::string TakeHudStats();
}
