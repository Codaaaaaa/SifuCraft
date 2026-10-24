#include "probe/probe.h"

#include <windows.h>

#include <algorithm>
#include <cmath>
#include <string>
#include <unordered_set>
#include <vector>

#include "core/log.h"
#include "core/safe_memory.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::probe {

namespace {

namespace off = gen::off;
using protocol::Rot3;
using protocol::Vec3;
using ue::FRotator;
using ue::FVector;

constexpr DWORD kEngineWaitMs = 500;
constexpr float kNearbyRadius = 5000.0f;      // Unreal units (1 uu = 1 cm)
constexpr float kPooledBelowZ = -100000.0f;   // Sifu parks pooled, inactive AI at z = -999000
constexpr std::size_t kMaxEnemiesLogged = 8;

struct ActorSample {
    std::uintptr_t actor = 0;
    FVector location{};
    FRotator rotation{};
    float health = NAN;
    float max_health = NAN;
    int faction = -1;
    float distance = 0.0f;
};

struct Classes {
    std::uintptr_t character = 0;
    std::uintptr_t fighting_character = 0;
    std::uintptr_t impostor = 0;       // ABaseCharacterImpostor (ASCCharacterImpostor derives from it)
    std::uintptr_t sc_impostor = 0;    // ASCCharacterImpostor
};

Classes g_classes;
TextFile g_reflection;
std::unordered_set<std::uintptr_t> g_dumped;
std::vector<std::uintptr_t> g_impostors;

constexpr float kImpostorLogRadius = 1000.0f;

bool IsImpostor(std::uintptr_t actor) {
    return ue::IsA(actor, g_classes.impostor) || ue::IsA(actor, g_classes.sc_impostor);
}

bool Bit(std::uintptr_t object, std::uint32_t offset, std::uint32_t shift) {
    return (mem::ReadOr<std::uint8_t>(object + offset, 0) >> shift) & 1;
}

Vec3 ToVec3(const FVector& v) { return {v.x, v.y, v.z}; }
Rot3 ToRot3(const FRotator& r) { return {r.pitch, r.yaw, r.roll}; }
std::string Vec(const Vec3& v) { return FormatString("(%.0f, %.0f, %.0f)", v.x, v.y, v.z); }
std::string Vec(const FVector& v) { return Vec(ToVec3(v)); }
std::string Rot(const Rot3& r) { return FormatString("(%.1f, %.1f, %.1f)", r.pitch, r.yaw, r.roll); }

float Distance(const FVector& a, const Vec3& b) {
    const float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
    return std::sqrt(dx * dx + dy * dy + dz * dz);
}

// Compares PDB-derived member offsets with what UE reflection reports at runtime.
void SelfCheck() {
    struct Expectation {
        const char* cls;
        const char* property;
        std::uint32_t offset;
    };
    static constexpr Expectation kExpectations[] = {
        {"World", "PersistentLevel", off::UWorld::PersistentLevel},
        {"World", "OwningGameInstance", off::UWorld::OwningGameInstance},
        {"World", "Levels", off::UWorld::Levels},
        {"Level", "OwningWorld", off::ULevel::OwningWorld},
        {"GameInstance", "LocalPlayers", off::UGameInstance::LocalPlayers},
        {"Player", "PlayerController", off::UPlayer::PlayerController},
        {"Actor", "RootComponent", off::AActor::RootComponent},
        {"Pawn", "Controller", off::APawn::Controller},
        {"Character", "CharacterMovement", off::ACharacter::CharacterMovement},
        {"Controller", "Pawn", off::AController::Pawn},
        {"PlayerController", "PlayerCameraManager", off::APlayerController::PlayerCameraManager},
        {"PlayerController", "AcknowledgedPawn", off::APlayerController::AcknowledgedPawn},
        {"PlayerCameraManager", "CameraCachePrivate", off::APlayerCameraManager::CameraCachePrivate},
        {"SceneComponent", "RelativeLocation", off::USceneComponent::RelativeLocation},
        {"SceneComponent", "RelativeRotation", off::USceneComponent::RelativeRotation},
        {"SceneComponent", "ComponentVelocity", off::USceneComponent::ComponentVelocity},
        {"SceneComponent", "AttachParent", off::USceneComponent::AttachParent},
        {"MovementComponent", "Velocity", off::UMovementComponent::Velocity},
        {"FightingCharacter", "m_HealthComponent", off::AFightingCharacter::m_HealthComponent},
        {"FightingCharacter", "m_eFaction", off::AFightingCharacter::m_eFaction},
        {"HealthComponent", "m_fHealth", off::UHealthComponent::m_fHealth},
        {"HealthComponent", "m_fMaxHealth", off::UHealthComponent::m_fMaxHealth},
    };

    int matched = 0, mismatched = 0, unknown = 0;
    for (const Expectation& e : kExpectations) {
        const std::uintptr_t cls = ue::FindClass(e.cls);
        if (!cls) {
            SC_LOG("selfcheck: %-20s class not found", e.cls);
            ++unknown;
            continue;
        }
        const std::int32_t actual = ue::FindPropertyOffset(cls, e.property);
        if (actual < 0) {
            SC_LOG("selfcheck: %s.%s not reflected (pdb 0x%X)", e.cls, e.property, e.offset);
            ++unknown;
        } else if (static_cast<std::uint32_t>(actual) != e.offset) {
            SC_LOG("selfcheck: MISMATCH %s.%s pdb=0x%X reflection=0x%X", e.cls, e.property, e.offset, actual);
            ++mismatched;
        } else {
            ++matched;
        }
    }
    SC_LOG("selfcheck: %d matched, %d mismatched, %d unverifiable", matched, mismatched, unknown);
}

void DumpClassOnce(std::uintptr_t cls) {
    if (!cls || !g_reflection.IsOpen() || !g_dumped.insert(cls).second) return;
    ue::DumpStruct(g_reflection, cls);
}

// World space even while the root is attached to something (see ue/transform.h).
void ReadTransform(std::uintptr_t actor, FVector* location, FRotator* rotation) {
    const std::uintptr_t root = mem::ReadPtr(actor + off::AActor::RootComponent);
    if (!ue::ReadWorldTransform(root, location, rotation)) {
        *location = FVector{};
        *rotation = FRotator{};
    }
}

ActorSample SampleActor(std::uintptr_t actor) {
    ActorSample s;
    s.actor = actor;
    ReadTransform(actor, &s.location, &s.rotation);
    if (ue::IsA(actor, g_classes.fighting_character)) {
        const std::uintptr_t health = mem::ReadPtr(actor + off::AFightingCharacter::m_HealthComponent);
        if (health) {
            s.health = mem::ReadOr<float>(health + off::UHealthComponent::m_fHealth, NAN);
            s.max_health = mem::ReadOr<float>(health + off::UHealthComponent::m_fMaxHealth, NAN);
        }
        s.faction = mem::ReadOr<std::uint8_t>(actor + off::AFightingCharacter::m_eFaction, 0xFF);
    }
    return s;
}

}  // namespace

