#pragma once
#include <array>
#include <cstring>
#include "sifucraft_world_motion.h"

namespace sifucraft::world_motion {
// Game-thread owned. Reset only when the bound peer session changes. Never evict in-flight work.
class Ledger {
public:
    enum class Decision { Fresh,Pending,Replay,Conflict,Stale,Full,Invalid };
    struct Result { Decision decision;Reply reply{}; };
    static constexpr std::size_t kCapacity=128;
    Result Begin(const Command& command){
        if(!Valid(command))return {Decision::Invalid};
        for(const auto& old:records_)if(old.used&&old.command.id==command.id){
            if(std::memcmp(&old.command,&command,sizeof(command))!=0)return {Decision::Conflict};
            return old.complete?Result{Decision::Replay,old.reply}:Result{Decision::Pending};
        }
        if(command.id<=highest_)return {Decision::Stale};
        Saved* destination=nullptr;
        for(auto& old:records_){
            if(!old.used){destination=&old;break;}
            if(old.complete&&(!destination||old.command.id<destination->command.id))destination=&old;
        }
        if(!destination)return {Decision::Full};
        *destination={true,false,command,{}};highest_=command.id;return {Decision::Fresh};
    }
    bool Finish(std::uint64_t id,const Reply& reply){
        if(!Valid(reply)||reply.id!=id)return false;
        for(auto& old:records_)if(old.used&&old.command.id==id){
            if(old.complete)return std::memcmp(&old.reply,&reply,sizeof(reply))==0;
            if(reply.world!=old.command.world||reply.target!=old.command.target||reply.spawn!=old.command.spawn||reply.source_hash!=old.command.payload_hash)return false;
            old.reply=reply;old.complete=true;return true;
        }
        return false;
    }
    void Reset(){*this={};}
private:
    struct Saved { bool used=false,complete=false;Command command{};Reply reply{}; };
    std::array<Saved,kCapacity> records_{};
    std::uint64_t highest_=0;
};
}
