#pragma once
#include <cstdint>
#include <algorithm>
#include <cmath>
#include "sifucraft_protocol.h"

namespace sifucraft::interaction {
inline bool InRange(const protocol::Vec3& eye, const protocol::InteractionSnapshot& target) {
    float distance = 0;
    const float e[] = {eye.x, eye.y, eye.z}, lo[] = {target.minimum.x, target.minimum.y, target.minimum.z},
        hi[] = {target.maximum.x, target.maximum.y, target.maximum.z};
    if (!std::isfinite(target.range_cm) || target.range_cm <= 0 || target.range_cm > 1000) return false;
    for (int i = 0; i < 3; ++i) {
        if (!std::isfinite(e[i]) || !std::isfinite(lo[i]) || !std::isfinite(hi[i]) || lo[i] > hi[i]) return false;
        const float delta = e[i] - std::clamp(e[i], lo[i], hi[i]); distance += delta * delta;
    }
    return distance <= target.range_cm * target.range_cm;
}
class Requests {
public:
    bool Claim(std::uint64_t session, std::uint64_t id) {
        if (!session || !id) return false;
        if (session != session_) { session_ = session; last_ = 0; }
        if (id <= last_) return false;
        last_ = id; return true;
    }
private:
    std::uint64_t session_ = 0, last_ = 0;
};
}
