#include "ui/player_hud.h"

#include <windows.h>
#include <array>
#include <atomic>
#include <optional>
#include <vector>
#include "core/log.h"
#include "core/safe_memory.h"
#include "ue/function_call.h"
#include "ue/objects.h"
#include "ui/visibility_lease.h"

namespace sifucraft::ui {
namespace {
namespace off = gen::off;
struct Ref {
    std::uintptr_t address = 0, cls = 0;
    ue::ObjectIdentity identity;
    bool operator==(const Ref&) const = default;
};
Ref Reference(std::uintptr_t at) { return {at, ue::ClassOf(at), ue::IdentityOf(at)}; }
bool Live(const Ref& ref) {
    return ref.address && ue::IsLive(ref.address) && ue::IdentityOf(ref.address) == ref.identity &&
        ue::ClassOf(ref.address) == ref.cls && ue::IsRegistered(ref.cls);
}
struct Context {
    Ref world, controller, pawn;
    render::Scope scope;
};
SRWLOCK context_lock = SRWLOCK_INIT;
Context published;
Context CurrentContext() {
    AcquireSRWLockShared(&context_lock); const Context copy = published; ReleaseSRWLockShared(&context_lock);
    return copy;
}

// Only H1 uses reflection caches. Do not use FindClass's runtime-thread cache here.
struct Layout {
    Ref cls;
    std::vector<ue::PropertyInfo> properties;
};
std::vector<Layout> layouts;
int Field(std::uintptr_t object, const char* name, const char* type, int size,
          const char* owner = nullptr, int expected = -1) {
    const auto cls = ue::ClassOf(object);
    if (!ue::IsRegistered(cls)) return -1;
    const auto identity = ue::IdentityOf(cls);
    Layout* found = nullptr;
    for (auto& layout : layouts) if (layout.cls.address == cls && layout.cls.identity == identity) { found = &layout; break; }
    if (!found) {
        if (layouts.size() >= 16) layouts.clear();
        layouts.push_back({Reference(cls), ue::ListProperties(cls)});
        found = &layouts.back();
    }
    const auto extent = mem::ReadOr<std::int32_t>(cls + off::UStruct::PropertiesSize, 0);
    if (extent < size || extent > 1024 * 1024) return -1;
    for (const auto& p : found->properties) {
        if (p.name == name && p.type == type && p.element_size == size && p.array_dim == 1 &&
            (!owner || p.owner == owner) && p.offset >= 0 && p.offset <= extent - size &&
            (expected < 0 || p.offset == expected)) return p.offset;
    }
    return -1;
}
bool NamedBase(std::uintptr_t object, const char* name) {
    auto cls = ue::ClassOf(object);
    for (int i = 0; cls && i < 64; ++i, cls = ue::SuperOf(cls)) {
        if (!ue::IsRegistered(cls)) return false;
        if (ue::ObjectName(cls) == name) return true;
    }
    return false;
}

enum Part { World, Controller, Pawn, Hud, Tree, Widget, Slot, Parent, Health, PartCount };
struct Target {
    std::array<Ref, PartCount> refs;
    std::uint64_t mc_session = 0, sifu_session = 0;
    std::uint32_t generation = 0, epoch = 0;
    int player_hud = -1, hud_widget = -1, hud_pawn = -1, hud_health = -1, hud_parent = -1;
    bool operator==(const Target&) const = default;
    std::uintptr_t at(Part part) const { return refs[part].address; }
};
VisibilityLease<Target> lease;
std::optional<Target> candidate;
ue::FunctionCall setter;
std::uintptr_t setter_class = 0;
std::uint64_t resolve_after = 0, call_after = 0;
std::atomic<std::uint64_t> hides{0}, restores{0}, rejected{0}, failed{0}, discovered{0};
std::atomic<bool> holding{false}, ready{false};
unsigned logged = 0;

bool Unbound(std::uintptr_t widget) {
    const auto delegate = widget + off::UWidget::VisibilityDelegate;
    const auto weak = delegate + off::ScriptDelegate::Object;
    ue::FName function{};
    mem::RawArray bindings;
    return mem::ReadOr<std::int32_t>(weak + off::FWeakObjectPtr::ObjectIndex, 0) == -1 &&
        mem::ReadOr<std::int32_t>(weak + off::FWeakObjectPtr::ObjectSerialNumber, -1) == 0 &&
        mem::Read(delegate + off::ScriptDelegate::FunctionName, &function) &&
        function.comparison_index == 0 && function.number == 0 &&
        mem::ReadArray(widget + off::UWidget::NativeBindings, &bindings) && bindings.num == 0;
}
bool Graph(const Target& t) {
    for (const auto& ref : t.refs) if (!Live(ref)) return false;
    // Slot and tree often have no assigned weak serial. They are never independent call
    // targets: every relationship is reread through the widget/HUD with nonzero serials.
    return ue::OuterOf(t.at(Tree)) == t.at(Hud) && ue::OuterOf(t.at(Widget)) == t.at(Tree) &&
        mem::ReadPtr(t.at(Hud) + off::UUserWidget::WidgetTree) == t.at(Tree) &&
        mem::ReadPtr(t.at(Hud) + t.hud_widget) == t.at(Widget) &&
        mem::ReadPtr(t.at(Hud) + t.hud_pawn) == t.at(Pawn) &&
        mem::ReadPtr(t.at(Hud) + t.hud_health) == t.at(Health) &&
        mem::ReadPtr(t.at(Pawn) + off::AFightingCharacter::m_HealthComponent) == t.at(Health) &&
        ue::OuterOf(t.at(Health)) == t.at(Pawn) &&
        mem::ReadPtr(t.at(Widget) + off::UWidget::Slot) == t.at(Slot) &&
        mem::ReadPtr(t.at(Slot) + off::UPanelSlot::Content) == t.at(Widget) &&
        mem::ReadPtr(t.at(Slot) + off::UPanelSlot::Parent) == t.at(Parent) &&
        mem::ReadPtr(t.at(Hud) + t.hud_parent) == t.at(Parent) && Unbound(t.at(Widget));
}
bool Matches(const Target& t, const Context& c) {
    return t.refs[World] == c.world && t.refs[Controller] == c.controller && t.refs[Pawn] == c.pawn &&
        t.mc_session == c.scope.mc_session && t.sifu_session == c.scope.sifu_session &&
        t.generation == c.scope.world && t.epoch == c.scope.epoch &&
        mem::ReadPtr(c.controller.address + t.player_hud) == t.at(Hud);
}
bool LocalPlayer(std::uintptr_t base, const Context& c) {
    if (!Live(c.world) || !Live(c.controller) || !Live(c.pawn) || !c.controller.identity.valid() || !c.pawn.identity.valid() ||
        mem::ReadPtr(base + gen::rva::GWorld) != c.world.address) return false;
    const auto instance = mem::ReadPtr(c.world.address + off::UWorld::OwningGameInstance);
    mem::RawArray players;
    if (!ue::IsLive(instance) || !mem::ReadArray(instance + off::UGameInstance::LocalPlayers, &players) ||
        players.num <= 0 || players.num > 8) return false;
    const auto player = mem::ReadPtr(players.data);
    return ue::IsLive(player) && mem::ReadPtr(player + off::UPlayer::PlayerController) == c.controller.address &&
        mem::ReadPtr(c.controller.address + off::AController::Pawn) == c.pawn.address;
}

std::optional<Target> Discover(const Context& c) {
    Target t;
    t.refs[World] = c.world; t.refs[Controller] = c.controller; t.refs[Pawn] = c.pawn;
    t.mc_session = c.scope.mc_session; t.sifu_session = c.scope.sifu_session;
    t.generation = c.scope.world; t.epoch = c.scope.epoch;
    const auto controller = c.controller.address;
    if (ue::ClassName(controller) != "FightingPlayerControllerBP_C") return {};
    t.player_hud = Field(controller, "PlayerHud", "ObjectProperty", 8);
    if (t.player_hud < 0) return {};
    const auto hud = mem::ReadPtr(controller + t.player_hud);
    if (!ue::IsLive(hud) || ue::ClassName(hud) != "BP_HUD_C") return {};
    t.hud_widget = Field(hud, "BP_Health_MC", "ObjectProperty", 8, "BP_HUD_C");
    t.hud_pawn = Field(hud, "MyChar", "ObjectProperty", 8, "BP_HUD_C");
    t.hud_health = Field(hud, "Health Component", "ObjectProperty", 8, "BP_HUD_C");
    t.hud_parent = Field(hud, "OverlayHealth", "ObjectProperty", 8, "BP_HUD_C");
    if (t.hud_widget < 0 || t.hud_pawn < 0 || t.hud_health < 0 || t.hud_parent < 0) return {};
    const auto widget = mem::ReadPtr(hud + t.hud_widget);
    if (!ue::IsLive(widget) || ue::ClassName(widget) != "BP_HUD_Bars_Health_C" || ue::ObjectName(widget) != "BP_Health_MC") return {};
    const auto slot = mem::ReadPtr(widget + off::UWidget::Slot);
    if (!ue::IsLive(slot)) return {};
    // PDB/native reflection crosschecks: fail closed if any size/type/offset differs.
    const bool checked =
        Field(hud, "WidgetTree", "ObjectProperty", 8, "UserWidget", off::UUserWidget::WidgetTree) >= 0 &&
        Field(widget, "Visibility", "EnumProperty", 1, "Widget", off::UWidget::Visibility) >= 0 &&
        Field(widget, "VisibilityDelegate", "DelegateProperty", 16, "Widget", off::UWidget::VisibilityDelegate) >= 0 &&
        Field(widget, "NativeBindings", "ArrayProperty", 16, "Widget", off::UWidget::NativeBindings) >= 0 &&
        Field(widget, "Slot", "ObjectProperty", 8, "Widget", off::UWidget::Slot) >= 0 &&
        Field(slot, "Parent", "ObjectProperty", 8, "PanelSlot", off::UPanelSlot::Parent) >= 0 &&
        Field(slot, "Content", "ObjectProperty", 8, "PanelSlot", off::UPanelSlot::Content) >= 0;
    if (!checked) return {};
    t.refs[Hud] = Reference(hud); t.refs[Widget] = Reference(widget); t.refs[Slot] = Reference(slot);
    t.refs[Tree] = Reference(mem::ReadPtr(hud + off::UUserWidget::WidgetTree));
    t.refs[Parent] = Reference(mem::ReadPtr(slot + off::UPanelSlot::Parent));
    t.refs[Health] = Reference(mem::ReadPtr(c.pawn.address + off::AFightingCharacter::m_HealthComponent));
    if (!t.refs[Hud].identity.valid() || !t.refs[Widget].identity.valid() || !Graph(t) ||
        !NamedBase(t.at(Health), "CharacterHealthComponent") || !NamedBase(t.at(Parent), "Overlay")) return {};
    if (!setter.Resolve(ue::ClassOf(widget), "SetVisibility") || !ue::IsRegistered(setter.function()) ||
        ue::ObjectName(ue::OuterOf(setter.function())) != "Widget" ||
        !VisibilitySignature(setter.params_size(), mem::ReadOr<std::uint16_t>(setter.function() + off::UFunction::ParmsSize, 0),
                             ue::ListProperties(setter.function()))) return {};
    setter_class = ue::ClassOf(widget);
    ready = true;
    ++discovered;
    if (logged++ < 40) SC_LOG("[hud] target ready: HUD=%p widget=%p identity=%d/%d native selfcheck=7/7 SetVisibility params=1",
        reinterpret_cast<void*>(hud), reinterpret_cast<void*>(widget), t.refs[Widget].identity.index, t.refs[Widget].identity.serial);
    return t;
}

struct Backend {
    std::uintptr_t base;
    render::Presentation presentation;
    bool owns_input;
    bool Read(const Target& t, std::uint8_t* value) {
        return Graph(t) && mem::Read(t.at(Widget) + off::UWidget::Visibility, value);
    }
    bool Set(const Target& t, std::uint8_t value) {
        const auto now = GetTickCount64();
        if (now < call_after) return false;
        bool valid = KnownVisibility(value) && Graph(t) && ue::ClassOf(t.at(Widget)) == setter_class &&
            ue::IsRegistered(setter.function()) && ue::FindFunction(setter_class, "SetVisibility") == setter.function();
        if (valid && value == visibility::Hidden) {
            const auto c = CurrentContext();
            valid = owns_input && Matches(t, c) && LocalPlayer(base, c) && render::FreshPresentation(c.scope, presentation, now);
        }
        const auto vtable = mem::ReadPtr(t.at(Widget));
        const auto process_event = vtable ? mem::ReadPtr(vtable + gen::slot::UObject::ProcessEvent) : 0;
        valid = valid && process_event >= base && process_event - base < gen::kSizeOfImage;
        if (!valid) { ++rejected; return false; }
        setter.Invoke(t.at(Widget), &value);
        std::uint8_t after = 0xFF;
        if (!Read(t, &after) || after != value) {
            ++failed; call_after = now + 250;
            if (logged++ < 40) SC_LOG("[hud] SetVisibility verification failed requested=%u actual=%u; retry delayed", value, after);
            return false;
        }
        if (value == visibility::Hidden) ++hides; else ++restores;
        if (logged++ < 40) SC_LOG("[hud] %s widget=%p identity=%d/%d visibility=%u epoch=%u",
            value == visibility::Hidden ? "hide" : "restore", reinterpret_cast<void*>(t.at(Widget)),
            t.refs[Widget].identity.index, t.refs[Widget].identity.serial, value, t.epoch);
        return true;
    }
};
}

void UpdateHudContext(std::uintptr_t world, std::uintptr_t controller, std::uintptr_t pawn, const render::Scope& scope) {
    const Context next{Reference(world), Reference(controller), Reference(pawn), scope};
    AcquireSRWLockExclusive(&context_lock); published = next; ReleaseSRWLockExclusive(&context_lock);
}
void ApplyHudOnGameThread(std::uintptr_t image_base, bool owns_input, const render::Presentation& presentation) {
    // EngineTick is normally non-reentrant; a widget's notification must not recurse into this work.
    static thread_local bool applying = false;
    if (applying) return;
    applying = true;
    try {
        const auto c = CurrentContext();
        const auto now = GetTickCount64();
        const bool local = LocalPlayer(image_base, c);
        if (candidate && (!local || !Matches(*candidate, c) || !Graph(*candidate))) candidate.reset();
        if (!candidate && local && now >= resolve_after) {
            resolve_after = now + 1000;
            candidate = Discover(c);
            if (!candidate) {
                ++rejected;
                if (logged++ < 40) SC_LOG("[hud] target unavailable: unsupported HUD, binding or identity/layout mismatch; leaving native UI unchanged");
            }
        }
        const auto desired = owns_input && candidate && render::FreshPresentation(c.scope, presentation, now) ? candidate : std::nullopt;
        Backend backend{image_base, presentation, owns_input};
        lease.Update(desired, backend);
        holding = lease.held();
    } catch (...) { ++failed; }
    applying = false;
}
std::string TakeHudStats() {
    return FormatString("ready=%d held=%d targets=%llu hides=%llu restores=%llu rejected=%llu failures=%llu",
        ready.load(), holding.load(), static_cast<unsigned long long>(discovered.load()),
        static_cast<unsigned long long>(hides.load()), static_cast<unsigned long long>(restores.load()),
        static_cast<unsigned long long>(rejected.load()), static_cast<unsigned long long>(failed.load()));
}
}
