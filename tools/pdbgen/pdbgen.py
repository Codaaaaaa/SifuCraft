"""Generate sifu-native's per-build offset table from the local Sifu PDB.

Reads Sifu-Win64-Shipping.exe/.pdb through the system dbghelp.dll and writes a C++
header with the build fingerprint, global symbol RVAs, struct member offsets, struct
sizes and the vtable hook plan that sifu-native needs. Every requested item must
resolve, and every hook slot must hold the expected implementation in the exe file;
anything else is a hard error so a stale or foreign table can never be emitted.

    python tools/pdbgen/pdbgen.py --game-dir "<Sifu>/Sifu/Binaries/Win64" \
        --out sifu-native/src/generated/sifu_build.g.h
"""

import argparse
import ctypes as C
import struct
import sys
from ctypes import wintypes as W
from pathlib import Path

EXE_NAME = "Sifu-Win64-Shipping.exe"

# Global variables whose RVA the native module reads.
SYMBOLS = ["GWorld", "GEngine", "GUObjectArray", "NamePoolData"]

# Vtable hook plan: name -> (class whose vftable is patched, class that introduces the virtual,
# method, implementation the slot must hold). Checked against the exe file here and against live
# memory by sifu-native before anything is patched.
HOOKS = {
    "EngineTick": ("UGameEngine", "UEngine", "Tick", "UGameEngine::Tick"),
    "PlayerInputKey": ("AFightingPlayerController", "APlayerController", "InputKey",
                       "AFightingPlayerController::InputKey"),
    "PlayerInputAxis": ("AFightingPlayerController", "APlayerController", "InputAxis",
                        "AFightingPlayerController::InputAxis"),
    "CameraUpdate": ("ASCPlayerCameraManager", "APlayerCameraManager", "UpdateCamera",
                     "APlayerCameraManager::UpdateCamera"),
    "MovementTick": ("UFightingMovementComponent", "UActorComponent", "TickComponent",
                     "UFightingMovementComponent::TickComponent"),
    # P5 S0 only inspects these targets; generating a plan does not install a hook.
    "ValidateHit": ("UHitComponent", "UHitComponent", "BPE_ValidateHit_Implementation",
                    "UHitComponent::BPE_ValidateHit_Implementation"),
    "InternalApplyDamage": ("UCharacterHealthComponent", "UHealthComponent", "InternalApplyDamage",
                            "UCharacterHealthComponent::InternalApplyDamage"),
}

# Functions sifu-native calls directly: identifier -> symbol.
FUNCTIONS = {"FKey_Destructor": "FKey::~FKey"}

# Virtual slots used for calls (dispatched through the object's own vtable): introducing class -> methods.
CALL_SLOTS = {"UObject": ["ProcessEvent"]}

