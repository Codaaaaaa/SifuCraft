#include "core/config.h"

#include <windows.h>

#include <algorithm>
#include <cstdio>
#include <string>

#include "core/log.h"

namespace sifucraft::config {

namespace {

constexpr int kMaxStage = 10;

bool Exists(const std::wstring& path) { return GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES; }

int ReadStage(const std::wstring& path) {
    FILE* file = _wfopen(path.c_str(), L"r");
    if (!file) return 0;
    int stage = 0;
    if (std::fscanf(file, "%d", &stage) != 1) stage = 0;
    std::fclose(file);
    return std::clamp(stage, 0, kMaxStage);
}

}  // namespace

Settings Load() {
    const std::wstring& root = log::RootDirectory();
    Settings settings;
    settings.no_hooks = Exists(root + L"\\no-hooks");
    settings.stage = settings.no_hooks ? 0 : ReadStage(root + L"\\stage");
    settings.npc_motion_probe = settings.stage >= 10 && Exists(root + L"\\npc-motion-probe");
    settings.npc_ai_probe = settings.stage >= 10 && Exists(root + L"\\npc-ai-probe");
    SC_LOG("config: rollout stage %d%s", settings.stage, settings.no_hooks ? " (no-hooks file present)" : "");
    return settings;
}

}  // namespace sifucraft::config
