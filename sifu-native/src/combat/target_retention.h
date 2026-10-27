#pragma once
#include <array>
#include <cstdint>
#include <vector>

namespace sifucraft::world_motion {
// Callers serialize access. Publication membership never grants actor authority.
// Active leases cannot be evicted; a non-renewable grace spans short handoffs.
class TargetRetention {
public:
    static constexpr std::size_t kCapacity=64;
    static constexpr std::uint64_t kGraceMs=600;
    bool Keep(std::uint32_t world,std::uint32_t id,std::uint32_t spawn,std::uint64_t now){
        if(!world||!id||!spawn)return false;
        const auto key=Key(id,spawn);
        for(auto& entry:entries_)if(entry.world==world&&entry.key==key){entry.until=0;return true;}
        for(auto& entry:entries_)if(!entry.key||(entry.until&&now>=entry.until)){entry={world,key,0};return true;}
        return false;
    }
    void Release(std::uint32_t world,std::uint32_t id,std::uint32_t spawn,std::uint64_t now){
        for(auto& entry:entries_)if(entry.world==world&&entry.key==Key(id,spawn)&&!entry.until)entry.until=now+kGraceMs;
    }
    std::vector<std::uint64_t> Snapshot(std::uint32_t world,std::uint64_t now)const{
        std::vector<std::uint64_t> result;
        for(const auto& entry:entries_)if(entry.world==world&&entry.key&&(!entry.until||now<entry.until))result.push_back(entry.key);
        return result;
    }
    std::vector<std::uint64_t> Active(std::uint32_t world)const{
        std::vector<std::uint64_t> result;for(const auto& entry:entries_)if(entry.world==world&&entry.key&&!entry.until)result.push_back(entry.key);return result;
    }
    static std::uint64_t Key(std::uint32_t id,std::uint32_t spawn){return (std::uint64_t(id)<<32)|spawn;}
    void Reset(){entries_={};}
private:
    struct Entry {std::uint32_t world=0;std::uint64_t key=0,until=0;};
    std::array<Entry,kCapacity> entries_{};
};
}
