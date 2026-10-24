#pragma once
#include <cmath>
#include <cstdint>
#include <map>
#include "sifucraft_protocol.h"

namespace sifucraft::combat {
// One credential per published hit. Consuming by original ID prevents a fresh reply ID from
// replaying a previously handled block. External locking belongs to the caller.
class ShieldLedger {
public:
    void Reset() { records_.clear(); }
    void Record(std::uint64_t id, std::uint64_t session, const protocol::PlayerHitPayload& hit, std::uint64_t created) {
        if (!id || !session || !hit.attacker_id || !hit.spawn) return;
        records_.emplace(id, RecordData{session, created, hit, false});
        while (records_.size() > 256) records_.erase(records_.begin());
    }
    bool Consume(std::uint64_t session, const protocol::PlayerBlockedPayload& reply, std::uint64_t now, std::uint64_t* created = nullptr) {
        const auto found = records_.find(reply.origin_hit_id);
        if (found == records_.end()) return false;
        auto& entry = found->second;
        const auto& hit = entry.hit;
        if (entry.consumed || session != entry.session || now < entry.created || now - entry.created > 2000 ||
            reply.attacker_id != hit.attacker_id || reply.spawn != hit.spawn || reply.world_generation != hit.world_generation ||
            reply.anchor_epoch != hit.anchor_epoch || !(hit.flags & protocol::kHitCanDefend) ||
            (reply.flags & ~protocol::kBlockedPerfect) ||
            ((reply.flags & protocol::kBlockedPerfect) && !(hit.flags & protocol::kHitPerfectWindow)) ||
            !std::isfinite(reply.blocked_damage) || reply.blocked_damage <= 0 || reply.blocked_damage > 1000000) return false;
        entry.consumed = true;
        if (created) *created = entry.created;
        return true;
    }
private:
    struct RecordData { std::uint64_t session, created; protocol::PlayerHitPayload hit; bool consumed; };
    std::map<std::uint64_t, RecordData> records_;
};
}
