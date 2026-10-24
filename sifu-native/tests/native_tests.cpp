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
#include "collision/voxels.h"
#include "collision/walk_check.h"
#include "core/build_guard.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "hooks/vtable_hook.h"
#include "input/input_router.h"
#include "input/key_map.h"
#include "ue/transform.h"

namespace {

int g_failures = 0;

void Expect(bool condition, const char* what) {
    std::printf("%s  %s\n", condition ? "ok  " : "FAIL", what);
    if (!condition) ++g_failures;
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
    Expect(!router.OnKey(MapKey("Escape"), "Escape", 1), "escape always stays with sifu");
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

}  // namespace

int main(int argc, char** argv) {
    TestGuardedReads();
    TestBuildGuard();
    TestVtableHook();
    TestKeyMap();
    TestInputRouter();
    TestWorldTransform();
    TestAffine();
    TestVoxels();
    TestCollisionFiles();
    if (argc > 1) TestProxy(argv[1]);
    std::printf("%s (%d failure%s)\n", g_failures ? "FAILED" : "PASSED", g_failures, g_failures == 1 ? "" : "s");
    return g_failures ? 1 : 0;
}
