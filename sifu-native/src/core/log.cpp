#include "core/log.h"

#include <cstdarg>
#include <cstdio>
#include <algorithm>

namespace sifucraft {

namespace {

std::string VFormat(const char* format, va_list args) {
    va_list copy;
    va_copy(copy, args);
    const int length = std::vsnprintf(nullptr, 0, format, copy);
    va_end(copy);
    if (length <= 0) return {};
    std::string text(static_cast<std::size_t>(length), '\0');
    std::vsnprintf(text.data(), text.size() + 1, format, args);
    return text;
}

TextFile g_log;
std::wstring g_directory;
std::wstring g_root;
bool g_echo = false;

}  // namespace

TextFile::~TextFile() {
    if (handle_ != INVALID_HANDLE_VALUE) CloseHandle(handle_);
}

bool TextFile::Open(const std::wstring& path, std::uint64_t max_bytes, unsigned archives) {
    if (handle_ != INVALID_HANDLE_VALUE) CloseHandle(handle_);
    path_ = path; bytes_ = retry_at_ = 0;
    max_bytes_ = std::clamp<std::uint64_t>(max_bytes, 256, MAXDWORD);
    archives_ = std::clamp(archives, 1u, 8u);
    handle_ = CreateFileW(path.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr, CREATE_ALWAYS,
                          FILE_ATTRIBUTE_NORMAL, nullptr);
    return handle_ != INVALID_HANDLE_VALUE;
}

bool TextFile::Rotate() {
    const auto now = GetTickCount64();
    if (now < retry_at_) return false;
    for (unsigned n = archives_; n > 1; --n) {
        const auto old_path = path_ + L"." + std::to_wstring(n - 1);
        const auto new_path = path_ + L"." + std::to_wstring(n);
        if (!MoveFileExW(old_path.c_str(), new_path.c_str(), MOVEFILE_REPLACE_EXISTING) && GetLastError() != ERROR_FILE_NOT_FOUND) {
            retry_at_ = now + 5000; return false;
        }
    }
    CloseHandle(handle_); handle_ = INVALID_HANDLE_VALUE;
    const bool moved = MoveFileExW(path_.c_str(), (path_ + L".1").c_str(), MOVEFILE_REPLACE_EXISTING) != FALSE;
    // A reader may deny rename. Keep the existing contents, retry later, and never grow
    // past the cap in the meantime; a failed rotation must not truncate the evidence.
    handle_ = CreateFileW(path_.c_str(), GENERIC_WRITE, FILE_SHARE_READ, nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (handle_ == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER end{};
    if (!SetFilePointerEx(handle_, {}, &end, FILE_END)) return false;
    bytes_ = static_cast<std::uint64_t>(end.QuadPart);
    retry_at_ = moved ? 0 : now + 5000;
    return moved;
}

void TextFile::WriteLocked(std::string_view text) {
    text = text.substr(0, static_cast<std::size_t>(max_bytes_));
    if (bytes_ + text.size() > max_bytes_ && !Rotate()) return;
    DWORD written = 0;
    if (handle_ != INVALID_HANDLE_VALUE && WriteFile(handle_, text.data(), static_cast<DWORD>(text.size()), &written, nullptr)) bytes_ += written;
}

void TextFile::Write(std::string_view text) {
    if (text.empty()) return;
    AcquireSRWLockExclusive(&lock_);
    if (handle_ != INVALID_HANDLE_VALUE) WriteLocked(text);
    ReleaseSRWLockExclusive(&lock_);
}

void TextFile::TryWrite(std::string_view text) {
    if (text.empty()) return;
    if (!TryAcquireSRWLockExclusive(&lock_)) return;
    if (handle_ != INVALID_HANDLE_VALUE) WriteLocked(text);
    ReleaseSRWLockExclusive(&lock_);
}

void TextFile::Printf(const char* format, ...) {
    va_list args;
    va_start(args, format);
    Write(VFormat(format, args));
    va_end(args);
}

std::string FormatString(const char* format, ...) {
    va_list args;
    va_start(args, format);
    std::string text = VFormat(format, args);
    va_end(args);
    return text;
}

namespace log {

bool Init(HMODULE module) {
    wchar_t path[MAX_PATH];
    const DWORD length = GetModuleFileNameW(module, path, MAX_PATH);
    if (length == 0 || length >= MAX_PATH) return false;
    std::wstring directory(path, length);
    directory.resize(directory.find_last_of(L"\\/") + 1);
    directory += L"SifuCraft";
    CreateDirectoryW(directory.c_str(), nullptr);
    g_root = directory;
    return InitAt(directory + L"\\logs", L"probe.log");
}

const std::wstring& RootDirectory() { return g_root; }

bool InitAt(const std::wstring& directory, const std::wstring& file_name) {
    CreateDirectoryW(directory.c_str(), nullptr);
    g_directory = directory;
    const std::wstring current = directory + L"\\" + file_name;
    const std::wstring stem = file_name.substr(0, file_name.find_last_of(L'.'));
    MoveFileExW(current.c_str(), (directory + L"\\" + stem + L".prev.log").c_str(), MOVEFILE_REPLACE_EXISTING);
    return g_log.Open(current);
}

void SetEcho(bool echo) { g_echo = echo; }

const std::wstring& Directory() { return g_directory; }

std::string Timestamp() {
    SYSTEMTIME now;
    GetLocalTime(&now);
    return FormatString("%04u-%02u-%02u %02u:%02u:%02u.%03u ", now.wYear, now.wMonth, now.wDay, now.wHour,
                        now.wMinute, now.wSecond, now.wMilliseconds);
}

void WriteFinal(std::string_view text) {
    std::string line = Timestamp();
    line.append(text);
    line += "\r\n";
    g_log.TryWrite(line);
}

void Write(const char* format, ...) {
    std::string line = Timestamp();
    va_list args;
    va_start(args, format);
    line += VFormat(format, args);
    va_end(args);
    line += "\r\n";
    g_log.Write(line);
    if (g_echo) {
        DWORD written = 0;
        WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), line.data(), static_cast<DWORD>(line.size()), &written, nullptr);
    }
}

}  // namespace log

}  // namespace sifucraft
