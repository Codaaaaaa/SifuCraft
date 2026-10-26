#include "runtime.h"
#include "render/overlay.h"

#include <windows.h>
#include <psapi.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <deque>
#include <string>
#include <utility>
#include <vector>

#include "blocks/block_mirror.h"
#include "bridge/sifu_bridge.h"
#include "combat/enemies.h"
#include "combat/motion_probe.h"
#include "combat/world_motion_service.h"
#include "combat/damage.h"
#include "combat/player_hits.h"
#include "core/config.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/camera_fade.h"
#include "hooks/streaming_guard.h"
#include "interaction/native_interaction.h"
#include "hooks/game_hooks.h"
#include "hooks/hook_plan.h"
#include "input/input_router.h"
#include "input/ownership_intent.h"
#include "input/displacement_guard.h"
#include "input/anchor_request.h"
#include "collision/builder.h"
#include "collision/components.h"
#include "collision/shapes.h"
#include "collision/walk_check.h"
#include "probe/block_census.h"
#include "probe/collision_census.h"
#include "probe/combat_census.h"
#include "probe/probe.h"
#include "ue/objects.h"
#include "ue/reflection.h"
#include "ue/transform.h"
#include "ue/language.h"
#include "ui/player_hud.h"

namespace sifucraft::runtime {

namespace {

namespace off = gen::off;
using protocol::InputOwner;

constexpr DWORD kTickMs = 10;
constexpr std::uint64_t kLogEveryTicks = 100;         // 1 s
constexpr std::uint64_t kStatsEveryTicks = 1000;      // 10 s
constexpr std::uint64_t kHookRetryEveryTicks = 200;   // 2 s
constexpr std::uint64_t kCollisionCheckEveryTicks = 50;  // 0.5 s
constexpr std::uint64_t kWalkCheckEveryTicks = 10;       // 0.1 s

bridge::SifuBridge g_bridge;

// Accumulates durations between two Take() calls.
class Timing {
public:
    void Add(double micros) {
        total_ += micros;
        max_ = std::max(max_, micros);
        ++count_;
    }
    std::string Take() {
        const std::string text =
            count_ ? FormatString("avg=%.0fus max=%.0fus n=%llu", total_ / count_, max_,
                                  static_cast<unsigned long long>(count_))
                   : "n=0";
        *this = Timing{};
        return text;
    }

private:
    double total_ = 0.0;
    double max_ = 0.0;
    std::uint64_t count_ = 0;
};

LARGE_INTEGER Now() {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    return now;
}

double MicrosSince(LARGE_INTEGER start) {
    static const LONGLONG frequency = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    return (Now().QuadPart - start.QuadPart) * 1e6 / frequency;
}

double PrivateMegabytes() {
    PROCESS_MEMORY_COUNTERS_EX counters{};
    counters.cb = sizeof(counters);
    if (!GetProcessMemoryInfo(GetCurrentProcess(), reinterpret_cast<PROCESS_MEMORY_COUNTERS*>(&counters),
                              sizeof(counters))) {
        return -1.0;
    }
    return counters.PrivateUsage / (1024.0 * 1024.0);
}

bool OurWindowInForeground() {
    DWORD pid = 0;
    GetWindowThreadProcessId(GetForegroundWindow(), &pid);
    return pid == GetCurrentProcessId();
}

bool CursorVisible(std::uintptr_t controller) {
    namespace bits = gen::bits::APlayerController;
    const auto word = mem::ReadOr<std::uint32_t>(controller + bits::bShowMouseCursor_Offset, 0);
    return (word >> bits::bShowMouseCursor_Shift) & 1;
}

// Width / height of the foreground (game) window's client area; 16:9 until known.
float WindowAspect() {
    static float aspect = 16.0f / 9.0f;
    RECT client{};
    if (OurWindowInForeground() && GetClientRect(GetForegroundWindow(), &client) && client.bottom > 0) {
        aspect = static_cast<float>(client.right) / static_cast<float>(client.bottom);
    }
    return aspect;
}

// Minecraft's FOV is vertical; Unreal's FMinimalViewInfo::FOV is horizontal.
float HorizontalFov(float vertical_degrees, float aspect) {
    constexpr double kDegrees = 180.0 / 3.14159265358979323846;
    const double half_vertical = vertical_degrees / kDegrees / 2.0;
    return static_cast<float>(2.0 * std::atan(std::tan(half_vertical) * aspect) * kDegrees);
}

// Minecraft's pose and camera are usable once Minecraft re-anchored to the current hand-over.
bool McAnchoredTo(std::uint32_t epoch, std::uint32_t generation) {
    const bridge::McState& mc = g_bridge.peer_state();
    return epoch != 0 && mc.valid && (mc.flags & protocol::kMcHasPose) &&
        !(mc.flags & (protocol::kMcDead | protocol::kMcRequestAnchor)) && mc.anchor_epoch == epoch &&
        input::SameScope(mc.sifu_session, g_bridge.session(), mc.world_generation, generation);
}

// H4's input (stage >= 4): Minecraft's camera replaces Sifu's while Minecraft owns the input.
void UpdateCameraOverride(std::uint32_t epoch, std::uint32_t generation) {
    static bool was_active = false;
    const bridge::McState& mc = g_bridge.peer_state();
    hooks::CameraOverride view;
    view.active = input::Router().owner() == InputOwner::Minecraft && McAnchoredTo(epoch, generation);
    if (view.active) {
        view.location = {mc.camera_location.x, mc.camera_location.y, mc.camera_location.z};
        view.rotation = {mc.camera_rotation.pitch, mc.camera_rotation.yaw, mc.camera_rotation.roll};
        view.fov = HorizontalFov(mc.camera_fov, WindowAspect());
    }
    hooks::SetCameraOverride(view);
    if (view.active != was_active) {
        was_active = view.active;
        if (view.active) {
            SC_LOG("[camera] override ON: loc=(%.0f, %.0f, %.0f) rot=(%.1f, %.1f) vfov=%.1f hfov=%.1f perspective=%u",
                   view.location.x, view.location.y, view.location.z, view.rotation.pitch, view.rotation.yaw,
                   mc.camera_fov, view.fov, mc.perspective);
        } else {
            SC_LOG("[camera] override OFF");
        }
    }
}

// Impostors are Sifu's stand-ins for characters (e.g. knocked-out enemies, named
// "Impostor_<character class>"). Only the player's own, right where the pawn is, stands in for the
// body Minecraft now owns; every other one is left alone.
constexpr float kImpostorHideRadius = 200.0f;

std::vector<std::uintptr_t> PlayerImpostorsNear(std::uintptr_t pawn, const protocol::Vec3& centre) {
    std::vector<std::uintptr_t> nearby;
    const std::string prefix = "Impostor_" + ue::ClassName(pawn);
    for (std::uintptr_t impostor : probe::Impostors()) {
        if (ue::ObjectName(impostor).rfind(prefix, 0) != 0) continue;
        const std::uintptr_t root = mem::ReadPtr(impostor + off::AActor::RootComponent);
        ue::FVector at{};
        if (!ue::ReadWorldLocation(root, &at)) continue;
        const float dx = at.x - centre.x, dy = at.y - centre.y, dz = at.z - centre.z;
        if (dx * dx + dy * dy + dz * dz <= kImpostorHideRadius * kImpostorHideRadius) nearby.push_back(impostor);
    }
    return nearby;
}

// H1's input (stage >= 5): Minecraft's player pose drives the Sifu pawn while Minecraft owns the
// input. Minecraft publishes feet; the pawn's location is its capsule centre.
void UpdatePoseOverride(std::uintptr_t pawn, float capsule_half_height, std::uint32_t epoch, std::uint32_t generation) {
    static bool was_active = false;
    const bridge::McState& mc = g_bridge.peer_state();
    hooks::PoseOverride pose;
    pose.active = hooks::PoseHooksReady() && input::Router().owner() == InputOwner::Minecraft &&
                  McAnchoredTo(epoch, generation) && capsule_half_height > 0.0f;
    std::vector<std::uintptr_t> impostors;
    if (pose.active) {
        pose.location = {mc.player_location.x, mc.player_location.y, mc.player_location.z + capsule_half_height};
        pose.yaw = mc.player_rotation.yaw;
        impostors = PlayerImpostorsNear(pawn, {pose.location.x, pose.location.y, pose.location.z});
    }
    hooks::SetPoseOverride(pose, impostors);
    if (pose.active != was_active) {
        was_active = pose.active;
        if (pose.active) {
            SC_LOG("[pose] authority -> MINECRAFT (epoch %u): pawn at (%.0f, %.0f, %.0f) yaw=%.1f", epoch,
                   pose.location.x, pose.location.y, pose.location.z, pose.yaw);
        } else {
            SC_LOG("[pose] authority -> sifu");
        }
    }
}

bool WorldPaused(std::uintptr_t world) {
    const std::uintptr_t level = mem::ReadPtr(world + off::UWorld::PersistentLevel);
    const std::uintptr_t settings = mem::ReadPtr(level + off::ULevel::WorldSettings);
    return mem::ReadPtr(settings + off::AWorldSettings::PauserPlayerState) != 0;
}

// What Sifu sets while a cinematic or script runs the player.
struct ScriptSignals {
    bool inputs_disabled = false;   // BP_FightingPlayer_C.b_InputsAreDisabled (Blueprint)
    std::uint8_t ignore_move = 0;   // AController::IgnoreMoveInput (Sequencer "disable movement input")
    std::uint8_t ignore_look = 0;   // AController::IgnoreLookInput
    bool cinematic = false;         // APlayerController::bCinematicMode
    std::uintptr_t attached_to = 0; // the capsule's attach parent (a cinematic carries the pawn)
    bool any() const { return inputs_disabled || ignore_move || ignore_look || cinematic || attached_to; }
    bool operator==(const ScriptSignals&) const = default;
};

// "Component (Owner)" for the log.
std::string DescribeComponent(std::uintptr_t component) {
    if (!component) return "-";
    return ue::ObjectName(component) + " (" + ue::ObjectName(ue::OuterOf(component)) + ")";
}

ScriptSignals ReadScriptSignals(const probe::PlayerObjects& player) {
    static std::uintptr_t cached_class = 0;
    static std::int32_t inputs_disabled_offset = -1;
    ScriptSignals s;
    if (player.pawn) {
        const std::uintptr_t cls = ue::ClassOf(player.pawn);
        if (cls != cached_class) {
            cached_class = cls;
            inputs_disabled_offset = ue::FindPropertyOffset(cls, "b_InputsAreDisabled");
            SC_LOG("[script] %s.b_InputsAreDisabled at %d", ue::ClassName(player.pawn).c_str(), inputs_disabled_offset);
        }
        if (inputs_disabled_offset >= 0) {
            s.inputs_disabled = mem::ReadOr<std::uint8_t>(player.pawn + inputs_disabled_offset, 0) != 0;
        }
        s.attached_to = ue::AttachParentOf(mem::ReadPtr(player.pawn + off::AActor::RootComponent));
    }
    if (player.controller) {
        namespace bits = gen::bits::APlayerController;
        s.ignore_move = mem::ReadOr<std::uint8_t>(player.controller + off::AController::IgnoreMoveInput, 0);
        s.ignore_look = mem::ReadOr<std::uint8_t>(player.controller + off::AController::IgnoreLookInput, 0);
        s.cinematic = (mem::ReadOr<std::uint8_t>(player.controller + bits::bCinematicMode_Offset, 0) >>
                       bits::bCinematicMode_Shift) & 1;
    }
    return s;
}

// Decides who gets the player (stage >= 3): input, and from stages 4/5 also camera and pose.
// Minecraft gets it only while every condition holds; otherwise Sifu does, and the router
// releases whatever Minecraft still holds. Sifu also gets it back while it runs the player itself:
// a cinematic / script flag is set, the pawn is attached to something, the pawn has only just
// spawned (a level start places and attaches it), or (fallback) Sifu keeps moving the pawn over
// our writes.
// Every hand-over to Minecraft starts a new anchor epoch so Minecraft re-anchors to where the
// pawn is now instead of pulling it back to where it was.
class Ownership {
public:
    // `minecraft_has_collision`: Minecraft has loaded a collision grid of the current world.
    void Update(std::uintptr_t world, const probe::PlayerObjects& player, const protocol::Vec3& pawn_location,
                std::uint64_t now_ms, bool minecraft_has_collision, std::uint32_t generation, bool allow_reanchor) {
        collision_ready_ = minecraft_has_collision;
        const ScriptSignals signals = ReadScriptSignals(player);
        if (!(signals == signals_)) {
            SC_LOG("[script] inputs_disabled=%d ignore_move=%u ignore_look=%u cinematic=%d attached_to=%s",
                   signals.inputs_disabled, signals.ignore_move, signals.ignore_look, signals.cinematic,
                   DescribeComponent(signals.attached_to).c_str());
            signals_ = signals;
        }
        const auto identity = ue::IdentityOf(player.pawn);
        const bool new_pawn_scope = player.pawn != pawn_ || identity != pawn_identity_ || generation != world_generation_;
        if (new_pawn_scope) {
            pawn_ = player.pawn;
            pawn_identity_ = identity;
            world_generation_ = generation;
            pawn_since_ms_ = now_ms;
            displacement_.Reset(pawn_location, now_ms);
            if (input::Router().owner() == InputOwner::Minecraft) {
                epoch_ = input::NextEpoch(epoch_);
                SC_LOG("[input] invalidated old pose after world/pawn change (epoch %u)", epoch_);
            }
        }
        const auto& mc = g_bridge.peer_state();
        if (allow_reanchor && world && player.pawn && g_bridge.peer_connected() && mc.valid &&
            input::SameScope(mc.sifu_session, g_bridge.session(), mc.world_generation, generation) &&
            input::WantsAnchor(mc.flags, mc.anchor_epoch, epoch_)) {
            epoch_ = input::NextEpoch(epoch_);
            SC_LOG("[respawn] acknowledged anchor request %u -> %u world=%u", mc.anchor_epoch, epoch_, generation);
        }
        now_ms_ = now_ms;
        if (!new_pawn_scope) UpdateDriving(player.pawn != 0, pawn_location, now_ms);

        const bool toggle = input::Router().TakeToggleRequest();
        const bool recovered = toggle && displacement_.ManualRecovery(now_ms);
        if (recovered) SC_LOG("[script] F8 cleared the displacement-only handover wait; explicit script/menu guards remain active");
        const char* guard = GuardReason(world, player);
        if (toggle) {
            intent_.RequestSwitch(input::Router().owner(), guard != nullptr || recovered);
            SC_LOG("[input] F8: request %s%s%s", intent_.enabled() ? "MINECRAFT" : "sifu",
                   guard ? "; waiting: " : "", guard ? guard : "");
        }
        const char* blocked = intent_.enabled() ? guard : "disabled with F8";
        const InputOwner desired = intent_.Desired(guard != nullptr);
        if (desired != input::Router().owner()) {
            if (desired == InputOwner::Minecraft) epoch_ = input::NextEpoch(epoch_);
            input::Router().SetOwner(desired);
            g_bridge.SendOwnerChanged(desired);
            SC_LOG("[input] owner -> %s%s%s (epoch %u)", desired == InputOwner::Minecraft ? "MINECRAFT" : "sifu",
                   blocked ? ": " : "", blocked ? blocked : "", epoch_);
        } else if (blocked && last_reason_ != blocked) {
            SC_LOG("[input] owner stays sifu: %s", blocked);
        }
        last_reason_ = blocked ? blocked : "";
    }