void WaitForEngine(std::uintptr_t image_base) {
    g_reflection.Open(log::Directory() + L"\\reflection.txt");
    for (int attempt = 0;; ++attempt) {
        if (ue::InitNames(image_base) && ue::InitObjects(image_base)) {
            SC_LOG("engine: name pool and object array ready (%d objects) after %d ms", ue::ObjectCount(),
                   attempt * static_cast<int>(kEngineWaitMs));
            return;
        }
        if (attempt % 20 == 0) SC_LOG("engine: waiting for name pool / object array ...");
        Sleep(kEngineWaitMs);
    }
}

// World -> GameInstance -> LocalPlayers[0] -> PlayerController -> Pawn / CameraManager
PlayerObjects FindPlayerObjects(std::uintptr_t world) {
    PlayerObjects chain;
    chain.game_instance = mem::ReadPtr(world + off::UWorld::OwningGameInstance);
    mem::RawArray local_players;
    std::uintptr_t local_player = 0;
    if (chain.game_instance &&
        mem::ReadArray(chain.game_instance + off::UGameInstance::LocalPlayers, &local_players) &&
        local_players.num > 0) {
        local_player = mem::ReadPtr(local_players.data);
    }
    chain.controller = mem::ReadPtr(local_player + off::UPlayer::PlayerController);
    chain.pawn = mem::ReadPtr(chain.controller + off::AController::Pawn);
    chain.camera = mem::ReadPtr(chain.controller + off::APlayerController::PlayerCameraManager);
    if (ue::IsA(chain.pawn, g_classes.character)) {
        chain.movement = mem::ReadPtr(chain.pawn + off::ACharacter::CharacterMovement);
    }
    return chain;
}

