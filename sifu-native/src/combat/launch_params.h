#pragma once
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <span>
#include "sifucraft_protocol.h"

namespace sifucraft::combat {
struct LaunchParams {
    int size = 0, vector = -1, xy = -1, z = -1;
    std::uint8_t xy_mask = 0, z_mask = 0;
    bool valid() const {
        const auto bit = [](unsigned n) { return n && (n & (n - 1)) == 0; };
        return size >= 14 && size <= 64 && vector >= 0 && vector <= size - 12 &&
            xy >= 0 && xy < size && z >= 0 && z < size && bit(xy_mask) && bit(z_mask) &&
            (xy < vector || xy >= vector + 12) && (z < vector || z >= vector + 12) &&
            (xy != z || !(xy_mask & z_mask));
    }
    bool Encode(std::span<std::uint8_t> buffer, const protocol::Vec3& velocity) const {
        if (!valid() || buffer.size() != static_cast<std::size_t>(size)) return false;
        std::fill(buffer.begin(), buffer.end(), 0);
        std::memcpy(buffer.data() + vector, &velocity, sizeof(velocity));
        buffer[xy] |= xy_mask; // bXYOverride=true; bZOverride=false
        return true;
    }
};
}