    std::uint32_t epoch() const { return epoch_; }

private:
    static constexpr std::uint64_t kSettleMs = 2000;

    // Fallback for scripts that set none of the flags: Sifu moving the pawn over our writes on
    // several frames in a row means it is driving the player. Take the player back only once the
    // pawn has stood still for a moment; each time Sifu takes it again right after, wait twice as
    // long before the next try.
    void UpdateDriving(bool has_pawn, const protocol::Vec3& location, std::uint64_t now_ms) {
        const auto frames = hooks::ConsecutiveDisplacements();
        const auto change = displacement_.Update(input::Router().owner() == InputOwner::Minecraft,
            hooks::PoseActive(), has_pawn, location, frames, now_ms);
        if (change == input::DisplacementGuard::Change::Yield) {
            SC_LOG("[script] Sifu moved the pawn on %u frames in a row: yielding the player to Sifu until it "
                   "stands still for %llu ms", frames, static_cast<unsigned long long>(displacement_.quiet_ms()));
        } else if (change == input::DisplacementGuard::Change::Recovered) {
            SC_LOG("[script] the pawn has been still for %llu ms: Sifu stopped driving it", static_cast<unsigned long long>(displacement_.quiet_ms()));
        }
    }

    const char* GuardReason(std::uintptr_t world, const probe::PlayerObjects& player) const {
        if (!hooks::InputHooksInstalled()) return "input hooks not installed";
        if (!g_bridge.peer_connected()) return "minecraft not connected";
        const bridge::McState& mc = g_bridge.peer_state();
        if (!mc.valid || !(mc.flags & protocol::kMcReady)) return "minecraft not in a world";
        if (!world || !player.pawn || !player.controller) return "no pawn";
        if (!collision_ready_) return "minecraft has no collision for this level yet";
        if (WorldPaused(world)) return "game paused";
        if (CursorVisible(player.controller)) return "cursor visible (menu)";
        if (signals_.cinematic) return "sifu script: cinematic mode";
        if (signals_.inputs_disabled) return "sifu script: player inputs disabled";
        if (signals_.ignore_move) return "sifu script: movement input ignored";
        if (signals_.ignore_look) return "sifu script: look input ignored";
        if (signals_.attached_to) return "sifu script: pawn attached";
        if (now_ms_ - pawn_since_ms_ < kSettleMs) return "pawn just spawned (settling)";
        if (displacement_.driving()) return "sifu script: Sifu is driving the pawn";
        if (!OurWindowInForeground()) return "sifu window not in foreground";
        return nullptr;
    }

