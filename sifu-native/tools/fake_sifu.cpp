// Stands in for Sifu when testing the Minecraft side of the bridge without the game: publishes a
// synthetic player walking in a circle and answers pings exactly like the in-game module.
//
//   sifucraft_fake_sifu [seconds] [--collision <grid.scvx>] [--pawn X Y Z [--yaw DEG] [--hand-over]]
//                       [--key CODE:FROM:TO]... [--button CODE:FROM:TO]... [--mouse DX:DY:AT]...
//                       [--then AT X Y Z DEG]...
//                       [--enemies | --enemy-scenario | --combat-scenario | --player-hit-scenario | --respawn-scenario]
//     seconds: 0 or omitted = run until Ctrl+C
//     --collision: also publish that grid file in the CollisionManifest, as the game does
//     --pawn: a pawn standing still at (X, Y, Z) (Unreal cm, capsule centre) in the grid's map
//     --yaw: the way it faces (Unreal degrees: 0 = +X, 90 = +Y)
//     --hand-over: route the player to Minecraft (input owner, anchor epoch 1)
//     --language: publish a UI culture such as zh-Hans (empty by default)
//     --key: hold SDL scancode CODE from FROM to TO ms after Minecraft has taken the player
//     --button: the same for SDL mouse button CODE (1 left, 3 right)
//     --mouse: move the mouse by (DX, DY) pixels AT ms after that (+DY looks down)
//     --then: AT ms after that, move the pawn to (X, Y, Z) facing DEG and hand it over again
//             (next anchor epoch): several test runs in one Minecraft session
//     --combat-scenario: two static test targets; validate damage events and reply with fake HP
//     --food-scenario: send one 5-HP hit after the real-client golden apple fixture is ready
//     --world-camera-scenario: six world/HUD handover phases, native camera roll/FOV and viewport checks

#include <windows.h>

#include <atomic>
#include <algorithm>
#include <bit>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iterator>
#include <string>
#include <vector>

#include "bridge/sifu_bridge.h"
#include "bridge/overlay_reader.h"
#include "bridge/world_reader.h"
#include "bridge/shape_reader.h"
#include "world_motion_fake.h"
#include "combat/damage_ledger.h"
#include "combat/shield_ledger.h"
#include "combat/impulse_ledger.h"
#include "input/anchor_request.h"
#include "core/log.h"

namespace {

constexpr DWORD kTickMs = 50;
constexpr std::uint64_t kStatsEveryTicks = 200;
constexpr double kRadius = 1000.0;
constexpr double kAngularSpeed = 0.5;  // rad/s

std::atomic<bool> g_stop{false};

BOOL WINAPI OnConsoleCtrl(DWORD) {
    g_stop = true;
    return TRUE;
}

sifucraft::bridge::PlayerFrame SyntheticFrame(double t) {
    using sifucraft::protocol::kSnapshotHasCamera;
    using sifucraft::protocol::kSnapshotHasPawn;
    const double angle = t * kAngularSpeed;
    const float x = static_cast<float>(kRadius * std::cos(angle));
    const float y = static_cast<float>(kRadius * std::sin(angle));
    const float yaw = static_cast<float>(std::fmod(angle * 180.0 / 3.14159265358979 + 90.0, 360.0));

    sifucraft::bridge::PlayerFrame frame;
    frame.world_generation = 1;
    frame.world_path = "/Game/Fake/FakeLevel.FakeLevel";
    frame.flags = kSnapshotHasPawn | kSnapshotHasCamera;
    frame.player_location = {x, y, 92.0f};
    frame.player_rotation = {0.0f, yaw, 0.0f};
    frame.player_velocity = {static_cast<float>(-kRadius * kAngularSpeed * std::sin(angle)),
                             static_cast<float>(kRadius * kAngularSpeed * std::cos(angle)), 0.0f};
    frame.camera_location = {x, y, 162.0f};
    frame.camera_rotation = frame.player_rotation;
    frame.camera_fov = 75.0f;
    frame.health = 120.0f;
    frame.max_health = 120.0f;
    return frame;
}

struct StandingPawn {
    bool enabled = false;
    double mouse_dx = 0, mouse_dy = 0;  // running totals, as the game sends them
    float x = 0, y = 0, z = 0;
    float yaw = 0;
    bool hand_over = false;
    std::uint32_t epoch = 1;
    std::string map = "FakeLevel";
};

struct PawnMove {
    std::uint64_t at_ms = 0;
    float x = 0, y = 0, z = 0, yaw = 0;
    bool done = false;
};

struct HeldKey {
    sifucraft::protocol::EventType type = sifucraft::protocol::EventType::Key;
    int code = 0;
    std::uint64_t from_ms = 0, to_ms = 0;
    bool pressed = false, released = false;
};

sifucraft::bridge::PlayerFrame StandingFrame(const StandingPawn& pawn) {
    using namespace sifucraft::protocol;
    sifucraft::bridge::PlayerFrame frame;
    frame.world_generation = 1;
    frame.world_path = "/Game/Maps/" + pawn.map + "/" + pawn.map + "." + pawn.map;
    frame.flags = kSnapshotHasPawn | kSnapshotHasCamera;
    frame.player_location = {pawn.x, pawn.y, pawn.z};
    frame.player_rotation = {0.0f, pawn.yaw, 0.0f};
    frame.camera_location = {pawn.x, pawn.y, pawn.z + 70.0f};
    frame.camera_rotation = frame.player_rotation;
    frame.camera_fov = 75.0f;
    frame.health = frame.max_health = 120.0f;
    frame.capsule_half_height = 90.0f;
    frame.input_owner = pawn.hand_over ? InputOwner::Minecraft : InputOwner::Sifu;
    frame.anchor_epoch = pawn.hand_over ? pawn.epoch : 0;
    frame.mouse_dx = pawn.mouse_dx;
    frame.mouse_dy = pawn.mouse_dy;
    return frame;
}

// The manifest for an existing grid file (format: sifu-native/src/collision/export.h).
bool DescribeGridFile(const std::string& path, sifucraft::bridge::CollisionInfo* info) {
    std::ifstream file(path, std::ios::binary);
    const std::vector<char> data((std::istreambuf_iterator<char>(file)), std::istreambuf_iterator<char>());
    if (data.size() < 36 || std::memcmp(data.data(), "SCVX", 4) != 0) return false;
    std::uint32_t name_length, count;
    std::memcpy(&name_length, data.data() + 28, 4);
    const std::size_t blocks_at = 32 + name_length + 4;
    if (data.size() < blocks_at) return false;
    std::memcpy(&count, data.data() + 32 + name_length, 4);
    if (data.size() < blocks_at + static_cast<std::size_t>(count) * 76) return false;
    info->map.assign(data.data() + 32, name_length);
    info->block_count = count;
    info->cell_count = 0;
    for (std::uint32_t b = 0; b < count; ++b) {
        for (int layer = 0; layer < 8; ++layer) {
            std::uint64_t bits;
            std::memcpy(&bits, data.data() + blocks_at + b * 76ull + 12 + layer * 8, 8);
            info->cell_count += std::popcount(bits);
        }
    }
    char full[MAX_PATH];
    info->path = GetFullPathNameA(path.c_str(), MAX_PATH, full, nullptr) ? full : path;
    info->world_generation = 1;
    info->generation = static_cast<std::uint32_t>(GetTickCount64() / 1000) | 1;
    return true;
}

}  // namespace