void Prepare() {
    // Native classes exist from engine start; resolve once and validate the offset table.
    g_classes.character = ue::FindClass("Character");
    g_classes.fighting_character = ue::FindClass("FightingCharacter");
    g_classes.impostor = ue::FindClass("BaseCharacterImpostor");
    g_classes.sc_impostor = ue::FindClass("SCCharacterImpostor");
    SC_LOG("probe: impostor classes BaseCharacterImpostor=%s SCCharacterImpostor=%s",
           g_classes.impostor ? "found" : "missing", g_classes.sc_impostor ? "found" : "missing");
    SelfCheck();
}

bool ActorHidden(std::uintptr_t actor) {
    return Bit(actor, gen::bits::AActor::bHidden_Offset, gen::bits::AActor::bHidden_Shift);
}

const std::vector<std::uintptr_t>& Impostors() { return g_impostors; }

void CapturePlayer(std::uintptr_t world, bridge::PlayerFrame* frame) {
    const PlayerObjects chain = FindPlayerObjects(world);
    if (chain.pawn) {
        const ActorSample player = SampleActor(chain.pawn);
        frame->flags |= protocol::kSnapshotHasPawn;
        frame->player_location = ToVec3(player.location);
        frame->player_rotation = ToRot3(player.rotation);
        frame->health = player.health;
        frame->max_health = player.max_health;
        const std::uintptr_t root = mem::ReadPtr(chain.pawn + off::AActor::RootComponent);
        frame->capsule_half_height = mem::ReadOr<float>(root + off::UCapsuleComponent::CapsuleHalfHeight, 0.0f);
        if (ue::IsA(chain.pawn, g_classes.character)) {
            const std::uintptr_t movement = mem::ReadPtr(chain.pawn + off::ACharacter::CharacterMovement);
            frame->player_velocity =
                ToVec3(mem::ReadOr<FVector>(movement + off::UMovementComponent::Velocity, FVector{}));
        }
    }
    if (chain.camera) {
        const std::uintptr_t pov =
            chain.camera + off::APlayerCameraManager::CameraCachePrivate + off::FCameraCacheEntry::POV;
        frame->flags |= protocol::kSnapshotHasCamera;
        frame->camera_location = ToVec3(mem::ReadOr<FVector>(pov + off::FMinimalViewInfo::Location, FVector{}));
        frame->camera_rotation = ToRot3(mem::ReadOr<FRotator>(pov + off::FMinimalViewInfo::Rotation, FRotator{}));
        frame->camera_fov = mem::ReadOr<float>(pov + off::FMinimalViewInfo::FOV, NAN);
    }
}