    input::OwnershipIntent intent_;
    bool collision_ready_ = false;
    std::string last_reason_;
    ScriptSignals signals_;
    std::uintptr_t pawn_ = 0;
    ue::ObjectIdentity pawn_identity_;
    std::uint32_t world_generation_ = 0;
    std::uint64_t pawn_since_ms_ = 0;
    std::uint64_t now_ms_ = 0;
    input::DisplacementGuard displacement_;
    std::uint32_t epoch_ = 0;
};

// "Wuguan_Main" from "/Game/Maps/Wuguan/Wuguan_Main.Wuguan_Main".
std::string MapName(const std::string& world_path) {
    const std::size_t dot = world_path.rfind('.');
    return dot == std::string::npos ? world_path : world_path.substr(dot + 1);
}

// P3: once a pawn exists and the set of visible levels has stayed the same for a few seconds (and
// again whenever level streaming changes that set), surveys the world's collision and has it
// voxelized off this thread. While Sifu itself walks the pawn, measures the grid against it.
class CollisionSchedule {
public:
    void Update(std::uintptr_t world, std::uint32_t generation, const std::string& world_path, std::uintptr_t pawn,
                const bridge::PlayerFrame& frame, std::uint64_t now_ms) {
        if (!world || !pawn) return;
        UpdateReferenceResponses(pawn);
        const int levels = collision::VisibleLevelCount(world);
        if (generation != generation_ || levels != levels_) {
            if (generation != generation_) last_components_ = 0;
            generation_ = generation;
            levels_ = levels;
            stable_since_ms_ = now_ms;
            done_ = false;
        }
        if (!done_ && now_ms - stable_since_ms_ >= kStableMs) {
            if (!have_reference_) return;  // the pawn's normal collision has not been seen yet
            done_ = true;
            probe::LogCollisionCensus(world, pawn);
            probe::LogBlockCensus(world, pawn);
            const ULONGLONG start = GetTickCount64();
            collision::BuildRequest request;
            request.shapes = collision::ExtractShapes(world, reference_, PawnWalkableFloorZ(pawn));
            if (request.shapes.components == 0 && last_components_ > 0) {
                // Nothing blocks any more in a world that had collision: the readout is wrong, not
                // the level. Keep Minecraft's grid and look again later.
                SC_LOG("[collision] extraction found nothing (last time %d components): keeping the current grid",
                       last_components_);
                stable_since_ms_ = now_ms;
                done_ = false;
                return;
            }
            last_components_ = request.shapes.components;
            request.map = MapName(world_path);
            request.generation = generation;
            map_ = request.map;
            request.pawn_feet = {frame.player_location.x, frame.player_location.y,
                                 frame.player_location.z - frame.capsule_half_height};
            const collision::ShapeSet& s = request.shapes;
            SC_LOG("[collision] extracted %zu convex %zu capsules %zu triangles from %d components in %llu ms | boxes=%d "
                   "convex_elems=%d spheres=%d capsule_elems=%d triangle_meshes=%d instances=%d | left out: "
                   "movable=%d unreadable_meshes=%d no_body_setup=%d capped=%d",
                   s.convexes.size(), s.capsules.size(), s.triangles.size(), s.components,
                   static_cast<unsigned long long>(GetTickCount64() - start), s.boxes, s.convex_elems, s.spheres,
                   s.capsule_elems, s.triangle_meshes, s.instances, s.skipped_movable, s.unreadable_meshes,
                   s.no_body_setup, s.capped);
            builder_.Submit(std::move(request));
        }
    }

