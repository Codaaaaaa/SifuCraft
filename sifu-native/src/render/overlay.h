#pragma once
#include <cstdint>
#include <string>
#include "render/overlay_status.h"

namespace sifucraft::render {
// Worker thread: guarded pointer discovery, CAS install/self-test, context publication.
void Maintain(std::uintptr_t image_base, const Scope& scope);
std::string TakeStats();
// Latest successful Present of a composited frame; never acquires the GPU resource lock.
Presentation LastPresentation();
// Process-exit notification only: no waiting, GPU calls, COM destruction or locks in DllMain.
void OnProcessExit();
}
