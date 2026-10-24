#include "hooks/game_hooks.h"

#include <windows.h>

#include <atomic>
#include <string>
#include <unordered_map>

#include "blocks/block_mirror.h"
#include "combat/damage.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/camera_fade.h"
#include "hooks/vtable_hook.h"
#include "input/input_router.h"
#include "input/key_map.h"
#include "probe/probe.h"
#include "render/overlay.h"
#include "ui/player_hud.h"
#include "ue/function_call.h"
#include "ue/names.h"
#include "ue/objects.h"
#include "ue/transform.h"
#include "ue/types.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <vector>

namespace sifucraft::hooks {

namespace {

std::uintptr_t g_image_base = 0;
int g_stage = 0;

std::atomic<std::uintptr_t> g_controller{0};
std::atomic<std::uintptr_t> g_camera{0};
std::atomic<std::uintptr_t> g_pawn{0};
std::atomic<std::uintptr_t> g_movement{0};

// ---- Pose authority (stage 5): data shared by H1 and H5 ----------------------------------------

SRWLOCK g_pose_lock = SRWLOCK_INIT;
PoseOverride g_pose_override;
std::atomic<bool> g_pose_active{false};

// Reflected functions H1 calls; resolved on the runtime thread, then read-only.
struct PoseCalls {
    ue::FunctionCall set_transform;  // AActor::K2_SetActorLocationAndRotation
    std::int32_t new_location = -1;
    std::int32_t new_rotation = -1;
    std::int32_t sweep = -1;
    std::int32_t teleport = -1;
    ue::FunctionCall set_hidden;     // AActor::SetActorHiddenInGame
    std::int32_t new_hidden = -1;
};
PoseCalls g_pose_calls;
std::atomic<bool> g_pose_calls_ready{false};
bool g_pose_calls_failed = false;

struct PoseStats {
    std::atomic<std::uint64_t> writes{0};
    std::atomic<std::uint64_t> displacements{0};   // Sifu moved the pawn between two of our writes
    std::atomic<std::uint64_t> max_displacement_mm{0};
    std::atomic<std::uint64_t> movement_ticks_skipped{0};
    std::atomic<std::uint64_t> hide_calls{0};
    std::atomic<std::uint64_t> pawn_rehides{0};    // Sifu showed the pawn again while hidden by us
    std::atomic<std::uint64_t> impostor_hides{0};  // Sifu's stand-in actors hidden near the pawn
    std::atomic<std::uint64_t> attached_skips{0};  // frames not written: the pawn was attached
};
PoseStats g_pose_stats;

constexpr float kDisplacementToleranceCm = 1.0f;
constexpr int kMaxLoggedDisplacements = 20;
constexpr int kMaxLoggedVisibility = 20;

std::vector<std::uintptr_t> g_impostor_requests;  // guarded by g_pose_lock

// Frames in a row on which Sifu moved the pawn after our write: Sifu is driving it (a script).
std::atomic<std::uint32_t> g_consecutive_displacements{0};

// Game thread only.
std::uintptr_t g_hidden_pawn = 0;
std::vector<std::uintptr_t> g_hidden_impostors;
bool g_have_last_write = false;
ue::FVector g_last_write{};
std::vector<std::uint8_t> g_params;
int g_logged_displacements = 0;
int g_logged_visibility = 0;

void CallSetHidden(std::uintptr_t pawn, bool hidden) {
    g_params.assign(static_cast<std::size_t>(g_pose_calls.set_hidden.params_size()), 0);
    g_params[g_pose_calls.new_hidden] = hidden ? 1 : 0;
    g_pose_calls.set_hidden.Invoke(pawn, g_params.data());
    g_pose_stats.hide_calls.fetch_add(1, std::memory_order_relaxed);
}

void CallSetTransform(std::uintptr_t pawn, const ue::FVector& location, float yaw) {
    const PoseCalls& c = g_pose_calls;
    g_params.assign(static_cast<std::size_t>(c.set_transform.params_size()), 0);
    std::memcpy(&g_params[c.new_location], &location, sizeof(location));
    const ue::FRotator rotation{0.0f, yaw, 0.0f};
    std::memcpy(&g_params[c.new_rotation], &rotation, sizeof(rotation));
    g_params[c.sweep] = 0;
    g_params[c.teleport] = 1;
    c.set_transform.Invoke(pawn, g_params.data());
}

// The local pawn resolved on the game thread itself: garbage collection runs on this thread too,
// so unlike the runtime thread's copy it can never be a freed object.
std::uintptr_t LivePawnOnGameThread() {
    const std::uintptr_t world = mem::ReadPtr(g_image_base + gen::rva::GWorld);
    if (!world) return 0;
    const std::uintptr_t pawn = probe::FindPlayerObjects(world).pawn;
    return ue::IsLive(pawn) ? pawn : 0;
}

// Runs on the game thread before Sifu's world tick.
void ApplyPose() {
    if (!g_pose_calls_ready.load(std::memory_order_acquire)) return;
    AcquireSRWLockShared(&g_pose_lock);
    const PoseOverride pose = g_pose_override;
    const std::vector<std::uintptr_t> impostors = g_impostor_requests;
    ReleaseSRWLockShared(&g_pose_lock);
    if (!pose.active && !g_hidden_pawn && g_hidden_impostors.empty()) return;
    const std::uintptr_t pawn = LivePawnOnGameThread();

    if (!pose.active || !pawn) {
        // Give the body back, but only to actors we hid that still exist (a new world brings a
        // new pawn).
        if (g_hidden_pawn && g_hidden_pawn == pawn) CallSetHidden(pawn, false);
        for (std::uintptr_t impostor : g_hidden_impostors) {
            if (ue::IsLive(impostor)) CallSetHidden(impostor, false);
        }
        g_hidden_impostors.clear();
        g_hidden_pawn = 0;
        g_have_last_write = false;
        g_consecutive_displacements.store(0, std::memory_order_relaxed);
        return;
    }
    if (g_hidden_pawn != pawn) {
        CallSetHidden(pawn, true);
        g_hidden_pawn = pawn;
        g_have_last_write = false;
    } else if (!probe::ActorHidden(pawn)) {
        CallSetHidden(pawn, true);
        g_pose_stats.pawn_rehides.fetch_add(1, std::memory_order_relaxed);
        if (g_logged_visibility++ < kMaxLoggedVisibility) SC_LOG("[pose] Sifu showed the pawn again: hidden again");
    }
    // Sifu can show the body through a stand-in actor (an impostor) instead of the pawn.
    for (std::uintptr_t impostor : impostors) {
        if (!ue::IsLive(impostor) || probe::ActorHidden(impostor)) continue;
        CallSetHidden(impostor, true);
        g_pose_stats.impostor_hides.fetch_add(1, std::memory_order_relaxed);
        if (std::find(g_hidden_impostors.begin(), g_hidden_impostors.end(), impostor) == g_hidden_impostors.end()) {
            g_hidden_impostors.push_back(impostor);
        }
        if (g_logged_visibility++ < kMaxLoggedVisibility) {
            SC_LOG("[pose] impostor %s (%s) visible next to the pawn: hidden", ue::ObjectName(impostor).c_str(),
                   ue::ClassName(impostor).c_str());
        }
    }
    // An attached pawn (a cinematic carries it on its own anchor) moves with its parent: a world
    // pose written into it only fights the parent. The runtime hands the player back to Sifu.
    const std::uintptr_t root = mem::ReadPtr(pawn + gen::off::AActor::RootComponent);
    if (ue::AttachParentOf(root)) {
        g_have_last_write = false;
        g_pose_stats.attached_skips.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    // Anything that moved the pawn since our previous write came from Sifu itself.
    ue::FVector current{};
    if (g_have_last_write && ue::ReadWorldLocation(root, &current)) {
        const float dx = current.x - g_last_write.x, dy = current.y - g_last_write.y, dz = current.z - g_last_write.z;
        const float distance = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= kDisplacementToleranceCm) {
            g_consecutive_displacements.store(0, std::memory_order_relaxed);
        } else {
            g_consecutive_displacements.fetch_add(1, std::memory_order_relaxed);
            g_pose_stats.displacements.fetch_add(1, std::memory_order_relaxed);
            const auto mm = static_cast<std::uint64_t>(distance * 10.0f);
            if (mm > g_pose_stats.max_displacement_mm.load(std::memory_order_relaxed)) {
                g_pose_stats.max_displacement_mm.store(mm, std::memory_order_relaxed);
            }
            if (g_logged_displacements++ < kMaxLoggedDisplacements) {
                SC_LOG("[pose] SIFU DISPLACED the pawn by %.1f cm: (%.0f, %.0f, %.0f) -> (%.0f, %.0f, %.0f)", distance,
                       g_last_write.x, g_last_write.y, g_last_write.z, current.x, current.y, current.z);
            }
        }
    }
    CallSetTransform(pawn, pose.location, pose.yaw);
    g_last_write = pose.location;
    g_have_last_write = true;
    g_pose_stats.writes.fetch_add(1, std::memory_order_relaxed);
}

void ResolvePoseCalls() {
    if (g_pose_calls_ready.load() || g_pose_calls_failed) return;
    const std::uintptr_t actor = ue::FindClass("Actor");
    PoseCalls& c = g_pose_calls;
    const bool found = actor && c.set_transform.Resolve(actor, "K2_SetActorLocationAndRotation") &&
                       c.set_hidden.Resolve(actor, "SetActorHiddenInGame");
    if (found) {
        c.new_location = c.set_transform.ParamOffset("NewLocation");
        c.new_rotation = c.set_transform.ParamOffset("NewRotation");
        c.sweep = c.set_transform.ParamOffset("bSweep");
        c.teleport = c.set_transform.ParamOffset("bTeleport");
        c.new_hidden = c.set_hidden.ParamOffset("bNewHidden");
    }
    const auto fits = [](std::int32_t offset, std::size_t size, std::int32_t total) {
        return offset >= 0 && offset + static_cast<std::int32_t>(size) <= total;
    };
    const std::int32_t transform_size = c.set_transform.params_size();
    if (!found || !fits(c.new_location, sizeof(ue::FVector), transform_size) ||
        !fits(c.new_rotation, sizeof(ue::FRotator), transform_size) || !fits(c.sweep, 1, transform_size) ||
        !fits(c.teleport, 1, transform_size) || !fits(c.new_hidden, 1, c.set_hidden.params_size())) {
        SC_LOG("[pose] REFUSED: reflected functions not usable (found=%d loc=%d rot=%d sweep=%d teleport=%d hidden=%d)",
               found, c.new_location, c.new_rotation, c.sweep, c.teleport, c.new_hidden);
        g_pose_calls_failed = true;
        return;
    }
    SC_LOG("[pose] K2_SetActorLocationAndRotation params=%d (loc@%d rot@%d sweep@%d teleport@%d), "
           "SetActorHiddenInGame params=%d (hidden@%d)",
           transform_size, c.new_location, c.new_rotation, c.sweep, c.teleport, c.set_hidden.params_size(),
           c.new_hidden);
    g_pose_calls_ready.store(true, std::memory_order_release);
}

// ---- H5: UFightingMovementComponent::TickComponent ------------------------------------------
// Only the local player's component is skipped, and only while Minecraft owns the pose: Sifu
// then computes no movement, gravity or root motion for the player. Every other character
// (enemies) ticks normally.

using TickComponentFn = void (*)(void* component, float delta_time, int tick_type, void* tick_function);

VtableHook g_movement_tick;

void MovementTickHook(void* component, float delta_time, int tick_type, void* tick_function) {
    if (g_stage >= 5 && g_pose_active.load(std::memory_order_relaxed) &&
        reinterpret_cast<std::uintptr_t>(component) == g_movement.load(std::memory_order_relaxed)) {
        g_pose_stats.movement_ticks_skipped.fetch_add(1, std::memory_order_relaxed);
        return;
    }
    g_movement_tick.original<TickComponentFn>()(component, delta_time, tick_type, tick_function);
}

// ---- H1: UGameEngine::Tick ------------------------------------------------------------------

VtableHook g_engine_tick;
using EngineTickFn = void (*)(void* engine, float delta_seconds, bool idle_mode);

struct FrameStats {
    std::atomic<std::uint64_t> ticks{0};
    std::atomic<std::uint64_t> window_ticks{0};
    std::atomic<std::uint64_t> window_max_frame_us{0};
    std::atomic<std::int64_t> last_qpc{0};
};
FrameStats g_frames;

std::int64_t QpcFrequency() {
    static const std::int64_t frequency = [] {
        LARGE_INTEGER f;
        QueryPerformanceFrequency(&f);
        return f.QuadPart;
    }();
    return frequency;
}

void EngineTickHook(void* engine, float delta_seconds, bool idle_mode) {
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    const std::int64_t last = g_frames.last_qpc.exchange(now.QuadPart, std::memory_order_relaxed);
    if (last) {
        const auto frame_us = static_cast<std::uint64_t>((now.QuadPart - last) * 1000000 / QpcFrequency());
        if (frame_us > g_frames.window_max_frame_us.load(std::memory_order_relaxed)) {
            g_frames.window_max_frame_us.store(frame_us, std::memory_order_relaxed);
        }
    }
    g_frames.ticks.fetch_add(1, std::memory_order_relaxed);
    g_frames.window_ticks.fetch_add(1, std::memory_order_relaxed);
    if (g_stage >= 5) ApplyPose();
    if (g_stage >= 6) blocks::ApplyOnGameThread(g_image_base);
    if (g_stage >= 7) combat::damage::ApplyOnGameThread(g_image_base);
    g_engine_tick.original<EngineTickFn>()(engine, delta_seconds, idle_mode);
    if (g_stage >= 8) ui::ApplyHudOnGameThread(g_image_base,
        input::Router().owner() == protocol::InputOwner::Minecraft && PoseActive(), render::LastPresentation());
}

std::uintptr_t Engine() { return mem::ReadPtr(g_image_base + gen::rva::GEngine); }

// ---- H4: APlayerCameraManager::UpdateCamera ------------------------------------------------
// Sifu computes its camera first (shakes, cinematics, ...); while Minecraft owns the view the
// final POV is then replaced. Rendering, audio and culling all read CameraCachePrivate.POV.

using UpdateCameraFn = void (*)(void* camera_manager, float delta_time);

VtableHook g_camera_update;
SRWLOCK g_camera_lock = SRWLOCK_INIT;
CameraOverride g_camera_override;
std::atomic<std::uint64_t> g_camera_overrides{0};

void WritePov(std::uintptr_t pov, const CameraOverride& view) {
    namespace info = gen::off::FMinimalViewInfo;
    *reinterpret_cast<ue::FVector*>(pov + info::Location) = view.location;
    *reinterpret_cast<ue::FRotator*>(pov + info::Rotation) = view.rotation;
    *reinterpret_cast<float*>(pov + info::FOV) = view.fov;
}

void UpdateCameraHook(void* camera_manager, float delta_time) {
    g_camera_update.original<UpdateCameraFn>()(camera_manager, delta_time);
    const auto manager = reinterpret_cast<std::uintptr_t>(camera_manager);
    if (g_stage < 4 || manager != g_camera.load(std::memory_order_relaxed)) return;
    AcquireSRWLockShared(&g_camera_lock);
    const CameraOverride view = g_camera_override;
    ReleaseSRWLockShared(&g_camera_lock);
    camera_fade::AfterCameraUpdate(manager, view.active);
    if (!view.active) return;
    namespace off = gen::off;
    WritePov(manager + off::APlayerCameraManager::CameraCachePrivate + off::FCameraCacheEntry::POV, view);
    WritePov(manager + off::APlayerCameraManager::ViewTarget + off::FTViewTarget::POV, view);
    g_camera_overrides.fetch_add(1, std::memory_order_relaxed);
}

// ---- H2/H3: AFightingPlayerController::InputKey / InputAxis ---------------------------------
// FKey (24 bytes) is passed by value: the caller passes a pointer to its temporary and, under the
// MSVC ABI, the callee destroys it. Passing the same pointer on to the original keeps that
// contract; a hook that swallows an event must call FKey::~FKey itself.

struct FKeyRaw {
    ue::FName name;
    void* details;               // TSharedPtr<FKeyDetails>: object
    void* reference_controller;  // TSharedPtr<FKeyDetails>: reference controller
};
static_assert(sizeof(FKeyRaw) == 24);

using InputKeyFn = bool (*)(void* controller, FKeyRaw* key, int event, float amount_depressed, bool gamepad);
using InputAxisFn = bool (*)(void* controller, FKeyRaw* key, float delta, float delta_time, int samples, bool gamepad);

VtableHook g_input_key;
VtableHook g_input_axis;

struct InputStats {
    std::atomic<std::uint64_t> keys{0};
    std::atomic<std::uint64_t> keys_unmapped{0};
    std::atomic<std::uint64_t> keys_to_minecraft{0};
    std::atomic<std::uint64_t> axes_to_minecraft{0};
    std::atomic<std::uint64_t> mouse_x_events{0};
    std::atomic<std::uint64_t> mouse_y_events{0};
    std::atomic<std::uint64_t> wheel_events{0};
    std::atomic<std::uint64_t> other_axis_events{0};
    std::atomic<std::int64_t> mouse_x_milli{0};
    std::atomic<std::int64_t> mouse_y_milli{0};
    std::atomic<std::int64_t> wheel_milli{0};
};
InputStats g_input;

constexpr int kMaxLoggedKeyEvents = 400;
int g_logged_key_events = 0;  // game thread only

struct KeyInfo {
    const std::string* name;
    input::MappedKey mapped;
    bool logged_unmapped_axis = false;
};

// Game thread only: FName -> name + mapping, resolved once per key.
KeyInfo& Describe(const FKeyRaw& key) {
    static std::unordered_map<std::uint64_t, KeyInfo> cache;
    const std::uint64_t id = (static_cast<std::uint64_t>(key.name.number) << 32) | key.name.comparison_index;
    if (auto it = cache.find(id); it != cache.end()) return it->second;
    static std::unordered_map<std::uint64_t, std::string> numbered;
    const std::string* name = &ue::NameEntry(key.name.comparison_index);
    if (key.name.number) name = &(numbered[id] = ue::NameToString(key.name));
    return cache.emplace(id, KeyInfo{name, input::MapKey(*name)}).first->second;
}

const char* KindName(input::KeyKind kind) {
    switch (kind) {
        case input::KeyKind::Keyboard: return "sdl-key";
        case input::KeyKind::MouseButton: return "sdl-button";
        case input::KeyKind::MouseX: return "mouse-x";
        case input::KeyKind::MouseY: return "mouse-y";
        case input::KeyKind::MouseWheel: return "wheel";
        default: return "UNMAPPED";
    }
}

std::int64_t Milli(float value) { return static_cast<std::int64_t>(value * 1000.0f); }

// A swallowed event never reaches the original, so its by-value FKey is destroyed here.
void DestroyKey(FKeyRaw* key) {
    using Destructor = void (*)(FKeyRaw*);
    reinterpret_cast<Destructor>(g_image_base + gen::fn::FKey_Destructor)(key);
}

bool InputKeyHook(void* controller, FKeyRaw* key, int event, float amount_depressed, bool gamepad) {
    if (reinterpret_cast<std::uintptr_t>(controller) == g_controller.load(std::memory_order_relaxed)) {
        const KeyInfo& info = Describe(*key);
        g_input.keys.fetch_add(1, std::memory_order_relaxed);
        if (info.mapped.kind == input::KeyKind::Unmapped) g_input.keys_unmapped.fetch_add(1, std::memory_order_relaxed);
        int action = 0;
        const bool has_action = input::MapAction(event, &action);
        const bool to_minecraft =
            g_stage >= 3 && has_action && !gamepad && input::Router().OnKey(info.mapped, *info.name, action);
        if (g_logged_key_events < kMaxLoggedKeyEvents) {
            ++g_logged_key_events;
            SC_LOG("[input] key %-18s %-11s -> %s %d action=%s%s -> %s", info.name->c_str(), input::EventName(event),
                   KindName(info.mapped.kind), info.mapped.code, has_action ? std::to_string(action).c_str() : "-",
                   gamepad ? " (gamepad)" : "", to_minecraft ? "MINECRAFT" : "sifu");
        }
        if (to_minecraft) {
            g_input.keys_to_minecraft.fetch_add(1, std::memory_order_relaxed);
            DestroyKey(key);
            return true;
        }
    }
    return g_input_key.original<InputKeyFn>()(controller, key, event, amount_depressed, gamepad);
}

bool InputAxisHook(void* controller, FKeyRaw* key, float delta, float delta_time, int samples, bool gamepad) {
    if (reinterpret_cast<std::uintptr_t>(controller) == g_controller.load(std::memory_order_relaxed)) {
        KeyInfo& info = Describe(*key);
        if (g_stage >= 3 && !gamepad && input::Router().OnAxis(info.mapped.kind, delta)) {
            g_input.axes_to_minecraft.fetch_add(1, std::memory_order_relaxed);
            DestroyKey(key);
            return true;
        }
        switch (info.mapped.kind) {
            case input::KeyKind::MouseX:
                g_input.mouse_x_events.fetch_add(1, std::memory_order_relaxed);
                g_input.mouse_x_milli.fetch_add(Milli(delta), std::memory_order_relaxed);
                break;
            case input::KeyKind::MouseY:
                g_input.mouse_y_events.fetch_add(1, std::memory_order_relaxed);
                g_input.mouse_y_milli.fetch_add(Milli(delta), std::memory_order_relaxed);
                break;
            case input::KeyKind::MouseWheel:
                g_input.wheel_events.fetch_add(1, std::memory_order_relaxed);
                g_input.wheel_milli.fetch_add(Milli(delta), std::memory_order_relaxed);
                break;
            default:
                g_input.other_axis_events.fetch_add(1, std::memory_order_relaxed);
                if (!info.logged_unmapped_axis) {
                    info.logged_unmapped_axis = true;
                    SC_LOG("[input] axis %s first seen (%s, delta=%.3f%s)", info.name->c_str(),
                           KindName(info.mapped.kind), delta, gamepad ? ", gamepad" : "");
                }
                break;
        }
    }
    return g_input_axis.original<InputAxisFn>()(controller, key, delta, delta_time, samples, gamepad);
}

// ---- uninstall / reinstall self-test --------------------------------------------------------
// Proves the hook can be removed cleanly: after uninstalling, the slot must hold the original
// again and the tick counter must stop; after reinstalling it must resume.

enum class SelfTest { Waiting, Uninstalled, Reinstalled, Done };

constexpr std::uint64_t kSelfTestAfterMs = 60000;
constexpr std::uint64_t kSelfTestPhaseMs = 10000;

SelfTest g_self_test = SelfTest::Waiting;
std::uint64_t g_self_test_ms = 0;
std::uint64_t g_self_test_ticks = 0;
std::uint64_t g_installed_ms = 0;

void RunSelfTest(std::uint64_t now_ms) {
    switch (g_self_test) {
        case SelfTest::Waiting:
            if (!g_engine_tick.installed() || now_ms - g_installed_ms < kSelfTestAfterMs) return;
            if (!g_engine_tick.Uninstall()) {
                SC_LOG("[selftest] FAIL: could not uninstall %s", g_engine_tick.name());
                g_self_test = SelfTest::Done;
                return;
            }
            SC_LOG("[selftest] uninstalled %s: slot now +0x%llX (%s)", g_engine_tick.name(),
                   static_cast<unsigned long long>(mem::ReadPtr(g_engine_tick.slot_address()) - g_image_base),
                   mem::ReadPtr(g_engine_tick.slot_address()) == g_engine_tick.original_address() ? "original"
                                                                                                 : "NOT ORIGINAL");
            g_self_test_ticks = g_frames.ticks.load();
            g_self_test_ms = now_ms;
            g_self_test = SelfTest::Uninstalled;
            return;
        case SelfTest::Uninstalled: {
            if (now_ms - g_self_test_ms < kSelfTestPhaseMs) return;
            const std::uint64_t while_off = g_frames.ticks.load() - g_self_test_ticks;
            SC_LOG("[selftest] %llu hooked ticks while uninstalled for %llu ms (%s)",
                   static_cast<unsigned long long>(while_off), static_cast<unsigned long long>(now_ms - g_self_test_ms),
                   while_off <= 1 ? "ok" : "FAIL");
            if (!g_engine_tick.Install(g_image_base, gen::hook::EngineTick, Engine(),
                                       reinterpret_cast<void*>(&EngineTickHook))) {
                SC_LOG("[selftest] FAIL: could not reinstall %s", g_engine_tick.name());
                g_self_test = SelfTest::Done;
                return;
            }
            g_frames.last_qpc.store(0);  // the gap while uninstalled is not a frame
            g_self_test_ticks = g_frames.ticks.load();
            g_self_test_ms = now_ms;
            g_self_test = SelfTest::Reinstalled;
            return;
        }
        case SelfTest::Reinstalled: {
            if (now_ms - g_self_test_ms < kSelfTestPhaseMs) return;
            const std::uint64_t while_on = g_frames.ticks.load() - g_self_test_ticks;
            SC_LOG("[selftest] %llu hooked ticks in %llu ms after reinstall -> %s",
                   static_cast<unsigned long long>(while_on), static_cast<unsigned long long>(now_ms - g_self_test_ms),
                   while_on > 0 ? "PASS" : "FAIL");
            g_self_test = SelfTest::Done;
            return;
        }
        case SelfTest::Done:
            return;
    }
}

}  // namespace

void Configure(std::uintptr_t image_base, int stage) {
    g_image_base = image_base;
    g_stage = stage;
}

bool InstallEngineHook() {
    if (g_stage < 1 || g_engine_tick.installed()) return g_engine_tick.installed();
    if (g_self_test != SelfTest::Waiting) return false;  // the self-test owns reinstallation
    if (!g_engine_tick.Install(g_image_base, gen::hook::EngineTick, Engine(), reinterpret_cast<void*>(&EngineTickHook))) {
        return false;
    }
    g_installed_ms = GetTickCount64();
    return true;
}

void SetLocalPlayer(std::uintptr_t controller, std::uintptr_t camera, std::uintptr_t pawn, std::uintptr_t movement) {
    g_controller.store(controller, std::memory_order_relaxed);
    g_camera.store(camera, std::memory_order_relaxed);
    g_pawn.store(pawn, std::memory_order_relaxed);
    g_movement.store(movement, std::memory_order_relaxed);
}

void InstallPlayerHooks() {
    const std::uintptr_t controller = g_controller.load();
    if (g_stage < 2 || !controller) return;
    if (!g_input_key.installed()) {
        g_input_key.Install(g_image_base, gen::hook::PlayerInputKey, controller, reinterpret_cast<void*>(&InputKeyHook));
    }
    if (!g_input_axis.installed()) {
        g_input_axis.Install(g_image_base, gen::hook::PlayerInputAxis, controller,
                             reinterpret_cast<void*>(&InputAxisHook));
    }
    // The startup map uses the base camera manager class: only try once Sifu's own class is live.
    const std::uintptr_t camera = g_camera.load();
    if (g_stage >= 4 && camera && !g_camera_update.installed() &&
        Resolve(g_image_base, gen::hook::CameraUpdate, camera).ready()) {
        g_camera_update.Install(g_image_base, gen::hook::CameraUpdate, camera,
                                reinterpret_cast<void*>(&UpdateCameraHook));
    }
    if (g_stage >= 5) {
        ResolvePoseCalls();
        const std::uintptr_t movement = g_movement.load();
        if (movement && !g_movement_tick.installed() && Resolve(g_image_base, gen::hook::MovementTick, movement).ready()) {
            g_movement_tick.Install(g_image_base, gen::hook::MovementTick, movement,
                                    reinterpret_cast<void*>(&MovementTickHook));
        }
    }
}

std::uint32_t ConsecutiveDisplacements() { return g_consecutive_displacements.load(std::memory_order_relaxed); }

bool PoseHooksReady() { return g_pose_calls_ready.load() && g_movement_tick.installed() && g_engine_tick.installed(); }
bool PoseActive() { return g_pose_active.load(std::memory_order_relaxed); }

void SetPoseOverride(const PoseOverride& pose, const std::vector<std::uintptr_t>& impostors) {
    AcquireSRWLockExclusive(&g_pose_lock);
    g_pose_override = pose;
    g_impostor_requests = impostors;
    ReleaseSRWLockExclusive(&g_pose_lock);
    g_pose_active.store(pose.active, std::memory_order_relaxed);
}

bool InputHooksInstalled() { return g_input_key.installed() && g_input_axis.installed(); }

void SetCameraOverride(const CameraOverride& view) {
    AcquireSRWLockExclusive(&g_camera_lock);
    g_camera_override = view;
    ReleaseSRWLockExclusive(&g_camera_lock);
}

void Maintain(std::uint64_t now_ms) {
    // From stage 5 on H1 carries the pose every frame: no unhooking it for a self-test then.
    if (g_stage >= 1 && g_stage < 5) RunSelfTest(now_ms);
}

std::string TakeStats() {
    static std::uint64_t last_ms = GetTickCount64();
    const std::uint64_t now_ms = GetTickCount64();
    const double seconds = (now_ms - last_ms) / 1000.0;
    last_ms = now_ms;
    const std::uint64_t ticks = g_frames.window_ticks.exchange(0);
    const std::uint64_t max_us = g_frames.window_max_frame_us.exchange(0);
    std::string text = FormatString("stage=%d EngineTick=%s ticks=%llu fps=%.1f max_frame=%.1fms", g_stage,
                                    g_engine_tick.installed() ? "on" : "off",
                                    static_cast<unsigned long long>(g_frames.ticks.load()),
                                    seconds > 0 ? ticks / seconds : 0.0, max_us / 1000.0);
    if (g_stage >= 2) {
        const auto u = [](const std::atomic<std::uint64_t>& v) { return static_cast<unsigned long long>(v.load()); };
        text += FormatString(
            " | InputKey=%s InputAxis=%s keys=%llu unmapped=%llu mouse_x(n=%llu sum=%.1f) mouse_y(n=%llu sum=%.1f) "
            "wheel(n=%llu sum=%.2f) other_axes=%llu to_minecraft(keys=%llu axes=%llu)",
            g_input_key.installed() ? "on" : "off", g_input_axis.installed() ? "on" : "off", u(g_input.keys),
            u(g_input.keys_unmapped), u(g_input.mouse_x_events), g_input.mouse_x_milli.load() / 1000.0,
            u(g_input.mouse_y_events), g_input.mouse_y_milli.load() / 1000.0, u(g_input.wheel_events),
            g_input.wheel_milli.load() / 1000.0, u(g_input.other_axis_events), u(g_input.keys_to_minecraft),
            u(g_input.axes_to_minecraft));
    }
    if (g_stage >= 4) {
        text += FormatString(" | CameraUpdate=%s overrides=%llu", g_camera_update.installed() ? "on" : "off",
                             static_cast<unsigned long long>(g_camera_overrides.load()));
    }
    if (g_stage >= 5) {
        const auto u = [](const std::atomic<std::uint64_t>& v) { return static_cast<unsigned long long>(v.load()); };
        text += FormatString(" | MovementTick=%s pose_writes=%llu displaced=%llu max_displacement=%.1fcm "
                             "movement_skipped=%llu hide_calls=%llu pawn_rehides=%llu impostor_hides=%llu "
                             "attached_skips=%llu",
                             g_movement_tick.installed() ? "on" : "off", u(g_pose_stats.writes),
                             u(g_pose_stats.displacements), g_pose_stats.max_displacement_mm.load() / 10.0,
                             u(g_pose_stats.movement_ticks_skipped), u(g_pose_stats.hide_calls),
                             u(g_pose_stats.pawn_rehides), u(g_pose_stats.impostor_hides),
                             u(g_pose_stats.attached_skips));
    }
    return text;
}

}  // namespace sifucraft::hooks
