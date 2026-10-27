#pragma once
#include <unordered_map>
#include "combat/world_motion_engine.h"

namespace sifucraft::world_motion {
// Survives MC-peer reconnects. A new command id cannot replay one blast's HP or motion.
class ExplosionLedger {
    struct Key {
        std::uint64_t save,map,hi,lo;
        std::uint32_t target,spawn;
        bool operator==(const Key&)const=default;
    };
    struct KeyHash {std::size_t operator()(const Key& key)const{
        const std::uint64_t words[]={key.save,key.map,key.hi,key.lo,key.target,key.spawn};
        return static_cast<std::size_t>(Hash(words,sizeof(words)));
    }};
    struct Entry {std::uint64_t type;float amount;Effect effect;bool complete=false,motion=false;};
public:
    enum class Decision { Fresh,Cached,Conflict,Full };
    void World(std::uint64_t native,std::uint32_t world){
        if(!native||!world)return;
        if(native!=native_||world!=world_){entries_.clear();native_=native;world_=world;}
    }
    static bool DamageType(std::uint64_t type){return type==EffectTag("minecraft:explosion")||type==EffectTag("minecraft:player_explosion");}
    Decision Begin(const Command& c,Effect* cached){
        auto at=entries_.find(Of(c));
        if(at!=entries_.end()){
            const auto& old=at->second;
            if(old.type!=c.action||old.amount!=c.amount)return Decision::Conflict;
            if(!old.complete)return Decision::Full;
            *cached=old.effect;cached->invoked=false;return Decision::Cached;
        }
        if(entries_.size()>=65536)return Decision::Full;
        entries_.emplace(Of(c),Entry{c.action,c.amount,{}});return Decision::Fresh;
    }
    void Complete(const Command& c,const Effect& effect){
        auto at=entries_.find(Of(c));if(at==entries_.end())return;
        at->second.effect=effect;at->second.complete=true;
    }
    bool Motion(const Command& c,std::int32_t actor_serial){
        auto at=entries_.find(Of(c));if(at==entries_.end())return false;
        auto& old=at->second;
        if(!old.complete||old.motion||old.effect.serial!=actor_serial||old.effect.health!=c.health||
            (old.effect.status!=Status::Applied&&old.effect.status!=Status::Blocked))return false;
        old.motion=true;return true;
    }
    std::size_t size()const{return entries_.size();}
private:
    static Key Of(const Command& c){return {c.save,c.map,c.source_hi,c.source_lo,c.target,c.spawn};}
    std::uint64_t native_=0;std::uint32_t world_=0;
    std::unordered_map<Key,Entry,KeyHash> entries_;
};
}