    // One sample of collision::WalkCheck, taken only while Sifu moves the pawn on the ground.
    void Check(std::uint32_t generation, const probe::PlayerObjects& player, const bridge::PlayerFrame& frame) {
        if (input::Router().owner() != InputOwner::Sifu || !player.pawn || !player.movement) return;
        const std::shared_ptr<const collision::VoxelGrid> grid = builder_.Latest(generation);
        if (!grid) return;
        const std::uintptr_t capsule = mem::ReadPtr(player.pawn + off::AActor::RootComponent);
        if (ue::AttachParentOf(capsule)) return;
        const auto mode = mem::ReadOr<std::uint8_t>(player.movement + off::UCharacterMovementComponent::MovementMode, 0);
        if (mode != kMoveWalking && mode != kMoveNavWalking) return;
        if (std::fabs(frame.player_velocity.z) > kStillVerticalCmPerS) return;
        const float half = frame.capsule_half_height;
        const float radius = mem::ReadOr<float>(capsule + off::UCapsuleComponent::CapsuleRadius, 0.0f);
        walk_check_.Sample(*grid, {{frame.player_location.x, frame.player_location.y, frame.player_location.z - half},
                                   radius, half});
    }

    // Logs the walk check and appends its samples to SifuCraft\debug\walk_<map>.csv.
    bool GridBuilt(std::uint32_t generation) const { return builder_.Latest(generation) != nullptr; }
    bool HasReference() const { return have_reference_; }
    collision::Responses Reference() const { return reference_; }
    float WalkableZ(std::uintptr_t pawn) { return PawnWalkableFloorZ(pawn); }
    bool NativeSupport(std::uint32_t generation, const protocol::Vec3& feet) const {
        const auto grid = builder_.Latest(generation);
        return grid && collision::HasFloorSupport(*grid, {feet.x, feet.y, feet.z});
    }
    std::shared_ptr<const collision::VoxelGrid> MotionGrid(std::uint32_t generation) const { return builder_.Latest(generation); }

    void FlushCheck() {
        if (walk_check_.samples() == 0) return;
        const std::wstring debug = log::RootDirectory() + L"\\debug";
        CreateDirectoryW(debug.c_str(), nullptr);
        const std::wstring csv = debug + L"\\walk_" + std::wstring(map_.begin(), map_.end()) + L".csv";
        if (!collision::AppendWalkSamples(csv, walk_check_.scored())) {
            SC_LOG("[collision-check] could not append to debug\\walk_%s.csv", map_.c_str());
        }
        walk_check_.Flush(map_);
    }

    // Hands a newly published grid to Minecraft (CollisionManifest) and reports when Minecraft has
    // loaded it.
    void Share() {
        const collision::PublishedGrid grid = builder_.Published();
        if (grid.generation != shared_generation_) {
            shared_generation_ = grid.generation;
            bridge::CollisionInfo info;
            info.generation = grid.generation;
            info.world_generation = grid.world_generation;
            info.block_count = grid.block_count;
            info.cell_count = grid.cell_count;
            info.map = grid.map;
            info.path = Utf8(grid.path);
            g_bridge.PublishCollision(info);
            history_.push_back({grid.generation, grid.world_generation});
            if (history_.size() > kHistory) history_.erase(history_.begin());
        }
        const std::uint32_t loaded = g_bridge.peer_connected() ? g_bridge.peer_state().collision_generation : 0;
        if (loaded != loaded_generation_) {
            loaded_generation_ = loaded;
            if (loaded == 0) {
                SC_LOG("[collision] minecraft has no grid loaded");
            } else {
                SC_LOG("[collision] minecraft loaded grid %u%s", loaded,
                       loaded == shared_generation_ ? " (current)" : " (not the current one)");
            }
        }
    }

    // Whether the grid Minecraft reports loaded was built from world `generation`. Any grid of the
    // current world will do: a rebuild after level streaming must not take the player away.
    bool MinecraftHasGrid(std::uint32_t generation) const {
        if (!g_bridge.peer_connected()) return false;
        const std::uint32_t loaded = g_bridge.peer_state().collision_generation;
        for (const auto& [grid, world] : history_) {
            if (grid == loaded) return world == generation;
        }
        return false;
    }

private:
    // The steepest floor the pawn's CharacterMovement walks on (as a normal's Z), logged when it
    // changes; UE's default if it cannot be read.
    float PawnWalkableFloorZ(std::uintptr_t pawn) {
        namespace movement = off::UCharacterMovementComponent;
        const std::uintptr_t component = mem::ReadPtr(pawn + off::ACharacter::CharacterMovement);
        const float z = component ? mem::ReadOr<float>(component + movement::WalkableFloorZ, 0.0f) : 0.0f;
        if (!(z > 0.0f && z <= 1.0f)) {
            SC_LOG("[collision] could not read the pawn's walkable floor Z: using %.3f",
                   collision::kDefaultWalkableFloorZ);
            return collision::kDefaultWalkableFloorZ;
        }
        if (z != logged_walkable_z_) {
            logged_walkable_z_ = z;
            SC_LOG("[collision] pawn movement: walkable floor angle %.2f deg (Z %.4f), max step height %.1f cm",
                   mem::ReadOr<float>(component + movement::WalkableFloorAngle, 0.0f), z,
                   mem::ReadOr<float>(component + movement::MaxStepHeight, 0.0f));
        }
        return z;
    }

