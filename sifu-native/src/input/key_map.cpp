#include "input/key_map.h"

#include <string>
#include <unordered_map>

namespace sifucraft::input {

namespace {

// EInputEvent
constexpr int kPressed = 0;
constexpr int kReleased = 1;
constexpr int kRepeat = 2;
constexpr int kDoubleClick = 3;
constexpr int kAxis = 4;

const std::unordered_map<std::string_view, MappedKey>& Table() {
    static const std::unordered_map<std::string_view, MappedKey> table = [] {
        std::unordered_map<std::string_view, MappedKey> t;
        const auto key = [&t](std::string_view name, int scancode) { t[name] = {KeyKind::Keyboard, scancode}; };

        static const char* const kLetters[] = {"A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
                                               "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"};
        for (int i = 0; i < 26; ++i) key(kLetters[i], 4 + i);
        static const char* const kDigits[] = {"One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine"};
        for (int i = 0; i < 9; ++i) key(kDigits[i], 30 + i);
        key("Zero", 39);
        static const char* const kFunction[] = {"F1", "F2", "F3", "F4",  "F5",  "F6",
                                                "F7", "F8", "F9", "F10", "F11", "F12"};
        for (int i = 0; i < 12; ++i) key(kFunction[i], 58 + i);
        static const char* const kNumPad[] = {"NumPadOne", "NumPadTwo",   "NumPadThree", "NumPadFour", "NumPadFive",
                                              "NumPadSix", "NumPadSeven", "NumPadEight", "NumPadNine"};
        for (int i = 0; i < 9; ++i) key(kNumPad[i], 89 + i);
        key("NumPadZero", 98);

        key("Enter", 40);
        key("Escape", 41);
        key("BackSpace", 42);
        key("Tab", 43);
        key("SpaceBar", 44);
        key("Hyphen", 45);
        key("Equals", 46);
        key("LeftBracket", 47);
        key("RightBracket", 48);
        key("Backslash", 49);
        key("Semicolon", 51);
        key("Apostrophe", 52);
        key("Tilde", 53);
        key("Comma", 54);
        key("Period", 55);
        key("Slash", 56);
        key("CapsLock", 57);
        key("Insert", 73);
        key("Home", 74);
        key("PageUp", 75);
        key("Delete", 76);
        key("End", 77);
        key("PageDown", 78);
        key("Right", 79);
        key("Left", 80);
        key("Down", 81);
        key("Up", 82);
        key("NumLock", 83);
        key("Divide", 84);
        key("Multiply", 85);
        key("Subtract", 86);
        key("Add", 87);
        key("Decimal", 99);
        key("LeftControl", 224);
        key("LeftShift", 225);
        key("LeftAlt", 226);
        key("LeftCommand", 227);
        key("RightControl", 228);
        key("RightShift", 229);
        key("RightAlt", 230);
        key("RightCommand", 231);

        t["LeftMouseButton"] = {KeyKind::MouseButton, 1};
        t["MiddleMouseButton"] = {KeyKind::MouseButton, 2};
        t["RightMouseButton"] = {KeyKind::MouseButton, 3};
        t["ThumbMouseButton"] = {KeyKind::MouseButton, 4};
        t["ThumbMouseButton2"] = {KeyKind::MouseButton, 5};

        t["MouseX"] = {KeyKind::MouseX, 0};
        t["MouseY"] = {KeyKind::MouseY, 0};
        t["MouseWheelAxis"] = {KeyKind::MouseWheel, 0};
        t["MouseScrollUp"] = {KeyKind::MouseWheel, 1};
        t["MouseScrollDown"] = {KeyKind::MouseWheel, -1};
        return t;
    }();
    return table;
}

}  // namespace

MappedKey MapKey(std::string_view unreal_name) {
    const auto& table = Table();
    const auto it = table.find(unreal_name);
    return it == table.end() ? MappedKey{} : it->second;
}

bool MapAction(int unreal_event, int* action) {
    switch (unreal_event) {
        case kPressed:
        case kDoubleClick: *action = 1; return true;
        case kReleased: *action = 0; return true;
        case kRepeat: *action = -1; return true;
        default: return false;
    }
}

const char* EventName(int unreal_event) {
    switch (unreal_event) {
        case kPressed: return "Pressed";
        case kReleased: return "Released";
        case kRepeat: return "Repeat";
        case kDoubleClick: return "DoubleClick";
        case kAxis: return "Axis";
        default: return "?";
    }
}

}  // namespace sifucraft::input
