#pragma once
#include <array>
#include <cstring>
#include "sifucraft_protocol.h"
#include "bridge/world_motion_mailbox.h"
#include "bridge/shape_reader.h"
#include "combat/world_motion_engine.h"
#include "core/log.h"

// Test-only AABB endpoint. It runs the SAME arbiter/mailbox as Native, never game actor APIs.
namespace sifucraft::world_motion::fake {
class Harness {
    struct Actor {std::uint32_t id=0,spawn=1;Body body;};
    class Endpoint final:public Backend {
    public:
        Harness* owner=nullptr;Actor* actor=nullptr;
        bool Bind(const Command& command,Body* out)override{
            for(auto& candidate:owner->actors_)if(candidate.id==command.target&&candidate.spawn==command.spawn){actor=&candidate;*out=actor->body;return true;}
            return false;
        }
        bool Check(Body* out)override{if(!actor)return false;*out=actor->body;return true;}
        Sweep Move(const Body& before,const std::array<float,3>& delta)override{
            int axis=0;while(axis<2&&delta[axis]==0)++axis;float amount=delta[axis];bool penetrating=false;
            float lo[3],hi[3];for(int i=0;i<3;++i){const float half=i==2?before.half:before.radius;lo[i]=before.position[i]-half;hi[i]=before.position[i]+half;}
            const auto clip=[&](const float* min,const float* max){
                for(int i=0;i<3;++i)if(i!=axis&&(hi[i]<=min[i]+.001f||lo[i]>=max[i]-.001f))return;
                if(hi[axis]>min[axis]+.001f&&lo[axis]<max[axis]-.001f){penetrating=true;amount=0;return;}
                if(amount>0&&hi[axis]<=min[axis]+.001f)amount=std::min(amount,std::max(0.f,min[axis]-hi[axis]));
                else if(amount<0&&lo[axis]>=max[axis]-.001f)amount=std::max(amount,std::min(0.f,max[axis]-lo[axis]));
            };
            const float floor_min[]{-100000,-100000,-100},floor_max[]{100000,100000,0};clip(floor_min,floor_max);
            if(owner->shapes_)for(const auto& section:owner->shapes_->parts)for(const auto& box:section.boxes){
                const float offset[]{section.section.x*100.f,section.section.y*100.f,section.section.z*100.f};
                float min[3],max[3];for(int i=0;i<3;++i){min[i]=offset[i]+box.min[i];max[i]=offset[i]+box.max[i];}clip(min,max);
            }
            actor->body.position[axis]+=amount;
            return {actor->body,true,true,amount==delta[axis],amount!=delta[axis],penetrating};
        }
        void Forget()override{actor=nullptr;}
    };
    std::array<Actor,2> actors_{};
    std::array<Endpoint,2> backends_{};
    std::array<Backend*,2> Pointers(){std::array<Backend*,2> out{};for(unsigned i=0;i<2;++i){backends_[i].owner=this;out[i]=&backends_[i];}return out;}
    Fleet<2> fleet_{Pointers()};Mailbox mailbox_;
    const shape_data::Frame* shapes_=nullptr;
    bool initialized_=false;unsigned calls_=0;
    std::deque<Reply> replies_;
public:
    void Initialize(const protocol::Vec3& player){
        if(initialized_)return;
        initialized_=true;
        for(unsigned i=0;i<2;++i){auto& a=actors_[i];a.id=101+i;a.body={static_cast<int>(1001+i),818+i,{player.x+450,player.y+50+i*200,92},60,35,92};}
    }
    void Tick(std::uint64_t native,std::uint64_t mc,std::uint64_t frame,std::uint32_t world,std::uint64_t now,const shape_data::Frame& shapes){
        shapes_=&shapes;const bool ready=mc&&shapes.header.revision&&shapes.source_mc==mc&&shapes.header.world==world;
        const auto map=ready?shape_data::Hash(shapes.header.map,std::strlen(shapes.header.map)):0;
        Environment env{native,mc,ready?shapes.header.save_key:0,map,ready?shapes.header.revision:0,frame,world,0,ready,true};
        fleet_.Update(env,now);
        for(unsigned i=0;i<2;++i)for(auto part:{1u,2u,4u})fleet_.at(i).Original(part); // simulated original ticks, never UE hooks
        fleet_.Update(env,now);
        if(!mc){mailbox_.Close();replies_.clear();return;}
        if(!mailbox_.Open(mc,native))return;
        mailbox_.Status(world,ready?kMotionCapability:0,now,73,env.revision);
        while(!replies_.empty()&&mailbox_.Write(replies_.front()))replies_.pop_front();
        if(replies_.size()<32&&mailbox_.CanReply())for(unsigned n=0;n<8;++n){
            Command command;bool valid;if(!mailbox_.Read(&command,&valid))break;
            if(valid){if(command.op==Op::Step)++calls_;fleet_.Submit(command,now);}
        }
        fleet_.Update(env,now);Reply reply;
        while(replies_.size()<64&&fleet_.Pop(&reply)){SC_LOG("[fake-motion] reply id=%llu lease=%llu target=%u/%u status=%u pos=(%.3f,%.3f,%.3f) delta=(%.3f,%.3f,%.3f) restored=%u",reply.id,reply.lease,reply.target,reply.spawn,static_cast<unsigned>(reply.status),reply.position[0],reply.position[1],reply.position[2],reply.delta[0],reply.delta[1],reply.delta[2],reply.restored);replies_.push_back(reply);}
        while(!replies_.empty()&&mailbox_.Write(replies_.front()))replies_.pop_front();
    }
    std::vector<protocol::EnemyEntry> Entries()const{
        std::vector<protocol::EnemyEntry> out;
        for(const auto& a:actors_)out.push_back({a.id,a.spawn,{a.body.position[0],a.body.position[1],a.body.position[2]},180,a.body.radius,a.body.half,a.body.health,60,
            protocol::kEnemyAlive|protocol::kEnemyCanDie,1,{},0});
        return out;
    }
};
}
