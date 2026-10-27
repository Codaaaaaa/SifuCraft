#pragma once
#include <cmath>

namespace sifucraft::world_motion {
// MC-owned pose translation is not native falling. Move the absolute native
// fall origin with that translation, preserving any pre-existing fall distance.
inline bool TranslateFallOrigin(float initial,float before,float after,float* result){
    if(!result||!std::isfinite(initial)||!std::isfinite(before)||!std::isfinite(after))return false;
    const double translated=static_cast<double>(initial)+static_cast<double>(after)-before;
    if(!std::isfinite(translated)||std::fabs(translated)>1e9)return false;
    *result=static_cast<float>(translated);return std::isfinite(*result);
}
}
