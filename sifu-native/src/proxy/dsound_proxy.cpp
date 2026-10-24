// Forwards every dsound.dll export to the system copy, loaded lazily on first use.

#include <windows.h>

#include <cwchar>

namespace {

HMODULE g_real = nullptr;
INIT_ONCE g_once = INIT_ONCE_STATIC_INIT;

BOOL CALLBACK LoadRealDsound(PINIT_ONCE, PVOID, PVOID*) {
    wchar_t path[MAX_PATH];
    const UINT length = GetSystemDirectoryW(path, MAX_PATH);
    if (length == 0 || length + 12 >= MAX_PATH) return TRUE;
    wcscat(path, L"\\dsound.dll");
    g_real = LoadLibraryW(path);
    return TRUE;
}

template <typename Fn>
Fn Real(const char* name) {
    InitOnceExecuteOnce(&g_once, LoadRealDsound, nullptr, nullptr);
    if (!g_real) return nullptr;
    return reinterpret_cast<Fn>(reinterpret_cast<void*>(GetProcAddress(g_real, name)));
}

}  // namespace

#define SC_FORWARD(name, params, args)                                    \
    extern "C" HRESULT WINAPI SC_##name params {                          \
        using Fn = HRESULT(WINAPI*) params;                               \
        static const Fn real = Real<Fn>(#name);                           \
        return real ? real args : E_FAIL;                                 \
    }

SC_FORWARD(DirectSoundCreate, (const GUID* device, void** out, void* outer), (device, out, outer))
SC_FORWARD(DirectSoundCreate8, (const GUID* device, void** out, void* outer), (device, out, outer))
SC_FORWARD(DirectSoundCaptureCreate, (const GUID* device, void** out, void* outer), (device, out, outer))
SC_FORWARD(DirectSoundCaptureCreate8, (const GUID* device, void** out, void* outer), (device, out, outer))
SC_FORWARD(DirectSoundEnumerateA, (void* callback, void* context), (callback, context))
SC_FORWARD(DirectSoundEnumerateW, (void* callback, void* context), (callback, context))
SC_FORWARD(DirectSoundCaptureEnumerateA, (void* callback, void* context), (callback, context))
SC_FORWARD(DirectSoundCaptureEnumerateW, (void* callback, void* context), (callback, context))
SC_FORWARD(GetDeviceID, (const GUID* source, GUID* dest), (source, dest))
SC_FORWARD(DllGetClassObject, (const GUID* clsid, const GUID* iid, void** out), (clsid, iid, out))
SC_FORWARD(DirectSoundFullDuplexCreate,
           (const GUID* capture, const GUID* render, const void* capture_desc, const void* render_desc,
            HWND window, DWORD level, void** duplex, void** capture_buffer, void** render_buffer,
            void* outer),
           (capture, render, capture_desc, render_desc, window, level, duplex, capture_buffer, render_buffer,
            outer))

extern "C" HRESULT WINAPI SC_DllCanUnloadNow() {
    using Fn = HRESULT(WINAPI*)();
    static const Fn real = Real<Fn>("DllCanUnloadNow");
    return real ? real() : S_FALSE;
}
