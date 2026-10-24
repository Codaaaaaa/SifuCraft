#pragma once

#include <windows.h>

#include <cstdint>

namespace sifucraft::bridge {

// A named, pagefile-backed shared memory section. Either process may create it; the second one
// opens the existing section. It lives until the last handle to it is closed.
class SharedMapping {
public:
    SharedMapping() = default;
    SharedMapping(const SharedMapping&) = delete;
    SharedMapping& operator=(const SharedMapping&) = delete;
    ~SharedMapping() { Close(); }

    bool Open(const wchar_t* name, std::uint32_t size);
    void Close();

    std::uint8_t* data() const { return view_; }
    bool created() const { return created_; }
    DWORD last_error() const { return last_error_; }

private:
    HANDLE handle_ = nullptr;
    std::uint8_t* view_ = nullptr;
    bool created_ = false;
    DWORD last_error_ = 0;
};

}  // namespace sifucraft::bridge
