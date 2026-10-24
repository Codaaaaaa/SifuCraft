#include "ue/names.h"

#include <windows.h>

#include <unordered_map>

#include "core/safe_memory.h"

namespace sifucraft::ue {

namespace {

namespace off = gen::off;
namespace bits = gen::bits::FNameEntryHeader;

// FNameEntryHandle: high bits select the block, low 16 bits the 2-byte-aligned slot in it.
constexpr std::uint32_t kBlockOffsetBits = 16;
constexpr std::uint32_t kEntryStride = 2;
constexpr std::uint32_t kMaxBlocks =
    (gen::size::FNameEntryAllocator - off::FNameEntryAllocator::Blocks) / sizeof(std::uintptr_t);

std::uintptr_t g_allocator = 0;
// Shared by the runtime thread and the game thread (input hooks). Elements never move once
// inserted (node-based map), so returned references stay valid without holding the lock.
std::unordered_map<std::uint32_t, std::string> g_cache;
SRWLOCK g_cache_lock = SRWLOCK_INIT;
const std::string kEmpty;

bool Decode(std::uint32_t index, std::string* out) {
    const std::uint32_t block = index >> kBlockOffsetBits;
    const std::uint32_t slot = index & ((1u << kBlockOffsetBits) - 1);
    if (block >= kMaxBlocks) return false;
    if (block > mem::ReadOr<std::uint32_t>(g_allocator + off::FNameEntryAllocator::CurrentBlock, 0)) {
        return false;
    }
    const std::uintptr_t block_base =
        mem::ReadPtr(g_allocator + off::FNameEntryAllocator::Blocks + block * sizeof(std::uintptr_t));
    if (!block_base) return false;

    const std::uintptr_t entry = block_base + slot * kEntryStride;
    std::uint16_t header = 0;
    if (!mem::Read(entry + off::FNameEntry::Header + bits::Len_Offset, &header)) return false;
    const bool wide = (header >> bits::bIsWide_Shift) & 1;
    const std::uint32_t length = (header >> bits::Len_Shift) & ((1u << bits::Len_Width) - 1);
    if (length == 0) return false;

    if (!wide) {
        out->resize(length);
        return mem::Copy(out->data(), entry + off::FNameEntry::AnsiName, length);
    }
    std::wstring text(length, L'\0');
    if (!mem::Copy(text.data(), entry + off::FNameEntry::WideName, length * sizeof(wchar_t))) return false;
    const int bytes = WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(length), nullptr, 0,
                                          nullptr, nullptr);
    out->resize(static_cast<std::size_t>(bytes));
    WideCharToMultiByte(CP_UTF8, 0, text.data(), static_cast<int>(length), out->data(), bytes, nullptr,
                        nullptr);
    return true;
}

}  // namespace

bool InitNames(std::uintptr_t image_base) {
    g_allocator = image_base + gen::rva::NamePoolData + off::FNamePool::Entries;
    std::string none;
    if (!Decode(0, &none) || none != "None") {
        g_allocator = 0;
        return false;
    }
    g_cache.clear();
    return true;
}

const std::string& NameEntry(std::uint32_t index) {
    if (!g_allocator) return kEmpty;
    AcquireSRWLockShared(&g_cache_lock);
    const auto found = g_cache.find(index);
    const std::string* cached = found != g_cache.end() ? &found->second : nullptr;
    ReleaseSRWLockShared(&g_cache_lock);
    if (cached) return *cached;

    std::string text;
    if (!Decode(index, &text)) return kEmpty;  // not cached: the slot may simply not be written yet
    AcquireSRWLockExclusive(&g_cache_lock);
    const std::string& entry = g_cache.emplace(index, std::move(text)).first->second;
    ReleaseSRWLockExclusive(&g_cache_lock);
    return entry;
}

std::string NameToString(FName name) {
    std::string text = NameEntry(name.comparison_index);
    if (!text.empty() && name.number > 0) text += "_" + std::to_string(name.number - 1);
    return text;
}

}  // namespace sifucraft::ue