    // The pawn's collision filtering as it is for walking. Sifu narrows it for moves such as a
    // vault (the capsule then blocks nothing) and in some menus (a few channels only), so the
    // collision is always extracted against the widest filtering seen: a narrower one never
    // replaces it.
    void UpdateReferenceResponses(std::uintptr_t pawn) {
        const collision::Responses now = collision::ReadResponses(mem::ReadPtr(pawn + off::AActor::RootComponent));
        const int blocked = static_cast<int>(std::count(std::begin(now.to_channel), std::end(now.to_channel), kBlock));
        if (now.object_type >= 32 || blocked < kMinBlockedChannels) return;
        if (have_reference_ && blocked <= reference_blocked_) return;
        SC_LOG("[collision] pawn collision reference: type %u, %d blocked channels", now.object_type, blocked);
        reference_ = now;
        reference_blocked_ = blocked;
        have_reference_ = true;
    }

    static constexpr std::uint64_t kStableMs = 3000;
    static constexpr std::uint8_t kBlock = 2;  // ECollisionResponse
    static constexpr int kMinBlockedChannels = 3;
    static constexpr std::size_t kHistory = 16;
    static constexpr std::uint8_t kMoveWalking = 1;     // EMovementMode
    static constexpr std::uint8_t kMoveNavWalking = 2;
    static constexpr float kStillVerticalCmPerS = 5.0f;
    static std::string Utf8(const std::wstring& text) {
        if (text.empty()) return {};
        const int size = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0,
                                             nullptr, nullptr);
        std::string out(static_cast<std::size_t>(size), '\0');
        WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), out.data(), size, nullptr,
                            nullptr);
        return out;
    }

    collision::Builder builder_;
    collision::Responses reference_;
    int reference_blocked_ = 0;
    bool have_reference_ = false;
    int last_components_ = 0;
    collision::WalkCheck walk_check_;
    float logged_walkable_z_ = 0.0f;
    std::string map_;
    std::uint32_t shared_generation_ = 0;
    std::uint32_t loaded_generation_ = 0;
    std::vector<std::pair<std::uint32_t, std::uint32_t>> history_;  // (grid, world generation) published
    std::uint32_t generation_ = 0;
    int levels_ = -1;
    std::uint64_t stable_since_ms_ = 0;
    bool done_ = false;
};

class DoorSchedule {
public:
    void Update(std::uintptr_t world, std::uint32_t generation, const std::string& path, std::uintptr_t pawn,
                CollisionSchedule& collision, std::uint64_t now) {
        if (!world || !pawn || !collision.HasReference() || now < next_) return;
        next_ = now + 100;
        if (generation != generation_) {
            generation_ = generation; hash_ = 0; scan_ = 0; shared_ = 0; components_.clear();
            g_bridge.PublishDoors({});
        }
        const int visible = collision::VisibleLevelCount(world);
        const auto candidate = interaction::Snapshot();
        if (now >= scan_ || visible != levels_ || (candidate.owner && candidate.owner != target_owner_)) {
            components_.clear();
            for (auto p : collision::DynamicSceneComponents(world)) components_.push_back({p, ue::IdentityOf(p)});
            scan_ = now + 5000; levels_ = visible; target_owner_ = candidate.owner;
        }
        ue::FVector center{};
        if (!ue::ReadWorldLocation(mem::ReadPtr(pawn + off::AActor::RootComponent), &center)) return;
        std::uint64_t hash = 14695981039346656037ull;
        auto mix = [&](std::uint64_t v) { hash = (hash ^ v) * 1099511628211ull; };
        std::vector<std::uintptr_t> nearby;
        const auto reference = collision.Reference();
        for (const auto& [p, id] : components_) {
            if (!ue::IsLive(p) || ue::IdentityOf(p) != id || !collision::VisibleLevelOf(p, world)) continue;
            ue::Affine transform;
            if (!ue::ReadComponentToWorld(p, &transform)) continue;
            ue::FVector origin = transform.t, extent{};
            mem::Read(p + off::USceneComponent::Bounds + off::FBoxSphereBounds::Origin, &origin);
            mem::Read(p + off::USceneComponent::Bounds + off::FBoxSphereBounds::BoxExtent, &extent);
            const float dx = std::max(0.0f, std::fabs(origin.x - center.x) - extent.x),
                dy = std::max(0.0f, std::fabs(origin.y - center.y) - extent.y),
                dz = std::max(0.0f, std::fabs(origin.z - center.z) - extent.z);
            if (dx * dx + dy * dy + dz * dz > 2500 * 2500) continue;
            nearby.push_back(p); mix(static_cast<std::uint32_t>(id.index)); mix(id.serial);
            mix(collision::Blocks(p, reference));
            mix(collision::BodySetupOf(p));
            const float* values = &transform.m[0][0];
            for (int i = 0; i < 9; ++i) mix(std::bit_cast<std::uint32_t>(values[i]));
            mix(std::bit_cast<std::uint32_t>(transform.t.x)); mix(std::bit_cast<std::uint32_t>(transform.t.y)); mix(std::bit_cast<std::uint32_t>(transform.t.z));
        }
        if (hash != hash_) {
            hash_ = hash;
            collision::BuildRequest request;
            request.shapes = collision::ExtractComponents(nearby, reference, collision.WalkableZ(pawn));
            request.map = path.substr(path.find_last_of('.') + 1); request.generation = generation;
            request.file_tag = "-doors"; request.diagnostics = false; request.pawn_feet = center;
            builder_.Submit(std::move(request));
        }
        const auto grid = builder_.Published();
        if (grid.generation && grid.world_generation == generation && grid.generation != shared_) {
            shared_ = grid.generation;
            const int size = WideCharToMultiByte(CP_UTF8, 0, grid.path.data(), static_cast<int>(grid.path.size()), nullptr, 0, nullptr, nullptr);
            std::string file(size, '\0');
            WideCharToMultiByte(CP_UTF8, 0, grid.path.data(), static_cast<int>(grid.path.size()), file.data(), size, nullptr, nullptr);
            g_bridge.PublishDoors({grid.generation, grid.world_generation, grid.block_count, grid.cell_count, grid.map, file});
        }
    }
private:
    collision::Builder builder_;
    std::vector<std::pair<std::uintptr_t, ue::ObjectIdentity>> components_;
    std::uint64_t next_ = 0, scan_ = 0, hash_ = 0;
    std::uint32_t generation_ = 0, shared_ = 0;
    std::uint32_t target_owner_ = 0;
    int levels_ = -1;
};
}  // namespace

