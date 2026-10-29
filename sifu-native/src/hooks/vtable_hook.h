#pragma once

#include <cstdint>

#include "generated/sifu_build.g.h"

namespace sifucraft::hooks {

// A planned hook target checked against live memory (read-only).
struct Resolution {
    const gen::hook::Target* target = nullptr;
    std::uintptr_t instance = 0;      // live object whose vtable would be patched
    std::uintptr_t vtable = 0;        // vtable pointer read from the instance
    std::uintptr_t slot_address = 0;  // vtable + slot
    std::uintptr_t slot_value = 0;    // function pointer currently in the slot
    unsigned long protection = 0;     // page protection of the slot
    bool vtable_ok = false;           // instance uses the expected class vftable
    bool slot_ok = false;             // slot still holds the expected original
    bool ready() const { return vtable_ok && slot_ok; }
};

// Checks `target` against `instance` without writing anything.
Resolution Resolve(std::uintptr_t image_base, const gen::hook::Target& target, std::uintptr_t instance);

// Replaces one function pointer in a class vftable (.rdata); no code bytes are modified.
// Install() only patches a slot that passes Resolve() (live instance uses the expected vftable
// and the slot still holds the expected original), and both directions use a compare-exchange
// so a slot changed by anyone else is never overwritten.
class VtableHook {
public:
    bool Install(std::uintptr_t image_base, const gen::hook::Target& target, std::uintptr_t instance,
                 void* replacement, bool log_lifecycle = true);
    bool Uninstall();
    // No write. Used only after a caller identifies a legitimate runtime replacement.
    bool RetireRestored(std::uintptr_t runtime_original);

    bool installed() const { return installed_; }
    const char* name() const { return target_ ? target_->name : "?"; }
    std::uintptr_t slot_address() const { return slot_address_; }
    std::uintptr_t original_address() const { return original_; }

    template <typename Fn>
    Fn original() const {
        return reinterpret_cast<Fn>(original_);
    }

private:
    bool Exchange(std::uintptr_t expected, std::uintptr_t desired);

    const gen::hook::Target* target_ = nullptr;
    std::uintptr_t slot_address_ = 0;
    std::uintptr_t original_ = 0;
    std::uintptr_t replacement_ = 0;
    bool installed_ = false;
    bool log_lifecycle_ = true;
};

}  // namespace sifucraft::hooks
