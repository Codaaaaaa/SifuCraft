#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "combat/activation_tracker.h"
#include "sifucraft_protocol.h"
#include "ue/objects.h"

namespace sifucraft::combat {

// Read-only producer of the nearby enemy table. Never calls UE functions or changes AI state.
class Enemies {
public:
    struct Target { std::uint32_t id, spawn; std::uintptr_t actor; ue::ObjectIdentity identity; };
    void Update(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, std::uint64_t now_ms);
    const std::vector<protocol::EnemyEntry>& entries() const { return entries_; }
    const std::vector<Target>& targets() const { return targets_; }
    std::string TakeStats();

private:
    struct Candidate { std::uintptr_t actor = 0; ue::ObjectIdentity identity; };
    void Scan(std::uintptr_t world, std::uintptr_t pawn);
    std::uintptr_t world_ = 0;
    std::uint32_t generation_ = 0;
    std::uint64_t next_scan_ms_ = 0;
    std::uintptr_t fighting_class_ = 0;
    std::uintptr_t health_class_ = 0;
    std::uintptr_t difficulty_class_ = 0;
    std::vector<Candidate> candidates_;
    std::vector<protocol::EnemyEntry> entries_;
    std::vector<Target> targets_;
    ActivationTracker identities_;
    std::uint64_t scans_ = 0, samples_ = 0, rejected_ = 0;
};

}  // namespace sifucraft::combat