# type -> members. A member may live in a base class; its offset is then made absolute.
MEMBERS = {
    # CoreUObject
    "UObjectBase": ["ObjectFlags", "InternalIndex", "ClassPrivate", "NamePrivate", "OuterPrivate"],
    "UStruct": ["SuperStruct", "Children", "ChildProperties", "PropertiesSize", "Script"],
    "UFunction": ["FunctionFlags", "ParmsSize"],
    "UField": ["Next"],
    "FField": ["ClassPrivate", "Next", "NamePrivate"],
    "FFieldClass": ["Name"],
    "FProperty": ["ArrayDim", "ElementSize", "Offset_Internal"],
    "FObjectPropertyBase": ["PropertyClass"],
    "FStructProperty": ["Struct"],
    "FArrayProperty": ["Inner"],
    "FBoolProperty": ["FieldSize", "ByteOffset", "ByteMask", "FieldMask"],
    "FUObjectArray": ["ObjObjects"],
    "FChunkedFixedUObjectArray": ["Objects", "MaxElements", "NumElements", "MaxChunks", "NumChunks"],
    "FUObjectItem": ["Object", "Flags", "SerialNumber"],
    "FWeakObjectPtr": ["ObjectIndex", "ObjectSerialNumber"],
    # Names
    "FNamePool": ["Entries"],
    "FNameEntryAllocator": ["CurrentBlock", "Blocks"],
    "FNameEntry": ["Header", "AnsiName", "WideName"],
    # Engine
    "UWorld": ["PersistentLevel", "OwningGameInstance", "Levels"],
    "ULevel": ["Actors", "OwningWorld", "WorldSettings"],
    "AWorldSettings": ["PauserPlayerState"],
    "UGameInstance": ["LocalPlayers"],
    "UPlayer": ["PlayerController"],
    "AActor": ["RootComponent"],
    "APawn": ["Controller"],
    "ACharacter": ["CharacterMovement", "Mesh", "CapsuleComponent"],
    "AController": ["Pawn", "IgnoreMoveInput", "IgnoreLookInput"],
    "APlayerController": ["PlayerCameraManager", "AcknowledgedPawn"],
    "APlayerCameraManager": ["CameraCachePrivate", "LastFrameCameraCachePrivate", "ViewTarget"],
    "FTViewTarget": ["POV"],
    "FCameraCacheEntry": ["POV"],
    "FMinimalViewInfo": ["Location", "Rotation", "FOV"],
    "USceneComponent": ["RelativeLocation", "RelativeRotation", "ComponentVelocity", "AttachParent", "ComponentToWorld",
                        "Bounds", "Mobility"],
    "FTransform": ["Rotation", "Translation", "Scale3D"],
    "FBoxSphereBounds": ["Origin", "BoxExtent"],
    # Collision
    "UPrimitiveComponent": ["BodyInstance"],
    "FBodyInstanceCore": ["BodySetup"],
    "FBodyInstance": ["ObjectType", "CollisionEnabled", "CollisionResponses", "WalkableSlopeOverride"],
    "FWalkableSlopeOverride": ["WalkableSlopeBehavior", "WalkableSlopeAngle"],
    "FCollisionResponse": ["ResponseToChannels"],
    "UBodySetupCore": ["CollisionTraceFlag", "TriMeshes"],
    "UBodySetup": ["AggGeom", "WalkableSlopeOverride"],
    "FKAggregateGeom": ["SphereElems", "BoxElems", "SphylElems", "ConvexElems", "TaperedCapsuleElems"],
    "FKConvexElem": ["VertexData", "IndexData", "Transform"],
    "FKBoxElem": ["Center", "Rotation", "X", "Y", "Z"],
    "FKSphereElem": ["Center", "Radius"],
    "FKSphylElem": ["Center", "Rotation", "Radius", "Length"],
    "FKTaperedCapsuleElem": ["Center", "Rotation", "Radius0", "Radius1", "Length"],
    "FInstancedStaticMeshInstanceData": ["Transform"],
    "UStaticMeshComponent": ["StaticMesh"],
    "UStaticMesh": ["BodySetup"],
    "UInstancedStaticMeshComponent": ["PerInstanceSMData"],
    "UMovementComponent": ["Velocity"],
    "UCharacterMovementComponent": ["MovementMode", "WalkableFloorAngle", "WalkableFloorZ", "MaxStepHeight"],
    "UCapsuleComponent": ["CapsuleHalfHeight", "CapsuleRadius"],
    "UEngine": ["GameViewport"],
    # Sifu
    "ASCCharacter": ["m_bIsPooled", "m_bPooledActorActive"],
    "AFightingCharacter": ["m_HealthComponent", "m_eFaction", "m_HitComponent", "m_DefenseComponent",
                           "m_AttackComponent", "m_HitCapsule", "m_AvoidCapsule", "m_AbilityComponent",
                           "m_bHasJustBeenHitted", "m_HittedDescription"],
    "UHealthComponent": ["m_fHealth", "m_fMaxHealth", "m_bCanBeDamaged", "m_bCanDieByDamage",
                         "m_bForceDeathOnApplyDamage"],
    "UCharacterHealthComponent": ["m_fMaxHealthMultiplier", "m_bIsDown", "m_eDownState"],
    "FDamageInfos": ["m_fDamage", "m_fHealthBeforeDamage", "m_Instigator", "m_uiApplyDamageBehavior"],
    "FHitRequest": ["m_Instigator", "m_HitBox", "m_uiApplyDamageBehavior", "m_vDamageDir",
                    "m_AttackProperties", "m_bCanBeDefended"],
    "FHitBox": ["m_HitRow", "m_CachedRow", "m_bCachedRowSetup"],
    "FHitboxDataRow": ["m_bCanBeLethal", "m_bCanBreakStructure", "m_OnHitImpact", "m_OnGuardImpact",
                       "m_OnDeflectImpact", "m_eHitType", "m_eAttackPower", "m_bParriable"],
    "FHitImpact": ["m_bUseDamage", "m_fDamage", "m_fStructureDamage"],
    "FHitDescription": ["m_Request", "m_Result", "m_ImpactResult"],
    "FImpactResult": ["m_bIsValid", "m_fDamage", "m_bLethal", "m_HitImpact"],
    "UAbilitySystemComponent": ["SpawnedAttributes"],
    "UCharacterStatAttributeSet": ["GameDifficulty"],
    "FGameplayAttributeData": ["BaseValue", "CurrentValue"],
}