void LogWorld(std::uintptr_t world, const bridge::PlayerFrame& frame) {
    const PlayerObjects chain = FindPlayerObjects(world);
    if (g_dumped.empty()) {
        // First world: record the layouts later phases will need.
        for (const char* name : {"FightingCharacter", "BaseCharacter", "HealthComponent", "CharacterHealthComponent",
                                 "PlayerCameraManager", "CharacterMovementComponent", "BaseCharacterImpostor",
                                 "SCCharacterImpostor"}) {
            DumpClassOnce(ue::FindClass(name));
        }
    }
    for (std::uintptr_t object : {world, chain.game_instance, chain.controller, chain.pawn, chain.camera}) {
        if (object) DumpClassOnce(ue::ClassOf(object));
    }

    const std::uintptr_t pawn = chain.pawn;
    if (pawn) {
        const int faction = ue::IsA(pawn, g_classes.fighting_character)
                                ? mem::ReadOr<std::uint8_t>(pawn + off::AFightingCharacter::m_eFaction, 0xFF)
                                : -1;
        namespace scene = gen::bits::USceneComponent;
        const std::uintptr_t mesh = mem::ReadPtr(pawn + off::ACharacter::Mesh);
        SC_LOG("[player] pawn=%s class=%s ctrl=%s loc=%s rot=%s vel=%s hp=%.1f/%.1f faction=%d hidden=%d "
               "mesh(visible=%d hidden_in_game=%d)",
               ue::ObjectName(pawn).c_str(), ue::ClassName(pawn).c_str(), ue::ClassName(chain.controller).c_str(),
               Vec(frame.player_location).c_str(), Rot(frame.player_rotation).c_str(),
               Vec(frame.player_velocity).c_str(), frame.health, frame.max_health, faction, ActorHidden(pawn),
               mesh ? Bit(mesh, scene::bVisible_Offset, scene::bVisible_Shift) : -1,
               mesh ? Bit(mesh, scene::bHiddenInGame_Offset, scene::bHiddenInGame_Shift) : -1);
    } else {
        SC_LOG("[player] no pawn (controller=%s)", chain.controller ? ue::ClassName(chain.controller).c_str() : "null");
    }
    if (chain.camera) {
        SC_LOG("[camera] class=%s loc=%s rot=%s fov=%.1f", ue::ClassName(chain.camera).c_str(),
               Vec(frame.camera_location).c_str(), Rot(frame.camera_rotation).c_str(), frame.camera_fov);
    }

    // Every FightingCharacter and character impostor in every loaded level.
    std::vector<ActorSample> fighters;
    g_impostors.clear();
    mem::RawArray levels;
    int level_count = 0, actor_count = 0, pooled = 0;
    if (mem::ReadArray(world + off::UWorld::Levels, &levels)) {
        level_count = levels.num;
        for (int i = 0; i < levels.num; ++i) {
            const std::uintptr_t level = mem::ReadPtr(levels.data + i * sizeof(std::uintptr_t));
            mem::RawArray actors;
            if (!level || !mem::ReadArray(level + off::ULevel::Actors, &actors)) continue;
            actor_count += actors.num;
            for (int j = 0; j < actors.num; ++j) {
                const std::uintptr_t actor = mem::ReadPtr(actors.data + j * sizeof(std::uintptr_t));
                if (!actor || actor == pawn) continue;
                if (IsImpostor(actor)) {
                    if (ue::IsLive(actor)) g_impostors.push_back(actor);
                    continue;
                }
                if (!ue::IsA(actor, g_classes.fighting_character) || !ue::IsLive(actor)) continue;
                ActorSample sample = SampleActor(actor);
                if (sample.location.z < kPooledBelowZ) {
                    ++pooled;
                    continue;
                }
                sample.distance = Distance(sample.location, frame.player_location);
                fighters.push_back(sample);
            }
        }
    }
    std::sort(fighters.begin(), fighters.end(),
              [](const ActorSample& a, const ActorSample& b) { return a.distance < b.distance; });
    const auto nearby = pawn ? std::count_if(fighters.begin(), fighters.end(),
                                             [](const ActorSample& s) { return s.distance <= kNearbyRadius; })
                             : 0;
    SC_LOG("[fighters] active=%zu pooled=%d nearby=%td (<=%.0fuu) levels=%d actors=%d objects=%d faults=%llu",
           fighters.size(), pooled, nearby, kNearbyRadius, level_count, actor_count, ue::ObjectCount(),
           static_cast<unsigned long long>(mem::FaultCount()));
    if (!pawn) return;  // distances are meaningless without a player
    for (std::uintptr_t impostor : g_impostors) {
        FVector location{};
        FRotator rotation{};
        ReadTransform(impostor, &location, &rotation);
        const float distance = Distance(location, frame.player_location);
        if (distance > kImpostorLogRadius) continue;
        DumpClassOnce(ue::ClassOf(impostor));
        SC_LOG("[impostor] %s class=%s dist=%.0f hidden=%d loc=%s", ue::ObjectName(impostor).c_str(),
               ue::ClassName(impostor).c_str(), distance, ActorHidden(impostor), Vec(location).c_str());
    }
    for (std::size_t i = 0; i < fighters.size() && i < kMaxEnemiesLogged; ++i) {
        const ActorSample& s = fighters[i];
        if (s.distance > kNearbyRadius) break;
        DumpClassOnce(ue::ClassOf(s.actor));
        // EMovementMode: 1 walking, 2 navmesh walking (follows the navigation mesh, no world sweeps).
        const std::uintptr_t movement = mem::ReadPtr(s.actor + off::ACharacter::CharacterMovement);
        const int mode = movement ? mem::ReadOr<std::uint8_t>(movement + off::UCharacterMovementComponent::MovementMode, 0xFF) : -1;
        SC_LOG("[fighter] dist=%.0f %s class=%s loc=%s yaw=%.1f hp=%.1f/%.1f faction=%d move=%d", s.distance,
               ue::ObjectName(s.actor).c_str(), ue::ClassName(s.actor).c_str(), Vec(s.location).c_str(),
               s.rotation.yaw, s.health, s.max_health, s.faction, mode);
    }
}

}  // namespace sifucraft::probe
