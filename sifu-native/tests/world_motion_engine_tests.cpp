#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "combat/world_motion_engine.h"
using namespace sifucraft::world_motion;
namespace {
unsigned checks=0;
void Require(bool value,const char* label){++checks;if(!value){std::fprintf(stderr,"FAIL %s\n",label);std::exit(1);}}
struct Fake final:Backend {
    Body body{19,818,{0,0,90},60,30,90};
    int binds=0,moves=0,forgets=0;bool valid=true,bind=true,block=false,penetrate=false,returned=true,read=true;
    float fraction=1;
    bool Bind(const Command&,Body* out)override{++binds;*out=body;return bind;}
    bool Check(Body* out)override{*out=body;return valid;}
    Sweep Move(const Body&,const std::array<float,3>& d)override{++moves;for(int i=0;i<3;++i)body.position[i]+=d[i]*fraction;return {body,true,read,returned,block,penetrate};}
    void Forget()override{++forgets;}
};
struct Case {
    Fake backend;Engine engine{backend};Environment env{10,20,30,40,50,1,2,0,true};
    std::uint64_t now=1000,next=1;
    Case(){engine.Update(env,now);}
    Command CommandFor(Op op=Op::Reserve){
        Command c{};c.op=op;c.source=Source::Machine;c.id=next++;c.lease=op==Op::Reserve?0:1;c.issued_ms=now;
        c.source_hi=3;c.source_lo=4;c.action=5;c.tick=6;c.save=30;c.map=40;c.shape_revision=env.revision;c.world=2;c.target=7;c.spawn=8;
        c.duration_ms=500;c.position[2]=90;c.health=60;c.radius=30;c.half_height=90;c.state_hash=77;c.section_revision=88;
        if(op!=Op::Reserve){c.actor_stamp=19;c.component_token=818;for(int i=0;i<3;++i)c.position[i]=backend.body.position[i];}
        if(op==Op::Step)c.delta[0]=10;
        if(op==Op::Damage)c.amount=5;
        Seal(c);return c;
    }
    void Send(Command c){Seal(c);Require(engine.Submit(c,now),"submit accepted into bounded arbiter");}
    Reply Pop(Status expected){Reply r{};Require(engine.Pop(&r),"one receipt present");Require(Valid(r),"receipt ABI/hash");Require(r.status==expected,"receipt status");return r;}
    void Empty(){Reply r;Require(!engine.Pop(&r),"no premature/duplicate receipt");}
    void Tick(){engine.Update(env,now);}
    void Acquire(){Send(CommandFor());Empty();Require(!engine.Skip(now),"preflight forwards movement");for(auto part:{1u,2u,4u})engine.Original(part);Tick();auto r=Pop(Status::Prepared);Require(r.actor_serial==19&&r.component_token==818&&r.lease==1,"native binding");Require(engine.Skip(now),"lease skips only after preflight");}
    void Restore(){for(auto part:{1u,2u,4u})engine.Original(part);Tick();Require(engine.phase()==Engine::Phase::Free,"all originals restored before reuse");}
};
void HandshakeAndReplay(){
    Case c;auto reserve=c.CommandFor();c.Send(reserve);c.Send(reserve);c.Empty();
    c.engine.Original(1);c.engine.Original(2);c.Tick();c.Empty();Require(!c.engine.Skip(c.now),"missing BT keeps forwarding");
    c.engine.Original(4);c.Tick();auto r=c.Pop(Status::Prepared);c.Send(reserve);auto replay=c.Pop(Status::Prepared);
    Require(!std::memcmp(&r,&replay,sizeof(r)),"reserve exact retry byte-for-byte replay");Require(c.backend.binds==1,"duplicate reserve binds once");
    auto step=c.CommandFor(Op::Step);c.Send(step);r=c.Pop(Status::Applied);Require(r.delta[0]==10&&r.position[0]==10&&c.backend.moves==1,"observed sweep reply");
    c.Send(step);replay=c.Pop(Status::Applied);Require(!std::memcmp(&r,&replay,sizeof(r))&&c.backend.moves==1,"step retry never moves twice");
    step.delta[0]=5;c.Send(step);c.Pop(Status::Conflict);Require(c.backend.moves==1,"same id different delta rejected");
    auto release=c.CommandFor(Op::Release);c.env.revision++;c.Tick();c.Send(release);c.Pop(Status::Stale);Require(c.engine.Skip(c.now),"old release revision does not discard lease");
    release=c.CommandFor(Op::Release);c.Send(release);c.Empty();Require(!c.engine.Skip(c.now),"release forwards all originals immediately");
    c.engine.Original(1);c.engine.Original(2);c.Tick();c.Empty();Require(c.engine.phase()==Engine::Phase::Releasing,"release waits for behavior original");
    c.engine.Original(4);c.Tick();r=c.Pop(Status::Released);Require(r.restored==7&&r.position[0]==10,"release confirms original mask and actual pose");
    Require(c.engine.phase()==Engine::Phase::Free&&c.backend.body.position[0]==10,"release never rewinds pose");c.Send(release);c.Pop(Status::Released);
}
void BoundsAndInvalidation(){
    for(int mutation=0;mutation<13;++mutation){
        Case c;c.Acquire();auto step=c.CommandFor(Op::Step);
        switch(mutation){
        case 0:step.actor_stamp++;break;case 1:step.component_token++;break;case 2:step.spawn++;break;
        case 3:step.action++;break;case 4:step.source_hi++;break;case 5:step.shape_revision++;break;
        case 6:step.save++;break;case 7:step.map++;break;case 8:step.world++;break;
        case 9:step.position[1]+=2;break;case 10:step.health-=1;break;
        case 11:step.block[1]++;break;case 12:step.state_hash++;break;}
        c.Send(step);c.Pop(Status::Stale);Require(c.backend.moves==0,"scope/binding/pose mismatch never invokes engine");
    }
    for(int axis=0;axis<3;++axis)for(float sign:{-1.f,1.f}){
        Case c;c.Acquire();auto step=c.CommandFor(Op::Step);step.delta[0]=0;step.delta[axis]=10*sign;c.Send(step);
        auto r=c.Pop(Status::Applied);Require(r.delta[axis]==10*sign,"all six directions return actual displacement");
    }
    Case c;c.Acquire();for(int i=0;i<10;++i){c.Send(c.CommandFor(Op::Step));c.Pop(Status::Applied);}
    auto step=c.CommandFor(Op::Step);step.delta[0]=1;c.Send(step);c.Pop(Status::Applied);
    step=c.CommandFor(Op::Step);step.delta[0]=0.01f;c.Send(step);c.Pop(Status::Invalid);
    Require(c.backend.moves==11&&c.backend.body.position[0]==101,"101cm contact cap books requested motion");
}
void SweepFailures(){
    for(int failure=0;failure<6;++failure){
        Case c;c.Acquire();
        switch(failure){case 0:c.backend.block=true;c.backend.fraction=0;break;
        case 1:c.backend.block=true;c.backend.fraction=.25f;break;case 2:c.backend.fraction=.5f;break;
        case 3:c.backend.returned=false;break;case 4:c.backend.penetrate=true;break;case 5:c.backend.read=false;break;}
        auto step=c.CommandFor(Op::Step);c.Send(step);auto r=c.Pop(failure==0?Status::Blocked:failure>=4?Status::TargetLost:Status::Partial);
        Require(r.delta[0]==10*c.backend.fraction,"failure still reports actual engine readback");
        Require(!c.engine.Skip(c.now),"blocked/partial/penetrating lease immediately releases");
        c.Send(step);c.Pop(r.status);Require(c.backend.moves==1,"failed sweep retry not reapplied");c.Restore();
    }
}
void Lifetime(){
    for(int failure=0;failure<7;++failure){
        Case c;c.Acquire();
        switch(failure){case 0:c.now+=500;break;case 1:c.env.flags=kPaused;break;case 2:c.env.ready=false;break;
        case 3:c.backend.body.health-=1;break;case 4:c.backend.body.position[0]+=2;break;
        case 5:c.backend.valid=false;break;case 6:c.env.world++;break;}
        c.Tick();Require(!c.engine.Skip(c.now),"deadline/world/pause/HP/external movement releases");c.Restore();
    }
    Case c;c.Acquire();c.now+=500;Require(!c.engine.Skip(c.now),"callback independently enforces absolute deadline");c.Restore();
    Case pre;auto reserve=pre.CommandFor();pre.Send(reserve);pre.engine.Original(1);pre.now+=500;pre.Tick();pre.Pop(Status::TimedOut);
    Require(pre.backend.moves==0&&!pre.engine.Skip(pre.now),"preflight timeout has no movement or active filter");pre.Restore();
    Case lost;lost.Acquire();lost.engine.Lost();lost.Tick();Require(lost.engine.phase()==Engine::Phase::Free,"destroyed identity needs no impossible old Tick");
    Case peer;peer.Acquire();auto step=peer.CommandFor(Op::Step);peer.Send(step);peer.Pop(Status::Applied);peer.env.peer++;peer.Tick();
    Require(peer.engine.phase()==Engine::Phase::Free&&!peer.engine.Skip(peer.now),"peer reset clears filter and activation binding");
    peer.Send(step);peer.Pop(Status::Stale);Require(peer.backend.moves==1,"new peer cannot reuse old lease");
    Case pending;pending.Acquire();auto cancel=pending.CommandFor(Op::Cancel);pending.Send(cancel);pending.Empty();pending.Restore();pending.Pop(Status::Cancelled);
    Case delayed;delayed.Acquire();auto release=delayed.CommandFor(Op::Release);delayed.Send(release);delayed.engine.Original(1);delayed.now+=1000;delayed.Tick();
    auto r=delayed.Pop(Status::TimedOut);Require(r.restored==1&&delayed.engine.phase()==Engine::Phase::Free,"missing original never reports restored and cannot deadlock admission");
}
void Capacity(){
    Case c;unsigned accepted=0;
    for(int i=0;i<100;++i){auto command=c.CommandFor(Op::Damage);if(!c.engine.Submit(command,c.now))break;++accepted;}
    Require(accepted==60,"outbox bounded with deferred receipt reserve");
    for(unsigned i=0;i<accepted;++i)c.Pop(Status::Unsupported);
    c.Empty();
    auto command=c.CommandFor(Op::Damage);Require(c.engine.Submit(command,c.now),"draining receipts resumes admission");c.Pop(Status::Unsupported);
}
}
int main(){HandshakeAndReplay();BoundsAndInvalidation();SweepFailures();Lifetime();Capacity();std::printf("world motion arbiter PASS %u assertions\n",checks);}
