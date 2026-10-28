#pragma once
#include <algorithm>
#include <cstdint>
#include <unordered_map>
#include <vector>

namespace sifucraft::collision {
// Runtime-thread only. Every use of an opaque key still goes through the caller's identity reader.
template<class Entry,std::size_t Capacity=16384> class ChangeWatch {
    struct Saved {Entry entry;std::uint64_t signature;};
    std::unordered_map<std::uintptr_t,Saved> entries_;std::vector<std::uintptr_t> order_;std::size_t next_=0;
public:
    void Reset(){entries_.clear();order_.clear();next_=0;}
    template<class Key,class Read>bool Observe(const std::vector<Entry>& values,Key key,Read read){
        bool room=true;
        for(const auto& entry:values){const auto id=key(entry);if(entries_.contains(id))continue;
            if(entries_.size()>=Capacity){room=false;continue;}const auto signature=read(entry);if(!signature){room=false;continue;}
            entries_.emplace(id,Saved{entry,signature});order_.push_back(id);
        }return room;
    }
    template<class Read>bool Changed(Read read,std::size_t budget=1024){
        for(std::size_t n=0;n<std::min(budget,order_.size());n++){
            if(next_>=order_.size())next_=0;
            const auto& saved=entries_.at(order_[next_++]);
            if(read(saved.entry)!=saved.signature)return true;
        }return false;
    }
};
}
