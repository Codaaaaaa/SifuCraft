#pragma once

#include <cstdint>
#include <limits>
#include <unordered_map>

namespace sifucraft::combat {

// Runtime-thread identity bookkeeping. Range selection is deliberately separate: walking out of
// the 30 m publishing radius and back does not create a new activation of the same actor.
class ActivationTracker {
public:
    std::uint32_t Observe(std::uint32_t id, std::int32_t serial, bool active) {
        if (serial <= 0) return 0;
        auto& state = states_[id];
        if (state.serial != serial) state = {serial, 0, false};
        if (active && !state.active) {
            // Fail closed instead of wrapping into the identity of an old activation.
            if (next_spawn_ == std::numeric_limits<std::uint32_t>::max()) return 0;
            state.spawn = ++next_spawn_;
        }
        state.active = active;
        return active ? state.spawn : 0;
    }
    void Forget(std::uint32_t id) { states_.erase(id); }
    void Reset() { states_.clear(); } // preserve the serial across world resets

private:
    struct State { std::int32_t serial = 0; std::uint32_t spawn = 0; bool active = false; };
    std::unordered_map<std::uint32_t, State> states_;
    std::uint32_t next_spawn_ = 0;
};

}  // namespace sifucraft::combat
