#pragma once

#include <cstdint>
#include <string>
#include <unordered_map>
#include <vector>

#include "combat/enemies.h"
#include "sifucraft_protocol.h"
#include "ue/transform.h"

namespace sifucraft::combat {

// Read-only producer of the enemy head table. Never calls UE functions.
class EnemyHeads {
public:
    void Update(const std::vector<Enemies::Target>& targets, const std::vector<protocol::EnemyEntry>& entries);
    const std::vector<protocol::EnemyHeadEntry>& entries() const { return entries_; }
    std::string TakeStats();

private:
    struct Skeleton { ue::ObjectIdentity identity; int bone = -1; ue::Affine reference; };
    const Skeleton* Find(std::uintptr_t skeletal_mesh);
    std::unordered_map<std::uintptr_t, Skeleton> skeletons_;
    std::vector<protocol::EnemyHeadEntry> entries_;
    std::uint64_t samples_ = 0, missing_ = 0;
};

}  // namespace sifucraft::combat