SIZES = ["FUObjectItem", "UObjectBase", "FName", "FNameEntryAllocator", "FTransform", "FKConvexElem", "FKBoxElem", "FKSphereElem",
         "FKSphylElem", "FKTaperedCapsuleElem", "FInstancedStaticMeshInstanceData", "FDamageInfos",
         "FHitRequest", "FHitBox", "FHitboxDataRow", "FHitImpact", "FHitDescription", "FImpactResult"]

# type -> bitfield members whose bit position/width are emitted.
BITFIELDS = {
    "FNameEntryHeader": ["bIsWide", "Len"],
    "APlayerController": ["bShowMouseCursor", "bCinematicMode"],
    "AActor": ["bHidden"],
    "ULevel": ["bIsVisible"],
    "USceneComponent": ["bVisible", "bHiddenInGame"],
    "FBodyInstance": ["bOverrideWalkableSlopeOnInstance"],
}

# --- dbghelp ---------------------------------------------------------------------------

SYMOPT_UNDNAME = 0x2
SYMOPT_DEFERRED_LOADS = 0x4
SYMOPT_FAIL_CRITICAL_ERRORS = 0x200
SYMOPT_EXACT_SYMBOLS = 0x400
SYMOPT_NO_PROMPTS = 0x80000

TI_GET_SYMTAG = 0
TI_GET_SYMNAME = 1
TI_GET_LENGTH = 2
TI_GET_TYPEID = 4
TI_FINDCHILDREN = 7
TI_GET_OFFSET = 10
TI_GET_CHILDRENCOUNT = 13
TI_GET_BITPOSITION = 14
TI_GET_VIRTUALBASEOFFSET = 29  # for a virtual function: its byte offset in the vftable

SYMTAG_FUNCTION = 5
SYMTAG_DATA = 7
SYMTAG_BASECLASS = 18


class SYMBOL_INFOW(C.Structure):
    _fields_ = [("SizeOfStruct", W.ULONG), ("TypeIndex", W.ULONG), ("Reserved", C.c_uint64 * 2),
                ("Index", W.ULONG), ("Size", W.ULONG), ("ModBase", C.c_uint64), ("Flags", W.ULONG),
                ("Value", C.c_uint64), ("Address", C.c_uint64), ("Register", W.ULONG),
                ("Scope", W.ULONG), ("Tag", W.ULONG), ("NameLen", W.ULONG), ("MaxNameLen", W.ULONG),
                ("Name", W.WCHAR * 1024)]


ENUM_CALLBACK = C.WINFUNCTYPE(W.BOOL, C.POINTER(SYMBOL_INFOW), W.ULONG, C.c_void_p)


def new_symbol_info():
    info = SYMBOL_INFOW()
    info.SizeOfStruct = SYMBOL_INFOW.Name.offset + C.sizeof(W.WCHAR)  # dbghelp wants the 1-char size
    info.SizeOfStruct = (info.SizeOfStruct + 7) & ~7
    info.MaxNameLen = 1024
    return info


