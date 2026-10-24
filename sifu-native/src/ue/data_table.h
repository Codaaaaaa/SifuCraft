#pragma once

#include "core/safe_memory.h"
#include "ue/types.h"

namespace sifucraft::ue {

// Read-only linear lookup in UE's sparse row map. Allocation bits distinguish live entries
// from free slots; all container offsets and the slot stride come from the matching PDB.
inline std::uintptr_t NamePointerMapValue(std::uintptr_t map, FName name) {
    namespace off = gen::off;
    if (!map || (!name.comparison_index && !name.number)) return 0;
    const auto sparse = map + off::DataTableMap::Pairs + off::DataTableSet::Elements;
    mem::RawArray data;
    if (!mem::ReadArray(sparse + off::DataTableSparse::Data, &data) || data.num > 16384) return 0;
    const auto bits = sparse + off::DataTableSparse::AllocationFlags;
    const auto allocator = bits + off::DataTableBits::AllocatorInstance;
    const int num = mem::ReadOr<int>(bits + off::DataTableBits::NumBits, -1);
    const int max = mem::ReadOr<int>(bits + off::DataTableBits::MaxBits, -1);
    if (num != data.num || max < num || max > 1048576) return 0;
    const auto secondary = mem::ReadPtr(allocator + off::DataTableBitAllocator::SecondaryData);
    const auto flags = secondary ? secondary : allocator + off::DataTableBitAllocator::InlineData;
    // The default bit allocator's inline capacity is four 32-bit words.
    if (!secondary && max > static_cast<int>((off::DataTableBitAllocator::SecondaryData - off::DataTableBitAllocator::InlineData) * 8)) return 0;
    for (int i = 0; i < data.num; ++i) {
        std::uint32_t word = 0;
        if (!mem::Read(flags + static_cast<std::size_t>(i / 32) * sizeof(word), &word)) return 0;
        if (!(word & (1u << (i % 32)))) continue;
        const auto pair = data.data + static_cast<std::size_t>(i) * gen::size::DataTableElement + off::DataTableElement::Value;
        FName key{};
        if (!mem::Read(pair + off::DataTablePair::Key, &key)) return 0;
        if (key.comparison_index == name.comparison_index && key.number == name.number) {
            return mem::ReadPtr(pair + off::DataTablePair::Value);
        }
    }
    return 0;
}

inline std::uintptr_t DataTableRow(std::uintptr_t table, FName name) {
    return table ? NamePointerMapValue(table + gen::off::UDataTable::RowMap, name) : 0;
}

}  // namespace sifucraft::ue
