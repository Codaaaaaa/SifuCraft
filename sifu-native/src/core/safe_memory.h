#pragma once

#include <cstddef>
#include <cstdint>
#include <type_traits>

// Fault-tolerant reads of game memory from a non-game thread. An access violation inside
// Copy() is caught by a vectored handler and turned into a `false` return.
namespace sifucraft::mem {

bool Install();
std::uint64_t FaultCount();

bool Copy(void* dst, std::uintptr_t src, std::size_t size);

template <typename T>
bool Read(std::uintptr_t address, T* out) {
    static_assert(std::is_trivially_copyable_v<T>);
    return Copy(out, address, sizeof(T));
}

template <typename T>
T ReadOr(std::uintptr_t address, T fallback = T{}) {
    T value;
    return Read(address, &value) ? value : fallback;
}

inline std::uintptr_t ReadPtr(std::uintptr_t address) { return ReadOr<std::uintptr_t>(address, 0); }

// Raw header of a UE TArray.
struct RawArray {
    std::uintptr_t data = 0;
    std::int32_t num = 0;
    std::int32_t max = 0;
};

// Reads and sanity-checks a TArray header (0 <= num <= max, bounded size).
bool ReadArray(std::uintptr_t address, RawArray* out);

}  // namespace sifucraft::mem
