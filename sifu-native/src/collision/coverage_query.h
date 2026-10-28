#pragma once
#include <cstdint>
#include <memory>
#include <vector>
#include "collision/components.h"
#include "ue/objects.h"

namespace sifucraft::collision::coverage {
struct Component {std::uintptr_t pointer=0;ue::ObjectIdentity identity;ue::FName name{};std::uintptr_t owner=0;ue::ObjectIdentity owner_identity;ue::FName owner_name{};};
struct Sample {
    std::uintptr_t world=0;std::uint32_t generation=0;std::uint64_t at_ms=0,epoch=0;
    ue::FVector center{},extent{};
    std::vector<Component> components;
    unsigned characters=0,derived=0,blocking=0,missing_setup=0;
};
// H1 samples after a real scene change or 8m travel, at most twice per second.
void Probe(std::uintptr_t base,std::uintptr_t world,std::uint32_t generation,std::uintptr_t pawn,const Responses& reference,std::uint64_t now);
std::shared_ptr<const Sample> Latest();
void Invalidate();
// Game-thread only. Enqueues a bounded read-only spatial query, never a movement lease.
bool Request(std::uintptr_t world,std::uint32_t generation,ue::FVector center);
// H1 only: a departed MC peer must not leave spatial work in the next save/session.
void Peer(std::uint64_t peer);
}
