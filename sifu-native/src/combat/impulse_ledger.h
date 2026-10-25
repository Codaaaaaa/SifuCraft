#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <map>
#include <set>
#include "sifucraft_protocol.h"

namespace sifucraft::combat {
inline bool ValidImpulse(const protocol::EnemyImpulsePayload& p) {
    return p.origin_damage_id && p.enemy_id && p.spawn && p.world_generation && p.anchor_epoch &&
        std::isfinite(p.mc_x) && std::isfinite(p.mc_z) && std::abs(p.mc_x) <= 16 && std::abs(p.mc_z) <= 16 &&
        std::hypot(p.mc_x, p.mc_z) > 1e-6f;
}
inline bool InImpulseRange(protocol::Vec3 origin, protocol::Vec3 target) {
    const double x = target.x - origin.x, y = target.y - origin.y, z = target.z - origin.z;
    return std::isfinite(x) && std::isfinite(y) && std::isfinite(z) && x*x + y*y + z*z <= 400.0 * 400.0;
}
inline bool ImpulseVelocity(const protocol::EnemyImpulsePayload& p, protocol::Vec3 current, protocol::Vec3* out) {
    if (!ValidImpulse(p) || !std::isfinite(current.x) || !std::isfinite(current.y) || !std::isfinite(current.z)) return false;
    double x = current.x + p.mc_x * 2000.0, y = current.y + p.mc_z * 2000.0;
    const double length = std::hypot(x, y), scale = length > 600.0 ? 600.0 / length : 1.0;
    *out = {static_cast<float>(x * scale), static_cast<float>(y * scale), 0};
    return true;
}
// Recorded only after a positive smash application. A target consumes its credential once.
class ImpulseLedger {
public:
    struct Origin { std::uint64_t created; protocol::Vec3 feet; };
    void Reset() { records_.clear(); }
    void Record(std::uint64_t id, std::uint64_t session, const protocol::EnemyDamagePayload& hit,
                protocol::Vec3 feet, std::uint64_t created) {
        if (!id || !session || !(hit.flags & protocol::kDamageSmash) || !InImpulseRange(feet, feet)) return;
        records_.emplace(id, RecordData{session, hit.world_generation, hit.anchor_epoch, {created, feet}, {}});
        while (records_.size() > 256) records_.erase(records_.begin());
    }
    bool Consume(std::uint64_t session, const protocol::EnemyImpulsePayload& push, protocol::Vec3 feet,
                 std::uint64_t now, Origin* origin = nullptr) {
        auto found = records_.find(push.origin_damage_id);
        if (!ValidImpulse(push) || found == records_.end()) return false;
        auto& r = found->second;
        const auto key = static_cast<std::uint64_t>(push.enemy_id) << 32 | push.spawn;
        if (r.session != session || r.world != push.world_generation || r.epoch != push.anchor_epoch ||
            now < r.origin.created || now - r.origin.created > 2000 || !InImpulseRange(r.origin.feet, feet) ||
            r.targets.size() >= 32 || !r.targets.insert(key).second) return false;
        if (origin) *origin = r.origin;
        return true;
    }
private:
    struct RecordData { std::uint64_t session; std::uint32_t world, epoch; Origin origin; std::set<std::uint64_t> targets; };
    std::map<std::uint64_t, RecordData> records_;
};
}
