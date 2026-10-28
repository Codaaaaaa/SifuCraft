#pragma once

// Control files in <Sifu>/Sifu/Binaries/Win64/SifuCraft, read once at startup.
//   disabled   the module stays inert (checked in DllMain's worker)
//   no-hooks   never patch anything: probe + bridge only (forces stage 0)
//   stage      rollout stage 0..10 (P2 1..5, blocks 6, combat 7, GUI 8, layers 9, shapes 10); missing or unreadable = 0
//              (hook-plan dry run only)
//   npc-motion-probe  startup opt-in to the bounded P8 M1/M2 diagnostic; no automatic movement
//   npc-ai-probe      startup opt-in to M3/M4 forwarding/hold diagnostic; no automatic control
//   player-world-observe  bounded read-only input/movement samples in Native control
//   collision-census  full diagnostic object/material surveys on streamed-level changes
namespace sifucraft::config {

struct Settings {
    int stage = 0;
    bool no_hooks = false;
    bool npc_motion_probe = false; // startup opt-in; explicit addressed commands still required
    bool npc_ai_probe = false;
    bool player_world_observe = false;
    bool collision_census = false;
};

Settings Load();

}  // namespace sifucraft::config
