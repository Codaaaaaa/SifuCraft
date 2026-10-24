// Entry point of the Sifu-side module. Loaded by Sifu as dsound.dll; the proxy exports keep
// audio working while a worker thread runs the read-only probe and the shared-memory bridge.

#include <windows.h>

#include "core/build_guard.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "runtime.h"

namespace {

HMODULE g_module = nullptr;

DWORD WINAPI Worker(LPVOID) {
    using namespace sifucraft;

    guard::GameModule game;
    if (!guard::ReadGameModule(&game)) return 0;  // not Sifu: act as a plain dsound proxy
    if (!log::Init(g_module)) return 0;
    SC_LOG("SifuCraft native %s (read-only probe + bridge), pid=%lu", SIFUCRAFT_VERSION, GetCurrentProcessId());

    if (GetFileAttributesW((log::Directory() + L"\\..\\disabled").c_str()) != INVALID_FILE_ATTRIBUTES) {
        SC_LOG("SifuCraft\\disabled exists -- staying inert");
        return 0;
    }
    if (!guard::Verify(game)) return 0;
    if (!mem::Install()) {
        SC_LOG("could not install the fault handler -- staying inert");
        return 0;
    }
    runtime::Run(game.base);
}

}  // namespace

BOOL WINAPI DllMain(HINSTANCE module, DWORD reason, LPVOID reserved) {
    if (reason == DLL_PROCESS_ATTACH) {
        g_module = module;
        DisableThreadLibraryCalls(module);
        if (HANDLE thread = CreateThread(nullptr, 0, Worker, nullptr, 0, nullptr)) CloseHandle(thread);
    } else if (reason == DLL_PROCESS_DETACH && reserved != nullptr) {
        sifucraft::runtime::OnProcessExit();
        sifucraft::log::WriteFinal("process exit");
    }
    return TRUE;
}
