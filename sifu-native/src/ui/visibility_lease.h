#pragma once
#include <cstdint>
#include <optional>
#include <vector>
#include "generated/sifu_build.g.h"
#include "ue/reflection.h"

namespace sifucraft::ui {
namespace visibility = gen::value::ESlateVisibility;
inline bool Visible(std::uint8_t value) {
    return value == visibility::Visible || value == visibility::HitTestInvisible || value == visibility::SelfHitTestInvisible;
}
inline bool KnownVisibility(std::uint8_t value) {
    return Visible(value) || value == visibility::Hidden || value == visibility::Collapsed;
}
inline bool VisibilitySignature(int storage_size, int params_size, const std::vector<ue::PropertyInfo>& fields) {
    return storage_size == 1 && params_size == 1 && fields.size() == 1 && fields[0].name == "InVisibility" &&
        fields[0].type == "EnumProperty" && fields[0].offset == 0 && fields[0].element_size == 1 && fields[0].array_dim == 1;
}

// Game-thread ownership of one widget. Backend revalidates the complete target before reads
// and calls. Failed restores retain their obligation for a later tick, without hiding a new
// widget first. An original Hidden/Collapsed never creates an obligation to make it visible.
template<class Key> class VisibilityLease {
public:
    bool held() const { return held_.has_value(); }
    template<class Backend> void Update(const std::optional<Key>& desired, Backend& backend) {
        std::uint8_t current = 0xFF;
        if (held_ && (!desired || *held_ != *desired)) {
            if (backend.Read(*held_, &current) && current == visibility::Hidden) {
                if (!backend.Set(*held_, original_)) return;
            }
            held_.reset();
        }
        if (!desired) return;
        if (!backend.Read(*desired, &current) || !KnownVisibility(current)) { held_.reset(); return; }
        if (held_ && current == visibility::Hidden) return;
        // A different value was set by the game. Respect a new hidden state, or save its new
        // visible state as the restoration baseline before applying our own hide again.
        held_.reset();
        if (!Visible(current)) return;
        held_ = *desired;
        original_ = current;
        backend.Set(*held_, static_cast<std::uint8_t>(visibility::Hidden));
    }
private:
    std::optional<Key> held_;
    std::uint8_t original_ = 0;
};
}
