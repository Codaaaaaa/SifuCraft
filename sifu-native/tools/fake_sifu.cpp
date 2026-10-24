// Stands in for Sifu when testing the Minecraft side of the bridge without the game: publishes a
// synthetic player walking in a circle and answers pings exactly like the in-game module.
//
//   sifucraft_fake_sifu [seconds] [--collision <grid.scvx>] [--pawn X Y Z [--yaw DEG] [--hand-over]]
//                       [--key CODE:FROM:TO]... [--button CODE:FROM:TO]... [--mouse DX:DY:AT]...
//                       [--then AT X Y Z DEG]...
//                       [--enemies | --enemy-scenario | --combat-scenario]
//     seconds: 0 or omitted = run until Ctrl+C
//     --collision: also publish that grid file in the CollisionManifest, as the game does
//     --pawn: a pawn standing still at (X, Y, Z) (Unreal cm, capsule centre) in the grid's map
//     --yaw: the way it faces (Unreal degrees: 0 = +X, 90 = +Y)
//     --hand-over: route the player to Minecraft (input owner, anchor epoch 1)
//     --key: hold SDL scancode CODE from FROM to TO ms after Minecraft has taken the player
//     --button: the same for SDL mouse button CODE (1 left, 3 right)
//     --mouse: move the mouse by (DX, DY) pixels AT ms after that (+DY looks down)
//     --then: AT ms after that, move the pawn to (X, Y, Z) facing DEG and hand it over again
//             (next anchor epoch): several test runs in one Minecraft session
//     --combat-scenario: two static test targets; validate damage events and reply with fake HP

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
#include "combat/damage_ledger.h"
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
    StandingPawn pawn;
    std::vector<HeldKey> keys;
    std::vector<PawnMove> moves;
    bool with_enemies = false, enemy_scenario = false, combat_scenario = false;
    struct MouseMove {
        double dx = 0, dy = 0;
        std::uint64_t at_ms = 0;
        bool done = false;
    };
    std::vector<MouseMove> mouse;
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--collision") == 0 && i + 1 < argc) {
            grid_file = argv[++i];
        } else if (std::strcmp(argv[i], "--pawn") == 0 && i + 3 < argc) {
            pawn.enabled = true;
            pawn.x = static_cast<float>(std::atof(argv[++i]));
            pawn.y = static_cast<float>(std::atof(argv[++i]));
            pawn.z = static_cast<float>(std::atof(argv[++i]));
        } else if (std::strcmp(argv[i], "--yaw") == 0 && i + 1 < argc) {
            pawn.yaw = static_cast<float>(std::atof(argv[++i]));
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
        } else if (std::strcmp(argv[i], "--enemies") == 0) {
            with_enemies = true;
        } else if (std::strcmp(argv[i], "--enemy-scenario") == 0) {
            with_enemies = enemy_scenario = true;
        } else if (std::strcmp(argv[i], "--combat-scenario") == 0) {
            with_enemies = combat_scenario = true;
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
    std::vector<protocol::Event> damage_events;
    unsigned applied_damage = 0;
    const ULONGLONG begin = GetTickCount64();
    for (std::uint64_t tick = 1; !g_stop; ++tick) {
        Sleep(kTickMs);
        const ULONGLONG now = GetTickCount64();
        const double elapsed = (now - begin) / 1000.0;
        if (seconds > 0 && elapsed >= seconds) break;
        bridge::PlayerFrame frame = pawn.enabled ? StandingFrame(pawn) : SyntheticFrame(elapsed);
        if (combat_scenario) frame.world_path = "/SifuCraft/Test/" + pawn.map + "." + pawn.map;
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
        bridge.DrainEnemyDamage(&damage_events);
        damage_ledger.SetSession(bridge.peer_connected() ? bridge.peer_session() : 0);
        for (const auto& event : damage_events) {
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
                        result.sifu_damage = std::min(hp, damage.mc_damage * protocol::kSifuHpPerMcHp);
                        hp -= result.sifu_damage;
                        ++applied_damage;
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
        if (with_enemies) {
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
        for (MouseMove& move : mouse) {
            if (!handed_over_at || move.done || now - handed_over_at < move.at_ms) continue;
            move.done = true;
            pawn.mouse_dx += move.dx;
            pawn.mouse_dy += move.dy;
            SC_LOG("[fake] mouse moved by (%.0f, %.0f)", move.dx, move.dy);
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
    bridge.MarkClosing();
    SC_LOG("[bridge] final: %s", bridge.Describe().c_str());
    return 0;
}
