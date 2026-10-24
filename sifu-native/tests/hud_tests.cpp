#include <cstdio>
#include <optional>
#include "render/overlay_status.h"
#include "ui/visibility_lease.h"

namespace {
using namespace sifucraft;
namespace v = ui::visibility;
unsigned failures = 0;
void Expect(bool condition, const char* message) {
    std::printf("%s %s\n", condition ? "ok" : "FAIL", message);
    if (!condition) ++failures;
}
// Synthetic objects and an API callback substitute; no game process is opened or called.
struct Widget { int serial = 7; bool live = true; std::uint8_t visibility = v::HitTestInvisible; };
struct Key {
    Widget* widget;
    int serial;
    std::uint32_t epoch = 1;
    bool operator==(const Key&) const = default;
};
struct Backend {
    unsigned calls = 0;
    bool fail = false, partial = false;
    bool Read(const Key& key, std::uint8_t* value) {
        if (!key.widget->live || key.widget->serial != key.serial) return false;
        *value = key.widget->visibility;
        return true;
    }
    bool Set(const Key& key, std::uint8_t value) {
        std::uint8_t before = 0;
        if (!Read(key, &before)) return false;
        ++calls;
        if (!fail || partial) key.widget->visibility = value;
        return !fail;
    }
};

void TestLease() {
    for (const auto original : {v::Visible, v::HitTestInvisible, v::SelfHitTestInvisible}) {
        Widget widget{7, true, static_cast<std::uint8_t>(original)};
        const std::optional<Key> target = Key{&widget, 7};
        Backend backend;
        ui::VisibilityLease<Key> lease;
        lease.Update(target, backend);
        Expect(widget.visibility == v::Hidden && lease.held() && backend.calls == 1, "hide only a visible target");
        for (int tick = 0; tick < 100; ++tick) lease.Update(target, backend);
        Expect(backend.calls == 1, "steady-state ticks do not repeat SetVisibility");
        lease.Update(std::nullopt, backend);
        Expect(widget.visibility == original && !lease.held() && backend.calls == 2, "F8/disconnect restores the exact original visibility");
    }
    for (const auto original : {v::Hidden, v::Collapsed, 255}) {
        Widget widget{7, true, static_cast<std::uint8_t>(original)};
        Backend backend;
        ui::VisibilityLease<Key> lease;
        lease.Update(std::optional<Key>{{&widget, 7}}, backend);
        lease.Update(std::nullopt, backend);
        Expect(widget.visibility == original && !lease.held() && !backend.calls, "original hidden/collapsed/unknown visibility stays untouched");
    }
    Widget widget;
    const std::optional<Key> target = Key{&widget, 7};
    Backend backend;
    ui::VisibilityLease<Key> lease;
    lease.Update(target, backend);
    widget.visibility = v::SelfHitTestInvisible;
    lease.Update(target, backend);
    lease.Update(std::nullopt, backend);
    Expect(widget.visibility == v::SelfHitTestInvisible && backend.calls == 3, "native visible changes become the new restoration baseline");
    lease.Update(target, backend);
    widget.visibility = v::Collapsed;
    lease.Update(target, backend);
    lease.Update(std::nullopt, backend);
    Expect(widget.visibility == v::Collapsed && !lease.held() && backend.calls == 4, "native collapse cancels our restoration obligation");
    widget.visibility = v::Visible;
    lease.Update(target, backend);
    widget.visibility = v::HitTestInvisible;
    lease.Update(std::nullopt, backend);
    Expect(widget.visibility == v::HitTestInvisible && backend.calls == 5, "native changes during release are preserved without an extra call");
    lease.Update(target, backend);
    ++widget.serial;
    widget.visibility = v::SelfHitTestInvisible;
    lease.Update(std::nullopt, backend);
    Expect(widget.visibility == v::SelfHitTestInvisible && backend.calls == 6, "reused address/index with a new serial is never restored");
    lease.Update(std::optional<Key>{{&widget, 8}}, backend);
    widget.live = false;
    lease.Update(std::nullopt, backend);
    Expect(!lease.held() && backend.calls == 7, "destroyed targets are forgotten without a call");
}

void TestFailuresAndReplacement() {
    Widget first, second;
    Backend backend;
    ui::VisibilityLease<Key> lease;
    const std::optional<Key> a = Key{&first, 7}, b = Key{&second, 7};
    backend.fail = true;
    lease.Update(a, backend);
    Expect(first.visibility == v::HitTestInvisible && lease.held(), "failed hide keeps the baseline for retry or release");
    backend.fail = false;
    lease.Update(a, backend);
    Expect(first.visibility == v::Hidden, "a failed hide can be retried");
    backend.fail = true;
    lease.Update(b, backend);
    Expect(first.visibility == v::Hidden && second.visibility == v::HitTestInvisible && lease.held(), "failed restoration blocks a replacement hide");
    backend.fail = false;
    const auto calls = backend.calls;
    lease.Update(b, backend);
    Expect(first.visibility == v::HitTestInvisible && second.visibility == v::Hidden && backend.calls == calls + 2,
           "replacement tick restores once and hides once");
    const auto changed_epoch = std::optional<Key>{{&second, 7, 2}};
    const auto epoch_calls = backend.calls;
    lease.Update(changed_epoch, backend);
    Expect(second.visibility == v::Hidden && backend.calls == epoch_calls + 2, "epoch changes release and reacquire the same live widget");
    lease.Update(std::nullopt, backend);
    Expect(second.visibility == v::HitTestInvisible, "new epoch restores its original state");
    backend.fail = backend.partial = true;
    lease.Update(a, backend);
    Expect(first.visibility == v::Hidden && lease.held(), "partial API failure retains the original state for cleanup");
    backend.fail = false;
    lease.Update(std::nullopt, backend);
    Expect(first.visibility == v::HitTestInvisible && !lease.held(), "cleanup handles a call that changed visibility before failing verification");
}

void TestFreshness() {
    render::Scope scope{11, 22, 3, 4, 1000, true};
    render::Presentation shown{scope, 1000, 1000};
    Expect(render::FreshPresentation(scope, shown, 1250), "250 ms boundary allows the current successfully presented frame");
    Expect(!render::FreshPresentation(scope, shown, 1251), "251 ms expires the HUD replacement");
    Expect(!render::FreshPresentation(scope, {}, 1000), "ownership alone cannot hide a native HUD without a successful Present");
    for (int field = 0; field < 4; ++field) {
        auto changed = scope;
        if (field == 0) ++changed.mc_session;
        if (field == 1) ++changed.sifu_session;
        if (field == 2) ++changed.world;
        if (field == 3) ++changed.epoch;
        Expect(!render::FreshPresentation(changed, shown, 1000), "session/world/epoch mismatch rejects a previous presentation");
    }
    auto disabled = scope; disabled.enabled = false;
    Expect(!render::FreshPresentation(disabled, shown, 1000), "F8/menu/disabled context cancels the replacement immediately");
    auto stale_image = shown; stale_image.image_ms = 749;
    Expect(!render::FreshPresentation(scope, stale_image, 1000), "repeated Presents cannot refresh an expired source image");
    auto stale_context = shown; stale_context.scope.updated_ms = 749;
    Expect(!render::FreshPresentation(scope, stale_context, 1000), "stale rendered context rejects otherwise fresh image/Present timestamps");
    Expect(!render::FreshPresentation(scope, shown, 999), "future timestamps cannot masquerade as fresh");
    // Exercise expiry through the real lease policy, including automatic restoration.
    Widget widget;
    Backend backend;
    ui::VisibilityLease<Key> lease;
    for (const auto now : {1000u, 1251u}) {
        const auto desired = render::FreshPresentation(scope, shown, now) ? std::optional<Key>{{&widget, 7}} : std::nullopt;
        lease.Update(desired, backend);
    }
    Expect(widget.visibility == v::HitTestInvisible && backend.calls == 2, "image timeout actually restores the original HUD");
}

void TestSignature() {
    const ue::PropertyInfo parameter{"SetVisibility", "InVisibility", "EnumProperty", "", 0, 1, 1, 0};
    Expect(ui::VisibilitySignature(1, 1, {parameter}), "SetVisibility accepts the exact reflected one-byte enum parameter");
    Expect(!ui::VisibilitySignature(4, 1, {parameter}) && !ui::VisibilitySignature(1, 4, {parameter}) &&
           !ui::VisibilitySignature(1, 1, {}) && !ui::VisibilitySignature(1, 1, {parameter, parameter}),
           "wrong parameter storage/size/count is rejected");
    for (int i = 0; i < 5; ++i) {
        auto wrong = parameter;
        if (i == 0) wrong.type = "ByteProperty";
        if (i == 1) wrong.offset = 1;
        if (i == 2) wrong.element_size = 4;
        if (i == 3) wrong.array_dim = 2;
        if (i == 4) wrong.name = "ReturnValue";
        Expect(!ui::VisibilitySignature(1, 1, {wrong}), "unknown parameter shape/name is rejected before any API call");
    }
}
}
int main() {
    TestLease(); TestFailuresAndReplacement(); TestFreshness(); TestSignature();
    return failures ? 1 : 0;
}
