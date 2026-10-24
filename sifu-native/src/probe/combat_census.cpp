#include "probe/combat_census.h"

#include <algorithm>
#include <cmath>
#include <set>

#include "combat/damage.h"
#include "combat/player_hits.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/vtable_hook.h"
#include "ue/function_call.h"
#include "ue/objects.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::probe {
namespace {

namespace off = gen::off;
constexpr std::uint32_t kFuncNative = 0x400;
constexpr std::size_t kMaxConfigurations = 64;
constexpr int kMaxChanges = 200;
constexpr int kMaxActors = 512;

void LogFunction(std::uintptr_t owner, const char* name) {
    ue::FunctionCall call;
    if (!owner || !call.Resolve(owner, name)) {
        SC_LOG("[combat-census] function %s NOT FOUND", name);
        return;
    }
    std::string params;
    for (const auto& p : ue::ListProperties(call.function())) {
        params += FormatString(" %s:%s@%d(%d)", p.name.c_str(), p.type.c_str(), p.offset, p.element_size);
    }
    const auto flags = mem::ReadOr<std::uint32_t>(call.function() + off::UFunction::FunctionFlags, 0);
    const auto size = mem::ReadOr<std::uint16_t>(call.function() + off::UFunction::ParmsSize, 0);
    SC_LOG("[combat-census] function %s flags=0x%X native=%d params=%u properties_size=%d |%s (not invoked)",
           ue::PathName(call.function()).c_str(), flags, (flags & kFuncNative) != 0, size,
           call.params_size(), params.c_str());
}

void LogTarget(std::uintptr_t base, const gen::hook::Target& target, std::uintptr_t component) {
    const auto resolution = hooks::Resolve(base, target, component);
    const bool own_observer = resolution.vtable_ok &&
        (combat::damage::OwnsObserver(resolution.slot_value) || combat::player_hits::OwnsObserver(resolution.slot_value));
    SC_LOG("[combat-census] %s class=%s vtable=0x%llX expected=0x%llX slot+0x%X=0x%llX %s (read-only)",
           target.name, ue::ClassName(component).c_str(), static_cast<unsigned long long>(resolution.vtable),
           static_cast<unsigned long long>(base + target.vftable), target.slot,
           static_cast<unsigned long long>(resolution.slot_value), own_observer ? "OUR_OBSERVER" : resolution.ready() ? "READY" : "REFUSE");
}

std::string Capsule(std::uintptr_t component) {
    if (!ue::IsLive(component)) return "unavailable";
    const float radius = mem::ReadOr<float>(component + off::UCapsuleComponent::CapsuleRadius, NAN);
    const float half = mem::ReadOr<float>(component + off::UCapsuleComponent::CapsuleHalfHeight, NAN);
    ue::Affine transform;
    const float scale = ue::ReadComponentToWorld(component, &transform) ? transform.MinAxisScale() : NAN;
    return FormatString("radius=%.1f half=%.1f scale=%.3f", radius, half, scale);
}

// Check only properties that are reflected; the PDB also covers private/unreflected members.
void SelfCheck() {
    struct Expected { const char* owner; const char* name; std::uint32_t offset; };
    const Expected expected[] = {
        {"Character", "CapsuleComponent", off::ACharacter::CapsuleComponent},
        {"FightingCharacter", "m_HitComponent", off::AFightingCharacter::m_HitComponent},
        {"FightingCharacter", "m_HitCapsule", off::AFightingCharacter::m_HitCapsule},
        {"FightingCharacter", "m_AvoidCapsule", off::AFightingCharacter::m_AvoidCapsule},
        {"FightingCharacter", "m_AbilityComponent", off::AFightingCharacter::m_AbilityComponent},
        {"FightingCharacter", "m_HittedDescription", off::AFightingCharacter::m_HittedDescription},
        {"HealthComponent", "m_fMaxHealth", off::UHealthComponent::m_fMaxHealth},
        {"CharacterHealthComponent", "m_fMaxHealthMultiplier", off::UCharacterHealthComponent::m_fMaxHealthMultiplier},
        {"CharacterHealthComponent", "m_bIsDown", off::UCharacterHealthComponent::m_bIsDown},
        {"CharacterStatAttributeSet", "GameDifficulty", off::UCharacterStatAttributeSet::GameDifficulty},
        {"DefenseComponent", "m_DefaultGuardDB", off::UDefenseComponent::m_DefaultGuardDB},
        {"GuardDB", "m_DefaultHitImpact", off::UGuardDB::m_DefaultHitImpact},
        {"GuardDB", "m_DefaultGuardImpact", off::UGuardDB::m_DefaultGuardImpact},
        {"GuardDB", "m_DefaultDeflectImpact", off::UGuardDB::m_DefaultDeflectImpact},
        {"DataTable", "RowStruct", off::UDataTable::RowStruct},
        {"AbilitySystemComponent", "SpawnedAttributes", off::UAbilitySystemComponent::SpawnedAttributes},
    };
    int matched = 0, mismatched = 0, unknown = 0;
    for (const auto& e : expected) {
        const auto cls = ue::FindClass(e.owner);
        const int actual = cls ? ue::FindPropertyOffset(cls, e.name) : -1;
        if (actual < 0) ++unknown;
        else if (static_cast<std::uint32_t>(actual) == e.offset) ++matched;
        else {
            ++mismatched;
            SC_LOG("[combat-census] MISMATCH %s.%s pdb=0x%X reflection=0x%X", e.owner, e.name, e.offset, actual);
        }
    }
    SC_LOG("[combat-census] selfcheck: %d matched, %d mismatched, %d unverifiable", matched, mismatched, unknown);
}

}  // namespace

