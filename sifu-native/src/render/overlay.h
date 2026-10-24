#pragma once
#include <cstdint>
#include <string>

namespace sifucraft::render {
struct Scope {
    std::uint64_t mc_session = 0, sifu_session = 0;
    std::uint32_t world = 0, epoch = 0;
    std::uint64_t updated_ms = 0;
    bool enabled = false;
};
// Worker thread: guarded pointer discovery, CAS install/self-test, context publication.
void Maintain(std::uintptr_t image_base, const Scope& scope);
std::string TakeStats();
// Process-exit notification only: no waiting, GPU calls, COM destruction or locks in DllMain.
void OnProcessExit();
}
