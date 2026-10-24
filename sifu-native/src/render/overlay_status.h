#pragma once
#include <cstdint>

namespace sifucraft::render {
inline constexpr std::uint64_t kOverlayTimeoutMs = 250;
struct Scope {
    std::uint64_t mc_session = 0, sifu_session = 0;
    std::uint32_t world = 0, epoch = 0;
    std::uint64_t updated_ms = 0;
    bool enabled = false;
};
inline bool SameScope(const Scope& a, const Scope& b) {
    return a.mc_session == b.mc_session && a.sifu_session == b.sifu_session && a.world == b.world && a.epoch == b.epoch;
}
struct Presentation {
    Scope scope;
    std::uint64_t image_ms = 0, presented_ms = 0;
};
inline bool Recent(std::uint64_t time, std::uint64_t now) {
    return time && now >= time && now - time <= kOverlayTimeoutMs;
}
inline bool FreshPresentation(const Scope& requested, const Presentation& shown, std::uint64_t now) {
    return requested.enabled && requested.mc_session && requested.sifu_session && requested.world && requested.epoch &&
        shown.scope.enabled && SameScope(requested, shown.scope) && Recent(requested.updated_ms, now) &&
        Recent(shown.scope.updated_ms, now) && Recent(shown.image_ms, now) && Recent(shown.presented_ms, now);
}
}
