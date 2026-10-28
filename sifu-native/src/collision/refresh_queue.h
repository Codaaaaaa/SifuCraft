#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <deque>
#include <optional>

namespace sifucraft::collision {
// Finite coordinate history, no UObject pointers. Local refresh and remote requests share the 2Hz query budget.
class RefreshQueue {
    struct Region {std::array<float,3> center;std::uint64_t at;};
    std::deque<Region> regions_;
public:
    void Reset(){regions_.clear();}
    void Done(std::array<float,3> center,std::uint64_t at){
        for(auto it=regions_.begin();it!=regions_.end();)if(it->center==center)it=regions_.erase(it);else ++it;
        regions_.push_back({center,at});while(regions_.size()>64)regions_.pop_front();
    }
    std::optional<std::array<float,3>> Due(std::uint64_t now)const{
        if(regions_.empty()||now<regions_.front().at||now-regions_.front().at<5000)return std::nullopt;
        return regions_.front().center;
    }
};
}
