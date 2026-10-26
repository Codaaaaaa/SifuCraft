// Offline checks for the parts of sifu-native that must never crash the game:
// guarded memory reads and the dsound proxy forwarding.

#include <windows.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "collision/shape_file.h"
#include "collision/builder.h"
#include "combat/activation_tracker.h"
#include "combat/damage_ledger.h"
#include "combat/hit_sample.h"
#include "combat/attack_glow.h"
#include "combat/light_impact.h"
#include "combat/shield_timing.h"
#include "combat/shield_ledger.h"
#include "combat/impulse_ledger.h"
#include "combat/launch_params.h"
#include "collision/voxels.h"
#include "collision/walk_check.h"
#include "core/build_guard.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/vtable_hook.h"
#include "input/input_router.h"
#include "input/ownership_intent.h"
#include "input/displacement_guard.h"
#include "interaction/door_motion.h"
#include "hooks/streaming_lease.h"
#include "interaction/intent.h"
#include "input/anchor_request.h"
#include "input/key_map.h"
#include "probe/combat_sample.h"
#include "ue/transform.h"
#include "ue/data_table.h"
#include "ue/language.h"

namespace {

int g_failures = 0;

void Expect(bool condition, const char* what) {
    std::printf("%s  %s\n", condition ? "ok  " : "FAIL", what);
    if (!condition) ++g_failures;
}

struct ImpactArguments {
    void* component = nullptr;
    std::uint8_t type = 0xFF, quadrant = 0xFF, cardinal = 0xFF, height = 0xFF;
    float freeze = -1, stun = -1, damage = -1, extra = -1;
    bool first = true, second = true;
    void* instigator = reinterpret_cast<void*>(1);
    sifucraft::ue::FVector location{}, direction{};
    unsigned calls = 0;
} g_impact;

void CaptureImpact(void* component, std::uint8_t type, std::uint8_t quadrant, std::uint8_t cardinal,
                   std::uint8_t height, float freeze, float stun, const sifucraft::ue::FVector* location,
                   float damage, float extra, bool first, bool second, void* instigator,
                   const sifucraft::ue::FVector* direction) {
    g_impact = {component, type, quadrant, cardinal, height, freeze, stun, damage, extra,
                first, second, instigator, *location, *direction, g_impact.calls + 1};
}

void TestLightImpactCall() {
    using namespace sifucraft;
    // Volatile pointer forces the same indirect x64 call as the generated in-game RVA.
    combat::LaunchImpactFn volatile entry = &CaptureImpact;
    int component = 0;
    const ue::FVector location{12.5f, -91.25f, 103.75f};
    combat::LaunchLightImpact(entry, &component, location, 18.125f);
    Expect(g_impact.calls == 1 && g_impact.component == &component &&
           g_impact.type == gen::value::EImpactType::Hit &&
           g_impact.quadrant == gen::value::EQuadrantTypes::FrontLeft &&
           g_impact.cardinal == gen::value::ESCCardinalPoints::North &&
           g_impact.height == gen::value::EHeight::Mid,
           "light impact x64 call keeps this and all byte-sized enum arguments");
    Expect(g_impact.freeze == 0 && g_impact.stun == 0.15f && g_impact.damage == 18.125f &&
           g_impact.extra == 0 && !g_impact.first && !g_impact.second && !g_impact.instigator &&
           g_impact.location.x == location.x && g_impact.location.y == location.y && g_impact.location.z == location.z &&
           g_impact.direction.x == 0 && g_impact.direction.y == 0 && g_impact.direction.z == 0,
           "light impact x64 stack preserves float, vector, bool and null-instigator arguments");
    Expect(combat::ImpactDamage(18, 60, true) == 18 && combat::ImpactDamage(27, 6, true) == 6 &&
           combat::ImpactDamage(27, 6, false) == 27,
           "light impact preserves ordinary, lethal and cannot-die wrapper damage semantics");
}

void TestGridPublication() {
    using namespace sifucraft::collision;
    BuildRequest request; request.generation=3;request.map="map";request.file_tag="-doors";
    PublishedContent content;
    auto first=std::make_shared<VoxelGrid>();first->Set(-9,3,2);first->Set(17,-2,4);
    Expect(!content.Matches(request,*first),"first grid needs publication, including after an unconfirmed write failure");
    content.Confirm(request,first);
    VoxelGrid reordered;reordered.Set(17,-2,4);reordered.Set(-9,3,2);
    Expect(content.Matches(request,reordered),"identical voxel cells in different extraction order retain the published generation");
    VoxelGrid changed;changed.Set(17,-2,4);changed.Set(-9,3,3);
    Expect(!content.Matches(request,changed),"same cell/block counts with a moved door still require publication");
    Expect(!content.Matches(request,VoxelGrid{}),"removing all dynamic collision publishes an empty replacement");
    request.generation++;
    Expect(!content.Matches(request,reordered),"same geometry in another Native world gets a new publication");
    request.generation--;request.map="other";
    Expect(!content.Matches(request,reordered),"same geometry in another map is not suppressed");
    request.map="map";request.file_tag="";
    Expect(!content.Matches(request,reordered),"static and dynamic grids keep separate publications");
}

void TestGuardedReads() {
    using namespace sifucraft;
    Expect(!mem::Copy(nullptr, 0x1000, 8), "copy refuses before the handler is installed");
    Expect(mem::Install(), "vectored handler installs");

    std::uint64_t value = 0x1122334455667788ull, out = 0;
    Expect(mem::Read(reinterpret_cast<std::uintptr_t>(&value), &out) && out == value, "8-byte read");
    char text[] = "sifucraft", copy[sizeof(text)] = {};
    Expect(mem::Copy(copy, reinterpret_cast<std::uintptr_t>(text), sizeof(text)) && !std::strcmp(copy, text),
           "byte-loop read");

    Expect(!mem::Read(0x20, &out), "null-page address rejected without faulting");
    Expect(!mem::Read(0xFFFF800000000000ull, &out), "kernel address rejected without faulting");

    const std::uint64_t faults = mem::FaultCount();
    void* reserved = VirtualAlloc(nullptr, 4096, MEM_RESERVE, PAGE_NOACCESS);
    Expect(!mem::Read(reinterpret_cast<std::uintptr_t>(reserved), &out), "uncommitted page read fails");
    Expect(!mem::Copy(copy, reinterpret_cast<std::uintptr_t>(reserved) + 1, 3), "uncommitted byte read fails");
    Expect(mem::FaultCount() == faults + 2, "both faults were caught by the handler");

    void* guarded = VirtualAlloc(nullptr, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE | PAGE_GUARD);
    Expect(!mem::Read(reinterpret_cast<std::uintptr_t>(guarded), &out), "guard page read fails");
    Expect(!mem::Read(reinterpret_cast<std::uintptr_t>(guarded), &out), "guard page is re-armed afterwards");

    mem::RawArray array;
    struct {
        void* data;
        std::int32_t num, max;
    } bad = {&value, 5, 2};
    Expect(!mem::ReadArray(reinterpret_cast<std::uintptr_t>(&bad), &array), "TArray with num > max rejected");

    VirtualFree(reserved, 0, MEM_RELEASE);
    VirtualFree(guarded, 0, MEM_RELEASE);
}

void TestBoundedLogs() {
    wchar_t temporary[MAX_PATH]{}; GetTempPathW(MAX_PATH, temporary);
    const auto path = std::wstring(temporary) + L"sifucraft-log-test-" + std::to_wstring(GetCurrentProcessId()) + L".txt";
    auto read = [](const std::wstring& name) {
        std::string result; HANDLE file = CreateFileW(name.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE,
            nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (file == INVALID_HANDLE_VALUE) return result;
        const DWORD size = GetFileSize(file, nullptr); result.resize(size);
        DWORD count = 0; ReadFile(file, result.data(), size, &count, nullptr); result.resize(count); CloseHandle(file); return result;
    };
    {
        sifucraft::TextFile file;
        Expect(file.Open(path, 256, 2), "open a bounded log");
        for (char c : {'A', 'B', 'C', 'D'}) file.Write(std::string(256, c));
        Expect(read(path) == std::string(256, 'D') && read(path + L".1") == std::string(256, 'C') &&
            read(path + L".2") == std::string(256, 'B'), "rotation keeps the newest bounded parts in order");
        HANDLE reader = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
        Expect(reader != INVALID_HANDLE_VALUE, "hold a reader that denies log renaming");
        file.Write(std::string(256, 'E'));
        Expect(read(path) == std::string(256, 'D'), "failed rotation preserves evidence and cannot grow beyond the cap");
        CloseHandle(reader);
    }
    {
        sifucraft::TextFile file; file.Open(path, 256, 2); file.Write(std::string(1024, 'F'));
        Expect(read(path).size() == 256, "one oversized message also obeys the file limit");
        file.TryWrite("exit");
        Expect(read(path) == "exit", "final nonblocking writer also rotates a full log");
    }
    DeleteFileW(path.c_str()); DeleteFileW((path + L".1").c_str()); DeleteFileW((path + L".2").c_str());
}

void TestBuildGuard() {
    using namespace sifucraft;
    const guard::GameModule exact{0x140000000, gen::kTimeDateStamp, gen::kSizeOfImage};
    Expect(guard::Verify(exact), "guard accepts the generated build");
    Expect(!guard::Verify({0x140000000, gen::kTimeDateStamp + 1, gen::kSizeOfImage}),
           "guard rejects a different timestamp");
    Expect(!guard::Verify({0x140000000, gen::kTimeDateStamp, gen::kSizeOfImage + 0x1000}),
           "guard rejects a different image size");
    guard::GameModule self;
    Expect(!guard::ReadGameModule(&self), "guard refuses to run outside Sifu-Win64-Shipping.exe");
}

// ---- VtableHook against a fake read-only vftable --------------------------------------------

int g_original_calls = 0;
int g_hook_calls = 0;
using TestFn = int (*)(void* self, int value);
sifucraft::hooks::VtableHook g_test_hook;

int OriginalFn(void*, int value) {
    ++g_original_calls;
    return value + 1;
}

int HookFn(void* self, int value) {
    ++g_hook_calls;
    return g_test_hook.original<TestFn>()(self, value) * 10;
}

void TestVtableHook() {
    using sifucraft::gen::hook::Target;
    // One page stands in for the image: a vftable at +0x100, made read-only like .rdata.
    auto* page = static_cast<std::uint8_t*>(VirtualAlloc(nullptr, 4096, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE));
    const auto base = reinterpret_cast<std::uintptr_t>(page);
    auto* vftable = reinterpret_cast<std::uintptr_t*>(page + 0x100);
    vftable[2] = reinterpret_cast<std::uintptr_t>(&OriginalFn);
    DWORD old = 0;
    VirtualProtect(page, 4096, PAGE_READONLY, &old);
    std::uintptr_t vptr = reinterpret_cast<std::uintptr_t>(vftable);  // the "object": just a vptr
    const auto instance = reinterpret_cast<std::uintptr_t>(&vptr);
    const auto call = [&](int value) {
        auto fn = reinterpret_cast<TestFn>(reinterpret_cast<std::uintptr_t*>(vptr)[2]);
        return fn(&vptr, value);
    };
    const std::uintptr_t original_rva = reinterpret_cast<std::uintptr_t>(&OriginalFn) - base;
    const Target target{"Test", 0x100, 0x10, original_rva};

    Expect(call(1) == 2, "vtable call reaches the original");
    Expect(g_test_hook.Install(base, target, instance, reinterpret_cast<void*>(&HookFn)),
           "hook installs into a read-only vftable");
    Expect(call(1) == 20 && g_hook_calls == 1 && g_original_calls == 2, "call runs hook, then original");
    MEMORY_BASIC_INFORMATION region{};
    VirtualQuery(page + 0x100, &region, sizeof(region));
    Expect(region.Protect == PAGE_READONLY, "page protection restored after patching");
    Expect(g_test_hook.Uninstall() && call(1) == 2 && g_hook_calls == 1, "uninstall restores the original");

    sifucraft::hooks::VtableHook refused;
    Expect(!refused.Install(base, Target{"WrongOriginal", 0x100, 0x10, original_rva + 16}, instance,
                            reinterpret_cast<void*>(&HookFn)),
           "refuses when the slot holds another function");
    Expect(!refused.Install(base, Target{"WrongClass", 0x200, 0x10, original_rva}, instance,
                            reinterpret_cast<void*>(&HookFn)),
           "refuses an instance of another class");
    Expect(!refused.Install(base, target, 0, reinterpret_cast<void*>(&HookFn)), "refuses without an instance");
    Expect(call(1) == 2, "refused installs leave the slot untouched");
    VirtualFree(page, 0, MEM_RELEASE);
}

// Expected values are Minecraft 26.3's InputConstants (SDL scancodes / buttons).
void TestKeyMap() {
    using sifucraft::input::KeyKind;
    using sifucraft::input::MapKey;
    const auto is = [](const char* name, KeyKind kind, int code) {
        const auto mapped = MapKey(name);
        return mapped.kind == kind && mapped.code == code;
    };
    Expect(is("W", KeyKind::Keyboard, 26) && is("A", KeyKind::Keyboard, 4) && is("D", KeyKind::Keyboard, 7),
           "letters map to SDL scancodes");
    Expect(is("SpaceBar", KeyKind::Keyboard, 44) && is("LeftShift", KeyKind::Keyboard, 225) &&
               is("LeftControl", KeyKind::Keyboard, 224) && is("Escape", KeyKind::Keyboard, 41),
           "space / shift / control / escape");
    Expect(is("F5", KeyKind::Keyboard, 62) && is("One", KeyKind::Keyboard, 30) && is("Zero", KeyKind::Keyboard, 39),
           "F5 and digit row");
    Expect(is("LeftMouseButton", KeyKind::MouseButton, 1) && is("RightMouseButton", KeyKind::MouseButton, 3),
           "mouse buttons map to SDL buttons");
    Expect(MapKey("MouseX").kind == KeyKind::MouseX && MapKey("Gamepad_LeftX").kind == KeyKind::Unmapped,
           "mouse axes recognised, gamepad left unmapped");
    int action = 99;
    Expect(sifucraft::input::MapAction(3, &action) && action == 1, "double click is a press");
    Expect(sifucraft::input::MapAction(1, &action) && action == 0 && !sifucraft::input::MapAction(4, &action),
           "release maps to 0, axis has no action");
}

void TestInputRouter() {
    using namespace sifucraft::input;
    using sifucraft::protocol::InputOwner;
    InputRouter router;
    const MappedKey w = MapKey("W"), shift = MapKey("LeftShift"), click = MapKey("LeftMouseButton");
    InputRouter::Drained drained;

    Expect(!router.OnKey(w, "W", 1), "sifu owns input: a press stays with sifu");
    router.SetOwner(InputOwner::Minecraft);
    Expect(!router.OnKey(w, "W", 0), "a key pressed under sifu is released to sifu");
    Expect(router.OnKey(shift, "LeftShift", 1) && router.OnKey(w, "W", 1) && router.OnKey(click, "LeftMouseButton", 1),
           "minecraft owns input: presses go to minecraft");
    router.Drain(&drained);
    Expect(drained.events.size() == 3 && drained.events[1].code == 26 && drained.events[1].modifiers == 0x0001,
           "forwarded W carries the held shift modifier");
    Expect(drained.events[2].type == sifucraft::protocol::EventType::MouseButton && drained.events[2].code == 1,
           "mouse button forwarded as a button event");
    Expect(!router.OnKey(MapKey("Escape"), "Escape", 1), "escape without a mirrored container stays with sifu");
    Expect(router.OnKey(MapKey("F8"), "F8", 1) && router.TakeToggleRequest() && !router.TakeToggleRequest(),
           "F8 is consumed and requests one toggle");
    Expect(router.OnAxis(KeyKind::MouseX, 3.0f) && router.OnAxis(KeyKind::MouseY, 2.0f), "mouse axes go to minecraft");
    drained = {};
    router.Drain(&drained);
    Expect(drained.mouse_dx == 3.0 && drained.mouse_dy == -2.0, "mouse totals use +x right, +y down");

    router.SetOwner(InputOwner::Sifu);
    drained = {};
    router.Drain(&drained);
    int releases = 0;
    for (const auto& e : drained.events) releases += e.action == 0;
    Expect(drained.events.size() == 3 && releases == 3, "handing back to sifu releases everything minecraft held");
    Expect(!router.OnKey(w, "W", 0) && !router.OnAxis(KeyKind::MouseX, 1.0f), "afterwards input goes to sifu");
}

void TestInventoryRouting() {
    using namespace sifucraft;
    using protocol::InputOwner;
    using protocol::EventType;
    input::InputRouter router;
    input::InputRouter::Drained events;
    router.SetOwner(InputOwner::Minecraft);
    router.SetGuiState(true, false, 0);
    Expect(router.OnKey(input::MapKey("E"), "E", 1), "E opens the mirrored inventory");
    router.Drain(&events);
    const auto opening = events.events.front().gui_revision;
    Expect(opening && events.events.size() == 2 && events.events.back().type == EventType::GuiMouseMotion,
           "inventory press precedes an ordered cursor baseline");
    router.SetGuiState(true, false, 0); // a snapshot from before the opening press
    router.OnAxis(input::KeyKind::MouseX, 31);
    router.OnKey(input::MapKey("LeftMouseButton"), "LeftMouseButton", 1);
    events = {}; router.Drain(&events);
    Expect(events.events.size() == 2 && events.events[0].type == EventType::GuiMouseMotion &&
           events.events[0].mouse_x == 31 && events.events[1].type == EventType::MouseButton,
           "GUI motion is ordered before the click at that position");
    Expect(router.OnKey(input::MapKey("Escape"), "Escape", 1), "fast E then Escape closes MC before its snapshot acknowledges opening");
    events = {}; router.Drain(&events);
    const auto closing = events.events.front().gui_revision;
    Expect(closing && closing != opening, "close carries its own acknowledgement token");
    router.SetGuiState(true, true, opening); // an older open echo must not reopen the prediction
    Expect(router.OnKey(input::MapKey("Escape"), "Escape", 0), "Escape release returns to the same handler as its press");
    Expect(!router.OnKey(input::MapKey("Escape"), "Escape", 1), "a second Escape reaches Sifu despite an old open echo");
    router.OnKey(input::MapKey("Escape"), "Escape", 0);
    router.SetGuiState(true, false, closing);
    router.OnKey(input::MapKey("E"), "E", 0);
    router.OnKey(input::MapKey("E"), "E", 1);
    router.SetOwner(InputOwner::Sifu);
    router.SetOwner(InputOwner::Minecraft);
    Expect(!router.OnKey(input::MapKey("Escape"), "Escape", 1), "F8 handoff clears stale GUI predictions");
    router.OnKey(input::MapKey("Escape"), "Escape", 0);
    router.SetGuiState(true, true, 0);
    router.SetGuiState(false, true, 0);
    Expect(!router.OnKey(input::MapKey("Escape"), "Escape", 1), "expired overlay cannot intercept Escape for invisible UI");
    router.OnKey(input::MapKey("Escape"), "Escape", 0);
    router.SetGuiState(true, true, 0, true);
    events = {}; router.Drain(&events);
    events = {};
    router.OnKey(input::MapKey("E"), "E", 1);
    router.OnAxis(input::KeyKind::MouseX, 1);
    router.Drain(&events);
    Expect(events.events.size() == 2 && events.events.front().gui_revision == 0 &&
           events.events.back().type == EventType::GuiMouseMotion, "typing E in a focused search box preserves GUI routing and cursor motion");
    router.OnKey(input::MapKey("E"), "E", 0);
    Expect(router.OnKey(input::MapKey("Escape"), "Escape", 1), "Escape still closes a focused search inventory");
}

BOOL CALLBACK CountDevice(GUID*, const wchar_t*, const wchar_t*, void* context) {
    ++*static_cast<int*>(context);
    return TRUE;
}

void TestProxy(const char* dll_path) {
    HMODULE proxy = LoadLibraryA(dll_path);
    Expect(proxy != nullptr, "proxy dsound.dll loads");
    if (!proxy) return;
    using Callback = BOOL(CALLBACK*)(GUID*, const wchar_t*, const wchar_t*, void*);
    using Enumerate = HRESULT(WINAPI*)(Callback, void*);
    // Ordinal 3 is DirectSoundEnumerateW; Sifu resolves its imports by ordinal.
    auto enumerate = reinterpret_cast<Enumerate>(
        reinterpret_cast<void*>(GetProcAddress(proxy, MAKEINTRESOURCEA(3))));
    Expect(enumerate != nullptr, "ordinal 3 is exported");
    int devices = 0;
    if (enumerate) {
        const HRESULT hr = enumerate(&CountDevice, &devices);
        Expect(SUCCEEDED(hr), "DirectSoundEnumerateW forwards to the system dll");
        std::printf("      (%d audio devices enumerated)\n", devices);
    }
    FreeLibrary(proxy);
}

// FRotator::Quaternion, the inverse of what ReadWorldTransform applies.
void RotatorToQuat(float pitch, float yaw, float roll, float q[4]) {
    const float half = 3.14159265358979f / 360.0f;
    const float sp = std::sin(pitch * half), cp = std::cos(pitch * half);
    const float sy = std::sin(yaw * half), cy = std::cos(yaw * half);
    const float sr = std::sin(roll * half), cr = std::cos(roll * half);
    q[0] = cr * sp * sy - sr * cp * cy;
    q[1] = -cr * sp * cy - sr * cp * sy;
    q[2] = cr * cp * sy - sr * sp * cy;
    q[3] = cr * cp * cy + sr * sp * sy;
}

bool Near(float a, float b) { return std::fabs(a - b) < 0.01f; }

void TestWorldTransform() {
    using namespace sifucraft;
    namespace off = gen::off;
    float q[4];
    RotatorToQuat(30.0f, 120.0f, 10.0f, q);
    ue::FRotator r = ue::QuatToRotator(q[0], q[1], q[2], q[3]);
    Expect(Near(r.pitch, 30.0f) && Near(r.yaw, 120.0f) && Near(r.roll, 10.0f), "quaternion -> rotator round trip");
    RotatorToQuat(0.0f, -139.7f, 0.0f, q);
    r = ue::QuatToRotator(q[0], q[1], q[2], q[3]);
    Expect(Near(r.pitch, 0.0f) && Near(r.yaw, -139.7f) && Near(r.roll, 0.0f), "negative yaw stays in (-180, 180]");

    // A scene component whose relative and world transforms differ, as when it is attached.
    std::vector<unsigned char> component(off::USceneComponent::ComponentToWorld + gen::size::FTransform, 0);
    const float relative[3] = {-29.0f, -40.0f, 0.0f};
    std::memcpy(&component[off::USceneComponent::RelativeLocation], relative, sizeof(relative));
    const std::uintptr_t parent = 0x1234;
    std::memcpy(&component[off::USceneComponent::AttachParent], &parent, sizeof(parent));
    RotatorToQuat(0.0f, 45.0f, 0.0f, q);
    const float translation[4] = {-637.0f, 2119.0f, 413.0f, 0.0f};
    std::memcpy(&component[off::USceneComponent::ComponentToWorld + off::FTransform::Rotation], q, sizeof(q));
    std::memcpy(&component[off::USceneComponent::ComponentToWorld + off::FTransform::Translation], translation,
                sizeof(translation));
    const auto address = reinterpret_cast<std::uintptr_t>(component.data());
    ue::FVector location{};
    Expect(ue::ReadWorldTransform(address, &location, &r) && location.x == -637.0f && location.y == 2119.0f &&
               location.z == 413.0f && Near(r.yaw, 45.0f),
           "world transform comes from ComponentToWorld, not RelativeLocation");
    Expect(ue::ReadWorldLocation(address, &location) && location.z == 413.0f, "world location alone");
    Expect(ue::AttachParentOf(address) == parent && ue::AttachParentOf(0) == 0, "attach parent");
    Expect(!ue::ReadWorldTransform(0, &location, &r), "null component refused");
}

bool NearV(const sifucraft::ue::FVector& v, float x, float y, float z) { return Near(v.x, x) && Near(v.y, y) && Near(v.z, z); }

void TestAffine() {
    using namespace sifucraft::ue;
    const Affine yaw90 = AffineFromRotator({0.0f, 90.0f, 0.0f}, {10.0f, 0.0f, 0.0f});
    Expect(NearV(yaw90.Apply({1, 0, 0}), 10, 1, 0), "yaw 90 turns +X into +Y, then translates");
    const Affine pitch90 = AffineFromRotator({90.0f, 0.0f, 0.0f}, {});
    Expect(NearV(pitch90.Apply({1, 0, 0}), 0, 0, 1), "pitch 90 turns +X up");
    float q[4];
    RotatorToQuat(20.0f, -70.0f, 5.0f, q);
    const Affine from_quat = AffineFromQuat(q, {1, 2, 3}, {2, 3, 4});
    const Affine from_rotator = AffineFromRotator({20.0f, -70.0f, 5.0f}, {1, 2, 3});
    const FVector p{1.5f, -2.0f, 0.25f};
    Expect(NearV(from_quat.Apply(p), from_rotator.Apply({3.0f, -6.0f, 1.0f}).x, from_rotator.Apply({3.0f, -6.0f, 1.0f}).y,
                 from_rotator.Apply({3.0f, -6.0f, 1.0f}).z),
           "FTransform scales before rotating");
    const Affine composed = AffineFromRotator({0, 90, 0}, {}) * AffineFromRotator({0, 0, 0}, {5, 0, 0});
    Expect(NearV(composed.Apply({0, 0, 0}), 0, 5, 0), "composition applies the right-hand side first");
    float m[4][4] = {{0, 1, 0, 0}, {-1, 0, 0, 0}, {0, 0, 1, 0}, {7, 8, 9, 1}};
    Expect(NearV(AffineFromRowMatrix(m).Apply({1, 0, 0}), 7, 9, 9), "FMatrix row vectors: p * M");
    Expect(Near(AffineFromQuat(q, {}, {2, 3, 4}).MinAxisScale(), 2.0f) &&
               Near(AffineFromQuat(q, {}, {2, 3, 4}).MaxAxisScale(), 4.0f),
           "axis scales");
}

void TestVoxels() {
    using namespace sifucraft::collision;
    using sifucraft::ue::FVector;
    Expect(FloorDiv(-1, 8) == -1 && FloorDiv(-8, 8) == -1 && FloorDiv(-9, 8) == -2 && FloorDiv(7, 8) == 0,
           "floor division rounds toward minus infinity");
    int bx, by, bz;
    VoxelGrid::Unkey(VoxelGrid::Key(-5, 300000, -1), &bx, &by, &bz);
    Expect(bx == -5 && by == 300000 && bz == -1, "block keys round-trip negative and large coordinates");

    VoxelGrid row;
    row.SetRow(-3, 9, -1, 2);
    Expect(row.CellCount() == 13 && row.Get(-3, -1, 2) && row.Get(9, -1, 2) && !row.Get(10, -1, 2) &&
               row.blocks().size() == 3,
           "rows cross block boundaries");

    const std::vector<FVector> cube = {{0, 0, 0}, {100, 0, 0}, {0, 100, 0}, {100, 100, 0},
                                       {0, 0, 100}, {100, 0, 100}, {0, 100, 100}, {100, 100, 100}};
    Expect(HullPlanes(cube).size() == 6, "a cube's hull has six faces");
    const std::vector<FVector> square = {{0, 0, 30}, {100, 0, 30}, {100, 100, 30}, {0, 100, 30}};
    Expect(HullPlanes(square).size() == 6, "a flat square: two faces plus four edge planes");

    const auto voxelize = [](const ShapeSet& shapes) {
        VoxelGrid grid;
        VoxelStats stats;
        Voxelize(shapes, &grid, &stats);
        return grid;
    };
    ShapeSet block;
    block.convexes.push_back({cube});
    const VoxelGrid block_grid = voxelize(block);
    Expect(block_grid.CellCount() == 512 && block_grid.blocks().size() == 1, "a 1 m cube fills exactly one block");

    ShapeSet slab;
    slab.convexes.push_back({{{0, 0, 40}, {100, 0, 40}, {0, 100, 40}, {100, 100, 40},
                              {0, 0, 42}, {100, 0, 42}, {0, 100, 42}, {100, 100, 42}}});
    const VoxelGrid slab_grid = voxelize(slab);
    Expect(slab_grid.CellCount() == 64 && slab_grid.Get(0, 0, 3) && slab_grid.Get(7, 7, 3),
           "a 2 cm slab is grown to one cell layer");

    ShapeSet flat;
    flat.convexes.push_back({square});
    Expect(voxelize(flat).CellCount() == 64, "a zero-thickness convex still makes a floor");

    ShapeSet floor;
    floor.triangles.push_back({{{0, 0, 30}, {100, 0, 30}, {100, 100, 30}}});
    floor.triangles.push_back({{{0, 0, 30}, {100, 100, 30}, {0, 100, 30}}});
    const VoxelGrid floor_grid = voxelize(floor);
    Expect(floor_grid.CellCount() == 64 && floor_grid.Get(3, 3, 2) && !floor_grid.Get(3, 3, 1),
           "a triangle floor marks one cell layer, ending at the boundary nearest the feet (z = 30 + 2.15 -> 37.5)");
    ShapeSet lower;
    lower.triangles.push_back({{{0, 0, 28}, {100, 0, 28}, {100, 100, 28}}});
    lower.triangles.push_back({{{0, 0, 28}, {100, 100, 28}, {0, 100, 28}}});
    const VoxelGrid lower_grid = voxelize(lower);
    Expect(lower_grid.CellCount() == 64 && Near(FloorUnder(lower_grid, 50, 50, 30.0f), 25.0f),
           "z = 28 (feet at 30.15) rounds down to 25");
    ShapeSet ceiling;  // facing down: no feet on it, nearest boundary to the surface itself
    ceiling.triangles.push_back({{{0, 0, 230}, {100, 100, 230}, {100, 0, 230}}});
    ceiling.triangles.push_back({{{0, 0, 230}, {0, 100, 230}, {100, 100, 230}}});
    const VoxelGrid ceiling_grid = voxelize(ceiling);
    Expect(ceiling_grid.CellCount() == 64 && ceiling_grid.Get(3, 3, 18) && !ceiling_grid.Get(3, 3, 17),
           "a ceiling at 230 starts at 225");
    VoxelGrid touched;
    VoxelStats touched_stats;
    VoxelOptions touch;
    touch.touch_triangles = true;
    Voxelize(floor, &touched, &touched_stats, touch);
    Expect(touched.CellCount() == 64 && touched.Get(3, 3, 2), "the touch rule still marks the cell holding the surface");

    ShapeSet wall;
    wall.triangles.push_back({{{0, 50, 0}, {100, 50, 0}, {100, 50, 100}}});
    wall.triangles.push_back({{{0, 50, 0}, {100, 50, 100}, {0, 50, 100}}});
    const VoxelGrid wall_grid = voxelize(wall);
    Expect(wall_grid.CellCount() == 64 && wall_grid.Get(0, 4, 0) && !wall_grid.Get(0, 3, 0),
           "a wall on a cell boundary marks only the layer behind it");

    ShapeSet snapped;
    snapped.triangles.push_back({{{0, 0, 300}, {100, 0, 300}, {100, 100, 300}}});
    snapped.triangles.push_back({{{0, 0, 300}, {100, 100, 300}, {0, 100, 300}}});
    const VoxelGrid snapped_grid = voxelize(snapped);
    Expect(snapped_grid.CellCount() == 64 && snapped_grid.Get(0, 0, 23) && !snapped_grid.Get(0, 0, 24),
           "a floor snapped to z = 300 has its solid top exactly at 300");
    Expect(Near(FloorUnder(snapped_grid, 50, 50, 302.0f), 300.0f) && std::isnan(FloorUnder(snapped_grid, 50, 50, 700.0f)) &&
               std::isnan(FloorUnder(snapped_grid, 150, 50, 302.0f)),
           "floor lookup: within reach under the feet only");

    ShapeSet ball;
    ball.capsules.push_back({{0, 0, 0}, {0, 0, 0}, 50.0f});
    const std::uint64_t ball_cells = voxelize(ball).CellCount();
    Expect(ball_cells > 230 && ball_cells < 310, "a 50 cm sphere fills about its volume in cells");

    // A 1 m cube turned 45 degrees: same volume, about 512 cells.
    ShapeSet turned;
    std::vector<FVector> corners;
    for (const FVector& v : cube) {
        const float x = v.x - 50, y = v.y - 50;
        corners.push_back({(x - y) * 0.70710678f + 500, (x + y) * 0.70710678f + 500, v.z});
    }
    turned.convexes.push_back({corners});
    const std::uint64_t turned_cells = voxelize(turned).CellCount();
    Expect(turned_cells > 470 && turned_cells < 560, "a rotated cube keeps its volume");

    // Solids thinner than a cell: a 3 cm slab (top 1050.5), a plane with both sides at z = 0.
    ShapeSet thin;
    const auto quad = [](ShapeSet* s, float z, bool up) {
        const FVector a{0, 0, z}, b{100, 0, z}, c{100, 100, z}, d{0, 100, z};
        s->triangles.push_back(up ? TriangleShape{{a, b, c}} : TriangleShape{{a, c, b}});
        s->triangles.push_back(up ? TriangleShape{{a, c, d}} : TriangleShape{{a, d, c}});
    };
    quad(&thin, 1050.5f, true);
    quad(&thin, 1047.5f, false);
    const VoxelGrid thin_grid = voxelize(thin);
    ShapeSet two_sided;
    quad(&two_sided, 0.0f, true);
    quad(&two_sided, 0.0f, false);
    const VoxelGrid two_sided_grid = voxelize(two_sided);
    Expect(thin_grid.CellCount() == 64 && Near(FloorUnder(thin_grid, 50, 50, 1052), 1050.0f) &&
               two_sided_grid.CellCount() == 64 && Near(FloorUnder(two_sided_grid, 50, 50, 2), 0.0f),
           "a slab thinner than a cell is one layer, rounded at its top");
    ShapeSet slab_mesh;  // 30 cm thick: both sides keep their cells
    quad(&slab_mesh, 330.0f, true);
    quad(&slab_mesh, 300.0f, false);
    const VoxelGrid slab_mesh_grid = voxelize(slab_mesh);
    Expect(slab_mesh_grid.CellCount() == 128 && slab_mesh_grid.Get(0, 0, 24) && slab_mesh_grid.Get(0, 0, 26),
           "a thicker slab keeps both of its sides");

    // A strip 3 cm wide between two rows of column centres: no centre line crosses it.
    ShapeSet sliver;
    sliver.triangles.push_back({{{0, 15, 50}, {100, 15, 50}, {100, 18, 50}}});
    VoxelGrid sliver_grid;
    VoxelStats sliver_stats;
    Voxelize(sliver, &sliver_grid, &sliver_stats);
    Expect(sliver_stats.slivers == 1 && sliver_grid.Get(4, 1, 3), "a sliver falls back to the cells it touches");

    // A wall at 45 degrees (solid on its -x side), 4 m long and 2 m high: one cell per (y, z)
    // column, neighbouring rows at most one cell apart, so nothing can pass between them.
    ShapeSet diagonal;
    diagonal.triangles.push_back({{{0, 0, 0}, {400, 400, 0}, {400, 400, 200}}});
    diagonal.triangles.push_back({{{0, 0, 0}, {400, 400, 200}, {0, 0, 200}}});
    const VoxelGrid diagonal_grid = voxelize(diagonal);
    bool one_per_column = true, connected = true;
    int previous = 0;
    for (int y = 0; y < 32; ++y) {
        int count = 0, at = 0;
        for (int x = -2; x < 34; ++x) {
            if (diagonal_grid.Get(x, y, 5)) {
                ++count;
                at = x;
            }
        }
        one_per_column = one_per_column && count == 1;
        if (y > 0 && std::abs(at - previous) > 1) connected = false;
        previous = at;
    }
    Expect(one_per_column && connected, "a diagonal wall is one connected layer of cells");

    // A 60 degree ramp (too steep: walkable Z 0.71) rising 1 m over 57.7 cm in x, 1 m wide.
    ShapeSet steep;
    const float run = 57.735f;
    steep.triangles.push_back({{{0, 0, 0}, {run, 0, 100}, {run, 100, 100}}});
    steep.triangles.push_back({{{0, 0, 0}, {run, 100, 100}, {0, 100, 0}}});
    VoxelGrid steep_grid;
    VoxelStats steep_stats;
    Voxelize(steep, &steep_grid, &steep_stats);
    VoxelGrid stairs_grid;
    VoxelStats stairs_stats;
    VoxelOptions no_steep;
    no_steep.steep_walls = false;
    Voxelize(steep, &stairs_grid, &stairs_stats, no_steep);
    // Column x = 1 (centre 18.75 cm): the ramp is 32.5 cm high there.
    Expect(steep_stats.steep_faces == 2 && steep_grid.Get(1, 4, 7) && Near(FloorUnder(steep_grid, 18.75f, 50, 100), 100.0f),
           "a face too steep to walk on becomes a wall up to its top");
    Expect(!stairs_grid.Get(1, 4, 7) && Near(FloorUnder(stairs_grid, 18.75f, 50, 40), 37.5f),
           "without steep walls it is a staircase of cells");
    // 4 m high: walls up to each 1.5 m terrace level, so it can still be walked down.
    ShapeSet cliff;
    cliff.triangles.push_back({{{0, 0, 0}, {4 * run, 0, 400}, {4 * run, 100, 400}}});
    cliff.triangles.push_back({{{0, 0, 0}, {4 * run, 100, 400}, {0, 100, 0}}});
    const VoxelGrid cliff_grid = voxelize(cliff);
    // Columns at x = 56.25 (surface 97 cm), 118.75 (206 cm), 206.25 (357 cm).
    Expect(Near(FloorUnder(cliff_grid, 56.25f, 50, 150), 150.0f) && Near(FloorUnder(cliff_grid, 118.75f, 50, 300), 300.0f) &&
               Near(FloorUnder(cliff_grid, 206.25f, 50, 400), 400.0f),
           "a tall steep face becomes terraces 1.5 m apart, up to its top");
    ShapeSet walkable_ramp = steep;
    for (TriangleShape& t : walkable_ramp.triangles) t.walkable_z = 0.4f;  // a slope override: walkable to 66 deg
    VoxelGrid walkable_grid;
    VoxelStats walkable_stats;
    Voxelize(walkable_ramp, &walkable_grid, &walkable_stats);
    Expect(walkable_stats.steep_faces == 0 && !walkable_grid.Get(1, 4, 7), "a walkable slope stays a slope");
    VoxelGrid wall_stats_grid;
    VoxelStats wall_stats;
    Voxelize(wall, &wall_stats_grid, &wall_stats);
    Expect(wall_stats.steep_faces == 0, "vertical faces are not steep floors");
    // A box tilted 30 degrees about y: its top is a walkable 30 degree slope, one long side an
    // up-facing 60 degree face (raised), the other an overhang.
    ShapeSet leaning;
    std::vector<FVector> box;
    for (int corner = 0; corner < 8; ++corner) {
        const float lx = corner & 1 ? 20.0f : -20.0f, ly = corner & 2 ? 50.0f : -50.0f, lz = corner & 4 ? 100.0f : -100.0f;
        // Rotated 30 degrees about y (x' = x cos + z sin, z' = -x sin + z cos), lifted to stand on z = 0.
        box.push_back({lx * 0.8660254f + lz * 0.5f + 300, ly + 300, -lx * 0.5f + lz * 0.8660254f + 96.6f});
    }
    leaning.convexes.push_back({box});
    VoxelGrid leaning_grid;
    VoxelStats leaning_stats;
    Voxelize(leaning, &leaning_grid, &leaning_stats);
    Expect(leaning_stats.steep_faces == 1, "a leaning box's up-facing steep side is raised");

    const FVector c{6.25f, 6.25f, 6.25f};
    Expect(TriangleOverlapsBox(c, 6.25f, {-10, -10, 6}, {30, -10, 6}, {-10, 30, 6}) &&
               !TriangleOverlapsBox(c, 6.25f, {-10, -10, 20}, {30, -10, 20}, {-10, 30, 20}) &&
               !TriangleOverlapsBox(c, 6.25f, {20, 20, 0}, {40, 20, 0}, {20, 40, 12}),
           "triangle / box overlap");
}

void TestCollisionFiles() {
    using namespace sifucraft::collision;
    wchar_t directory[MAX_PATH];
    GetTempPathW(MAX_PATH, directory);
    const std::wstring shapes_path = std::wstring(directory) + L"sifucraft_test.shapes";
    ShapeSet shapes;
    shapes.pawn_walkable_z = 0.65f;
    shapes.convexes.push_back({{{0, 0, 0}, {1, 0, 0}, {0, 1, 0}, {0, 0, 1}}, 0.5f});
    shapes.capsules.push_back({{1, 2, 3}, {4, 5, 6}, 7.0f});
    shapes.triangles.push_back({{{0, 0, 30}, {100, 0, 30}, {100, 100, 30}}, 2.0f});
    ShapeSet back;
    std::string map;
    Expect(WriteShapeFile(shapes_path, shapes, "TestMap") && ReadShapeFile(shapes_path, &back, &map) &&
               map == "TestMap" && back.pawn_walkable_z == 0.65f && back.convexes.size() == 1 &&
               back.convexes[0].vertices.size() == 4 && back.convexes[0].walkable_z == 0.5f &&
               back.capsules.size() == 1 && back.capsules[0].b.y == 5.0f && back.capsules[0].radius == 7.0f &&
               back.triangles.size() == 1 && back.triangles[0].v[2].y == 100.0f && back.triangles[0].walkable_z == 2.0f,
           "shape files round-trip");
    DeleteFileW(shapes_path.c_str());

    ShapeSet floor;
    floor.triangles.push_back({{{0, 0, 300}, {100, 0, 300}, {100, 100, 300}}});
    floor.triangles.push_back({{{0, 0, 300}, {100, 100, 300}, {0, 100, 300}}});
    VoxelGrid grid;
    VoxelStats stats;
    Voxelize(floor, &grid, &stats);
    WalkCheck check;
    const bool first = check.Sample(grid, {{50, 50, 302}, 42, 90});
    const bool still = check.Sample(grid, {{50.2f, 50, 302}, 42, 90});
    const bool moved = check.Sample(grid, {{60, 50, 302}, 42, 90});
    Expect(first && !still && moved && check.samples() == 2 && check.scored().size() == 2,
           "the walk check skips a pawn standing still");
    const std::vector<std::string> report = check.Report("TestMap");
    Expect(!report.empty() && report[0].find("mean -2.0 cm") != std::string::npos &&
               report[0].find("overlapping solid: 0") != std::string::npos,
           "walk check: 2 cm above a floor at 300");

    const std::wstring csv = std::wstring(directory) + L"sifucraft_test_walk.csv";
    DeleteFileW(csv.c_str());
    std::vector<WalkSample> read;
    Expect(AppendWalkSamples(csv, check.scored()) && AppendWalkSamples(csv, check.scored()) &&
               ReadWalkSamples(csv, &read) && read.size() == 4 && read[1].feet.x == 60.0f &&
               read[3].capsule_half_height == 90.0f,
           "walk samples append to one CSV and read back");
    DeleteFileW(csv.c_str());
}

void TestCombatSamples() {
    using namespace sifucraft;
    namespace off = gen::off;
    std::vector<std::uint8_t> component(off::UCharacterHealthComponent::m_eDownState + 1);
    const auto put = [](auto& buffer, std::uint32_t offset, auto value) {
        std::memcpy(buffer.data() + offset, &value, sizeof(value));
    };
    put(component, off::UHealthComponent::m_fHealth, 180.0f);
    put(component, off::UHealthComponent::m_fMaxHealth, 120.0f);
    for (int d = 0; d < 3; ++d) {
        put(component, off::UCharacterHealthComponent::m_fMaxHealthMultiplier + d * sizeof(float), 1.0f + d * 0.5f);
    }
    component[off::UHealthComponent::m_bCanBeDamaged] = 1;
    component[off::UHealthComponent::m_bCanDieByDamage] = 1;
    const auto address = reinterpret_cast<std::uintptr_t>(component.data());
    const auto before = component;
    probe::HealthSample health;
    Expect(probe::ReadHealthSample(address, &health) && health.health == 180.0f && health.can_die &&
               probe::EffectiveMaxHealth(health, 1) == 180.0f && probe::EffectiveMaxHealth(health, 2) == 240.0f,
           "combat sample reads PDB health layout and applies the selected difficulty multiplier");
    Expect(std::isnan(probe::EffectiveMaxHealth(health, -1)) && probe::EffectiveMaxHealth(health, 3) == 120.0f,
           "combat sample keeps unknown difficulty unknown and matches the game's out-of-range fallback");
    Expect(component == before, "combat sampling never changes source memory");
    put(component, off::UHealthComponent::m_fHealth, NAN);
    Expect(!probe::ReadHealthSample(address, &health), "combat sample rejects non-finite health");
    put(component, off::UHealthComponent::m_fHealth, 180.0f);
    put(component, off::UCharacterHealthComponent::m_fMaxHealthMultiplier, 0.0f);
    Expect(!probe::ReadHealthSample(address, &health), "combat sample rejects uninitialized health multipliers");
    Expect(!probe::ReadHealthSample(0, &health) && !probe::ReadHealthSample(0x20, &health),
           "combat sample refuses absent and unreadable health components");

    std::vector<std::uint8_t> description(gen::size::FHitDescription);
    const auto hit_address = reinterpret_cast<std::uintptr_t>(description.data());
    const auto box = off::FHitDescription::m_Request + off::FHitRequest::m_HitBox;
    const auto row = box + off::FHitBox::m_CachedRow;
    const auto impact = row + off::FHitboxDataRow::m_OnHitImpact;
    const auto result = off::FHitDescription::m_ImpactResult;
    probe::HitSample hit;
    Expect(!probe::ReadHitSample(hit_address, &hit), "combat sample ignores an empty last-hit description");
    description[result + off::FImpactResult::m_bIsValid] = 1;
    put(description, result + off::FImpactResult::m_fDamage, 18.0f);
    put(description, impact + off::FHitImpact::m_fDamage, 12.0f);
    description[impact + off::FHitImpact::m_bUseDamage] = 1;
    Expect(!probe::ReadHitSample(hit_address, &hit), "combat sample refuses hitbox damage before its row is cached");
    description[box + off::FHitBox::m_bCachedRowSetup] = 1;
    Expect(probe::ReadHitSample(hit_address, &hit) && hit.use_damage && hit.base_damage == 12 && hit.final_damage == 18,
           "combat sample distinguishes cached base damage from Sifu's resolved damage");
    description[impact + off::FHitImpact::m_bUseDamage] = 0;
    put(description, impact + off::FHitImpact::m_fDamage, NAN);
    Expect(probe::ReadHitSample(hit_address, &hit) && !hit.use_damage && std::isnan(hit.base_damage),
           "combat sample flags default damage instead of trusting an unused cached value");
    description[impact + off::FHitImpact::m_bUseDamage] = 1;
    Expect(!probe::ReadHitSample(hit_address, &hit), "combat sample refuses a non-finite explicit damage value");
    put(description, impact + off::FHitImpact::m_fDamage, 12.0f);
    put(description, result + off::FImpactResult::m_fDamage, -1.0f);
    Expect(!probe::ReadHitSample(hit_address, &hit), "combat sample refuses negative resolved damage");
    Expect(!probe::ReadHitSample(0, &hit) && !probe::ReadHitSample(0x20, &hit),
           "combat sample refuses absent and unreadable hit descriptions");
}

void TestEnemyIdentity() {
    sifucraft::combat::ActivationTracker tracker;
    Expect(tracker.Observe(10, 0, true) == 0 && tracker.Observe(10, 1, false) == 0,
           "enemy identity refuses uninitialized serials and inactive pooled actors");
    const auto first = tracker.Observe(10, 1, true);
    Expect(first != 0 && tracker.Observe(10, 1, true) == first,
           "an active enemy keeps its identity across samples and range changes");
    tracker.Observe(10, 1, false);
    const auto second = tracker.Observe(10, 1, true);
    Expect(second != 0 && second != first, "reactivating a pooled actor changes its spawn identity");
    const auto replaced = tracker.Observe(10, 2, true);
    Expect(replaced != 0 && replaced != second, "a reused UObject internal index changes its spawn identity");
    tracker.Forget(10);
    const auto returned = tracker.Observe(10, 2, true);
    Expect(returned != replaced, "a removed and rediscovered object cannot reuse an expired activation");
    tracker.Reset();
    Expect(tracker.Observe(10, 2, true) != returned, "world reset does not reuse an old activation identity");
}

void TestLiveHitSamples() {
    using namespace sifucraft;
    namespace off = gen::off;
    std::vector<std::uint8_t> request(gen::size::FHitRequest, 0);
    const auto address = reinterpret_cast<std::uintptr_t>(request.data());
    const auto box = address + off::FHitRequest::m_HitBox;
    const auto impact = box + off::FHitBox::m_CachedRow + off::FHitboxDataRow::m_OnHitImpact;
    const auto put = [](std::uintptr_t at, const auto& value) { std::memcpy(reinterpret_cast<void*>(at), &value, sizeof(value)); };
    combat::HitRequestSample hit;
    put(impact + off::FHitImpact::m_bUseDamage, std::uint8_t{1});
    put(impact + off::FHitImpact::m_fDamage, 30.0f);
    Expect(!combat::ReadCachedHitRequest(address, &hit), "live hit parser refuses an uninitialized inline row cache");
    put(box + off::FHitBox::m_bCachedRowSetup, std::uint8_t{1});
    float damage = NAN;
    Expect(combat::ReadCachedHitRequest(address, &hit) && combat::ResolveHitDamage(hit, NAN, &damage) && damage == 30,
           "live hit parser reads explicit request damage without needing a default");
    put(impact + off::FHitImpact::m_bUseDamage, std::uint8_t{0});
    put(impact + off::FHitImpact::m_fDamage, float{NAN});
    Expect(combat::ReadCachedHitRequest(address, &hit) && !combat::ResolveHitDamage(hit, NAN, &damage) &&
           combat::ResolveHitDamage(hit, 15, &damage) && damage == 15,
           "default hit damage never treats an unused raw field as zero or as the effective damage");
    Expect(combat::ResolveHitDamage(hit, 0, &damage) && damage == 0,
           "a verified zero default is distinct from an unresolved default");
    put(impact + off::FHitImpact::m_bUseDamage, std::uint8_t{1});
    Expect(!combat::ReadCachedHitRequest(address, &hit), "live hit parser refuses non-finite explicit damage");
    put(impact + off::FHitImpact::m_fDamage, 15.0f);
    put(box + off::FHitBox::m_bCachedRowSetup, std::uint8_t{0});
    Expect(combat::ReadHitRequest(address, box + off::FHitBox::m_CachedRow, &hit) && hit.cached == 0 && hit.raw_damage == 15,
           "live hit parser accepts a separately resolved data-table row for an uncached request");

    std::vector<std::uint8_t> attack(off::FComboAttack::m_fAnimFrameRate + 4), animation(off::UAnimSequenceBase::Notifies + 16);
    std::vector<std::uint8_t> curve(off::UCurveFloat::FloatCurve + off::FRichCurve::Keys + 16);
    std::vector<std::uint8_t> events(gen::size::FAnimNotifyEvent), keys(3 * gen::size::FRichCurveKey);
    const auto aa = reinterpret_cast<std::uintptr_t>(attack.data()), an = reinterpret_cast<std::uintptr_t>(animation.data());
    const auto ca = reinterpret_cast<std::uintptr_t>(curve.data()), ea = reinterpret_cast<std::uintptr_t>(events.data());
    const auto ka = reinterpret_cast<std::uintptr_t>(keys.data());
    put(aa + off::FComboAttack::m_fAnimFrameRate, 60.0f);
    put(ca + off::UCurveFloat::FloatCurve + off::FRichCurve::Keys, mem::RawArray{ka, 3, 3});
    put(ka + off::FRichCurveKey::Time, 0.0f);
    put(ka + gen::size::FRichCurveKey + off::FRichCurveKey::Time, 0.8f);
    put(ka + 2 * gen::size::FRichCurveKey + off::FRichCurveKey::Time, 1.5f);
    const auto range = box + off::FHitBox::m_FrameRange + off::FFrameRange::m_Anim;
    const auto set_range = [&](float start, float end) {
        put(range + off::FFloatRange::LowerBound + off::FloatRangeBound::Type, std::uint8_t{gen::value::ERangeBoundTypes::Type::Inclusive});
        put(range + off::FFloatRange::UpperBound + off::FloatRangeBound::Type, std::uint8_t{gen::value::ERangeBoundTypes::Type::Inclusive});
        put(range + off::FFloatRange::LowerBound + off::FloatRangeBound::Value, start);
        put(range + off::FFloatRange::UpperBound + off::FloatRangeBound::Value, end);
    };
    set_range(30, 33); // Live Grunt_Blunt_DownSmash hit window.
    const auto resolve = [ca](std::uintptr_t notify) { return notify == 42 ? ca : 0; };
    Expect(!combat::AttackHasGlow(address, aa, an, resolve), "ordinary weapon Strong has no glow and never disables the shield");
    put(an + off::UAnimSequenceBase::Notifies, mem::RawArray{ea, 1, 1});
    put(ea + off::FAnimNotifyEvent::Notify, std::uintptr_t{42});
    put(ea + off::FAnimLinkableElement::LinkMethod, std::uint8_t{gen::value::EAnimLinkMethod::Type::Absolute});
    put(ea + off::FAnimLinkableElement::LinkValue, 0.0192489065f);
    Expect(combat::AttackHasGlow(address, aa, an, resolve), "tagless weapon DownSmash is classified by its real orange glow notify and curve lifetime");
    put(ea + off::FAnimNotifyEvent::Notify, std::uintptr_t{43});
    Expect(!combat::AttackHasGlow(address, aa, an, resolve), "unrelated animation effects cannot enable shield disabling");
    put(ea + off::FAnimNotifyEvent::Notify, std::uintptr_t{42});
    put(ea + off::FAnimLinkableElement::LinkValue, 0.8f);
    set_range(10, 12);
    Expect(!combat::AttackHasGlow(address, aa, an, resolve), "an earlier ordinary strike is not classified by a later glow in the same animation");
    set_range(54, 58);
    Expect(combat::AttackHasGlow(address, aa, an, resolve), "the later glowing strike is classified within its own hit window");
    set_range(180, 184);
    Expect(!combat::AttackHasGlow(address, aa, an, resolve), "an expired glow does not classify a later ordinary strike");
    set_range(30, 33);
    put(ea + off::FAnimLinkableElement::LinkValue, float{NAN});
    Expect(!combat::AttackHasGlow(address, aa, an, resolve), "invalid glow timing cannot enable shield disabling");
    put(ka + 2 * gen::size::FRichCurveKey + off::FRichCurveKey::Time, -1.0f);
    Expect(!std::isfinite(combat::GlowDuration(ca)), "malformed curve timing is refused");

    std::vector<std::uint8_t> defense(off::UDefenseComponent::m_GuardDB + sizeof(mem::RawArray), 0);
    std::vector<std::uint8_t> nodes(2 * gen::size::GuardStackValue, 0);
    const auto defense_at = reinterpret_cast<std::uintptr_t>(defense.data());
    const auto nodes_at = reinterpret_cast<std::uintptr_t>(nodes.data());
    Expect(combat::StaticGuardValue(defense_at) == 0, "empty GuardDB stack remains unresolved");
    put(defense_at + off::UDefenseComponent::m_GuardDB, mem::RawArray{nodes_at, 2, 2});
    const auto top = nodes_at + gen::size::GuardStackValue;
    Expect(combat::StaticGuardValue(defense_at) == top + off::GuardStackValue::m_Value,
           "GuardDB reader chooses the current override, rather than the default or first entry");
    put(top + off::GuardStackValue::m_ValueGetter + off::GuardValueGetter::Callable, std::uintptr_t{1});
    Expect(combat::StaticGuardValue(defense_at) == 0, "dynamic GuardDB getter is not invoked or guessed from stale cached data");
}

void TestDataTableRows() {
    using namespace sifucraft;
    namespace off = gen::off;
    std::vector<std::uint8_t> table(off::UDataTable::RowMap + 128, 0);
    std::vector<std::uint8_t> slots(130 * gen::size::DataTableElement, 0);
    std::uint32_t heap_bits[5] = {};
    const auto at = reinterpret_cast<std::uintptr_t>(table.data());
    const auto data = reinterpret_cast<std::uintptr_t>(slots.data());
    const auto sparse = at + off::UDataTable::RowMap + off::DataTableMap::Pairs + off::DataTableSet::Elements;
    const auto bits = sparse + off::DataTableSparse::AllocationFlags;
    const auto allocator = bits + off::DataTableBits::AllocatorInstance;
    const auto put = [](std::uintptr_t address, const auto& value) { std::memcpy(reinterpret_cast<void*>(address), &value, sizeof(value)); };
    const auto entry = [&](int i, ue::FName name, std::uintptr_t row) {
        const auto pair = data + i * gen::size::DataTableElement + off::DataTableElement::Value;
        put(pair + off::DataTablePair::Key, name); put(pair + off::DataTablePair::Value, row);
    };
    put(sparse + off::DataTableSparse::Data, mem::RawArray{data, 2, 130});
    put(bits + off::DataTableBits::NumBits, 2); put(bits + off::DataTableBits::MaxBits, 128);
    entry(0, {42, 0}, 123); entry(1, {42, 1}, 456);
    put(allocator + off::DataTableBitAllocator::InlineData, std::uint32_t{2});
    Expect(ue::DataTableRow(at, {42, 0}) == 0 && ue::DataTableRow(at, {42, 1}) == 456,
           "row-map lookup skips free sparse entries and compares both FName fields");
    put(sparse + off::DataTableSparse::Data, mem::RawArray{data, 130, 130});
    put(bits + off::DataTableBits::NumBits, 130); put(bits + off::DataTableBits::MaxBits, 160);
    put(allocator + off::DataTableBitAllocator::SecondaryData, reinterpret_cast<std::uintptr_t>(heap_bits));
    heap_bits[4] = 2; entry(129, {99, 0}, 789);
    Expect(ue::DataTableRow(at, {99, 0}) == 789, "row-map lookup reads heap allocation flags beyond the inline capacity");
    put(bits + off::DataTableBits::NumBits, 129);
    Expect(ue::DataTableRow(at, {99, 0}) == 0, "row-map lookup refuses inconsistent sparse array and bit counts");
}

void TestLanguage() {
    using namespace sifucraft;
    namespace off = gen::off;
    std::vector<std::uint8_t> store(gen::size::InternationalizationStore, 0);
    std::vector<std::uint8_t> culture(gen::size::FCulture, 0);
    wchar_t name[] = L"zh-Hans";
    const auto at = reinterpret_cast<std::uintptr_t>(store.data());
    const auto base = at - gen::rva::InternationalizationStore;
    const auto instance = at + off::InternationalizationStore::Data;
    const auto culture_at = reinterpret_cast<std::uintptr_t>(culture.data());
    const auto put = [](std::uintptr_t address, const auto& value) { std::memcpy(reinterpret_cast<void*>(address), &value, sizeof(value)); };
    put(at + off::InternationalizationStore::Ptr, instance);
    put(instance + off::FInternationalization::CurrentLanguage, culture_at);
    const auto cached = culture_at + off::FCulture::CachedName;
    put(cached, mem::RawArray{reinterpret_cast<std::uintptr_t>(name), 8, 8});
    Expect(ue::ReadLanguage(base).empty(), "language refuses an uninitialized internationalization singleton");
    put(instance + off::FInternationalization::bIsInitialized, std::uint8_t{1});
    Expect(ue::ReadLanguage(base) == "zh-Hans", "language reads the current UI culture through generated offsets");
    name[2] = L' ';
    Expect(ue::ReadLanguage(base).empty(), "language refuses malformed culture text");
    name[2] = L'-'; name[7] = L'x';
    Expect(ue::ReadLanguage(base).empty(), "language requires a terminated culture name");
    name[7] = 0;
    put(cached, mem::RawArray{0x20, 8, 8});
    Expect(ue::ReadLanguage(base).empty(), "language refuses an unreadable culture string");
    put(cached, mem::RawArray{reinterpret_cast<std::uintptr_t>(name), 21, 21});
    Expect(ue::ReadLanguage(base).empty(), "language bounds the culture string before copying it");
    put(at + off::InternationalizationStore::Ptr, instance + 8);
    Expect(ue::ReadLanguage(base).empty(), "language validates the singleton's inline-storage pointer");
}

void TestShieldTimingAndLedger() {
    using namespace sifucraft;
    namespace off = gen::off;
    std::vector<std::uint8_t> db(off::UGuardDB::m_ParryWindow + 16, 0);
    const auto at = reinterpret_cast<std::uintptr_t>(db.data());
    struct Bound { std::uint8_t type, padding[3]; float value; };
    Bound range[2]{{1, {}, 0}, {1, {}, 10}};
    std::memcpy(db.data() + off::UGuardDB::m_ParryWindow, range, sizeof(range));
    Expect(combat::InParryWindow(at, 0) && combat::InParryWindow(at, 0.1) && !combat::InParryWindow(at, 0.18),
           "shield timing uses Sifu's 0..10 logic-frame range, independently of render rate");
    Expect(!combat::InParryWindow(at, NAN) && !combat::InParryWindow(at, -0.01) && !combat::InParryWindow(at, 1),
           "shield timing refuses missing clocks and prolonged holding");
    range[0].type = 0;
    std::memcpy(db.data() + off::UGuardDB::m_ParryWindow, range, sizeof(range));
    Expect(!combat::InParryWindow(at, 0) && combat::InParryWindow(at, 0.001), "shield timing respects exclusive range bounds");
    range[1].type = 2;
    std::memcpy(db.data() + off::UGuardDB::m_ParryWindow, range, sizeof(range));
    Expect(!combat::InParryWindow(at, 0.1), "shield timing refuses unbounded or unknown window metadata");

    input::InputRouter router;
    router.SetOwner(protocol::InputOwner::Minecraft);
    router.SetUseBinding(0x10003);
    const input::MappedKey right{input::KeyKind::MouseButton, 3};
    router.OnKey(right, "RightMouseButton", 1, 10);
    Expect(std::abs(router.UseAge(10.1) - 0.1) < 0.0001, "shield press is timestamped on Sifu's input/game clock");
    router.OnKey(right, "RightMouseButton", 1, 10.2);
    Expect(std::abs(router.UseAge(10.3) - 0.3) < 0.0001, "repeated press notifications cannot renew perfect timing");
    router.OnKey(right, "RightMouseButton", 0, 10.3);
    Expect(!std::isfinite(router.UseAge(10.4)), "releasing use closes the timing window");
    router.OnKey(right, "RightMouseButton", 1, 11);
    router.SetGuiState(true, true, 0);
    Expect(!std::isfinite(router.UseAge(11.05)), "inventory input cannot count as a shield raise");
    router.SetOwner(protocol::InputOwner::Sifu);
    Expect(!std::isfinite(router.UseAge(11.1)), "ownership loss invalidates shield timing");

    combat::ShieldLedger ledger;
    protocol::PlayerHitPayload hit{42, 3, 54, protocol::kHitCanDefend | protocol::kHitParriable | protocol::kHitPerfectWindow, 7, 9, 1, 0};
    protocol::PlayerBlockedPayload reply{100, 42, 3, 7, 9, 6, protocol::kBlockedPerfect};
    Expect(!ledger.Consume(55, reply, 1100), "shield feedback must reference an actually published native hit");
    ledger.Record(100, 55, hit, 1000);
    auto bad = reply; ++bad.spawn;
    Expect(!ledger.Consume(55, bad, 1100) && !ledger.Consume(56, reply, 1100), "shield receipt rejects another activation or peer");
    bad = reply; ++bad.world_generation;
    Expect(!ledger.Consume(55, bad, 1100), "shield receipt rejects stale world scope");
    bad = reply; bad.blocked_damage = NAN;
    Expect(!ledger.Consume(55, bad, 1100), "shield receipt rejects nonfinite blocked damage");
    Expect(ledger.Consume(55, reply, 1100) && !ledger.Consume(55, reply, 1101), "one native hit can produce at most one shield feedback even under a new reply ID");
    ledger.Record(101, 55, hit, 1000); reply.origin_hit_id = 101;
    Expect(!ledger.Consume(55, reply, 3001), "shield receipt cannot revive an expired original hit");
    ledger.Reset();
    Expect(!ledger.Consume(55, reply, 1100), "scope invalidation clears previous shield credentials");
    hit.flags &= ~protocol::kHitPerfectWindow;
    ledger.Record(102, 55, hit, 1000); reply.origin_hit_id = 102;
    Expect(!ledger.Consume(55, reply, 1100), "ordinary timing cannot be upgraded to perfect by a client receipt");
    reply.flags = 0;
    Expect(ledger.Consume(55, reply, 1100), "ordinary blocks can be acknowledged without scheduling a native impact");
}

void TestMaceImpulses() {
    using namespace sifucraft;
    combat::ImpulseLedger ledger;
    protocol::EnemyDamagePayload hit{101, 1, 21, .15f, 0, 3, 7, 0};
    protocol::EnemyImpulsePayload push{100, 102, 1, 3, 7, 1, -2};
    Expect(!ledger.Consume(10, push, {}, 200), "range impulse needs an applied origin");
    ledger.Record(100, 10, hit, {}, 100);
    Expect(!ledger.Consume(10, push, {}, 200), "ordinary damage cannot authorize range motion");
    hit.flags = protocol::kDamageSmash; ledger.Record(100, 10, hit, {}, 100);
    Expect(!ledger.Consume(11, push, {}, 200), "range impulse rejects another session");
    auto bad = push; bad.world_generation++;
    Expect(!ledger.Consume(10, bad, {}, 200), "range impulse rejects another world");
    bad = push; bad.anchor_epoch++;
    Expect(!ledger.Consume(10, bad, {}, 200), "range impulse rejects another anchor epoch");
    bad = push; bad.mc_x = NAN;
    Expect(!ledger.Consume(10, bad, {}, 200), "range impulse rejects NaN");
    bad = push; bad.mc_z = 17;
    Expect(!ledger.Consume(10, bad, {}, 200), "range impulse rejects unbounded motion");
    Expect(!ledger.Consume(10, push, {}, 99) && !ledger.Consume(10, push, {}, 2101), "origin has a bounded forward-time lifetime");
    Expect(!ledger.Consume(10, push, {401, 0, 0}, 200), "range impulse rejects distant targets");
    combat::ImpulseLedger::Origin origin{};
    Expect(ledger.Consume(10, push, {400, 0, 0}, 200, &origin) && origin.created == 100, "finite range boundary consumes the original credential");
    Expect(!ledger.Consume(10, push, {}, 200), "a new transport ID cannot replay the same origin-target pair");
    push.enemy_id++;
    Expect(ledger.Consume(10, push, {}, 200), "one smash can push several distinct targets once");
    ledger.Reset(); push.enemy_id++;
    Expect(!ledger.Consume(10, push, {}, 200), "ownership, map and stage reset discard range credentials");
    for (unsigned id = 1; id <= 300; ++id) ledger.Record(id, 10, hit, {}, 100);
    push.origin_damage_id = 1;
    Expect(!ledger.Consume(10, push, {}, 200), "bounded credential eviction cannot resurrect old motion");
    protocol::Vec3 velocity{}; push.origin_damage_id = 300;
    Expect(combat::ImpulseVelocity(push, {100, 0, 300}, &velocity) &&
           std::abs(std::hypot(velocity.x, velocity.y) - 600) < .01 && velocity.z == 0,
           "horizontal units map to Sifu with a 600cm/s vector cap and no added Z");
    Expect(!combat::ImpulseVelocity(push, {NAN, 0, 0}, &velocity), "non-finite live velocity never reaches a game call");
    combat::LaunchParams shape{16, 0, 12, 13, 1, 1};
    std::uint8_t buffer[18]; std::memset(buffer, 0xAA, sizeof(buffer));
    Expect(shape.Encode(std::span(buffer + 1, 16), {123, -456, 0}) && buffer[0] == 0xAA && buffer[17] == 0xAA &&
           buffer[13] == 1 && buffer[14] == 0, "reflected launch params preserve guards and set XY true / Z false");
    shape.z = 12; shape.xy_mask = 2; shape.z_mask = 4;
    Expect(shape.Encode(std::span(buffer + 1, 16), {}) && buffer[13] == 2, "packed bool parameters honor distinct reflected masks");
    shape.z_mask = 2;
    Expect(!shape.valid(), "overlapping reflected bool masks reject the call");
    shape.z = 4;
    Expect(!shape.valid(), "reflected bools cannot overwrite the velocity vector");
}
void TestScriptHandoverIntent() {
    using namespace sifucraft;
    input::OwnershipIntent intent;
    using protocol::InputOwner;
    Expect(intent.Desired(false) == InputOwner::Minecraft, "safe startup requests Minecraft");
    Expect(intent.Desired(true) == InputOwner::Sifu, "scripts and menus keep the real player");
    intent.RequestSwitch(InputOwner::Minecraft, true); // cutscene entered before the router sampled it
    Expect(intent.enabled() && intent.Desired(true) == InputOwner::Sifu,
           "F8 on the first cinematic frame does not disable Minecraft or bypass the cinematic");
    for (int i = 0; i < 5; ++i) intent.RequestSwitch(InputOwner::Sifu, true);
    Expect(intent.Desired(true) == InputOwner::Sifu && intent.Desired(false) == InputOwner::Minecraft,
           "repeated F8 during a blocked handover resumes Minecraft when the guard clears");
    intent.RequestSwitch(InputOwner::Minecraft, false);
    Expect(!intent.enabled() && intent.Desired(false) == InputOwner::Sifu, "F8 under actual MC control yields manually");
    Expect(intent.Desired(true) == InputOwner::Sifu && intent.Desired(false) == InputOwner::Sifu,
           "a manual yield is not undone just because a later cutscene ends");
    intent.RequestSwitch(InputOwner::Sifu, false);
    Expect(intent.Desired(false) == InputOwner::Minecraft, "F8 requests MC again after a manual yield");
}
void TestDoorViewHandover() {
    using namespace sifucraft;
    using input::DisplacementGuard;
    using Change = DisplacementGuard::Change;
    const interaction::MotionScope scope{10, 2, 3, 100, 5, 200, 6};
    interaction::DoorMotionWindow door;
    Expect(interaction::IsDoorClass("BP_2WaysInterractiveLockableDoor_C") &&
           interaction::IsDoorClass("BP_Door_C") && !interaction::IsDoorClass("BP_OutdoorLamp_C") &&
           !interaction::IsDoorClass("BP_Pickup_C"), "only door classes explain door animation motion");
    Expect(!door.Observe(scope, 100, 1.1f), "no door motion exception without a dispatched use");
    door.Arm(scope, 100, true);
    DisplacementGuard guard;
    guard.Reset({}, 100);
    std::uint32_t consecutive = 0;
    // Replay the 1.0-1.2 cm per-frame corrections observed before the real handover bug.
    for (std::uint64_t now = 104; now < 4100; now += 4) {
        const bool keep = door.Observe(scope, now, 1.1f);
        consecutive = keep ? 0 : consecutive + 1;
        guard.Update(true, true, true, {}, consecutive, now);
    }
    Expect(!guard.driving() && consecutive == 0, "own door root motion keeps MC pose and camera throughout the animation");
    door.UpdateScope(scope, true); // MC inventory can stay open; ownership remains MC.
    Expect(door.Observe(scope, 9999, 1.2f), "same-scope GUI does not cancel the door window");
    Expect(!door.Observe(scope, 10100, 1.2f), "door exception expires at ten seconds without sliding extension");
    Expect(guard.Update(true, true, true, {}, 3, 10104) == Change::Yield,
           "ordinary displacement guard resumes after the bounded window");
    Expect(guard.Update(false, false, true, {2, 0, 0}, 0, 11104) == Change::None && guard.driving(),
           "continued native movement reproduces the old automatic recovery wait");
    Expect(guard.ManualRecovery(11105) && !guard.driving(), "F8 immediately clears a displacement-only wait");
    input::OwnershipIntent intent;
    intent.RequestSwitch(protocol::InputOwner::Sifu, true);
    Expect(intent.Desired(false) == protocol::InputOwner::Minecraft && intent.Desired(true) == protocol::InputOwner::Sifu,
           "F8 recovery requests MC without bypassing real cinematic, pause or input guards");
    Expect(guard.Update(true, true, true, {}, 0, 11106) == Change::None, "no stale displacement latch after F8");
    Expect(guard.Update(true, true, true, {}, 3, 12000) == Change::Yield &&
           guard.Update(false, false, true, {}, 0, 14000) == Change::Recovered,
           "non-door script motion still yields and automatically recovers when quiet");
    for (int field = 0; field < 6; ++field) {
        auto changed = scope;
        switch (field) {
            case 0: ++changed.session; break; case 1: ++changed.world; break; case 2: ++changed.epoch; break;
            case 3: ++changed.pawn_serial; break; case 4: ++changed.controller_serial; break; case 5: ++changed.pawn; break;
        }
        door.Arm(scope, 100, true);
        Expect(!door.Observe(changed, 104, 1.1f) && !door.Observe(scope, 108, 1.1f),
               "door view exception cannot survive a scope or object-lifetime change");
    }
    door.Arm(scope, 100, true); door.UpdateScope(scope, false); door.UpdateScope(scope, true);
    Expect(!door.Observe(scope, 104, 1.1f), "F8, menu and disconnect clear rather than suspend a door exception");
    door.Arm(scope, 100, true);
    Expect(!door.Observe(scope, 104, 51) && !door.Observe(scope, 108, 1.1f), "large native snaps revoke the exception");
    door.Arm(scope, 100, true);
    Expect(!door.Observe(scope, 104, NAN), "invalid motion cannot suppress the native script guard");
    door.Arm(scope, 100, true); door.Arm(scope, 104, false);
    Expect(!door.Observe(scope, 108, 1.1f), "a new non-door use cannot inherit door motion permission");
    auto invalid = scope; invalid.pawn_serial = 0; door.Arm(invalid, 100, true);
    Expect(!door.Observe(invalid, 104, 1.1f), "door motion needs valid current actor identities");
    guard.Reset({}, 15000);
    Expect(!guard.driving() && !guard.ManualRecovery(15001), "new pawn scope and ordinary F8 have no old fallback state");
}
}  // namespace

int main(int argc, char** argv) {
    TestBoundedLogs();
    TestInventoryRouting();
    {
        using namespace sifucraft;
        const auto request = protocol::kMcReady | protocol::kMcRequestAnchor;
        std::uint32_t epoch = 7;
        Expect(input::WantsAnchor(request, 7, epoch), "scoped respawn request asks for a new anchor");
        epoch = input::NextEpoch(epoch);
        Expect(!input::WantsAnchor(request, 7, epoch), "repeated snapshot cannot acknowledge respawn twice");
        Expect(!input::WantsAnchor(request | protocol::kMcDead, epoch, epoch) &&
               !input::WantsAnchor(request | protocol::kMcHasPose, epoch, epoch), "dead or old-pose snapshots cannot complete a respawn");
        Expect(input::NextEpoch(0xffffffffu) == 1, "anchor wrap skips the invalid zero epoch");
        Expect(input::SameScope(42, 42, 3, 3) && !input::SameScope(41, 42, 3, 3) && !input::SameScope(42, 42, 2, 3),
               "matching epoch never makes an old Sifu lifetime or world a valid pose/request");
    }
    {
        using namespace sifucraft;
        combat::DamageLedger ledger;
        ledger.SetSession(10);
        protocol::DamageResultPayload result{1, 42, 9, 0, 54, 6, 3}, cached{};
        using Claim = combat::DamageLedger::Claim;
        Expect(ledger.Begin(10, 1, &cached) == Claim::Fresh && ledger.Begin(10, 1, &cached) == Claim::Pending,
               "damage ledger applies a command once while pending");
        ledger.Complete(10, 1, result);
        Expect(ledger.Begin(10, 1, &cached) == Claim::Cached && cached.sifu_damage == 54 && cached.health_after == 6,
               "damage ledger replays the result, not the damage");
        for (std::uint64_t id = 2; id < 300; ++id) ledger.Begin(10, id, &cached);
        Expect(ledger.Begin(10, 1, &cached) == Claim::TooOld && ledger.Begin(10, 0, &cached) == Claim::TooOld,
               "eviction and reordered old IDs never permit damage replay");
        Expect(ledger.Begin(11, 1, &cached) == Claim::WrongSession, "damage ledger refuses a foreign session");
        ledger.SetSession(11);
        Expect(ledger.Begin(11, 1, &cached) == Claim::Fresh && ledger.Begin(10, 300, &cached) == Claim::WrongSession,
               "a new session may restart IDs without accepting old-session damage");
        protocol::EnemyDamagePayload valid{42, 9, 6, 0.25f, 0, 3, 1, 0};
        Expect(combat::ValidDamage(valid), "combat accepts a finite scoped damage payload");
        auto bad = valid; bad.mc_damage = NAN;
        Expect(!combat::ValidDamage(bad), "combat refuses non-finite damage");
        bad = valid; bad.anchor_epoch = 0;
        Expect(!combat::ValidDamage(bad), "combat refuses an unanchored damage payload");
        bad = valid; bad.flags = 16;
        Expect(!combat::ValidDamage(bad), "combat refuses unknown damage flags");
    }
    TestGuardedReads();
    TestLanguage();
    TestShieldTimingAndLedger();
    TestLightImpactCall();
    TestMaceImpulses();
    TestScriptHandoverIntent();
    TestDoorViewHandover();
    {
        sifucraft::interaction::Requests ledger;
        Expect(ledger.Claim(10, 1) && !ledger.Claim(10, 1) && !ledger.Claim(10, 0), "native use gesture cannot replay a request");
        Expect(ledger.Claim(10, 4) && !ledger.Claim(10, 2) && ledger.Claim(11, 1), "native use IDs are ordered per peer lifetime");
        sifucraft::protocol::InteractionSnapshot target{};
        target.minimum = {100, -50, 0}; target.maximum = {120, 50, 200}; target.range_cm = 150;
        Expect(sifucraft::interaction::InRange({0, 0, 162}, target), "native use distance is measured to the target bounds");
        Expect(!sifucraft::interaction::InRange({-100, 0, 162}, target) &&
            !sifucraft::interaction::InRange({NAN, 0, 162}, target), "far and invalid native use positions are refused");
        target.maximum.x = 0;
        Expect(!sifucraft::interaction::InRange({0, 0, 162}, target), "inverted candidate bounds cannot authorize an input");
    }
    {
        using sifucraft::hooks::StreamingLease;
        StreamingLease lease;
        Expect(!lease.Update(false, true) && !lease.held(), "ground movement keeps streaming enabled");
        Expect(lease.Update(true, true) == false && lease.held(), "takeoff freezes streaming before the pawn moves");
        Expect(!lease.Update(true, false), "airborne frames and sky platforms do not repeat the call");
        Expect(lease.Update(false, false) == true && !lease.held(), "landing/F8/script/lost scope restores enabled streaming");
        Expect(!lease.Update(false, true), "restored ground frames do not repeat the call");
        Expect(!lease.Update(true, false) && lease.held() && !lease.Update(false, false),
            "a pre-existing disabled state remains disabled on release");
        lease.Update(true, true); lease.Discard();
        Expect(!lease.Update(false, false), "a different world/manager does not receive a stale restore");
        sifucraft::collision::VoxelGrid floor;
        floor.Set(0, 0, -1);
        Expect(sifucraft::collision::HasFloorSupport(floor, {6, 6, 0}), "original scene floor permits landing restore");
        Expect(sifucraft::collision::HasFloorSupport(floor, {30, 6, 0}), "capsule edge support permits landing restore");
        Expect(!sifucraft::collision::HasFloorSupport(floor, {6, 6, 100}) &&
            !sifucraft::collision::HasFloorSupport(floor, {6, 6, 2000}), "a sky platform above the floor keeps the scene loaded");
        Expect(!sifucraft::collision::HasFloorSupport(floor, {NAN, 6, 0}), "invalid support coordinates are refused");
    }
    TestBuildGuard();
    TestVtableHook();
    TestKeyMap();
    TestInputRouter();
    TestWorldTransform();
    TestAffine();
    TestGridPublication();
    TestVoxels();
    TestCollisionFiles();
    TestCombatSamples();
    TestLiveHitSamples();
    TestDataTableRows();
    TestEnemyIdentity();
    if (argc > 1) TestProxy(argv[1]);
    std::printf("%s (%d failure%s)\n", g_failures ? "FAILED" : "PASSED", g_failures, g_failures == 1 ? "" : "s");
    return g_failures ? 1 : 0;
}
