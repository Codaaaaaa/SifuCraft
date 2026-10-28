#pragma once
#include <array>
#include "combat/world_motion_engine.h"

namespace sifucraft::world_motion {
// A confirmed blast may finish its Native hit order outside idle. Only the
// same boat may reclaim the same binding after orders/montages have ended.
// This proof owns no movement and never extends an acquired movement lease.
class PassengerEffectReturn {
public:
    void Update(const Environment& env,std::uint64_t now){
        const std::array<std::uint64_t,5> scope{env.native,env.peer,env.world,env.save,env.map};
        if(scope!=scope_){proofs_={};scope_=scope;paused_=false;}
        const bool paused=(env.flags&kPaused)!=0;
        if(paused&&!paused_)for(auto& proof:proofs_)if(proof.live){
            if(now>proof.until)proof.live=false;else proof.remaining=proof.until-now;
        }
        if(!paused&&paused_)for(auto& proof:proofs_)if(proof.live){proof.at=now;proof.until=now+proof.remaining;}
        paused_=paused;
        if(!paused)for(auto& proof:proofs_)if(proof.live&&now>proof.until)proof.live=false;
    }
    bool Remember(const Command& source,std::int32_t serial,std::uint64_t token,float health,std::uint64_t now,bool fall=false){
        if(!Scoped(source)||source.source!=Source::Passenger||source.op!=Op::Reserve||source.flags||serial<=0||!token||!std::isfinite(health)||health<=0||paused_)return false;
        const auto until=now+(fall?1000:10000);
        for(auto& proof:proofs_)if(proof.live&&proof.source.target==source.target&&proof.source.spawn==source.spawn){proof={source,serial,token,health,now,until,0,true,fall};return true;}
        for(auto& proof:proofs_)if(!proof.live||now>proof.until){proof={source,serial,token,health,now,until,0,true,fall};return true;}
        return false;
    }
    bool Candidate(const Command& c,std::int32_t serial,float health,std::uint64_t now)const{
        for(const auto& proof:proofs_)if(Matches(proof,c,serial,health,now))return true;
        return false;
    }
    bool Eligible(const Command& c,std::int32_t serial,std::uint64_t token,float health,std::uint64_t now)const{
        for(const auto& proof:proofs_)if(proof.token==token&&Matches(proof,c,serial,health,now))return true;
        return false;
    }
    bool Consume(const Command& c,std::int32_t serial,std::uint64_t token,float health,std::uint64_t now){
        for(auto& proof:proofs_)if(proof.token==token&&Matches(proof,c,serial,health,now)){proof.live=false;return true;}
        return false;
    }
    void Superseded(std::uint32_t target,std::uint32_t spawn,std::int32_t serial){
        for(auto& proof:proofs_)if(proof.source.target==target&&proof.source.spawn==spawn&&proof.serial==serial)proof.live=false;
    }
private:
    struct Proof {Command source{};std::int32_t serial=0;std::uint64_t token=0;float health=0;std::uint64_t at=0,until=0,remaining=0;bool live=false,fall=false;};
    bool Scoped(const Command& c)const{return scope_[0]&&scope_[1]&&c.world==scope_[2]&&c.save==scope_[3]&&c.map==scope_[4];}
    bool Matches(const Proof& p,const Command& c,std::int32_t serial,float health,std::uint64_t now)const{
        return p.live&&!paused_&&now>=p.at&&now<=p.until&&Scoped(c)&&c.op==Op::Reserve&&!c.flags&&
            (p.fall?c.source==Source::Fluid&&c.action==EffectTag("sifucraft:passenger_fall"):c.source==Source::Passenger&&c.action==p.source.action)&&
            c.source_hi==p.source.source_hi&&c.source_lo==p.source.source_lo&&c.target==p.source.target&&c.spawn==p.source.spawn&&
            serial==p.serial&&health==p.health&&c.health==health;
    }
    std::array<std::uint64_t,5> scope_{};
    std::array<Proof,16> proofs_{};
    bool paused_=false;
};
}
