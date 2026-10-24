#pragma once

#include <cstdint>

// Main loop of the Sifu-side module: 20 Hz player capture + bridge tick, 1 Hz detailed probe log,
// periodic bridge/performance statistics.
namespace sifucraft::runtime {

[[noreturn]] void Run(std::uintptr_t image_base);

// Called from DllMain during process exit: tells the peer we are going away.
void OnProcessExit();

}  // namespace sifucraft::runtime
