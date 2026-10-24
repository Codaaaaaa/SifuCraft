#pragma once

// Control files in <Sifu>/Sifu/Binaries/Win64/SifuCraft, read once at startup.
//   disabled   the module stays inert (checked in DllMain's worker)
//   no-hooks   never patch anything: probe + bridge only (forces stage 0)
//   stage      rollout stage 0..8 (P2 hooks 1..5, P4 blocks 6, P5 combat 7, P6 GUI 8); missing or unreadable = 0
//              (hook-plan dry run only)
namespace sifucraft::config {

struct Settings {
    int stage = 0;
    bool no_hooks = false;
};

Settings Load();

}  // namespace sifucraft::config
