#include "core/safe_memory.h"

#include <windows.h>

#include <atomic>

extern "C" {
int sc_guarded_copy(void* dst, const void* src, std::size_t size);
extern const char sc_guarded_copy_fault[];
extern const char sc_guarded_copy_end[];
}

// Leaf routine without stack usage: on a fault the handler simply moves RIP to
// sc_guarded_copy_fault, which returns 0 to the caller.
asm(R"ASM(
    .text
    .p2align 4
    .globl sc_guarded_copy
    .globl sc_guarded_copy_fault
    .globl sc_guarded_copy_end
sc_guarded_copy:
    cmpq $8, %r8
    jne 1f
    movq (%rdx), %rax
    movq %rax, (%rcx)
    movl $1, %eax
    ret
1:
    testq %r8, %r8
    jz 3f
2:
    movb (%rdx), %al
    movb %al, (%rcx)
    incq %rdx
    incq %rcx
    decq %r8
    jnz 2b
3:
    movl $1, %eax
    ret
sc_guarded_copy_fault:
    xorl %eax, %eax
    ret
sc_guarded_copy_end:
)ASM");

namespace sifucraft::mem {

namespace {

constexpr std::uintptr_t kMinAddress = 0x10000;
constexpr std::uintptr_t kMaxAddress = 0x7FFFFFFF0000;
constexpr std::int32_t kMaxArrayElements = 1 << 24;

std::atomic<std::uint64_t> g_faults{0};
PVOID g_handler = nullptr;

LONG CALLBACK OnException(EXCEPTION_POINTERS* info) {
    const DWORD code = info->ExceptionRecord->ExceptionCode;
    if (code != EXCEPTION_ACCESS_VIOLATION && code != EXCEPTION_IN_PAGE_ERROR &&
        code != STATUS_GUARD_PAGE_VIOLATION) {
        return EXCEPTION_CONTINUE_SEARCH;
    }
    const auto rip = static_cast<std::uintptr_t>(info->ContextRecord->Rip);
    const auto begin = reinterpret_cast<std::uintptr_t>(&sc_guarded_copy);
    const auto end = reinterpret_cast<std::uintptr_t>(sc_guarded_copy_end);
    if (rip < begin || rip >= end) return EXCEPTION_CONTINUE_SEARCH;

    if (code == STATUS_GUARD_PAGE_VIOLATION) {
        // Touching a guard page clears it; re-arm it so the owner's stack growth still works.
        auto* address = reinterpret_cast<void*>(info->ExceptionRecord->ExceptionInformation[1]);
        MEMORY_BASIC_INFORMATION region;
        DWORD old = 0;
        if (VirtualQuery(address, &region, sizeof(region))) {
            VirtualProtect(address, 1, region.Protect | PAGE_GUARD, &old);
        }
    }
    g_faults.fetch_add(1, std::memory_order_relaxed);
    info->ContextRecord->Rip = reinterpret_cast<DWORD64>(sc_guarded_copy_fault);
    return EXCEPTION_CONTINUE_EXECUTION;
}

}  // namespace

bool Install() {
    if (!g_handler) g_handler = AddVectoredExceptionHandler(1, OnException);
    return g_handler != nullptr;
}

std::uint64_t FaultCount() { return g_faults.load(std::memory_order_relaxed); }

bool Copy(void* dst, std::uintptr_t src, std::size_t size) {
    if (src < kMinAddress || src > kMaxAddress || size > kMaxAddress - src) return false;
    if (!g_handler) return false;
    return sc_guarded_copy(dst, reinterpret_cast<const void*>(src), size) != 0;
}

bool ReadArray(std::uintptr_t address, RawArray* out) {
    if (!Read(address, out)) return false;
    if (out->num < 0 || out->num > out->max || out->max > kMaxArrayElements) return false;
    return out->num == 0 || out->data != 0;
}

}  // namespace sifucraft::mem
