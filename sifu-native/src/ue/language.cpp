#include "ue/language.h"
#include <array>
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"

namespace sifucraft::ue {
std::string ReadLanguage(std::uintptr_t base) {
    namespace off = gen::off;
    const auto store = base + gen::rva::InternationalizationStore;
    const auto instance = mem::ReadPtr(store + off::InternationalizationStore::Ptr);
    if (instance != store + off::InternationalizationStore::Data ||
        mem::ReadOr<std::uint8_t>(instance + off::FInternationalization::bIsInitialized, 0) != 1) return {};
    const auto culture = mem::ReadPtr(instance + off::FInternationalization::CurrentLanguage);
    if (!culture) return {};
    mem::RawArray name, after;
    if (!mem::ReadArray(culture + off::FCulture::CachedName, &name) || name.num < 2 || name.num > 20) return {};
    std::array<wchar_t, 20> text{};
    if (!mem::Copy(text.data(), name.data, name.num * sizeof(wchar_t)) || text[name.num - 1] != 0 ||
        mem::ReadPtr(instance + off::FInternationalization::CurrentLanguage) != culture ||
        !mem::ReadArray(culture + off::FCulture::CachedName, &after) || after.data != name.data || after.num != name.num) return {};
    std::string result;
    for (int i = 0; i < name.num - 1; ++i) {
        const wchar_t c = text[i];
        if (!((c >= L'a' && c <= L'z') || (c >= L'A' && c <= L'Z') || (c >= L'0' && c <= L'9') || c == L'-' || c == L'_')) return {};
        result += static_cast<char>(c);
    }
    return result;
}
}