int main(int argc, char** argv) {
    using namespace sifucraft;
    double seconds = 0.0;
    std::string grid_file;
    std::string language;
    std::string overlay_output;
    unsigned viewport_width = 0, viewport_height = 0;
    std::string world_output;
    StandingPawn pawn;
    std::vector<HeldKey> keys;
    std::vector<PawnMove> moves;
    bool with_enemies = false, enemy_scenario = false, combat_scenario = false;
    bool player_hit_scenario = false;
    bool interaction_scenario = false;
    std::string door_closed, door_open;
    std::string fluid_gate_control,fluid_gate_closed,fluid_gate_open;
    std::string motion_pause_control;
    int fluid_gate_state=-1;std::uint32_t fluid_gate_revision=0;
    bool respawn_scenario = false;
    bool gui_test = false;
    bool world_camera_scenario = false;
    bool world_audio_scenario = false;
    bool shapes_test = false;
    bool motion_scenario=false;
    float motion_rise=0; // synthetic initial fixture height, never an in-game teleport
    bool shield_scenario = false;
    bool totem_scenario = false, totem_sent = false;
    bool food_scenario = false, food_sent = false;
    bool gui_motion_sent = false;
    struct MouseMove {
        double dx = 0, dy = 0;
        std::uint64_t at_ms = 0;
        bool done = false;
    };
    std::vector<MouseMove> mouse;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--collision") == 0 && i + 1 < argc) {
            grid_file = argv[++i];
        } else if (std::strcmp(argv[i], "--overlay-output") == 0 && i + 1 < argc) {
            overlay_output = argv[++i];
        } else if (std::strcmp(argv[i], "--viewport") == 0 && i + 2 < argc) {
            viewport_width = unsigned(std::atoi(argv[++i])); viewport_height = unsigned(std::atoi(argv[++i]));
        } else if (std::strcmp(argv[i], "--world-output") == 0 && i + 1 < argc) {
            world_output = argv[++i];
        } else if (std::strcmp(argv[i], "--world-camera-scenario") == 0) {
            world_camera_scenario = true;
        } else if (std::strcmp(argv[i], "--shapes") == 0) {
            shapes_test = true;
        } else if (std::strcmp(argv[i], "--world-audio-scenario") == 0) {
            world_camera_scenario = world_audio_scenario = true;
        } else if (std::strcmp(argv[i], "--gui") == 0) {
            gui_test = true;
        } else if (std::strcmp(argv[i], "--pawn") == 0 && i + 3 < argc) {
            pawn.enabled = true;
            pawn.x = static_cast<float>(std::atof(argv[++i]));
            pawn.y = static_cast<float>(std::atof(argv[++i]));
            pawn.z = static_cast<float>(std::atof(argv[++i]));
        } else if (std::strcmp(argv[i], "--yaw") == 0 && i + 1 < argc) {
            pawn.yaw = static_cast<float>(std::atof(argv[++i]));
        } else if (std::strcmp(argv[i], "--language") == 0 && i + 1 < argc) {
            language = argv[++i];
        } else if (std::strcmp(argv[i], "--hand-over") == 0) {
            pawn.hand_over = true;
        } else if (std::strcmp(argv[i], "--mouse") == 0 && i + 1 < argc) {
            MouseMove move;
            unsigned long long at = 0;
            if (std::sscanf(argv[++i], "%lf:%lf:%llu", &move.dx, &move.dy, &at) == 3) {
                move.at_ms = at;
                mouse.push_back(move);
            }
        } else if (std::strcmp(argv[i], "--then") == 0 && i + 5 < argc) {
            PawnMove move;
            move.at_ms = std::strtoull(argv[++i], nullptr, 10);
            move.x = static_cast<float>(std::atof(argv[++i]));
            move.y = static_cast<float>(std::atof(argv[++i]));
            move.z = static_cast<float>(std::atof(argv[++i]));
            move.yaw = static_cast<float>(std::atof(argv[++i]));
            moves.push_back(move);
        } else if ((std::strcmp(argv[i], "--key") == 0 || std::strcmp(argv[i], "--button") == 0) && i + 1 < argc) {
            HeldKey key;
            if (argv[i][2] == 'b') key.type = protocol::EventType::MouseButton;
            unsigned long long from = 0, to = 0;
            if (std::sscanf(argv[++i], "%d:%llu:%llu", &key.code, &from, &to) == 3) {
                key.from_ms = from;
                key.to_ms = to;
                keys.push_back(key);
            }
        } else if (std::strcmp(argv[i], "--interaction-scenario") == 0 && i + 2 < argc) {
            interaction_scenario = true; door_closed = argv[++i]; door_open = argv[++i];
        } else if (std::strcmp(argv[i], "--fluid-gate") == 0 && i + 3 < argc) {
            fluid_gate_control=argv[++i];fluid_gate_closed=argv[++i];fluid_gate_open=argv[++i];
        } else if (std::strcmp(argv[i], "--motion-pause") == 0 && i + 1 < argc) {
            motion_pause_control=argv[++i];
        } else if (std::strcmp(argv[i], "--motion-scenario") == 0) {
            motion_scenario=true;with_enemies=true;shapes_test=true;
        } else if (std::strcmp(argv[i], "--motion-rise") == 0 && i+1<argc) {
            motion_rise=static_cast<float>(std::atof(argv[++i]));
            if(!std::isfinite(motion_rise)||std::fabs(motion_rise)>10000)return 2;
        } else if (std::strcmp(argv[i], "--enemies") == 0) {
            with_enemies = true;
        } else if (std::strcmp(argv[i], "--enemy-scenario") == 0) {
            with_enemies = enemy_scenario = true;
        } else if (std::strcmp(argv[i], "--combat-scenario") == 0) {
            with_enemies = combat_scenario = true;
        } else if (std::strcmp(argv[i], "--shield-scenario") == 0) {
            with_enemies = combat_scenario = shield_scenario = true;
        } else if (std::strcmp(argv[i], "--totem-scenario") == 0) {
            with_enemies = combat_scenario = totem_scenario = true;
        } else if (std::strcmp(argv[i], "--food-scenario") == 0) {
            with_enemies = combat_scenario = food_scenario = true;
        } else if (std::strcmp(argv[i], "--player-hit-scenario") == 0) {
            with_enemies = combat_scenario = player_hit_scenario = true;
        } else if (std::strcmp(argv[i], "--respawn-scenario") == 0) {
            with_enemies = combat_scenario = respawn_scenario = true;
        } else {
            seconds = std::atof(argv[i]);
        }
    }

    wchar_t exe[MAX_PATH];
    const DWORD length = GetModuleFileNameW(nullptr, exe, MAX_PATH);
    std::wstring directory(exe, length);
    directory.resize(directory.find_last_of(L"\\/"));
    log::InitAt(directory + L"\\logs", L"fake_sifu.log");
    log::SetEcho(true);
    SetConsoleCtrlHandler(OnConsoleCtrl, TRUE);

    SC_LOG("fake sifu: pid=%lu duration=%s", GetCurrentProcessId(),
           seconds > 0 ? FormatString("%.0f s", seconds).c_str() : "until Ctrl+C");
    bridge::SifuBridge bridge;
    if (!bridge.Start()) return 1;
    bridge::SharedMapping test_mapping;
    if (player_hit_scenario && !test_mapping.Open(protocol::kMappingName, protocol::kMappingSize)) return 1;
    int hit_phase = 0;
    bool saw_dead = false, sent_lethal = false, saw_respawn_pose = false;
    bridge::CollisionInfo grid;
    if (!grid_file.empty()) {
        if (!DescribeGridFile(grid_file, &grid)) {
            SC_LOG("fake sifu: %s is not a readable grid file", grid_file.c_str());
            return 1;
        }
        bridge.PublishCollision(grid);
        SC_LOG("[collision] published grid %u: %s, %u blocks, %llu cells -> %s", grid.generation, grid.map.c_str(),
               grid.block_count, static_cast<unsigned long long>(grid.cell_count), grid.path.c_str());
        pawn.map = grid.map;
    }
    ULONGLONG handed_over_at = 0;

    std::uint32_t loaded = 0;
    std::uint32_t blocks_seen = 0;
    ULONGLONG enemies_ready_at = 0;
    int enemy_phase_seen = -1;
    float enemy_health[2] = {1000, 1000};
    combat::DamageLedger damage_ledger;
    combat::ShieldLedger shield_ledger;
    combat::ImpulseLedger impulse_ledger;
    std::uint64_t impulse_session = 0;
    unsigned impulse_accepted = 0, impulse_rejected = 0;
    unsigned shield_sent = 0, shield_replies = 0;
    bool shield_released = false, shield_fast_pressed = false;
    std::vector<protocol::Event> damage_events;
    unsigned applied_damage = 0;
    unsigned interaction_accepted = 0, interaction_rejected = 0;
    std::uint32_t door_generation = 0;
    overlay::Reader overlay_reader;
    overlay::Frame overlay_frame;
    unsigned overlay_frames = 0;
    bool overlay_saved = false;
    world_image::Reader world_reader;
    world_image::Frame world_frame;
    shape_data::Reader shape_reader;
    shape_data::Frame shape_frame;
    world_motion::fake::Harness motion;
    unsigned world_frames = 0;
    bool world_saved = false;
    unsigned camera_samples[6]{}, camera_visible[6]{}, camera_errors = 0, native_hud_frames = 0;
    int camera_phase = 0, logged_camera_phase = -1;
    const ULONGLONG begin = GetTickCount64();
    for (std::uint64_t tick = 1; !g_stop; ++tick) {
        Sleep(motion_scenario?8:kTickMs);
        const ULONGLONG now = GetTickCount64();
        const double elapsed = (now - begin) / 1000.0;
        if (seconds > 0 && elapsed >= seconds) break;
        bridge::PlayerFrame frame = pawn.enabled ? StandingFrame(pawn) : SyntheticFrame(elapsed);
        frame.language = language;
        if (gui_test) frame.flags |= protocol::kSnapshotGuiAvailable;
        if (world_camera_scenario && handed_over_at) {
            const auto age = now - handed_over_at;
            camera_phase = age < 3000 ? 0 : age < 7000 ? 1 : age < 11000 ? 2 : age < 15000 ? 3 : age < 19000 ? 4 : 5;
            const bool native = camera_phase == 1 || camera_phase == 2 || camera_phase == 4;
            if (native) {
                frame.input_owner = protocol::InputOwner::Sifu;
                frame.camera_location = {pawn.x + (camera_phase == 2 ? 100.f : -80.f), pawn.y - 250.f, pawn.z + 130.f};
                const float dx = pawn.x - frame.camera_location.x, dy = pawn.y + 150.f - frame.camera_location.y;
                frame.camera_rotation = {float(std::atan2(-60.f, std::hypot(dx, dy)) * 180 / 3.141592653589793),
                    float(std::atan2(dy, dx) * 180 / 3.141592653589793), camera_phase == 2 ? 17.f : -12.f};
                frame.camera_fov = camera_phase == 2 ? 95.f : 70.f;
            }
            if (camera_phase >= 2) { viewport_width = 1366; viewport_height = 768; }
            if (camera_phase == 5) frame.anchor_epoch = pawn.epoch + 1;
            if (camera_phase != logged_camera_phase) {
                SC_LOG("[world-camera-test] phase=%d owner=%s epoch=%u viewport=%ux%u", camera_phase, native ? "sifu" : "mc", frame.anchor_epoch, viewport_width, viewport_height);
                logged_camera_phase = camera_phase;
            }
        }
        if (combat_scenario) frame.world_path = "/SifuCraft/Test/" + pawn.map + "." + pawn.map;
        if(motion_scenario){frame.world_path="/SifuCraft/Test/"+pawn.map+"."+pawn.map;motion.Initialize(frame.player_location,motion_rise);}
        if (with_enemies && !enemies_ready_at && bridge.peer_connected() &&
            (bridge.peer_state().flags & protocol::kMcReady)) enemies_ready_at = now;
        // Start the phase clock in Minecraft's world, so client startup cannot skip the test.
        const int phase = enemy_scenario && enemies_ready_at ?
            std::min(4, static_cast<int>((now - enemies_ready_at) / 4000)) : 0;
        if (phase == 4) {
            frame.world_generation = 2;
            if (frame.anchor_epoch) ++frame.anchor_epoch;
            if (grid.world_generation != 2 && !grid_file.empty()) {
                grid.world_generation = 2;
                ++grid.generation;
                bridge.PublishCollision(grid);
            }
        }
        bridge.Tick(frame, now);
        if(!fluid_gate_control.empty()){
            std::ifstream control(fluid_gate_control);char state=0;control.get(state);
            if((state=='0'||state=='1')&&state!=fluid_gate_state){
                bridge::CollisionInfo gate;
                if(DescribeGridFile(state=='1'?fluid_gate_closed:fluid_gate_open,&gate)){
                    gate.generation=++fluid_gate_revision;gate.world_generation=frame.world_generation;
                    bridge.PublishDoors(gate);fluid_gate_state=state;
                    SC_LOG("[fluid-gate] revision=%u closed=%d blocks=%u",gate.generation,state=='1',gate.block_count);
                }
            }
        }
        if (shapes_test && bridge.peer_connected()) {
            if (shape_reader.Read(bridge.peer_session(),bridge.session(),frame.world_generation,pawn.map,now,&shape_frame))
                SC_LOG("[shape-test] received revision=%llu sections=%zu",(unsigned long long)shape_frame.header.revision,shape_frame.parts.size());
            if (shape_frame.header.revision) shape_reader.Acknowledge(shape_frame,true,now);
        }
        if(motion_scenario){
            std::uint32_t motion_flags=0;if(!motion_pause_control.empty()){std::ifstream pause(motion_pause_control);char value=0;pause.get(value);if(value=='1')motion_flags=world_motion::kPaused;}
            motion.Tick(bridge.session(),bridge.peer_connected()?bridge.peer_session():0,bridge.frame(),frame.world_generation,now,shape_frame,motion_flags);
        }
        if (interaction_scenario) {
            protocol::InteractionSnapshot candidate{};
            candidate.frame = now; candidate.revision = 7; candidate.world = frame.world_generation; candidate.epoch = frame.anchor_epoch;
            candidate.flags = frame.input_owner == protocol::InputOwner::Minecraft ? protocol::kInteractAvailable : 0;
            candidate.target = 1001; candidate.serial = 9; candidate.owner = 1002; candidate.owner_serial = 10;
            candidate.minimum = {-14350, 6225, 0}; candidate.maximum = {-14325, 6350, 200}; candidate.location = {-14337.5f, 6275, 100};
            candidate.range_cm = 300; bridge.PublishInteraction(candidate);
            const auto desired = 100 + interaction_accepted;
            if (door_generation != desired) {
                door_generation = desired;
                const bool closed = (interaction_accepted % 2) == 0;
                bridge.PublishDoors({door_generation, frame.world_generation, closed ? 2u : 0u, closed ? 128ull : 0ull,
                    "Hideout_1_Main", closed ? door_closed : door_open});
            }
        }
        if (gui_test && !gui_motion_sent && (bridge.peer_state().flags & protocol::kMcGuiOpen))
            gui_motion_sent = bridge.SendGuiMotion(0, 0, 0);
        if (!world_output.empty() && bridge.peer_connected() &&
            world_reader.Read(bridge.peer_session(), bridge.session(), frame.world_generation, frame.anchor_epoch, GetTickCount64(), &world_frame)) {
            ++world_frames;
            const auto plane = world_frame.pixels.size() / 2;
            unsigned colored = 0, covered = 0;
            for (std::size_t at = 0; at < plane; at += 4) {
                if (world_frame.pixels[at + 3] <= 8) continue;
                ++colored;
                float depth; std::memcpy(&depth, world_frame.pixels.data() + plane + at, 4);
                if (std::isfinite(depth) && depth > 0 && depth <= 1) ++covered;
            }
            if (world_camera_scenario && handed_over_at) {
                const auto age = now - handed_over_at;
                const std::uint64_t starts[] = {0, 3000, 7000, 11000, 15000, 19000};
                if (age - starts[camera_phase] > 750) {
                    ++camera_samples[camera_phase];
                    if (colored >= 20 && covered >= colored * .95) ++camera_visible[camera_phase];
                    if (frame.input_owner == protocol::InputOwner::Sifu) {
                        const auto& h = world_frame.header;
                        const double d = 3.141592653589793 / 180;
                        const double cp=std::cos(frame.camera_rotation.pitch*d), sp=std::sin(frame.camera_rotation.pitch*d);
                        const double cy=std::cos(frame.camera_rotation.yaw*d), sy=std::sin(frame.camera_rotation.yaw*d);
                        const double cr=std::cos(frame.camera_rotation.roll*d), sr=std::sin(frame.camera_rotation.roll*d);
                        const double forward[] = {cp*cy, sp, cp*sy};
                        const double up[] = {-cr*sp*cy-sr*sy, cr*cp, cy*sr-cr*sp*sy};
                        bool valid = std::abs(h.camera_cm[0]-frame.camera_location.x)<.02 && std::abs(h.camera_cm[1]-frame.camera_location.y)<.02 &&
                            std::abs(h.camera_cm[2]-frame.camera_location.z)<.02 && h.width==viewport_width && h.height==viewport_height;
                        for (int row=0;row<3;++row) {
                            double f=0,u=0;
                            for(int col=0;col<3;++col) { f+=h.view_rotation[col*4+row]*forward[col]; u+=h.view_rotation[col*4+row]*up[col]; }
                            valid = valid && std::abs(f-(row==2 ? -1:0))<.0002 && std::abs(u-(row==1 ? 1:0))<.0002;
                        }
                        const double expected=1/std::tan(frame.camera_fov*d/2);
                        valid = valid && std::abs(h.projection[0]-expected)<.0002 && std::abs(h.projection[5]-expected*h.width/h.height)<.0002;
                        if (!valid) ++camera_errors;
                    }
                }
            }
            if (!world_saved && colored >= 20 && covered >= colored * .95) {
                std::ofstream output(world_output, std::ios::binary);
                output.write(reinterpret_cast<const char*>(&world_frame.header), sizeof(world_frame.header));
                output.write(reinterpret_cast<const char*>(world_frame.pixels.data()), static_cast<std::streamsize>(world_frame.pixels.size()));
                output.close(); world_saved = !output.fail();
                SC_LOG("[world-image-test] paired=%u depth=%u source_age_ms=%llu frame=%llu -> %s", colored, covered,
                    static_cast<unsigned long long>(GetTickCount64() - world_frame.header.captured_ms),
                    static_cast<unsigned long long>(world_frame.header.frame_id), world_output.c_str());
            }
        }
        if (world_camera_scenario) overlay_reader.SetViewport(viewport_width, viewport_height);
        if ((!overlay_output.empty() || (viewport_width && viewport_height)) && bridge.peer_connected() &&
            overlay_reader.Read(bridge.peer_session(), bridge.session(), frame.world_generation, frame.anchor_epoch, &overlay_frame)) {
            if (world_camera_scenario && frame.input_owner == protocol::InputOwner::Sifu && now - handed_over_at > 3750 &&
                now - handed_over_at < 6500) ++native_hud_frames;
            ++overlay_frames;
            if (viewport_width && viewport_height) overlay_reader.SetViewport(viewport_width, viewport_height);
            std::size_t transparent = 0, visible = 0, translucent = 0;
            for (std::size_t at = 3; at < overlay_frame.pixels.size(); at += 4) {
                if (overlay_frame.pixels[at] == 0) ++transparent; else ++visible;
                if (overlay_frame.pixels[at] > 0 && overlay_frame.pixels[at] < 255) ++translucent;
            }
            const bool transparent_hud = visible > 0 && (gui_test ?
                translucent > 0 && (bridge.peer_state().flags & protocol::kMcGuiOpen) : transparent > visible);
            if (!overlay_output.empty() && ((!gui_test && overlay_frames == 30) || (overlay_frames > 30 && !overlay_saved && transparent_hud))) {
                std::ofstream output(overlay_output, std::ios::binary);
                output.write(reinterpret_cast<const char*>(&overlay_frame.header), sizeof(overlay_frame.header));
                output.write(reinterpret_cast<const char*>(overlay_frame.pixels.data()), static_cast<std::streamsize>(overlay_frame.pixels.size()));
                if (!output) { SC_LOG("[overlay-test] failed to save capture"); return 1; }
                overlay_saved = transparent_hud;
                SC_LOG("[overlay-test] saved %ux%u frame=%llu transparent=%zu visible=%zu -> %s",
                    overlay_frame.header.width, overlay_frame.header.height,
                    static_cast<unsigned long long>(overlay_frame.header.frame_id), transparent, visible, overlay_output.c_str());
            }
        }
        if (respawn_scenario && bridge.peer_connected()) {
            const auto& mc = bridge.peer_state();
            if (!saw_dead && (mc.flags & protocol::kMcDead)) {
                saw_dead = true;
                SC_LOG("[fake-respawn] MC death reported, pose=%d", (mc.flags & protocol::kMcHasPose) != 0);
            }
            if (mc.valid && input::SameScope(mc.sifu_session, bridge.session(), mc.world_generation, frame.world_generation) &&
                input::WantsAnchor(mc.flags, mc.anchor_epoch, pawn.epoch)) {
                pawn.epoch = input::NextEpoch(pawn.epoch);
                SC_LOG("[fake-respawn] acknowledged anchor %u -> %u", mc.anchor_epoch, pawn.epoch);
            }
            if (saw_dead && !saw_respawn_pose && pawn.epoch > 1 && mc.anchor_epoch == pawn.epoch &&
                (mc.flags & protocol::kMcHasPose) && !(mc.flags & (protocol::kMcDead | protocol::kMcRequestAnchor))) {
                saw_respawn_pose = true;
                const float dx = mc.player_location.x - pawn.x, dy = mc.player_location.y - pawn.y;
                const float dz = mc.player_location.z - (pawn.z - frame.capsule_half_height);
                SC_LOG("[fake-respawn] pose resumed epoch=%u error=(%.2f,%.2f,%.2f) cm", pawn.epoch, dx, dy, dz);
                protocol::PlayerHitPayload old{101, 1, 9000, protocol::kHitCanDefend, frame.world_generation, 1, 1, 0};
                bridge.SendPlayerHit(old); // a delayed old-life lethal event must not touch the respawned player
            }
        }
        bridge.DrainEnemyDamage(&damage_events);
        damage_ledger.SetSession(bridge.peer_connected() ? bridge.peer_session() : 0);
        if (impulse_session != (bridge.peer_connected() ? bridge.peer_session() : 0)) {
            impulse_session = bridge.peer_connected() ? bridge.peer_session() : 0; impulse_ledger.Reset();
        }
        for (const auto& event : damage_events) {
            if (event.type == static_cast<std::uint32_t>(protocol::EventType::NativeInteract)) {
                protocol::InteractPayload request{}; std::memcpy(&request, event.payload, sizeof(request));
                const bool valid = interaction_scenario && request.target == 1001 && request.serial == 9 && request.revision == 7 &&
                    request.world == frame.world_generation && request.epoch == frame.anchor_epoch && request.flags == 0 && request.reserved == 0;
                if (valid) ++interaction_accepted; else ++interaction_rejected;
                bridge.SendInteractionResult({event.id, request.revision, valid ? 0u : 1u, request.target, request.world, request.epoch});
                SC_LOG("[fake-interaction] request=%llu valid=%d accepted=%u rejected=%u", static_cast<unsigned long long>(event.id),
                    valid, interaction_accepted, interaction_rejected);
                continue;
            }
            if (event.type == static_cast<std::uint32_t>(protocol::EventType::EnemyImpulse)) {
                protocol::EnemyImpulsePayload push{}; std::memcpy(&push, event.payload, sizeof(push));
                const protocol::Vec3 feet{frame.player_location.x + 180, frame.player_location.y + (push.enemy_id == 101 ? 0.0f : 50.0f), frame.player_location.z + 2 - 92};
                const bool accepted = combat_scenario && push.spawn == 1 && push.enemy_id >= 101 && push.enemy_id <= 102 &&
                    frame.input_owner == protocol::InputOwner::Minecraft && frame.anchor_epoch == push.anchor_epoch &&
                    impulse_ledger.Consume(event.source_session, push, feet, now);
                if (accepted) ++impulse_accepted; else ++impulse_rejected;
                SC_LOG("[fake-mace] original=%llu enemy=%u/%u x=%.4f z=%.4f accepted=%d total=%u rejected=%u",
                    static_cast<unsigned long long>(push.origin_damage_id), push.enemy_id, push.spawn, push.mc_x, push.mc_z, accepted, impulse_accepted, impulse_rejected);
                continue;
            }
            if (event.type == static_cast<std::uint32_t>(protocol::EventType::PlayerBlocked)) {
                protocol::PlayerBlockedPayload reply{};
                std::memcpy(&reply, event.payload, sizeof(reply));
                const bool accepted = shield_ledger.Consume(event.source_session, reply, now);
                if (accepted) ++shield_replies;
                SC_LOG("[fake-shield] original=%llu perfect=%d accepted=%d replies=%u", static_cast<unsigned long long>(reply.origin_hit_id),
                    (reply.flags & protocol::kBlockedPerfect) != 0, accepted, shield_replies);
                continue;
            }
            protocol::EnemyDamagePayload damage;
            std::memcpy(&damage, event.payload, sizeof(damage));
            protocol::DamageResultPayload result{event.id, damage.enemy_id, damage.spawn,
                static_cast<std::uint32_t>(protocol::DamageOutcome::Stale), 0, 0, damage.world_generation};
            const auto claim = damage_ledger.Begin(event.source_session, event.id, &result);
            if (claim == combat::DamageLedger::Claim::Fresh) {
                if (!combat::ValidDamage(damage)) result.result = static_cast<std::uint32_t>(protocol::DamageOutcome::Invalid);
                else if (frame.input_owner != protocol::InputOwner::Minecraft || damage.anchor_epoch != frame.anchor_epoch) {
                    result.result = static_cast<std::uint32_t>(protocol::DamageOutcome::NotOwned);
                } else if (combat_scenario && damage.world_generation == frame.world_generation && damage.spawn == 1 &&
                           damage.enemy_id >= 101 && damage.enemy_id <= 102) {
                    auto& hp = enemy_health[damage.enemy_id - 101];
                    result.result = static_cast<std::uint32_t>(hp > 0 ? protocol::DamageOutcome::Applied : protocol::DamageOutcome::Dead);
                    if (hp > 0) {
                        result.sifu_damage = std::min(hp, damage.mc_damage * protocol::kEnemySifuHpPerMcHp);
                        hp -= result.sifu_damage;
                        ++applied_damage;
                        impulse_ledger.Record(event.id, event.source_session, damage,
                            {frame.player_location.x + 180, frame.player_location.y + (damage.enemy_id == 101 ? 0.0f : 50.0f), frame.player_location.z + 2 - 92}, now);
                    }
                    result.health_after = hp;
                }
                damage_ledger.Complete(event.source_session, event.id, result);
            }
            if (claim == combat::DamageLedger::Claim::Fresh || claim == combat::DamageLedger::Claim::Cached) {
                bridge.SendDamageResult(result);
                SC_LOG("[fake] damage event=%llu enemy=%u/%u mc=%.3f flags=%u stun=%.3f result=%u lost=%.3f HP=%.3f applications=%u",
                       static_cast<unsigned long long>(event.id), damage.enemy_id, damage.spawn, damage.mc_damage,
                       damage.flags, damage.stun_hint, result.result, result.sifu_damage, result.health_after, applied_damage);
            }
        }
        if(motion_scenario)bridge.PublishEnemies(frame.world_generation,motion.Entries());
        else if (with_enemies) {
            std::vector<protocol::EnemyEntry> enemies;
            if (phase != 3) {
                for (std::uint32_t id = 101; id <= (phase == 1 || phase == 2 ? 101u : 102u); ++id) {
                    if (combat_scenario && enemy_health[id - 101] <= 0) continue;
                    const float angle = static_cast<float>(elapsed * 0.5 + id);
                    // Resize an existing activation too: this exercises dimension synchronization,
                    // separately from replacing an entity when its spawn generation changes.
                    const bool resized = enemy_scenario && id == 101 && phase == 0 && enemies_ready_at &&
                        now - enemies_ready_at >= 2000;
                    const float radius = resized ? 55.0f : id == 101 ? 35.0f : 45.0f;
                    const float half = resized ? 110.0f : 92.0f;
                    enemies.push_back({id, phase >= 2 ? 2u : 1u,
                        {frame.player_location.x + (combat_scenario ? 180.0f : 200 + 70 * std::cos(angle)),
                         frame.player_location.y + (combat_scenario ? (id == 101 ? 0.0f : 50.0f) :
                            (id == 101 ? -100.0f : 100.0f) + 70 * std::sin(angle)),
                         frame.player_location.z + 2},
                        180, radius, half, combat_scenario ? enemy_health[id - 101] : 60,
                        combat_scenario ? 1000.0f : 100.0f, protocol::kEnemyAlive | protocol::kEnemyCanDie, 1,
                        {combat_scenario ? 0 : -35 * std::sin(angle), combat_scenario ? 0 : 35 * std::cos(angle), 0}, 0});
                }
            }
            bridge.PublishEnemies(frame.world_generation, enemies);
            if (phase != enemy_phase_seen) {
                enemy_phase_seen = phase;
                SC_LOG("[fake] enemy phase=%d world=%u count=%zu first_spawn=%u", phase, frame.world_generation,
                       enemies.size(), enemies.empty() ? 0 : enemies[0].spawn);
            }
        }
        if (pawn.hand_over && !handed_over_at && bridge.peer_connected() && bridge.peer_state().anchor_epoch == 1) {
            handed_over_at = now;
            SC_LOG("[fake] minecraft has taken the player (anchor epoch 1)");
        }
        if (respawn_scenario && handed_over_at && !sent_lethal && now - handed_over_at >= 1000) {
            protocol::PlayerHitPayload lethal{101, 1, 9000, protocol::kHitCanDefend, frame.world_generation, pawn.epoch, 1, 0};
            sent_lethal = bridge.SendPlayerHit(lethal);
            if (sent_lethal) SC_LOG("[fake-respawn] sent lethal hit at epoch %u", pawn.epoch);
        }
        for (MouseMove& move : mouse) {
            if (!handed_over_at || move.done || now - handed_over_at < move.at_ms) continue;
            move.done = true;
            pawn.mouse_dx += move.dx;
            pawn.mouse_dy += move.dy;
            SC_LOG("[fake] mouse moved by (%.0f, %.0f)", move.dx, move.dy);
        }
        if (totem_scenario && !totem_sent && handed_over_at && now - handed_over_at >= 40000u) {
            protocol::PlayerHitPayload hit{101, 1, 270, protocol::kHitCanDefend, frame.world_generation, pawn.epoch, -1, 0};
            totem_sent = bridge.SendPlayerHit(hit);
            if (totem_sent) SC_LOG("[fake-totem] sent lethal hit: 270 Sifu / 30 Minecraft damage");
        }
        if (food_scenario && !food_sent && handed_over_at && now - handed_over_at >= 45000u) {
            protocol::PlayerHitPayload hit{101, 1, 45, protocol::kHitCanDefend, frame.world_generation, pawn.epoch, -1, 0};
            food_sent = bridge.SendPlayerHit(hit);
            if (food_sent) SC_LOG("[fake-food] sent absorption hit: 45 Sifu / 5 Minecraft damage");
        }
        if (shield_scenario && handed_over_at && shield_sent == 2 && !shield_released && now - handed_over_at >= 54000u)
            shield_released = bridge.SendInput(protocol::EventType::MouseButton, 3, 0, 0);
        if (shield_scenario && handed_over_at && now - handed_over_at >= (shield_sent == 2 ? 55000u : 45000u + shield_sent * 1500u) && shield_sent < 3) {
            protocol::PlayerHitPayload hit{101, 1, 54, protocol::kHitCanDefend | protocol::kHitParriable |
                (shield_sent ? protocol::kHitPerfectWindow | (shield_sent == 1 ? (2u << 8) : 0) : 0), frame.world_generation, pawn.epoch, -1, 0};
            if (shield_sent == 1) hit.flags &= ~protocol::kHitParriable; // ordinary armed hit: perfect timing is independent
            if (shield_sent == 2 && !shield_fast_pressed) {
                shield_fast_pressed = bridge.SendInput(protocol::EventType::MouseButton, 3, 1, 0);
                if (!shield_fast_pressed) continue;
            }
            std::uint64_t id = 0;
            if (bridge.SendPlayerHit(hit, &id)) {
                shield_ledger.Record(id, bridge.peer_session(), hit, now);
                ++shield_sent;
                SC_LOG("[fake-shield] sent hit=%llu phase=%u", static_cast<unsigned long long>(id), shield_sent);
            }
        }
        if (player_hit_scenario && handed_over_at && now - handed_over_at >= (hit_phase ? 2500u : 1000u) && hit_phase < 2) {
            protocol::PlayerHitPayload hit{101, 1, hit_phase ? 36.0f : 18.0f,
                protocol::kHitCanDefend | protocol::kHitParriable | (1u << 8), frame.world_generation, pawn.epoch, -1, 0};
            if (hit_phase == 0) {
                auto& ring = *reinterpret_cast<protocol::EventRing*>(test_mapping.data() + protocol::kSifuToMcRingOffset);
                const auto before = std::atomic_ref<std::uint64_t>(ring.write_index).load();
                if (!bridge.SendPlayerHit(hit)) continue;
                // Same producer thread: inject an exact wire duplicate before sending newer IDs.
                const auto write = std::atomic_ref<std::uint64_t>(ring.write_index).load();
                ring.slots[write % protocol::kRingCapacity] = ring.slots[before % protocol::kRingCapacity];
                std::atomic_ref<std::uint64_t>(ring.write_index).store(write + 1, std::memory_order_release);
                bridge.SendPlayerHit(hit); // different ID, same damage inside vanilla invulnerability frames
                auto stale = hit; ++stale.world_generation; bridge.SendPlayerHit(stale);
                stale = hit; ++stale.anchor_epoch; bridge.SendPlayerHit(stale);
                auto invalid = hit; invalid.sifu_damage = NAN; bridge.SendPlayerHit(invalid);
            } else bridge.SendPlayerHit(hit);
            SC_LOG("[fake] player-hit phase=%d: base=%.1f; first phase includes duplicate, immediate second hit, stale world/epoch and NaN", hit_phase, hit.sifu_damage);
            ++hit_phase;
        }
        for (PawnMove& move : moves) {
            if (!handed_over_at || move.done || now - handed_over_at < move.at_ms) continue;
            move.done = true;
            pawn.x = move.x;
            pawn.y = move.y;
            pawn.z = move.z;
            pawn.yaw = move.yaw;
            ++pawn.epoch;
            SC_LOG("[fake] pawn moved to (%.0f, %.0f, %.0f) facing %.0f: hand-over epoch %u", pawn.x, pawn.y, pawn.z,
                   pawn.yaw, pawn.epoch);
        }
        for (HeldKey& key : keys) {
            if (!handed_over_at) break;
            const std::uint64_t since = now - handed_over_at;
            if (!key.pressed && since >= key.from_ms) {
                key.pressed = bridge.SendInput(key.type, key.code, 1, 0);
                SC_LOG("[fake] %s %d down", key.type == protocol::EventType::Key ? "key" : "button", key.code);
            }
            if (key.pressed && !key.released && since >= key.to_ms) {
                key.released = bridge.SendInput(key.type, key.code, 0, 0);
                SC_LOG("[fake] %s %d up", key.type == protocol::EventType::Key ? "key" : "button", key.code);
            }
        }
        const std::uint32_t acked = bridge.peer_connected() ? bridge.peer_state().collision_generation : 0;
        if (acked != loaded) {
            loaded = acked;
            SC_LOG("[collision] minecraft reports grid %u loaded%s", loaded,
                   loaded && loaded == grid.generation ? " (current)" : "");
        }
        if (bridge.blocks().generation != blocks_seen) {
            const bridge::BlockList& blocks = bridge.blocks();
            blocks_seen = blocks.generation;
            std::string list;
            for (std::size_t i = 0; i < blocks.entries.size() && i < 8; ++i) {
                const protocol::BlockEntry& e = blocks.entries[i];
                list += FormatString(" (%d, %d, %d) kind %u", e.x, e.y, e.z, e.kind);
            }
            SC_LOG("[blocks] table %u for %s (palette %u): %zu blocks%s%s", blocks.generation, blocks.map.c_str(),
                   blocks.palette_generation, blocks.entries.size(), list.c_str(),
                   blocks.entries.size() > 8 ? " ..." : "");
        }
        if (tick % kStatsEveryTicks == 0) SC_LOG("[bridge] %s", bridge.Describe().c_str());
    }
    if (interaction_scenario) SC_LOG("[fake-interaction] %s accepted=%u rejected=%u", interaction_accepted == 2 && interaction_rejected == 0 ? "PASS" : "FAIL",
        interaction_accepted, interaction_rejected);
    bridge.MarkClosing();
    if (world_audio_scenario) {
        SC_LOG("[world-audio-fixture] %s final_phase=%d", camera_phase == 5 ? "PASS" : "FAIL", camera_phase);
        if (camera_phase != 5) return 1;
    }
    if (world_camera_scenario && !world_audio_scenario) {
        bool passed = camera_errors == 0 && native_hud_frames == 0;
        for (int phase=0;phase<6;++phase) {
            SC_LOG("[world-camera-test] phase=%d samples=%u visible=%u",phase,camera_samples[phase],camera_visible[phase]);
            passed = passed && camera_samples[phase] >= 5 && camera_visible[phase] >= 3;
        }
        SC_LOG("[world-camera-test] %s camera_errors=%u native_hud_frames=%u",passed?"PASS":"FAIL",camera_errors,native_hud_frames);
        if(!passed) return 1;
    }
    if (impulse_accepted || impulse_rejected) SC_LOG("[fake-mace] %s accepted=%u rejected=%u", impulse_rejected ? "FAIL" : "PASS", impulse_accepted, impulse_rejected);
    SC_LOG("[bridge] final: %s", bridge.Describe().c_str());
    if (shield_scenario) {
        SC_LOG("[fake-shield] %s sent=%u replies=%u", shield_sent == 3 && shield_replies == 3 ? "PASS" : "FAIL", shield_sent, shield_replies);
        if (shield_sent != 3 || shield_replies != 3) return 1;
    }
    if (totem_scenario) {
        SC_LOG("[fake-totem] %s sent=%d", totem_sent ? "PASS" : "FAIL", totem_sent);
        if (!totem_sent) return 1;
    }
    if (food_scenario) {
        SC_LOG("[fake-food] %s sent=%d", food_sent ? "PASS" : "FAIL", food_sent);
        if (!food_sent) return 1;
    }
    if (!overlay_output.empty()) {
        SC_LOG("[overlay-test] %s frames=%u schema=%u layout=%016llX", overlay_saved ? "PASS" : "FAIL", overlay_frames,
            overlay::kVersion, static_cast<unsigned long long>(overlay::kLayoutHash));
        if (!overlay_saved) return 1;
    }
    if (!world_output.empty()) {
        SC_LOG("[world-image-test] %s frames=%u schema=%u layout=%016llX", world_saved ? "PASS" : "FAIL", world_frames,
            world_image::kVersion, static_cast<unsigned long long>(world_image::kLayoutHash));
        if (!world_saved) return 1;
    }
    return 0;
}
