#pragma once
#include <cstdint>
#include <string_view>

namespace sifucraft::world_motion {
constexpr std::uint64_t EffectTag(std::string_view name){std::uint64_t h=14695981039346656037ull;for(unsigned char c:name){h^=c;h*=1099511628211ull;}return h;}
inline bool EnvironmentEffect(std::uint64_t tag){
    for(auto name:{"minecraft:lava","minecraft:in_fire","minecraft:on_fire","minecraft:hot_floor","minecraft:drown","minecraft:in_wall","minecraft:fall"})
        if(tag==EffectTag(name))return true;
    return false;
}
}
