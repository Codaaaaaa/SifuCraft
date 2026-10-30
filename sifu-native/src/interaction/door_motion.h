#pragma once
#include <cmath>
#include <cstdint>
#include <string_view>

namespace sifucraft::interaction {
struct MotionScope {
    std::uint64_t session = 0;
    std::uint32_t world = 0, epoch = 0;
    std::int32_t pawn = -1, pawn_serial = 0, controller = -1, controller_serial = 0;
    bool valid() const { return session && world && epoch && pawn >= 0 && pawn_serial > 0 && controller >= 0 && controller_serial > 0; }
    bool operator==(const MotionScope&) const = default;
};
inline bool IsDoorClass(std::string_view name) {
    return name.find("Door") != name.npos || name.find("_door") != name.npos || name.starts_with("door");
}
// Only a dispatched, scoped door use can explain animation/root-motion corrections. A long door
// animation warps the pawn smoothly toward its slot (>50 cm over ~2 s), so the guard bounds the
// per-frame change, not the total. Snaps, timeout and ownership/lifetime changes restore the ordinary guard.
class DoorMotionWindow {
public:
    static constexpr std::uint64_t kDurationMs = 10000;
    static constexpr float kMaxCorrectionCm = 300.0f, kMaxStepCm = 20.0f;
    void Arm(const MotionScope& scope, std::uint64_t now, bool door) {
        Clear();
        if (!door || !scope.valid()) return;
        scope_ = scope; start_ = now; last_ = 0; armed_ = true;
    }
    void UpdateScope(const MotionScope& scope, bool pose_owned) {
        if (!pose_owned || scope != scope_) Clear();
    }
    bool Observe(const MotionScope& scope, std::uint64_t now, float correction_cm) {
        if (!armed_) return false;
        if (scope != scope_ || now < start_ || now - start_ >= kDurationMs ||
            !std::isfinite(correction_cm) || correction_cm < 0 || correction_cm > kMaxCorrectionCm ||
            std::fabs(correction_cm - last_) > kMaxStepCm) {
            Clear(); return false;
        }
        last_ = correction_cm;
        return true;
    }
    // A door use may also attach the pawn to a sequence anchor for the rest of the animation.
    bool Active(const MotionScope& scope, std::uint64_t now) const {
        return armed_ && scope == scope_ && now >= start_ && now - start_ < kDurationMs;
    }
private:
    void Clear() { armed_ = false; }
    MotionScope scope_{};
    std::uint64_t start_ = 0;
    float last_ = 0;
    bool armed_ = false;
};
}
