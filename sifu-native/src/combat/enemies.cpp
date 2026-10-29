#include "combat/enemies.h"

#include <algorithm>
#include <cmath>
#include <set>

#include "core/log.h"
#include "core/safe_memory.h"
#include "probe/combat_sample.h"
#include "combat/world_motion_service.h"
#include "ue/transform.h"

namespace sifucraft::combat {
namespace {
namespace off = gen::off;
constexpr float kRadiusCm = 3000.0f;
constexpr int kMaxCandidates = 1024;

bool Finite(const ue::FVector& v) { return std::isfinite(v.x) && std::isfinite(v.y) && std::isfinite(v.z); }

int Difficulty(std::uintptr_t pawn, std::uintptr_t attribute_class) {
    const auto ability = mem::ReadPtr(pawn + off::AFightingCharacter::m_AbilityComponent);
    mem::RawArray attributes;
    if (!ue::IsLive(ability) || !mem::ReadArray(ability + off::UAbilitySystemComponent::SpawnedAttributes, &attributes)) return -1;
    for (int i = 0; i < attributes.num && i < 64; ++i) {
        const auto object = mem::ReadPtr(attributes.data + i * sizeof(std::uintptr_t));
        if (!ue::IsLive(object) || !ue::IsA(object, attribute_class)) continue;
        const float value = mem::ReadOr<float>(object + off::UCharacterStatAttributeSet::GameDifficulty +
                                             off::FGameplayAttributeData::CurrentValue, NAN);
        if (std::isfinite(value) && value >= 0 && value <= 2 && std::fabs(value - std::round(value)) < 0.001f) {
            return static_cast<int>(std::round(value));
        }
    }
    return -1;
}
}  // namespace

void Enemies::Scan(std::uintptr_t world, std::uintptr_t pawn) {
    ++scans_;
    std::set<std::uintptr_t> found;
    mem::RawArray levels;
    if (mem::ReadArray(world + off::UWorld::Levels, &levels)) {
        for (int l = 0; l < levels.num && l < 512; ++l) {
            const auto level = mem::ReadPtr(levels.data + l * sizeof(std::uintptr_t));
            mem::RawArray actors;
            if (!ue::IsLive(level) || !mem::ReadArray(level + off::ULevel::Actors, &actors)) continue;
            for (int a = 0; a < actors.num && a < 100000 && found.size() < kMaxCandidates; ++a) {
                const auto actor = mem::ReadPtr(actors.data + a * sizeof(std::uintptr_t));
                if (actor != pawn && ue::IsA(actor, fighting_class_) && ue::IsLive(actor)) found.insert(actor);
            }
        }
    }
    for (const auto& candidate : candidates_) {
        if (!found.contains(candidate.actor)) identities_.Forget(static_cast<std::uint32_t>(candidate.identity.index));
    }
    candidates_.clear();
    for (const auto actor : found) {
        const auto identity = ue::IdentityOf(actor);
        if (identity.valid()) candidates_.push_back({actor, identity});
    }
}

void Enemies::Update(std::uintptr_t world, std::uint32_t generation, std::uintptr_t pawn, std::uint64_t now_ms) {
    if (world != world_ || generation != generation_) {
        world_ = world;
        generation_ = generation;
        candidates_.clear();
        identities_.Reset();
        next_scan_ms_ = 0;
    }
    entries_.clear();
    targets_.clear();
    if (!world || !ue::IsLive(pawn)) return;
    if (!fighting_class_) fighting_class_ = ue::FindClass("FightingCharacter");
    if (!health_class_) health_class_ = ue::FindClass("CharacterHealthComponent");
    if (!difficulty_class_) difficulty_class_ = ue::FindClass("CharacterStatAttributeSet");
    if (!fighting_class_ || !health_class_ || !difficulty_class_ || !ue::IsA(pawn, fighting_class_)) return;
    const auto pawn_root = mem::ReadPtr(pawn + off::AActor::RootComponent);
    ue::FVector player_location;
    if (!ue::ReadWorldLocation(pawn_root, &player_location) || !Finite(player_location)) return;
    const auto player_faction = mem::ReadOr<std::uint8_t>(pawn + off::AFightingCharacter::m_eFaction, 0xFF);
    const int difficulty = Difficulty(pawn, difficulty_class_);
    if (difficulty < 0 || player_faction >= gen::value::EFactionsEnums::Count) return;
    if (now_ms >= next_scan_ms_) {
        next_scan_ms_ = now_ms + 500;
        Scan(world, pawn);
    }
    const auto retained=world_motion::RetainedTargets(generation,now_ms);
    const auto priority=[&](std::uint32_t id,std::uint32_t spawn){const auto key=(std::uint64_t(id)<<32)|spawn;
        if(std::find(retained.active.begin(),retained.active.end(),key)!=retained.active.end())return 2;
        return std::find(retained.retained.begin(),retained.retained.end(),key)!=retained.retained.end()?1:0;};
    for (const auto& candidate : candidates_) {
        const auto actor = candidate.actor;
        const auto id = static_cast<std::uint32_t>(candidate.identity.index);
        if (actor == pawn || ue::IdentityOf(actor) != candidate.identity) {
            identities_.Forget(id);
            continue;
        }
        ++samples_;
        std::uint8_t pooled = 0, active = 0;
        if (!mem::Read(actor + off::ASCCharacter::m_bIsPooled, &pooled) || pooled > 1 ||
            !mem::Read(actor + off::ASCCharacter::m_bPooledActorActive, &active) || active > 1) {
            ++rejected_;
            continue;
        }
        const auto spawn = identities_.Observe(id, candidate.identity.serial, !pooled || active);
        if (!spawn) continue;
        const auto faction = mem::ReadOr<std::uint8_t>(actor + off::AFightingCharacter::m_eFaction, 0xFF);
        if (faction >= gen::value::EFactionsEnums::Count || faction == gen::value::EFactionsEnums::TMP_Neutral ||
            faction == player_faction) continue;
        const auto health_component = mem::ReadPtr(actor + off::AFightingCharacter::m_HealthComponent);
        probe::HealthSample health;
        if (!ue::IsLive(health_component) || !ue::IsA(health_component, health_class_) ||
            !probe::ReadHealthSample(health_component, &health) || health.health <= 0) continue;
        const auto capsule = mem::ReadPtr(actor + off::AFightingCharacter::m_HitCapsule);
        ue::Affine transform;
        ue::FVector location;
        ue::FRotator rotation;
        if (!ue::IsLive(capsule) || !ue::ReadComponentToWorld(capsule, &transform) ||
            !ue::ReadWorldTransform(capsule, &location, &rotation) || !Finite(location) || location.z < -100000) continue;
        const float dx = location.x - player_location.x, dy = location.y - player_location.y;
        const float dz = location.z - player_location.z;
        if (dx * dx + dy * dy + dz * dz > kRadiusCm * kRadiusCm && !priority(id,spawn)) continue;
        const float scale = transform.MinAxisScale();
        const float radius = mem::ReadOr<float>(capsule + off::UCapsuleComponent::CapsuleRadius, NAN) * scale;
        const float half = mem::ReadOr<float>(capsule + off::UCapsuleComponent::CapsuleHalfHeight, NAN) * scale;
        const auto root = mem::ReadPtr(actor + off::AActor::RootComponent);
        const auto velocity = mem::ReadOr<ue::FVector>(root + off::USceneComponent::ComponentVelocity, {});
        const float max_health = probe::EffectiveMaxHealth(health, difficulty);
        if (!std::isfinite(radius) || !std::isfinite(half) || radius <= 0 || half < radius ||
            radius > 1000 || half > 2000 || !Finite(velocity) || !std::isfinite(rotation.yaw) ||
            !std::isfinite(max_health) || max_health <= 0 || ue::IdentityOf(actor) != candidate.identity) {
            ++rejected_;
            continue;
        }
        const auto flags = protocol::kEnemyAlive | (health.down ? protocol::kEnemyDown : 0) |
            (health.can_be_damaged ? 0 : protocol::kEnemyInvincible) | (health.can_die ? protocol::kEnemyCanDie : 0);
        entries_.push_back({id, spawn, {location.x, location.y, location.z}, rotation.yaw, radius, half,
            health.health, max_health, flags, faction, {velocity.x, velocity.y, velocity.z}, 0});
        targets_.push_back({id, spawn, actor, candidate.identity});
    }
    const auto distance = [&](const protocol::EnemyEntry& e) {
        const float dx = e.location.x - player_location.x, dy = e.location.y - player_location.y;
        const float dz = e.location.z - player_location.z;
        return dx * dx + dy * dy + dz * dz;
    };
    std::sort(entries_.begin(), entries_.end(), [&](const auto& a, const auto& b) {
        const auto ar=priority(a.id,a.spawn),br=priority(b.id,b.spawn);if(ar!=br)return ar>br;
        const float da = distance(a), db = distance(b);
        return da != db ? da < db : a.id < b.id;
    });
    if (entries_.size() > protocol::kMaxEnemies) entries_.resize(protocol::kMaxEnemies);
    std::erase_if(targets_, [&](const Target& target) {
        return std::none_of(entries_.begin(), entries_.end(), [&](const auto& e) { return e.id == target.id; });
    });
}

std::string Enemies::TakeStats() {
    const std::string text = FormatString("world=%u candidates=%zu nearby=%zu scans=%llu samples=%llu rejected=%llu",
        generation_, candidates_.size(), entries_.size(), static_cast<unsigned long long>(scans_),
        static_cast<unsigned long long>(samples_), static_cast<unsigned long long>(rejected_));
    scans_ = samples_ = rejected_ = 0;
    return text;
}
}  // namespace sifucraft::combat
