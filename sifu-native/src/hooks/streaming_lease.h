#pragma once
#include <optional>

namespace sifucraft::hooks {
// A temporary override must restore exactly the state observed before it began.
class StreamingLease {
public:
    std::optional<bool> Update(bool freeze, bool current_enabled) {
        if (freeze) {
            if (!held_) { original_ = current_enabled; held_ = true; }
            return current_enabled ? std::optional<bool>(false) : std::nullopt;
        }
        if (!held_) return std::nullopt;
        held_ = false;
        return current_enabled != original_ ? std::optional<bool>(original_) : std::nullopt;
    }
    void Discard() { held_ = false; }
    bool held() const { return held_; }
private:
    bool held_ = false, original_ = true;
};
}