void Run(std::uintptr_t image_base) {
    probe::WaitForEngine(image_base);
    g_bridge.Start();
    const config::Settings settings = config::Load();
    combat::motion::Configure(settings.npc_motion_probe,settings.npc_ai_probe);
    hooks::Configure(image_base, settings.stage);
    bool engine_hooked = false;
    Ownership ownership;
    CollisionSchedule collision_schedule;
    DoorSchedule door_schedule;
    probe::CombatCensus combat_census;
    combat::Enemies enemies;
    shape_data::Reader shape_reader;
    shape_data::Frame shape_frame;
    world_motion::Mailbox world_mailbox;
    std::vector<protocol::Event> damage_events;
    std::vector<combat::damage::Reply> damage_replies;
    std::deque<combat::damage::Reply> pending_damage_replies;
    std::vector<combat::player_hits::Hit> player_hits;
    std::deque<combat::player_hits::Hit> pending_player_hits;
    unsigned logged_player_hits = 0;
    input::InputRouter::Drained drained;

    std::uintptr_t world_ptr = 0;
    std::string world_path;
    std::string language;
    std::uint32_t generation = 0;
    std::uint32_t dry_run_generation = 0;
    bool prepared = false;
    Timing tick_timing, log_timing;

    for (std::uint64_t tick = 1;; ++tick) {
        Sleep(kTickMs);
        const LARGE_INTEGER start = Now();
        const bool log_tick = tick % kLogEveryTicks == 0;
        const std::uintptr_t world = mem::ReadPtr(image_base + gen::rva::GWorld);

        bridge::PlayerFrame frame;
        if (tick % 100 == 1) {
            const auto current_language = ue::ReadLanguage(image_base);
            if (!current_language.empty() && current_language != language) {
                language = current_language;
                SC_LOG("[language] UI culture=%s (read-only)", language.c_str());
            }
        }
        frame.language = language;
        if (!world) {
            if (world_ptr) SC_LOG("[world] none");
            world_ptr = 0;
            world_path.clear();
        } else {
            if (!prepared) {
                probe::Prepare();
                prepared = true;
            }
            if (world != world_ptr || log_tick) {
                std::string path = ue::PathName(world);
                if (world != world_ptr || path != world_path) {
                    world_ptr = world;
                    world_path = std::move(path);
                    ++generation;
                    SC_LOG("[world] gen=%u %s (0x%llX)", generation, world_path.c_str(),
                           static_cast<unsigned long long>(world));
                }
            }
            frame.world_generation = generation;
            frame.world_path = world_path;
            probe::CapturePlayer(world, &frame);
            if ((frame.flags & protocol::kSnapshotHasPawn) && dry_run_generation != generation) {
                // Instances change with every world: re-verify the hook plan once a pawn exists.
                hooks::DryRun(image_base, world);
                dry_run_generation = generation;
            }
        }

        probe::PlayerObjects player;
        if (settings.stage >= 1) {
            if (world) player = probe::FindPlayerObjects(world);
            hooks::SetLocalPlayer(player.controller, player.camera, player.pawn, player.movement);
            if (tick % kHookRetryEveryTicks == 1) {
                if (!engine_hooked) engine_hooked = hooks::InstallEngineHook();
                hooks::InstallPlayerHooks();
            }
            hooks::Maintain(GetTickCount64());
            if (settings.stage >= 3) {
                ownership.Update(world, player, frame.player_location, GetTickCount64(),
                                 collision_schedule.MinecraftHasGrid(generation), generation, settings.stage >= 7);
                frame.anchor_epoch = ownership.epoch();
                const auto& gui_peer = g_bridge.peer_state();
                render::Scope gui_scope{g_bridge.peer_connected() ? g_bridge.peer_session() : 0, g_bridge.session(),
                    generation, ownership.epoch(), GetTickCount64(),
                    frame.input_owner == InputOwner::Minecraft};
                gui_scope.enabled = input::Router().owner() == InputOwner::Minecraft && hooks::PoseActive() &&
                    McAnchoredTo(ownership.epoch(), generation);
                const bool gui_surface = settings.stage >= 8 &&
                    render::FreshPresentation(gui_scope, render::LastPresentation(), GetTickCount64());
                if (gui_surface) frame.flags |= protocol::kSnapshotGuiAvailable;
                input::Router().SetGuiState(gui_surface && gui_peer.valid && (gui_peer.flags & protocol::kMcGuiAvailable),
                    (gui_peer.flags & protocol::kMcGuiOpen) != 0, gui_peer.gui_input_ack,
                    (gui_peer.flags & protocol::kMcGuiTextInput) != 0);
                drained.events.clear();
                input::Router().Drain(&drained);
                for (const input::ForwardedEvent& e : drained.events) {
                    if (e.type == protocol::EventType::GuiMouseMotion) g_bridge.SendGuiMotion(e.mouse_x, e.mouse_y, e.scroll);
                    else if (!g_bridge.SendInput(e.type, e.code, e.action, e.modifiers, e.gui_revision))
                        input::Router().GuiInputDropped(e.gui_revision);
                }
                frame.input_owner = input::Router().owner();
                frame.mouse_dx = drained.mouse_dx;
                frame.mouse_dy = drained.mouse_dy;
                frame.scroll = drained.scroll;
            }
        }
        g_bridge.Tick(frame, GetTickCount64());
        if (settings.npc_motion_probe || settings.npc_ai_probe) {
            combat::motion::Context motion;
            const auto world_id = ue::IdentityOf(world);
            motion.scope = {g_bridge.session(), g_bridge.peer_connected() ? g_bridge.peer_session() : 0,
                world, generation, world_id.index, world_id.serial};
            motion.pawn = player.pawn; motion.pawn_identity = ue::IdentityOf(player.pawn);
            motion.updated_ms = GetTickCount64();
            const auto& motion_peer = g_bridge.peer_state();
            motion.enabled = g_bridge.peer_connected() && motion_peer.valid && world && player.pawn &&
                motion_peer.sifu_session == g_bridge.session() && motion_peer.world_generation == generation &&
                (motion_peer.flags & protocol::kMcReady) && !(motion_peer.flags & protocol::kMcGuiOpen);
            motion.targets = enemies.targets(); motion.grid = collision_schedule.MotionGrid(generation);
            combat::motion::UpdateContext(std::move(motion));
        }
        if (settings.stage >= 10) {
            const auto shape_map = MapName(world_path);
            if (!g_bridge.peer_connected() || shape_frame.source_mc != g_bridge.peer_session() ||
                shape_frame.header.world != generation || shape_map != shape_frame.header.map) shape_frame = {};
            if (g_bridge.peer_connected()) shape_reader.Read(g_bridge.peer_session(),g_bridge.session(),generation,shape_map,GetTickCount64(),&shape_frame);
            else shape_reader.Close();
        }
        input::Router().SetUseBinding(settings.stage >= 8 && g_bridge.peer_connected() && g_bridge.peer_state().valid ? g_bridge.peer_state().use_binding : 0);
        if (settings.stage >= 7 && tick % 2 == 0) {
            enemies.Update(world, generation, player.pawn, GetTickCount64());
            g_bridge.PublishEnemies(world ? generation : 0, enemies.entries());
        }
        // Blocks wait until the level has settled (its collision grid is built): by then the
        // materials they are made of have streamed in.
        if (settings.stage >= 6 && world && (frame.flags & protocol::kSnapshotHasPawn) &&
            collision_schedule.GridBuilt(generation)) {
            const auto* shapes = settings.stage >= 10 && shape_frame.header.revision ? &shape_frame : nullptr;
            blocks::Update(world, MapName(world_path), g_bridge.blocks(),shapes);
            if (shapes) shape_reader.Acknowledge(*shapes,blocks::ShapesReady(),GetTickCount64(),blocks::ShapesAppliedRevision());
        }
        if(settings.stage>=10) {
            world_motion::Context context;
            const auto world_id=ue::IdentityOf(world);
            context.actors.scope={g_bridge.session(),g_bridge.peer_connected()?g_bridge.peer_session():0,
                world,generation,world_id.index,world_id.serial};
            context.actors.pawn=player.pawn;context.actors.pawn_identity=ue::IdentityOf(player.pawn);
            context.actors.updated_ms=GetTickCount64();
            const auto& peer=g_bridge.peer_state();
            context.actors.enabled=g_bridge.peer_connected()&&peer.valid&&world&&player.pawn&&
                peer.sifu_session==g_bridge.session()&&peer.world_generation==generation&&
                (peer.flags&protocol::kMcReady);
            context.actors.targets=enemies.targets();context.actors.grid=collision_schedule.MotionGrid(generation);
            context.enabled=!settings.npc_motion_probe&&!settings.npc_ai_probe;
            if(shape_frame.source_mc==context.actors.scope.peer_session&&shape_frame.header.world==generation&&
               shape_frame.header.revision&&MapName(world_path)==shape_frame.header.map) {
                context.save=shape_frame.header.save_key;
                context.map=shape_data::Hash(shape_frame.header.map,std::strlen(shape_frame.header.map));
            }
            world_motion::Exchange(std::move(context),world_mailbox);
        }
        if (settings.stage >= 4) {
            UpdateCameraOverride(ownership.epoch(), generation);
            if (world) hooks::camera_fade::Resolve();
        }
        if (settings.stage >= 5) UpdatePoseOverride(player.pawn, frame.capsule_half_height, ownership.epoch(), generation);
        hooks::streaming_guard::Context streaming;
        if (settings.stage >= 8) {
            hooks::streaming_guard::Resolve();
            streaming.world = world; streaming.pawn = player.pawn;
            streaming.world_identity = ue::IdentityOf(world);
            streaming.pawn_identity = ue::IdentityOf(player.pawn);
            streaming.updated_ms = GetTickCount64();
            streaming.active = hooks::PoseActive() && input::Router().owner() == InputOwner::Minecraft &&
                McAnchoredTo(ownership.epoch(), generation);
            const auto& mc = g_bridge.peer_state();
            streaming.freeze = !(mc.flags & protocol::kMcOnGround) || !collision_schedule.NativeSupport(generation, mc.player_location);
        }
        hooks::streaming_guard::UpdateContext(streaming);
        interaction::Context interact;
        interact.world = world; interact.pawn = player.pawn; interact.controller = player.controller;
        interact.pawn_id = ue::IdentityOf(player.pawn); interact.controller_id = ue::IdentityOf(player.controller);
        interact.generation = generation; interact.epoch = ownership.epoch(); interact.updated_ms = GetTickCount64();
        interact.session = g_bridge.peer_connected() ? g_bridge.peer_session() : 0;
        interact.eye = g_bridge.peer_state().camera_location;
        interact.pose_owned = settings.stage >= 8 && streaming.active;
        interact.active = interact.pose_owned && !(g_bridge.peer_state().flags & protocol::kMcGuiOpen);
        interaction::UpdateContext(interact);
        g_bridge.PublishInteraction(interaction::Snapshot());
        if (settings.stage >= 8) {
            render::Scope scope;
            scope.mc_session = g_bridge.peer_connected() ? g_bridge.peer_session() : 0;
            scope.sifu_session = g_bridge.session();
            scope.world = generation; scope.epoch = ownership.epoch(); scope.updated_ms = GetTickCount64();
            scope.enabled = g_bridge.peer_connected() && frame.input_owner == InputOwner::Minecraft &&
                hooks::PoseActive() && McAnchoredTo(ownership.epoch(), generation);
            scope.world_enabled = settings.stage >= 9 && g_bridge.peer_connected() && world && player.controller &&
                (frame.flags & protocol::kSnapshotHasPawn) && (frame.flags & protocol::kSnapshotHasCamera) &&
                McAnchoredTo(ownership.epoch(), generation) && !WorldPaused(world) && !CursorVisible(player.controller);
            render::Maintain(image_base, scope, settings.stage);
            ui::UpdateHudContext(world, player.controller, player.pawn, scope);
        }
        if (settings.stage >= 7) {
            combat::damage::InstallObserver(image_base, player.pawn);
            combat::player_hits::InstallObserver(image_base, player.pawn);
            combat::damage::UpdateContext(world, generation, player.pawn,
                g_bridge.peer_connected() && frame.input_owner == InputOwner::Minecraft && hooks::PoseActive(),
                ownership.epoch(), g_bridge.peer_connected() ? g_bridge.peer_session() : 0, enemies.targets(), enemies.entries(), settings.stage >= 8);
            combat::player_hits::UpdateContext(world, generation, player.pawn,
                g_bridge.peer_connected() && frame.input_owner == InputOwner::Minecraft,
                hooks::PoseActive() && McAnchoredTo(ownership.epoch(), generation), ownership.epoch(),
                g_bridge.peer_connected() ? g_bridge.peer_session() : 0, enemies.targets());
            g_bridge.DrainEnemyDamage(&damage_events);
            for (const auto& event : damage_events) {
                if (event.type == static_cast<std::uint32_t>(protocol::EventType::PlayerBlocked)) {
                    if (settings.stage >= 8) combat::damage::SubmitBlocked(event);
                }
                else if (event.type == static_cast<std::uint32_t>(protocol::EventType::EnemyImpulse)) {
                    if (settings.stage >= 8) combat::damage::SubmitImpulse(event);
                }
                else if (event.type == static_cast<std::uint32_t>(protocol::EventType::NativeInteract)) {
                    if (settings.stage >= 8) interaction::Submit(event);
                }
                else combat::damage::Submit(event);
            }
            if (pending_damage_replies.size() < 512) {
                combat::damage::DrainReplies(&damage_replies);
                for (const auto& reply : damage_replies) pending_damage_replies.push_back(reply);
            }
            while (!pending_damage_replies.empty()) {
                const auto& reply = pending_damage_replies.front();
                if (!g_bridge.peer_connected() || reply.session != g_bridge.peer_session()) {
                    pending_damage_replies.pop_front();
                    continue;
                }
                if (!g_bridge.SendDamageResult(reply.payload)) break;
                pending_damage_replies.pop_front();
            }
            if (pending_player_hits.size() < 128) {
                combat::player_hits::DrainHits(&player_hits);
                for (const auto& hit : player_hits) pending_player_hits.push_back(hit);
            }
            while (!pending_player_hits.empty()) {
                const auto& hit = pending_player_hits.front();
                if (!g_bridge.peer_connected() || hit.session != g_bridge.peer_session() ||
                    frame.input_owner != InputOwner::Minecraft || hit.payload.world_generation != generation ||
                    hit.payload.anchor_epoch != ownership.epoch() || !McAnchoredTo(ownership.epoch(), generation) || GetTickCount64() - hit.created_ms > 2000) {
                    if (logged_player_hits++ < 200) SC_LOG("[player-hits] discarded queued hit after scope change or timeout");
                    pending_player_hits.pop_front(); continue;
                }
                std::uint64_t hit_id = 0;
                if (!g_bridge.SendPlayerHit(hit.payload, &hit_id)) break;
                if (settings.stage >= 8) combat::damage::RecordPlayerHit(hit_id, hit.session, hit.payload, hit.created_ms);
                if (logged_player_hits++ < 200) SC_LOG("[player-hits] sent attacker=%u/%u base=%.3f flags=0x%X world=%u epoch=%u",
                    hit.payload.attacker_id, hit.payload.spawn, hit.payload.sifu_damage, hit.payload.flags,
                    hit.payload.world_generation, hit.payload.anchor_epoch);
                pending_player_hits.pop_front();
            }
        }
        if (settings.stage < 7) combat::damage::UpdateContext(world, generation, player.pawn, false, 0, 0, {}, {}, false);
        {
            static std::deque<interaction::Reply> pending;
            std::vector<interaction::Reply> replies;
            interaction::DrainReplies(&replies);
            for (const auto& reply : replies) if (pending.size() < 64) pending.push_back(reply);
            while (!pending.empty()) {
                if (!g_bridge.peer_connected() || pending.front().session != g_bridge.peer_session()) { pending.pop_front(); continue; }
                if (!g_bridge.SendInteractionResult(pending.front().payload)) break;
                pending.pop_front();
            }
        }
        // The P5 S0 census remains read-only even at stage 0 / no-hooks, independently of
        // the stage-7 damage path.
        combat_census.Update(image_base, world, generation,
                             world ? (player.pawn ? player.pawn : probe::FindPlayerObjects(world).pawn) : 0,
                             GetTickCount64());
        tick_timing.Add(MicrosSince(start));

        if (world && tick % kCollisionCheckEveryTicks == 0) {
            const std::uintptr_t pawn = player.pawn ? player.pawn : probe::FindPlayerObjects(world).pawn;
            collision_schedule.Update(world, generation, world_path, pawn, frame, GetTickCount64());
        }
        if (world && tick % kWalkCheckEveryTicks == 0) {
            collision_schedule.Check(generation, player.pawn ? player : probe::FindPlayerObjects(world), frame);
        }
        if (tick % kWalkCheckEveryTicks == 5) collision_schedule.Share();
        if (settings.stage >= 8) door_schedule.Update(world, generation, world_path, player.pawn, collision_schedule, GetTickCount64());
        if (world && log_tick) {
            const LARGE_INTEGER log_start = Now();
            probe::LogWorld(world, frame);
            log_timing.Add(MicrosSince(log_start));
        }
        if (tick % kStatsEveryTicks == 0) {
            SC_LOG("[bridge] %s", g_bridge.Describe().c_str());
            SC_LOG("[perf] tick %s | detailed-log %s | private=%.1fMB", tick_timing.Take().c_str(),
                   log_timing.Take().c_str(), PrivateMegabytes());
            if (settings.stage >= 1) SC_LOG("[hooks] %s", hooks::TakeStats().c_str());
            if (settings.stage >= 6) SC_LOG("[blocks] %s", blocks::TakeStats().c_str());
            SC_LOG("[combat-census] %s", combat_census.TakeStats().c_str());
            if (settings.stage >= 7) SC_LOG("[combat] enemies %s", enemies.TakeStats().c_str());
            if (settings.stage >= 7) SC_LOG("[combat] %s", combat::damage::TakeStats().c_str());
            if (settings.stage >= 7) SC_LOG("[player-hits] %s", combat::player_hits::TakeStats().c_str());
            if (settings.stage >= 8) SC_LOG("[overlay] %s", render::TakeStats().c_str());
            if (settings.stage >= 8) SC_LOG("[hud] %s", ui::TakeHudStats().c_str());
            if (settings.stage >= 8) SC_LOG("[streaming] %s", hooks::streaming_guard::TakeStats().c_str());
            if (settings.stage >= 8) SC_LOG("[interaction] %s", interaction::TakeStats().c_str());
            collision_schedule.FlushCheck();
        }
    }
}

void OnProcessExit() { render::OnProcessExit(); g_bridge.MarkClosing(); }

}  // namespace sifucraft::runtime