class Pdb:
    def __init__(self, game_dir: Path, exe: Path):
        self.dbg = C.WinDLL(str(Path(_system_dir()) / "dbghelp.dll"), use_last_error=True)
        d = self.dbg
        d.SymSetOptions.argtypes = [W.DWORD]
        d.SymInitializeW.argtypes = [C.c_void_p, W.LPCWSTR, W.BOOL]
        d.SymCleanup.argtypes = [C.c_void_p]
        d.SymLoadModuleExW.argtypes = [C.c_void_p, C.c_void_p, W.LPCWSTR, W.LPCWSTR, C.c_uint64, W.DWORD,
                                       C.c_void_p, W.DWORD]
        d.SymLoadModuleExW.restype = C.c_uint64
        d.SymFromNameW.argtypes = [C.c_void_p, W.LPCWSTR, C.POINTER(SYMBOL_INFOW)]
        d.SymEnumTypesByNameW.argtypes = [C.c_void_p, C.c_uint64, W.LPCWSTR, ENUM_CALLBACK, C.c_void_p]
        d.SymGetTypeInfo.argtypes = [C.c_void_p, C.c_uint64, W.ULONG, C.c_int, C.c_void_p]
        C.windll.kernel32.LocalFree.argtypes = [C.c_void_p]

        self.process = C.c_void_p(0x51F0C4AF7)  # any unique value; no live process is involved
        d.SymSetOptions(SYMOPT_UNDNAME | SYMOPT_DEFERRED_LOADS | SYMOPT_FAIL_CRITICAL_ERRORS
                        | SYMOPT_EXACT_SYMBOLS | SYMOPT_NO_PROMPTS)
        if not d.SymInitializeW(self.process, str(game_dir), False):
            raise RuntimeError(f"SymInitialize failed ({C.get_last_error()})")
        self.base = d.SymLoadModuleExW(self.process, None, str(exe), None, 0x140000000, 0, None, 0)
        if not self.base:
            raise RuntimeError(f"SymLoadModuleEx failed ({C.get_last_error()})")
        self._type_cache = {}

    def close(self):
        self.dbg.SymCleanup(self.process)

    def rva(self, name: str) -> int:
        info = new_symbol_info()
        if not self.dbg.SymFromNameW(self.process, name, C.byref(info)):
            raise LookupError(f"symbol '{name}' not found")
        return info.Address - self.base

    def type_id(self, name: str) -> int:
        if name in self._type_cache:
            return self._type_cache[name]
        found = []

        def collect(p, _size, _ctx):
            s = p.contents
            if s.Name == name:
                found.append((s.TypeIndex, s.Size))
            return True

        self.dbg.SymEnumTypesByNameW(self.process, self.base, name, ENUM_CALLBACK(collect), None)
        defined = {tid: size for tid, size in found if size > 0}
        if not defined:
            raise LookupError(f"type '{name}' not found (or only forward-declared)")
        if len(set(defined.values())) > 1:
            raise LookupError(f"type '{name}' is ambiguous: sizes {sorted(set(defined.values()))}")
        tid = next(iter(defined))
        self._type_cache[name] = tid
        return tid

    def _info(self, tid: int, kind: int, ctype):
        value = ctype()
        if not self.dbg.SymGetTypeInfo(self.process, self.base, tid, kind, C.byref(value)):
            return None
        return value.value

    def _name(self, tid: int):
        ptr = C.c_void_p()
        if not self.dbg.SymGetTypeInfo(self.process, self.base, tid, TI_GET_SYMNAME, C.byref(ptr)):
            return None
        name = C.wstring_at(ptr.value)
        C.windll.kernel32.LocalFree(ptr)
        return name

    def _children(self, tid: int):
        count = self._info(tid, TI_GET_CHILDRENCOUNT, W.DWORD) or 0
        if not count:
            return []

        class Params(C.Structure):
            _fields_ = [("Count", W.ULONG), ("Start", W.ULONG), ("ChildId", W.ULONG * count)]

        params = Params(count, 0)
        if not self.dbg.SymGetTypeInfo(self.process, self.base, tid, TI_FINDCHILDREN, C.byref(params)):
            raise RuntimeError(f"TI_FINDCHILDREN failed for type id {tid}")
        return list(params.ChildId)

    def size_of(self, type_name: str) -> int:
        return self._info(self.type_id(type_name), TI_GET_LENGTH, C.c_uint64)

    def find_member(self, tid: int, member: str):
        """Returns (offset, child id) of `member`, searching base classes too."""
        bases = []
        for child in self._children(tid):
            tag = self._info(child, TI_GET_SYMTAG, W.DWORD)
            if tag == SYMTAG_DATA and self._name(child) == member:
                return self._info(child, TI_GET_OFFSET, W.DWORD), child
            if tag == SYMTAG_BASECLASS:
                bases.append(child)
        for base in bases:
            base_type = self._info(base, TI_GET_TYPEID, W.DWORD)
            found = self.find_member(base_type, member)
            if found:
                return self._info(base, TI_GET_OFFSET, W.DWORD) + found[0], found[1]
        return None

    def member_offset(self, type_name: str, member: str) -> int:
        found = self.find_member(self.type_id(type_name), member)
        if not found:
            raise LookupError(f"member '{type_name}::{member}' not found")
        return found[0]

    def bitfield(self, type_name: str, member: str):
        found = self.find_member(self.type_id(type_name), member)
        if not found:
            raise LookupError(f"bitfield '{type_name}::{member}' not found")
        position = self._info(found[1], TI_GET_BITPOSITION, W.DWORD)
        width = self._info(found[1], TI_GET_LENGTH, C.c_uint64)
        if position is None:
            raise LookupError(f"'{type_name}::{member}' is not a bitfield")
        return found[0], position, width

    def virtual_slot(self, type_name: str, method: str) -> int:
        """vftable byte offset of `method` in the class that introduces it.

        dbghelp reports the offset only on the introducing class (overrides and non-virtual
        functions read 0), so the method must be unique there and its slot nonzero.
        """
        slots = set()
        for child in self._children(self.type_id(type_name)):
            if self._info(child, TI_GET_SYMTAG, W.DWORD) == SYMTAG_FUNCTION and self._name(child) == method:
                slots.add(self._info(child, TI_GET_VIRTUALBASEOFFSET, W.DWORD) or 0)
        slots.discard(0)
        if len(slots) != 1:
            raise LookupError(f"virtual '{type_name}::{method}': expected one slot, found {sorted(slots)}")
        return slots.pop()


