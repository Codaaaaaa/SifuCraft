#include "core/build_guard.h"

#include <windows.h>

#include <cwchar>

#include "core/log.h"
#include "generated/sifu_build.g.h"

namespace sifucraft::guard {

bool ReadGameModule(GameModule* out) {
    wchar_t path[MAX_PATH];
    const DWORD length = GetModuleFileNameW(nullptr, path, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return false;
    const wchar_t* name = path;
    for (const wchar_t* p = path; *p; ++p) {
        if (*p == L'\\' || *p == L'/') name = p + 1;
    }
    if (_wcsicmp(name, L"Sifu-Win64-Shipping.exe") != 0) return false;

    const auto* base = reinterpret_cast<const std::uint8_t*>(GetModuleHandleW(nullptr));
    const auto* dos = reinterpret_cast<const IMAGE_DOS_HEADER*>(base);
    if (dos->e_magic != IMAGE_DOS_SIGNATURE) return false;
    const auto* nt = reinterpret_cast<const IMAGE_NT_HEADERS64*>(base + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return false;

    out->base = reinterpret_cast<std::uintptr_t>(base);
    out->time_date_stamp = nt->FileHeader.TimeDateStamp;
    out->size_of_image = nt->OptionalHeader.SizeOfImage;
    return true;
}

bool Verify(const GameModule& game) {
    SC_LOG("guard: exe base=0x%llX stamp=0x%08X size=0x%08X", static_cast<unsigned long long>(game.base),
           game.time_date_stamp, game.size_of_image);
    if (game.time_date_stamp != gen::kTimeDateStamp || game.size_of_image != gen::kSizeOfImage) {
        SC_LOG("guard: UNKNOWN BUILD (offset table is for stamp=0x%08X size=0x%08X) -- staying inert",
               gen::kTimeDateStamp, gen::kSizeOfImage);
        SC_LOG("guard: regenerate with tools/pdbgen/pdbgen.py against this executable and rebuild");
        return false;
    }
    SC_LOG("guard: build matched");
    return true;
}

}  // namespace sifucraft::guard
