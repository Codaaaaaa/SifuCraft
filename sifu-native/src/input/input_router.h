#pragma once

#include <windows.h>

#include <cstdint>
#include <string_view>
#include <unordered_map>
#include <vector>

#include "input/key_map.h"
#include "sifucraft_protocol.h"

namespace sifucraft::input {

struct ForwardedEvent {
    protocol::EventType type;  // Key or MouseButton
    std::int32_t code;
    std::int32_t action;       // 1 press, 0 release, -1 repeat
    std::int32_t modifiers;    // SDL keymod bits
};

// Routes the local player's keyboard and mouse between Sifu and Minecraft.
//
// A key's press and release always go to the same side: the side that received the press keeps
// the key until it is released, whatever the owner is by then. When Minecraft loses ownership,
// every key it still holds is released on its side. Escape always stays with Sifu (pause menu);
// F8 never reaches Sifu and toggles routing on/off.
//
// Called from the game thread (input hooks) and the runtime thread (ownership, draining); all
// state is behind one lock.
class InputRouter {
public:
    // Game thread. True when the event belongs to Minecraft: the hook must consume it.
    bool OnKey(const MappedKey& key, std::string_view unreal_name, int action);
    bool OnAxis(KeyKind kind, float delta);

    // Runtime thread.
    protocol::InputOwner owner() const;
    void SetOwner(protocol::InputOwner owner);
    bool TakeToggleRequest();

    struct Drained {
        std::vector<ForwardedEvent> events;
        double mouse_dx = 0.0;  // running totals
        double mouse_dy = 0.0;
        double scroll = 0.0;
    };
    void Drain(Drained* out);

private:
    std::int32_t ModifiersLocked() const;
    void PushLocked(protocol::EventType type, std::int32_t code, std::int32_t action);

    mutable SRWLOCK lock_ = SRWLOCK_INIT;
    protocol::InputOwner owner_ = protocol::InputOwner::Sifu;
    std::unordered_map<std::int32_t, protocol::InputOwner> held_;  // key id -> side that got the press
    std::vector<ForwardedEvent> pending_;
    double mouse_dx_ = 0.0;
    double mouse_dy_ = 0.0;
    double scroll_ = 0.0;
    bool toggle_requested_ = false;
};

InputRouter& Router();

}  // namespace sifucraft::input
