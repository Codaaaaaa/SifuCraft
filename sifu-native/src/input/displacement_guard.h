#pragma once
#include <algorithm>
#include <cstdint>
#include "sifucraft_protocol.h"

namespace sifucraft::input {
// Read-only fallback for scripts without explicit cinematic/input flags.
class DisplacementGuard {
public:
    enum class Change { None, Yield, Recovered };
    void Reset(const protocol::Vec3& location, std::uint64_t now) {
        driving_ = false; released_ = 0; quiet_ms_ = kQuietMs; reference_ = location; since_ = now;
    }
    Change Update(bool minecraft, bool pose_active, bool has_pawn, const protocol::Vec3& location,
                  std::uint32_t consecutive, std::uint64_t now) {
        if (!driving_) {
            if (!minecraft || !pose_active || !has_pawn || consecutive < 3) return Change::None;
            driving_ = true; reference_ = location; since_ = now;
            quiet_ms_ = released_ && now >= released_ && now - released_ < 3000 ? std::min(quiet_ms_ * 2, std::uint64_t{8000}) : kQuietMs;
            return Change::Yield;
        }
        if (!has_pawn) return Change::None;
        const float dx = location.x - reference_.x, dy = location.y - reference_.y, dz = location.z - reference_.z;
        if (dx * dx + dy * dy + dz * dz > 1.0f) { reference_ = location; since_ = now; }
        else if (now >= since_ && now - since_ >= quiet_ms_) {
            driving_ = false; released_ = now; return Change::Recovered;
        }
        return Change::None;
    }
    bool ManualRecovery(std::uint64_t now) {
        if (!driving_) return false;
        driving_ = false; released_ = now; quiet_ms_ = kQuietMs;
        return true;
    }
    bool driving() const { return driving_; }
    std::uint64_t quiet_ms() const { return quiet_ms_; }
private:
    static constexpr std::uint64_t kQuietMs = 1000;
    bool driving_ = false;
    protocol::Vec3 reference_{};
    std::uint64_t since_ = 0, released_ = 0, quiet_ms_ = kQuietMs;
};
}