int CombatCensus::Difficulty(std::uintptr_t pawn) const {
    if (!ue::IsLive(pawn)) return -1;
    const auto ability = mem::ReadPtr(pawn + off::AFightingCharacter::m_AbilityComponent);
    mem::RawArray attributes;
    if (!ue::IsLive(ability) || !mem::ReadArray(ability + off::UAbilitySystemComponent::SpawnedAttributes, &attributes)) {
        return -1;
    }
    for (int i = 0; i < attributes.num && i < 64; ++i) {
        const auto object = mem::ReadPtr(attributes.data + i * sizeof(std::uintptr_t));
        if (!ue::IsLive(object) || !ue::IsA(object, difficulty_class_)) continue;
        const float value = mem::ReadOr<float>(object + off::UCharacterStatAttributeSet::GameDifficulty +
                                             off::FGameplayAttributeData::CurrentValue, NAN);
        if (std::isfinite(value) && value >= 0 && value <= 2 && std::fabs(value - std::round(value)) < 0.001f) {
            return static_cast<int>(std::round(value));
        }
    }
    return -1;
}

void CombatCensus::InspectCharacter(std::uintptr_t base, std::uintptr_t actor, bool player) {
    const auto hit = mem::ReadPtr(actor + off::AFightingCharacter::m_HitComponent);
    const auto health = mem::ReadPtr(actor + off::AFightingCharacter::m_HealthComponent);
    if (!ue::IsLive(hit) || !ue::IsLive(health) || !ue::IsA(health, health_class_)) return;
    const auto movement = mem::ReadPtr(actor + off::ACharacter::CapsuleComponent);
    const auto hit_capsule = mem::ReadPtr(actor + off::AFightingCharacter::m_HitCapsule);
    const auto avoid = mem::ReadPtr(actor + off::AFightingCharacter::m_AvoidCapsule);
    const auto function = ue::FindFunction(ue::ClassOf(hit), "BPE_ValidateHit");
    const auto native_owner = ue::FindClass("HitComponent");
    const auto flags = function ? mem::ReadOr<std::uint32_t>(function + off::UFunction::FunctionFlags, 0) : 0;
    const auto resolution = hooks::Resolve(base, gen::hook::ValidateHit, hit);
    // Native code is reached only if the effective UFunction still belongs to the native class.
    const bool native_validation = function && ue::OuterOf(function) == native_owner && (flags & kFuncNative);
    const bool h6_ready = native_validation && (resolution.ready() || combat::player_hits::OwnsObserver(resolution.slot_value));
    const auto faction = mem::ReadOr<std::uint8_t>(actor + off::AFightingCharacter::m_eFaction, 0xFF);
    const std::string key = FormatString("%s %s hit=%s health=%s faction=%u validation=%s move[%s] hit[%s] avoid[%s]",
        player ? "PLAYER" : "enemy", ue::ClassName(actor).c_str(), ue::ClassName(hit).c_str(),
        ue::ClassName(health).c_str(), faction, ue::PathName(function).c_str(), Capsule(movement).c_str(),
        Capsule(hit_capsule).c_str(), Capsule(avoid).c_str());
    if (configurations_.size() >= kMaxConfigurations || !configurations_.insert(key).second) return;
    SC_LOG("[combat-census] %s", key.c_str());
    LogTarget(base, gen::hook::ValidateHit, hit);
    LogTarget(base, gen::hook::InternalApplyDamage, health);
    SC_LOG("[combat-census] validation flags=0x%X native_owner=%d H6=%s | health_vtable_shared_with_native=%d",
           flags, native_validation, h6_ready ? "READY" : "REFUSE",
           mem::ReadPtr(health) == base + gen::hook::InternalApplyDamage.vftable);
    if (player) {
        LogFunction(ue::ClassOf(hit), "BPE_ValidateHit");
        LogFunction(ue::ClassOf(actor), "BPF_LaunchImpact");
    }
}

