#pragma once

#include <cstdint>
#include <string>
#include <vector>
#include "combat/enemies.h"

namespace sifucraft::combat::player_hits {

// Runtime thread installs the approved H6; only MC ownership suppresses native player hits.
void InstallObserver(std::uintptr_t image_base, std::uintptr_t pawn);
bool UninstallObserver();
bool OwnsObserver(std::uintptr_t slot_value);
void UpdateContext(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, bool active, bool can_forward,
                   std::uint32_t epoch, std::uint64_t session, const std::vector<Enemies::Target>& targets);
struct Hit { std::uint64_t session, created_ms; protocol::PlayerHitPayload payload; };
void DrainHits(std::vector<Hit>* hits);
// H7 fallback: non-hit native player damage is discarded, never translated to generic damage.
bool BlockHealthDamage(std::uintptr_t health, std::uintptr_t infos);
// Called by H7 after the original, solely to compare real native damage against live requests.
void ObserveHealthDamage(std::uintptr_t health, std::uintptr_t infos, float before, float after);
std::string TakeStats();

}  // namespace sifucraft::combat::player_hits
