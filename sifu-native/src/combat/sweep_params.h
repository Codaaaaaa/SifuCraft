#pragma once
#include <algorithm>
#include <array>
#include <cstring>
#include <span>
#include "ue/types.h"

namespace sifucraft::combat::motion {
struct SweepParams {
    enum Field { Location, Rotation, Sweep, Hit, Teleport, Return, Count };
    int size = 0;
    std::array<int, Count> offset{-1,-1,-1,-1,-1,-1};
    static constexpr std::array<int, Count> widths{12,12,1,gen::size::FHitResult,1,1};
    bool valid() const {
        if (size <= 0 || size > 512) return false;
        for(int i=0;i<Count;++i) {
            if(offset[i]<0 || offset[i]>size-widths[i]) return false;
            for(int j=0;j<i;++j) if(offset[i]<offset[j]+widths[j] && offset[j]<offset[i]+widths[i]) return false;
        }
        return true;
    }
    bool Encode(std::span<std::uint8_t> bytes, const ue::FVector& location, const ue::FRotator& rotation) const {
        if(!valid() || bytes.size()<static_cast<std::size_t>(size)) return false;
        std::fill_n(bytes.begin(),size,0);
        std::memcpy(bytes.data()+offset[Location],&location,sizeof(location));
        std::memcpy(bytes.data()+offset[Rotation],&rotation,sizeof(rotation));
        bytes[offset[Sweep]]=1; // bTeleport remains false, rotation is preserved in all axes.
        return true;
    }
};
}  // namespace sifucraft::combat::motion
