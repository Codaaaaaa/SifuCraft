#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "probe/combat_sample.h"

namespace sifucraft::probe {

// P5 S0: inspects reflected functions, candidate vtable slots, capsules, pool flags and health.
// Runtime thread only; no hooks are installed and no UE functions or memory writes are used.
class CombatCensus {
public:
    void Update(std::uintptr_t image_base, std::uintptr_t world, std::uint32_t generation,
                std::uintptr_t pawn, std::uint64_t now_ms);
    std::string TakeStats();

private:
    struct Observed {
        std::uintptr_t health_component = 0;
        float health = 0;
        bool active = false;
        bool sampled = false;
    };

    void Survey(std::uintptr_t image_base, std::uintptr_t world, std::uintptr_t pawn);
    void InspectCharacter(std::uintptr_t image_base, std::uintptr_t actor, bool player);
    void Sample(std::uintptr_t actor, bool player);
    int Difficulty(std::uintptr_t pawn) const;

    std::uintptr_t world_ = 0;
    std::uint32_t generation_ = 0;
    std::uint64_t next_survey_ms_ = 0;
    std::uint64_t next_sample_ms_ = 0;
    std::uintptr_t fighting_class_ = 0;
    std::uintptr_t health_class_ = 0;
    std::uintptr_t difficulty_class_ = 0;
    int difficulty_ = -1;
    bool metadata_logged_ = false;
    std::vector<std::uintptr_t> actors_;
    std::unordered_map<std::uintptr_t, Observed> observed_;
    std::unordered_set<std::string> configurations_;
    std::unordered_set<std::string> hit_rows_;
    std::map<int, int> factions_;
    int logged_changes_ = 0;
    std::uint64_t surveys_ = 0;
    std::uint64_t samples_ = 0;
    std::uint64_t health_changes_ = 0;
    std::uint64_t pool_changes_ = 0;
    std::uint64_t unreadable_ = 0;
};

}  // namespace sifucraft::probe