class ExeImage:
    """Raw PE file contents, for reading initialized data (vftables) at an RVA."""

    def __init__(self, exe: Path):
        self.data = exe.read_bytes()
        pe = struct.unpack_from("<I", self.data, 0x3C)[0]
        self.image_base = struct.unpack_from("<Q", self.data, pe + 24 + 24)[0]
        count = struct.unpack_from("<H", self.data, pe + 6)[0]
        table = pe + 24 + struct.unpack_from("<H", self.data, pe + 20)[0]
        self.sections = [struct.unpack_from("<8xIIII", self.data, table + i * 40) for i in range(count)]

    def read_va_as_rva(self, rva: int) -> int:
        """Reads the absolute pointer stored at `rva` and returns it as an RVA."""
        for virtual_size, va, raw_size, raw in self.sections:
            if va <= rva < va + max(virtual_size, raw_size):
                return struct.unpack_from("<Q", self.data, raw + rva - va)[0] - self.image_base
        raise LookupError(f"rva 0x{rva:X} is not in any section")


def _system_dir() -> str:
    buf = C.create_unicode_buffer(260)
    C.windll.kernel32.GetSystemDirectoryW(buf, 260)
    return buf.value


def read_pe_identity(exe: Path):
    with exe.open("rb") as f:
        header = f.read(4096)
    pe = struct.unpack_from("<I", header, 0x3C)[0]
    if header[pe:pe + 4] != b"PE\0\0":
        raise RuntimeError(f"{exe} is not a PE image")
    stamp = struct.unpack_from("<I", header, pe + 8)[0]
    size_of_image = struct.unpack_from("<I", header, pe + 24 + 56)[0]
    return stamp, size_of_image


