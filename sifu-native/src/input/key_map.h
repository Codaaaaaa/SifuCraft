#pragma once

#include <string_view>

// Unreal FKey names -> Minecraft 26.3 input codes. Minecraft's input layer is SDL: keyboard codes
// are SDL scancodes (USB HID usages, e.g. W = 26) and mouse buttons are SDL buttons (left = 1).
namespace sifucraft::input {

enum class KeyKind {
    Unmapped,
    Keyboard,     // code = SDL scancode
    MouseButton,  // code = SDL mouse button
    MouseX,       // axis
    MouseY,       // axis
    MouseWheel,   // axis (MouseWheelAxis) or the scroll up/down "keys"; code = +1 / -1 for the keys
};

struct MappedKey {
    KeyKind kind = KeyKind::Unmapped;
    int code = 0;
};

MappedKey MapKey(std::string_view unreal_name);

// Minecraft action for an Unreal EInputEvent: PRESS = 1, RELEASE = 0, REPEAT = -1.
// A double click is a second press. Returns false for events with no key action (axis).
bool MapAction(int unreal_event, int* action);

const char* EventName(int unreal_event);

}  // namespace sifucraft::input
