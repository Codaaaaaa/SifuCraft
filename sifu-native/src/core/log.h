#pragma once

#include <windows.h>
#include <cstdint>

#include <string>
#include <string_view>

namespace sifucraft {

// Bounded text file; completed writes go straight to the OS and older parts rotate.
class TextFile {
public:
    TextFile() = default;
    TextFile(const TextFile&) = delete;
    TextFile& operator=(const TextFile&) = delete;
    ~TextFile();

    bool Open(const std::wstring& path, std::uint64_t max_bytes = 16 * 1024 * 1024, unsigned archives = 3);
    bool IsOpen() const { return handle_ != INVALID_HANDLE_VALUE; }
    void Write(std::string_view text);
    // Never blocks: drops the text if another thread holds the lock (used during process exit,
    // when that thread may already have been terminated mid-write).
    void TryWrite(std::string_view text);
    void Printf(const char* format, ...) __attribute__((format(gnu_printf, 2, 3)));

private:
    void WriteLocked(std::string_view text);
    bool Rotate();
    HANDLE handle_ = INVALID_HANDLE_VALUE;
    SRWLOCK lock_ = SRWLOCK_INIT;
    std::wstring path_;
    std::uint64_t bytes_ = 0, max_bytes_ = 0, retry_at_ = 0;
    unsigned archives_ = 0;
};

namespace log {

// Creates <module dir>/SifuCraft/logs and opens probe.log (previous run kept as probe.prev.log).
bool Init(HMODULE module);
// Opens <directory>/<file_name> (previous run kept as <stem>.prev.log); creates the directory.
bool InitAt(const std::wstring& directory, const std::wstring& file_name);
// Also copy every line to stdout (console tools).
void SetEcho(bool echo);
const std::wstring& Directory();
// <module dir>/SifuCraft: holds logs/ and the control files (stage, no-hooks, disabled).
const std::wstring& RootDirectory();
void Write(const char* format, ...) __attribute__((format(gnu_printf, 1, 2)));
// Safe to call from DllMain during process exit.
void WriteFinal(std::string_view text);

}  // namespace log

std::string FormatString(const char* format, ...) __attribute__((format(gnu_printf, 1, 2)));

}  // namespace sifucraft

#define SC_LOG(...) ::sifucraft::log::Write(__VA_ARGS__)
