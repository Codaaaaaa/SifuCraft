#pragma once
#include <cstdint>
namespace sifucraft::blocks {
// GUObjectArray allocates a nonzero serial lazily. The caller must first validate the live
// pointer/index and unique object name. Class, owner and world must also pass before engine calls.
inline bool MatchOwnedSerial(std::int32_t index, std::int32_t& serial,
                             std::int32_t current_index, std::int32_t current_serial) {
    if (index < 0 || index != current_index || serial < 0 || current_serial < 0 ||
        (serial != 0 && serial != current_serial)) return false;
    if (serial == 0) serial = current_serial;
    return true;
}
}
