#include "hooks/hook_plan.h"

#include <windows.h>

#include "core/log.h"
#include "core/safe_memory.h"
#include "probe/probe.h"
#include "ue/objects.h"

namespace sifucraft::hooks {

namespace {

namespace off = gen::off;

const char* ProtectionName(unsigned long protection) {
    switch (protection & 0xFF) {
        case PAGE_READONLY: return "PAGE_READONLY";
        case PAGE_READWRITE: return "PAGE_READWRITE";
        case PAGE_WRITECOPY: return "PAGE_WRITECOPY";
        case PAGE_EXECUTE_READ: return "PAGE_EXECUTE_READ";
        case PAGE_EXECUTE_READWRITE: return "PAGE_EXECUTE_READWRITE";
        default: return "other";
    }
}

void LogResolution(const Resolution& r, std::uintptr_t image_base) {
    const auto rel = [image_base](std::uintptr_t va) {
        return static_cast<unsigned long long>(va >= image_base ? va - image_base : va);
    };
    if (!r.instance) {
        SC_LOG("[hookplan] %-16s no live instance -> REFUSE", r.target->name);
        return;
    }
    SC_LOG("[hookplan] %-16s instance=%s class=%s vtable=+0x%llX %s slot+0x%X=+0x%llX %s (%s) -> %s",
           r.target->name, ue::ObjectName(r.instance).c_str(), ue::ClassName(r.instance).c_str(), rel(r.vtable),
           r.vtable_ok ? "OK" : "MISMATCH", r.target->slot, rel(r.slot_value), r.slot_ok ? "OK" : "MISMATCH",
           ProtectionName(r.protection), r.ready() ? "READY" : "REFUSE");
}

}  // namespace

int DryRun(std::uintptr_t image_base, std::uintptr_t world) {
    const probe::PlayerObjects player = probe::FindPlayerObjects(world);
    const std::uintptr_t engine = mem::ReadPtr(image_base + gen::rva::GEngine);

    struct Planned {
        const gen::hook::Target& target;
        std::uintptr_t instance;
    };
    const Planned plan[] = {
        {gen::hook::EngineTick, engine},
        {gen::hook::PlayerInputKey, player.controller},
        {gen::hook::PlayerInputAxis, player.controller},
        {gen::hook::CameraUpdate, player.camera},
        {gen::hook::MovementTick, player.movement},
    };

    int ready = 0;
    for (const Planned& p : plan) {
        const Resolution r = Resolve(image_base, p.target, p.instance);
        LogResolution(r, image_base);
        ready += r.ready();
    }

    // Context the P2 design depends on.
    const std::uintptr_t viewport = mem::ReadPtr(engine + off::UEngine::GameViewport);
    const std::uintptr_t capsule = mem::ReadPtr(player.pawn + off::AActor::RootComponent);
    const auto cursor = mem::ReadOr<std::uint32_t>(player.controller + gen::bits::APlayerController::bShowMouseCursor_Offset, 0);
    SC_LOG("[hookplan] context: viewport=%s pawn=%s root=%s capsule_half_height=%.1f movement_mode=%u show_cursor=%u",
           ue::ClassName(viewport).c_str(), ue::ClassName(player.pawn).c_str(), ue::ClassName(capsule).c_str(),
           mem::ReadOr<float>(capsule + off::UCapsuleComponent::CapsuleHalfHeight, -1.0f),
           mem::ReadOr<std::uint8_t>(player.movement + off::UCharacterMovementComponent::MovementMode, 0xFF),
           (cursor >> gen::bits::APlayerController::bShowMouseCursor_Shift) & 1);
    SC_LOG("[hookplan] %d/%zu targets verified; nothing was patched (dry run)", ready, std::size(plan));
    return ready;
}

}  // namespace sifucraft::hooks
