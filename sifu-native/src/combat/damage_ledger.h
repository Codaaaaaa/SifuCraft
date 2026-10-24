#pragma once

#include <cmath>
#include <cstdint>
#include <map>

#include "sifucraft_protocol.h"

namespace sifucraft::combat {

inline bool ValidDamage(const protocol::EnemyDamagePayload& p) {
    return p.spawn != 0 && p.world_generation != 0 && p.anchor_epoch != 0 && p.reserved == 0 &&
        std::isfinite(p.mc_damage) && p.mc_damage > 0 && p.mc_damage <= 1000000 &&
        std::isfinite(p.stun_hint) && p.stun_hint >= 0 && p.stun_hint <= 5 && (p.flags & ~15u) == 0;
}

// Retains recent results for duplicate replies; the high-water mark outlives evicted results, so
// a delayed duplicate can never become a new application. External locking belongs to the caller.
class DamageLedger {
public:
    enum class Claim { Fresh, Pending, Cached, TooOld, WrongSession };
    void SetSession(std::uint64_t session) {
        if (session == session_) return;
        session_ = session;
        highest_ = 0;
        records_.clear();
    }
    Claim Begin(std::uint64_t session, std::uint64_t id, protocol::DamageResultPayload* cached) {
        if (!session || session != session_) return Claim::WrongSession;
        if (id <= highest_) {
            const auto found = records_.find(id);
            if (found == records_.end()) return Claim::TooOld;
            if (!found->second.done) return Claim::Pending;
            *cached = found->second.result;
            return Claim::Cached;
        }
        highest_ = id;
        records_[id] = {};
        if (records_.size() > 256) records_.erase(records_.begin());
        return Claim::Fresh;
    }
    void Complete(std::uint64_t session, std::uint64_t id, const protocol::DamageResultPayload& result) {
        if (session != session_) return;
        if (auto found = records_.find(id); found != records_.end()) found->second = {true, result};
    }
private:
    struct Record { bool done = false; protocol::DamageResultPayload result{}; };
    std::map<std::uint64_t, Record> records_;
    std::uint64_t session_ = 0, highest_ = 0;
};

}  // namespace sifucraft::combat
