#pragma once
#include <cstdint>
#include <string>
#include "ue/objects.h"

namespace sifucraft::hooks::streaming_guard {
struct Context {
    std::uintptr_t world = 0, pawn = 0;
    ue::ObjectIdentity world_identity, pawn_identity;
    std::uint64_t updated_ms = 0;
    bool active = false, freeze = false;
};
// Runtime thread: immutable reflected call and the next game-thread request.
void Resolve();
void UpdateContext(const Context& context);
// H1 before pose writes: the only place that invokes the approved game function.
void ApplyOnGameThread(std::uintptr_t base, int stage);
std::string TakeStats();
}
