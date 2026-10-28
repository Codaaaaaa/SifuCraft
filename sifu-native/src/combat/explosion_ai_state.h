#pragma once
#include <cstdint>

namespace sifucraft::world_motion {
struct ExplosionAiState {
    std::uint8_t out_idle=255,force=255,running=0,paused=1;
    bool eligible()const{return out_idle<=1&&force==0&&running==1&&paused==0;}
    bool retains(const ExplosionAiState& before)const{return eligible()&&before.eligible()&&out_idle==before.out_idle;}
};
}
