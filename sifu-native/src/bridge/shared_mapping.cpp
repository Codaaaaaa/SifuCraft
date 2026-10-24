#include "bridge/shared_mapping.h"

namespace sifucraft::bridge {

bool SharedMapping::Open(const wchar_t* name, std::uint32_t size) {
    Close();
    handle_ = CreateFileMappingW(INVALID_HANDLE_VALUE, nullptr, PAGE_READWRITE, 0, size, name);
    last_error_ = GetLastError();
    if (!handle_) return false;
    created_ = last_error_ != ERROR_ALREADY_EXISTS;
    view_ = static_cast<std::uint8_t*>(MapViewOfFile(handle_, FILE_MAP_ALL_ACCESS, 0, 0, size));
    if (!view_) {
        last_error_ = GetLastError();
        Close();
        return false;
    }
    return true;
}

void SharedMapping::Close() {
    if (view_) UnmapViewOfFile(view_);
    if (handle_) CloseHandle(handle_);
    view_ = nullptr;
    handle_ = nullptr;
    created_ = false;
}

}  // namespace sifucraft::bridge