def emit(stamp, size_of_image, rvas, members, sizes, bits, functions, slots, hooks) -> str:
    out = [
        "// Generated by tools/pdbgen/pdbgen.py from the local Sifu PDB. Do not edit.",
        "// Regenerate whenever Sifu-Win64-Shipping.exe changes; the build guard rejects",
        "// any executable whose stamp/size differs from the values below.",
        "#pragma once",
        "",
        "#include <cstdint>",
        "",
        "namespace sifucraft::gen {",
        "",
        f"inline constexpr std::uint32_t kTimeDateStamp = 0x{stamp:08X};",
        f"inline constexpr std::uint32_t kSizeOfImage = 0x{size_of_image:08X};",
        "",
        "namespace rva {",
    ]
    out += [f"inline constexpr std::uintptr_t {name} = 0x{value:X};" for name, value in rvas.items()]
    out += ["}  // namespace rva", "", "namespace off {"]
    for type_name, fields in members.items():
        out.append(f"namespace {type_name} {{")
        out += [f"inline constexpr std::uint32_t {name} = 0x{value:X};" for name, value in fields.items()]
        out.append("}")
    out += ["}  // namespace off", "", "namespace size {"]
    out += [f"inline constexpr std::uint32_t {name} = 0x{value:X};" for name, value in sizes.items()]
    out += ["}  // namespace size", "", "namespace bits {"]
    for type_name, fields in bits.items():
        out.append(f"namespace {type_name} {{")
        for name, (offset, position, width) in fields.items():
            out.append(f"inline constexpr std::uint32_t {name}_Offset = 0x{offset:X};")
            out.append(f"inline constexpr std::uint32_t {name}_Shift = {position};")
            out.append(f"inline constexpr std::uint32_t {name}_Width = {width};")
        out.append("}")
    out += ["}  // namespace bits", "", "// Functions called directly.", "namespace fn {"]
    out += [f"inline constexpr std::uintptr_t {name} = 0x{value:X};" for name, value in functions.items()]
    out += ["}  // namespace fn", "", "// Virtual slots used for calls through an object's own vtable.", "namespace slot {"]
    for type_name, methods in slots.items():
        out.append(f"namespace {type_name} {{")
        out += [f"inline constexpr std::uint32_t {name} = 0x{value:X};" for name, value in methods.items()]
        out.append("}")
    out += [
        "}  // namespace slot",
        "",
        "// Vtable hook plan, verified against the exe file at generation time. Before patching,",
        "// sifu-native checks that the live instance uses `vftable` and the slot still holds",
        "// `original` (both relative to the image base).",
        "namespace hook {",
        "struct Target {",
        "    const char* name;",
        "    std::uintptr_t vftable;",
        "    std::uint32_t slot;",
        "    std::uintptr_t original;",
        "};",
    ]
    for name, (owner, introducer, method, impl, vftable, slot, original) in hooks.items():
        out.append(f"// {owner} vftable, {introducer}::{method} slot -> {impl}")
        out.append(f'inline constexpr Target {name}{{"{name}", 0x{vftable:X}, 0x{slot:X}, 0x{original:X}}};')
    out += ["}  // namespace hook", "", "}  // namespace sifucraft::gen", ""]
    return "\n".join(out)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--game-dir", required=True, type=Path, help="folder holding the Sifu exe and pdb")
    parser.add_argument("--out", required=True, type=Path, help="header to write")
    args = parser.parse_args()

    exe = args.game_dir / EXE_NAME
    if not (exe.is_file() and exe.with_suffix(".pdb").is_file()):
        print(f"error: {exe} and its .pdb must both exist", file=sys.stderr)
        return 1

    stamp, size_of_image = read_pe_identity(exe)
    image = ExeImage(exe)
    pdb = Pdb(args.game_dir, exe)
    errors = []
    rvas, members, sizes, bits, functions, slots, hooks = {}, {}, {}, {}, {}, {}, {}
    try:
        for name, symbol in FUNCTIONS.items():
            try:
                functions[name] = pdb.rva(symbol)
            except LookupError as e:
                errors.append(str(e))
        for type_name, methods in CALL_SLOTS.items():
            slots[type_name] = {}
            for method in methods:
                try:
                    slots[type_name][method] = pdb.virtual_slot(type_name, method)
                except LookupError as e:
                    errors.append(str(e))
        for name, (owner, introducer, method, impl) in HOOKS.items():
            try:
                vftable = pdb.rva(f"{owner}::`vftable'")
                slot = pdb.virtual_slot(introducer, method)
                original = pdb.rva(impl)
                actual = image.read_va_as_rva(vftable + slot)
                if actual != original:
                    errors.append(f"hook {name}: {owner} vftable+0x{slot:X} holds 0x{actual:X}, "
                                  f"expected {impl} 0x{original:X}")
                    continue
                hooks[name] = (owner, introducer, method, impl, vftable, slot, original)
            except LookupError as e:
                errors.append(f"hook {name}: {e}")
        for name in SYMBOLS:
            try:
                rvas[name] = pdb.rva(name)
            except LookupError as e:
                errors.append(str(e))
        for type_name, fields in MEMBERS.items():
            members[type_name] = {}
            for field in fields:
                try:
                    members[type_name][field] = pdb.member_offset(type_name, field)
                except LookupError as e:
                    errors.append(str(e))
        for type_name in SIZES:
            try:
                sizes[type_name] = pdb.size_of(type_name)
            except LookupError as e:
                errors.append(str(e))
        for type_name, fields in BITFIELDS.items():
            bits[type_name] = {}
            for field in fields:
                try:
                    bits[type_name][field] = pdb.bitfield(type_name, field)
                except LookupError as e:
                    errors.append(str(e))
    finally:
        pdb.close()

    if errors:
        for e in errors:
            print(f"error: {e}", file=sys.stderr)
        return 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(emit(stamp, size_of_image, rvas, members, sizes, bits, functions, slots, hooks),
                        encoding="utf-8", newline="\n")
    print(f"wrote {args.out} (stamp 0x{stamp:08X}, size 0x{size_of_image:08X}, "
          f"{len(rvas)} symbols, {sum(len(f) for f in members.values())} members, "
          f"{len(hooks)} verified hook targets)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
