#pragma once

#include "sifucraft_protocol.h"

namespace sifucraft::input {
inline bool SameScope(std::uint64_t target, std::uint64_t session, std::uint32_t world, std::uint32_t current_world) {
    return target && target == session && world && world == current_world;
}
// Request carries the epoch it invalidates. Once the producer advances the epoch, a repeated
// snapshot cannot advance it again. Pose+request or dead+request is not a completed respawn.
inline bool WantsAnchor(std::uint32_t flags, std::uint32_t requested_epoch, std::uint32_t current_epoch) {
    constexpr auto required = protocol::kMcReady | protocol::kMcRequestAnchor;
    return (flags & required) == required && !(flags & (protocol::kMcDead | protocol::kMcHasPose)) &&
        requested_epoch == current_epoch;
}
inline std::uint32_t NextEpoch(std::uint32_t epoch) { return ++epoch ? epoch : 1; }
}  // namespace sifucraft::input
