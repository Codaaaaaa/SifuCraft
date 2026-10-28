#pragma once
#include <array>
#include <cstdint>
#include <cmath>

namespace sifucraft::combat {
// One native pushed-fall landing can follow the existing Light reaction after
// an MC blast releases a passenger. MC already owns this fall's settlement.
// This bounded proof never exempts an ordinary combat hit or another actor.
class PassengerBlastLanding {
public:
    struct Binding {
        std::uint64_t world=0,peer=0,actor=0,health=0;
        std::uint32_t generation=0,spawn=0;
        std::int32_t actor_serial=0,health_serial=0;
        bool operator==(const Binding&)const=default;
    };
    bool Arm(Binding binding,float expected,std::uint64_t now){
        if(!binding.world||!binding.peer||!binding.actor||!binding.health||!binding.generation||!binding.spawn||
           binding.actor_serial<=0||binding.health_serial<=0||!std::isfinite(expected)||expected<=0)return false;
        for(auto& proof:proofs_)if(proof.binding==binding){proof={binding,expected,now,now+2000};return true;}
        for(auto& proof:proofs_)if(!proof.until||now>proof.until){proof={binding,expected,now,now+2000};return true;}
        return false;
    }
    bool Pending(const Binding& binding,float health,std::uint64_t now)const{
        for(const auto& proof:proofs_)if(proof.until&&now>=proof.at&&now<=proof.until&&proof.binding==binding&&proof.health==health)return true;
        return false;
    }
    bool Consume(const Binding& binding,float health,std::uint64_t now,bool pushed_landing,bool unowned){
        if(!pushed_landing||!unowned)return false;
        for(auto& proof:proofs_)if(proof.until&&now>=proof.at&&now<=proof.until&&proof.binding==binding&&proof.health==health){proof.until=0;return true;}
        return false;
    }
    void Clear(){proofs_={};}
private:
    struct Proof {Binding binding;float health=0;std::uint64_t at=0,until=0;};
    std::array<Proof,16> proofs_{};
};
}
