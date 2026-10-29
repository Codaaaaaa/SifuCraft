#include "hooks/vtable_hook.h"

#include <windows.h>

#include "core/log.h"
#include "core/safe_memory.h"

namespace sifucraft::hooks {

Resolution Resolve(std::uintptr_t image_base, const gen::hook::Target& target, std::uintptr_t instance) {
    Resolution r;
    r.target = &target;
    r.instance = instance;
    if (!instance) return r;
    r.vtable = mem::ReadPtr(instance);
    r.vtable_ok = r.vtable == image_base + target.vftable;
    r.slot_address = r.vtable + target.slot;
    r.slot_value = mem::ReadPtr(r.slot_address);
    r.slot_ok = r.vtable_ok && r.slot_value == image_base + target.original;
    MEMORY_BASIC_INFORMATION region;
    if (r.vtable && VirtualQuery(reinterpret_cast<void*>(r.slot_address), &region, sizeof(region))) {
        r.protection = region.Protect;
    }
    return r;
}

bool VtableHook::Exchange(std::uintptr_t expected, std::uintptr_t desired) {
    void* slot = reinterpret_cast<void*>(slot_address_);
    DWORD protection = 0;
    if (!VirtualProtect(slot, sizeof(std::uintptr_t), PAGE_READWRITE, &protection)) {
        SC_LOG("[hook] %s: VirtualProtect failed (error %lu)", name(), GetLastError());
        return false;
    }
    const LONG64 previous = InterlockedCompareExchange64(static_cast<volatile LONG64*>(slot),
                                                         static_cast<LONG64>(desired), static_cast<LONG64>(expected));
    DWORD ignored = 0;
    VirtualProtect(slot, sizeof(std::uintptr_t), protection, &ignored);
    if (static_cast<std::uintptr_t>(previous) != expected) {
        SC_LOG("[hook] %s: slot holds 0x%llX, expected 0x%llX -- left untouched", name(),
               static_cast<unsigned long long>(previous), static_cast<unsigned long long>(expected));
        return false;
    }
    return true;
}

bool VtableHook::Install(std::uintptr_t image_base, const gen::hook::Target& target, std::uintptr_t instance,
                         void* replacement, bool log_lifecycle) {
    if (installed_) return true;
    target_ = &target;
    log_lifecycle_ = log_lifecycle;
    const Resolution r = Resolve(image_base, target, instance);
    if (!r.ready()) {
        SC_LOG("[hook] %s: REFUSED (vtable %s, slot %s)", target.name, r.vtable_ok ? "ok" : "mismatch",
               r.slot_ok ? "ok" : "mismatch");
        return false;
    }
    if (r.slot_address % sizeof(std::uintptr_t) != 0) {
        SC_LOG("[hook] %s: REFUSED (slot not 8-byte aligned)", target.name);
        return false;
    }
    slot_address_ = r.slot_address;
    original_ = r.slot_value;
    replacement_ = reinterpret_cast<std::uintptr_t>(replacement);
    if (!Exchange(original_, replacement_)) return false;
    installed_ = true;
    if (log_lifecycle_) SC_LOG("[hook] %s: INSTALLED slot=0x%llX original=+0x%llX", target.name,
           static_cast<unsigned long long>(slot_address_), static_cast<unsigned long long>(original_ - image_base));
    return true;
}

bool VtableHook::Uninstall() {
    if (!installed_) return true;
    if (!Exchange(replacement_, original_)) return false;
    installed_ = false;
    if (log_lifecycle_) SC_LOG("[hook] %s: UNINSTALLED (slot restored)", name());
    return true;
}
bool VtableHook::RetireRestored(std::uintptr_t runtime_original) {
    if (!installed_) return true;
    if (!runtime_original || runtime_original == replacement_ || mem::ReadPtr(slot_address_) != runtime_original) return false;
    installed_ = false;
    return true;
}

}  // namespace sifucraft::hooks