void CombatCensus::Survey(std::uintptr_t base, std::uintptr_t world, std::uintptr_t pawn) {
    ++surveys_;
    std::set<std::uintptr_t> found;
    if (ue::IsLive(pawn) && ue::IsA(pawn, fighting_class_)) found.insert(pawn);
    mem::RawArray levels;
    if (mem::ReadArray(world + off::UWorld::Levels, &levels)) {
        for (int l = 0; l < levels.num && l < 512; ++l) {
            const auto level = mem::ReadPtr(levels.data + l * sizeof(std::uintptr_t));
            mem::RawArray actors;
            if (!ue::IsLive(level) || !mem::ReadArray(level + off::ULevel::Actors, &actors)) continue;
            for (int a = 0; a < actors.num && a < 100000 && found.size() < kMaxActors; ++a) {
                const auto actor = mem::ReadPtr(actors.data + a * sizeof(std::uintptr_t));
                if (ue::IsA(actor, fighting_class_) && ue::IsLive(actor)) found.insert(actor);
            }
        }
    }
    actors_.assign(found.begin(), found.end());
    std::erase_if(observed_, [&](const auto& entry) { return !found.contains(entry.first); });
    std::map<int, int> factions;
    for (const auto actor : actors_) {
        ++factions[mem::ReadOr<std::uint8_t>(actor + off::AFightingCharacter::m_eFaction, 0xFF)];
        InspectCharacter(base, actor, actor == pawn);
    }
    if (factions != factions_) {
        factions_ = factions;
        std::string counts;
        for (const auto& [faction, count] : factions) counts += FormatString(" %d:%d", faction, count);
        SC_LOG("[combat-census] faction:count%s | characters=%zu (includes pooled actors)", counts.c_str(), actors_.size());
    }
}

