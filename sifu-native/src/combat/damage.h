#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "combat/enemies.h"

namespace sifucraft::combat::damage {

// Runtime thread: resolve the approved impact call and install H7 (enemy observer + player guard).
void InstallObserver(std::uintptr_t image_base, std::uintptr_t pawn);
bool UninstallObserver();
bool OwnsObserver(std::uintptr_t slot_value);
bool ObserverReady();
void UpdateContext(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, bool active,
                   std::uint32_t epoch, std::uint64_t peer_session, const std::vector<Enemies::Target>& targets);
void Submit(const protocol::Event& event);
struct Reply { std::uint64_t session; protocol::DamageResultPayload payload; };
void DrainReplies(std::vector<Reply>* replies);

// H1 game thread, at most eight applications per frame. All UE pointers are revalidated here.
void ApplyOnGameThread(std::uintptr_t image_base);
std::string TakeStats();

}  // namespace sifucraft::combat::damage
