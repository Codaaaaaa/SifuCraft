#include "input/input_router.h"

namespace sifucraft::input {

namespace {

using protocol::EventType;
using protocol::InputOwner;

constexpr std::int32_t kMouseButtonBit = 0x10000;  // key id space: scancodes vs mouse buttons

// SDL scancode -> SDL keymod bit.
std::int32_t ModifierBit(std::int32_t scancode) {
    switch (scancode) {
        case 225: return 0x0001;  // LSHIFT
        case 229: return 0x0002;  // RSHIFT
        case 224: return 0x0040;  // LCTRL
        case 228: return 0x0080;  // RCTRL
        case 226: return 0x0100;  // LALT
        case 230: return 0x0200;  // RALT
        case 227: return 0x0400;  // LGUI
        case 231: return 0x0800;  // RGUI
        default: return 0;
    }
}

class Lock {
public:
    explicit Lock(SRWLOCK& lock) : lock_(lock) { AcquireSRWLockExclusive(&lock_); }
    ~Lock() { ReleaseSRWLockExclusive(&lock_); }
    Lock(const Lock&) = delete;
    Lock& operator=(const Lock&) = delete;

private:
    SRWLOCK& lock_;
};

}  // namespace

InputRouter& Router() {
    static InputRouter router;
    return router;
}

std::int32_t InputRouter::ModifiersLocked() const {
    std::int32_t modifiers = 0;
    for (const auto& [id, side] : held_) {
        if (side == InputOwner::Minecraft && !(id & kMouseButtonBit)) modifiers |= ModifierBit(id);
    }
    return modifiers;
}

void InputRouter::PushLocked(EventType type, std::int32_t code, std::int32_t action, std::uint32_t revision) {
    pending_.push_back({type, code, action, ModifiersLocked(), revision, mouse_dx_, mouse_dy_, scroll_});
}

bool InputRouter::OnKey(const MappedKey& key, std::string_view unreal_name, int action) {
    Lock lock(lock_);
    if (unreal_name == "F8") {
        if (action == 1) toggle_requested_ = true;
        return true;
    }
    if (key.kind == KeyKind::MouseWheel) return owner_ == InputOwner::Minecraft;  // wheel travels as an axis
    if (key.kind != KeyKind::Keyboard && key.kind != KeyKind::MouseButton) return false;

    const bool button = key.kind == KeyKind::MouseButton;
    const std::int32_t id = key.code | (button ? kMouseButtonBit : 0);
    InputOwner side = owner_;
    const auto held = held_.find(id);
    const bool escape = unreal_name == "Escape";
    if (escape && action != 1 && held == held_.end()) return false;
    if (escape && action == 1 && !(owner_ == InputOwner::Minecraft && gui_available_ && gui_open_)) side = InputOwner::Sifu;
    if (action == 1) {
        held_[id] = side;
    } else if (held != held_.end()) {
        side = held->second;
        if (action == 0) held_.erase(held);
    }
    if (side != InputOwner::Minecraft) return false;
    std::uint32_t revision = 0;
    if (action == 1 && gui_available_ && !button && (key.code == 8 || escape)) {
        gui_open_ = escape ? false : !gui_open_;
        if (++gui_revision_ == 0) ++gui_revision_;
        revision = gui_pending_ = gui_revision_;
    }
    PushLocked(button ? EventType::MouseButton : EventType::Key, key.code, action, revision);
    if (revision && gui_open_) PushLocked(EventType::GuiMouseMotion, 0, 0); // establish an ordered cursor baseline
    return true;
}

bool InputRouter::OnAxis(KeyKind kind, float delta) {
    Lock lock(lock_);
    if (owner_ != InputOwner::Minecraft) return false;
    switch (kind) {
        case KeyKind::MouseX: mouse_dx_ += delta; break;
        case KeyKind::MouseY: mouse_dy_ -= delta; break;  // Unreal: +up; protocol: +down
        case KeyKind::MouseWheel: scroll_ += delta; break;
        default: return false;
    }
    if (gui_available_ && gui_open_) PushLocked(EventType::GuiMouseMotion, 0, 0);
    return true;
}

InputOwner InputRouter::owner() const {
    AcquireSRWLockShared(&lock_);
    const InputOwner owner = owner_;
    ReleaseSRWLockShared(&lock_);
    return owner;
}

void InputRouter::SetOwner(InputOwner owner) {
    Lock lock(lock_);
    if (owner == owner_) return;
    if (owner_ == InputOwner::Minecraft) {
        // Release on Minecraft's side whatever it still holds; later real releases go to Sifu.
        for (auto it = held_.begin(); it != held_.end();) {
            if (it->second != InputOwner::Minecraft) {
                ++it;
                continue;
            }
            const bool button = it->first & kMouseButtonBit;
            const std::int32_t code = it->first & ~kMouseButtonBit;
            it = held_.erase(it);
            PushLocked(button ? EventType::MouseButton : EventType::Key, code, 0);
        }
    }
    owner_ = owner;
    gui_available_ = gui_open_ = false;
    gui_pending_ = 0;
}

void InputRouter::SetGuiState(bool available, bool open, std::uint32_t acknowledged) {
    Lock lock(lock_);
    gui_available_ = available && owner_ == InputOwner::Minecraft;
    if (!gui_available_) { gui_open_ = false; gui_pending_ = 0; return; }
    if (!gui_pending_ || acknowledged == gui_pending_) {
        if (open && !gui_open_) PushLocked(EventType::GuiMouseMotion, 0, 0);
        gui_open_ = open; gui_pending_ = 0;
    }
}
void InputRouter::GuiInputDropped(std::uint32_t revision) {
    Lock lock(lock_);
    if (revision && gui_pending_ == revision) { gui_pending_ = 0; gui_open_ = false; }
}

bool InputRouter::TakeToggleRequest() {
    Lock lock(lock_);
    const bool requested = toggle_requested_;
    toggle_requested_ = false;
    return requested;
}

void InputRouter::Drain(Drained* out) {
    Lock lock(lock_);
    out->events.insert(out->events.end(), pending_.begin(), pending_.end());
    pending_.clear();
    out->mouse_dx = mouse_dx_;
    out->mouse_dy = mouse_dy_;
    out->scroll = scroll_;
}

}  // namespace sifucraft::input