void CombatCensus::Sample(std::uintptr_t actor, bool player) {
    if (!ue::IsLive(actor) || !ue::IsA(actor, fighting_class_)) return;
    const auto component = mem::ReadPtr(actor + off::AFightingCharacter::m_HealthComponent);
    HealthSample health;
    if (!ue::IsLive(component) || !ue::IsA(component, health_class_) || !ReadHealthSample(component, &health)) {
        ++unreadable_;
        return;
    }
    ++samples_;
    std::uint8_t pooled = 0, pool_active = 0;
    if (!mem::Read(actor + off::ASCCharacter::m_bIsPooled, &pooled) || pooled > 1 ||
        !mem::Read(actor + off::ASCCharacter::m_bPooledActorActive, &pool_active) || pool_active > 1) return;
    const bool active = !pooled || pool_active;
    auto& previous = observed_[actor];
    const bool comparable = previous.sampled && previous.health_component == component;
    const bool health_changed = comparable && health.health != previous.health;
    const bool pool_changed = comparable && active != previous.active;
    health_changes_ += health_changed;
    pool_changes_ += pool_changed;
    if ((!comparable || health_changed || pool_changed) && logged_changes_ < kMaxChanges) {
        ++logged_changes_;
        const float before = comparable ? previous.health : health.health;
        SC_LOG("[combat-census] %s %s id=%d pool=%u active=%u HP=%.2f->%.2f delta=%+.2f "
               "base_max=%.2f multipliers=[%.3f,%.3f,%.3f] difficulty=%d max=%.2f damageable=%d mortal=%d down=%d",
               player ? "PLAYER" : "enemy", ue::ObjectName(actor).c_str(),
               mem::ReadOr<std::int32_t>(actor + off::UObjectBase::InternalIndex, -1), pooled, pool_active,
               before, health.health, health.health - before, health.base_max,
               health.multipliers[0], health.multipliers[1], health.multipliers[2], difficulty_,
               EffectiveMaxHealth(health, difficulty_), health.can_be_damaged, health.can_die, health.down);
    }
    previous = {component, health.health, active, true};
    if (!active) return;
    HitSample hit;
    const auto description = actor + off::AFightingCharacter::m_HittedDescription;
    if (!ReadHitSample(description, &hit)) return;
    const std::string row = FormatString("%s use_damage=%d base=%.3f final=%.3f power=%u type=%u defend=%d lethal=%d",
        player ? "PLAYER" : "enemy", hit.use_damage, hit.base_damage, hit.final_damage, hit.attack_power,
        hit.hit_type, hit.can_be_defended, hit.lethal);
    if (hit_rows_.size() < kMaxConfigurations && hit_rows_.insert(row).second) {
        SC_LOG("[combat-census] last-hit %s%s (sampled state, not an event counter)", row.c_str(),
               hit.use_damage ? "" : " DEFAULT DAMAGE REQUIRED: cached base is not used by Sifu");
    }
}

void CombatCensus::Update(std::uintptr_t base, std::uintptr_t world, std::uint32_t generation,
                          std::uintptr_t pawn, std::uint64_t now_ms) {
    if (world != world_ || generation != generation_) {
        world_ = world;
        generation_ = generation;
        actors_.clear();
        observed_.clear();
        configurations_.clear();
        hit_rows_.clear();
        factions_.clear();
        difficulty_ = -1;
        logged_changes_ = 0;
        next_survey_ms_ = next_sample_ms_ = 0;
        SC_LOG("[combat-census] reset world=%u (read-only; no combat hooks or calls)", generation);
    }
    if (!world || now_ms < next_sample_ms_) return;
    next_sample_ms_ = now_ms + 100;
    if (!metadata_logged_) {
        fighting_class_ = ue::FindClass("FightingCharacter");
        health_class_ = ue::FindClass("CharacterHealthComponent");
        difficulty_class_ = ue::FindClass("CharacterStatAttributeSet");
        if (!fighting_class_ || !health_class_ || !difficulty_class_) return;
        SelfCheck();
        LogFunction(fighting_class_, "BPF_LaunchImpact");
        metadata_logged_ = true;
    }
    const int difficulty = Difficulty(pawn);
    if (difficulty != difficulty_) {
        difficulty_ = difficulty;
        configurations_.clear();
        SC_LOG("[combat-census] difficulty=%d (player attribute; -1 means unavailable, no function called)", difficulty_);
    }
    if (now_ms >= next_survey_ms_) {
        next_survey_ms_ = now_ms + 500;
        Survey(base, world, pawn);
    }
    for (const auto actor : actors_) Sample(actor, actor == pawn);
}

std::string CombatCensus::TakeStats() {
    const std::string text = FormatString("world=%u characters=%zu difficulty=%d surveys=%llu samples=%llu "
        "health_changes=%llu pool_changes=%llu unreadable=%llu change_logs=%d/%d hit_configs=%zu (sampled, read-only)",
        generation_, actors_.size(), difficulty_, static_cast<unsigned long long>(surveys_),
        static_cast<unsigned long long>(samples_), static_cast<unsigned long long>(health_changes_),
        static_cast<unsigned long long>(pool_changes_), static_cast<unsigned long long>(unreadable_),
        logged_changes_, kMaxChanges, hit_rows_.size());
    surveys_ = samples_ = health_changes_ = pool_changes_ = unreadable_ = 0;
    return text;
}

}  // namespace sifucraft::probe
