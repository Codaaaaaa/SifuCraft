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
    int binds=0,moves=0,forgets=0,hurts=0;bool valid=true,bind=true,block=false,penetrate=false,returned=true,read=true,effects=false;
    float fraction=1;
    std::array<float,3> normal{};
    bool Bind(const Command&,Body* out)override{++binds;*out=body;return bind;}
    bool Check(Body* out)override{*out=body;return valid;}
    Sweep Move(const Body&,const std::array<float,3>& d)override{++moves;for(int i=0;i<3;++i)body.position[i]+=d[i]*fraction;return {body,true,read,returned,block,penetrate,normal};}
    Effect Hurt(const Command& c)override{if(!effects)return {};hurts++;body.health=std::max(0.f,body.health-c.amount*3);return {Status::Applied,true,body.serial,body.health,body.position,0};}
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
void GeometryBarrier(){
    Case c;c.Acquire();c.env.settled=false;c.Tick();Require(c.engine.Skip(c.now),"pending new geometry retains the bounded existing lease");
    c.Send(c.CommandFor(Op::Step));c.Pop(Status::Busy);Require(c.backend.moves==0,"partial collision revision cannot authorize a new move");
    c.env.settled=true;c.Tick();c.Send(c.CommandFor(Op::Step));c.Pop(Status::Applied);
    c.Send(c.CommandFor(Op::Release));c.Pop(Status::Stale);Require(c.engine.Skip(c.now),"pre-commit revision cannot authorize release");
    c.env.revision++;c.env.settled=false;c.Tick();c.Send(c.CommandFor(Op::Release));c.Pop(Status::Stale);
    c.env.settled=true;c.Tick();c.Send(c.CommandFor(Op::Release));c.Empty();c.Restore();c.Pop(Status::Released);
}
void MultipleTargets(){
    Fake a,b;b.body.serial=20;b.body.token=819;
    Fleet<2> fleet({&a,&b});Case commands;auto env=commands.env;const auto now=commands.now;
    fleet.Update(env,now);
    auto send=[&](Command c){Seal(c);Require(fleet.Submit(c,now),"multi-target bounded admission");return c;};
    auto pop=[&](Status status){Reply r;Require(fleet.Pop(&r)&&Valid(r)&&r.status==status,"multi-target final receipt");return r;};
    auto reserveA=send(commands.CommandFor());auto reserveB=commands.CommandFor();reserveB.target=8;send(reserveB);
    Require(a.binds==1&&b.binds==1,"distinct targets have distinct simultaneous bindings");
    for(auto part:{1u,2u,4u})fleet.at(0).Original(part);
    fleet.Update(env,now);auto preparedA=pop(Status::Prepared);Reply none;
    Require(preparedA.id==reserveA.id&&!fleet.Pop(&none)&&fleet.at(0).Skip(now)&&!fleet.at(1).Skip(now),"one target's original ticks cannot qualify another");
    for(auto part:{1u,2u,4u})fleet.at(1).Original(part);
    fleet.Update(env,now);pop(Status::Prepared);Require(fleet.at(1).Skip(now),"second target qualifies independently");
    auto stepA=send(commands.CommandFor(Op::Step));auto appliedA=pop(Status::Applied);
    auto stepB=commands.CommandFor(Op::Step);stepB.target=8;stepB.lease=reserveB.id;stepB.actor_stamp=20;stepB.component_token=819;send(stepB);pop(Status::Applied);
    Require(a.moves==1&&b.moves==1&&a.body.position[0]==10&&b.body.position[0]==10,"each move affects its own backend once");
    send(stepA);auto replay=pop(Status::Applied);Require(!std::memcmp(&appliedA,&replay,sizeof(replay))&&a.moves==1,"global ledger replays older target after newer target effects");
    auto changed=stepA;changed.target=8;send(changed);pop(Status::Conflict);Require(b.moves==1,"changing target cannot bypass deduplication by routing to another slot");
    auto third=commands.CommandFor();third.target=9;send(third);pop(Status::Busy);Require(a.binds==1&&b.binds==1,"full target capacity cannot displace active leases");
    auto cancel=commands.CommandFor(Op::Cancel);send(cancel);
    for(auto part:{1u,2u})fleet.at(0).Original(part);
    fleet.Update(env,now);
    Require(!fleet.Pop(&none)&&fleet.at(1).Skip(now),"releasing one target preserves another target's active control");
    fleet.at(0).Original(4);fleet.Update(env,now);auto released=pop(Status::Cancelled);
    Require(released.restored==7&&fleet.at(0).phase()==Engine::Phase::Free&&fleet.at(1).Skip(now),"only fully restored slot can be reused");
    send(reserveA);replay=pop(Status::Prepared);Require(!std::memcmp(&preparedA,&replay,sizeof(replay))&&a.binds==1,"completed reserve replay does not reacquire target");
    third=commands.CommandFor();third.target=9;third.position[0]=10;send(third);Require(a.binds==2&&fleet.at(0).phase()==Engine::Phase::Acquiring,"free slot admits a new target");
    send(stepA);pop(Status::Applied);Require(a.moves==1,"replay remains inert after its original slot was reassigned");
    env.peer++;fleet.Update(env,now);Require(fleet.at(0).phase()==Engine::Phase::Free&&fleet.at(1).phase()==Engine::Phase::Free,"peer change releases all target filters together");
}
void ReleaseObservation(){
    Case c;c.Acquire();c.Send(c.CommandFor(Op::Release));
    c.backend.body.position[0]=3; // original movement resumes before the final receipt
    c.Restore();auto r=c.Pop(Status::Released);Require(r.position[0]==3,"release reports the pose after original movement, not the cached last external step");
}
void PhysicalBinding(){
    Case c;c.backend.body.root_offset={0,0,2.5f};c.backend.body.root_radius=30;c.backend.body.root_half=88;
    c.Send(c.CommandFor());for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();auto r=c.Pop(Status::Prepared);
    Require(r.delta[2]==2.5f&&r.root_radius==30&&r.root_half_height==88,"prepared binds real root geometry separately from hit anchor");
    c.Send(c.CommandFor(Op::Step));r=c.Pop(Status::Applied);
    Require(r.delta[0]==10&&r.delta[2]==0&&r.root_half_height==88,"applied delta remains actual displacement, not prepared root offset");
    c.backend.body.root_half=87;Require(!c.engine.Skip(c.now),"physical capsule change releases active ownership");c.Restore();
    Case invalid;invalid.backend.body.root_radius=30;invalid.backend.body.root_half=29;invalid.Send(invalid.CommandFor());invalid.Pop(Status::TargetLost);
}
void WorldEffects(){
    Case c;c.backend.effects=true;auto hurt=c.CommandFor(Op::Damage);hurt.source=Source::WorldEffect;hurt.action=EffectTag("minecraft:lava");hurt.lease=0;hurt.amount=2;
    c.Send(hurt);auto applied=c.Pop(Status::Applied);Require(c.backend.hurts==1&&c.backend.body.health==54&&applied.health==54&&applied.actor_serial==19,"world source applies final MC damage without a movement reservation/input epoch");
    c.Send(hurt);auto replay=c.Pop(Status::Applied);Require(c.backend.hurts==1&&!std::memcmp(&applied,&replay,sizeof(replay)),"world damage exact retry returns cached actual HP without a second effect");
    auto conflict=hurt;conflict.amount=3;c.Send(conflict);c.Pop(Status::Conflict);Require(c.backend.hurts==1,"changed world damage cannot reuse an applied ID");
    auto unknown=c.CommandFor(Op::Damage);unknown.source=Source::WorldEffect;unknown.action=EffectTag("minecraft:generic_kill");c.Send(unknown);c.Pop(Status::Unsupported);Require(c.backend.hurts==1,"unknown environment type does not reach a backend");
    auto old=c.CommandFor(Op::Damage);old.source=Source::WorldEffect;old.action=hurt.action;old.world++;c.Send(old);c.Pop(Status::Stale);Require(c.backend.hurts==1,"old world source cannot damage current targets");
    Case moving;moving.Acquire();moving.backend.effects=true;auto during=moving.CommandFor(Op::Damage);during.source=Source::WorldEffect;during.action=EffectTag("minecraft:drown");moving.Send(during);moving.Pop(Status::Applied);
    Require(!moving.engine.Skip(moving.now)&&moving.engine.phase()==Engine::Phase::Releasing,"actual world damage yields physical ownership to native hit/death processing");moving.Restore();
}
void FluidRenewal(){
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Fluid;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();c.Pop(Status::Prepared);
    auto fluid=[&](Op op){auto cmd=c.CommandFor(op);cmd.source=Source::Fluid;return cmd;};
    c.now+=250;c.Tick();auto renew=fluid(Op::Renew);c.Send(renew);auto receipt=c.Pop(Status::Applied);
    Require(c.backend.moves==0&&c.engine.Skip(c.now),"fluid heartbeat never creates displacement or resumes originals");
    c.now+=200;c.Tick();c.Send(renew);auto replay=c.Pop(Status::Applied);
    Require(!std::memcmp(&receipt,&replay,sizeof(receipt)),"renewal replay is exact and does not extend absolute deadline");
    c.now+=200;c.Tick();Require(c.engine.Skip(c.now),"fresh renewal survives the original 500ms deadline");
    auto step=fluid(Op::Step);step.delta[0]=6;step.delta[1]=0;step.delta[2]=6;c.Send(step);c.Pop(Status::Applied);
    c.backend.block=true;c.backend.fraction=.25f;step=fluid(Op::Step);c.Send(step);auto partial=c.Pop(Status::Partial);
    Require(partial.delta[0]==2.5f&&c.engine.Skip(c.now),"valid partial fluid sweep retains its short lease for actual-position reconciliation");
    c.backend.fraction=0;step=fluid(Op::Step);c.Send(step);c.Pop(Status::Blocked);Require(c.engine.Skip(c.now),"continuous fluid support contact does not unnecessarily restart AI");
    c.Send(fluid(Op::Release));c.Empty();c.Restore();auto released=c.Pop(Status::Released);
    Require(released.restored==7,"fluid release after movement needs no fabricated geometry revision");
    Case timeout;reserve=timeout.CommandFor();reserve.source=Source::Fluid;timeout.Send(reserve);
    for(auto part:{1u,2u,4u})timeout.engine.Original(part);timeout.Tick();timeout.Pop(Status::Prepared);
    timeout.now+=250;renew=timeout.CommandFor(Op::Renew);renew.source=Source::Fluid;timeout.Send(renew);timeout.Pop(Status::Applied);
    timeout.now+=500;timeout.Tick();Require(!timeout.engine.Skip(timeout.now),"stopped fresh heartbeat releases by its own absolute deadline");timeout.Restore();
    Case machine;machine.Acquire();machine.Send(machine.CommandFor(Op::Renew));machine.Pop(Status::Invalid);
    Require(machine.engine.Skip(machine.now),"machine cannot use fluid renewal to evade its contact budget");machine.now+=500;machine.Tick();machine.Restore();
}
void MachinePriority(){
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Fluid;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();c.Pop(Status::Prepared);
    Require(c.engine.Skip(c.now),"continuous body initially owns its movement");
    auto machine=c.CommandFor();c.Send(machine);c.Pop(Status::Busy);
    Require(!c.engine.Skip(c.now)&&c.engine.phase()==Engine::Phase::Releasing&&c.backend.binds==1,"machine priority yields old continuous authority before any new bind");
    c.Restore();c.Send(machine);c.Pop(Status::Busy);Require(c.backend.binds==1,"same machine request remains a cached Busy, never a hidden reacquisition");
    c.Send(c.CommandFor());for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();c.Pop(Status::Prepared);
    Require(c.backend.binds==2&&c.engine.Skip(c.now),"a fresh machine request acquires only after all old original ticks restored");
}
void ContactNormals(){
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Fluid;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);
    c.Tick();c.Pop(Status::Prepared);c.backend.block=true;c.backend.fraction=.25f;c.backend.normal={1,0,0};
    auto step=c.CommandFor(Op::Step);step.source=Source::Fluid;step.delta[0]=-6;step.delta[2]=-6;c.Send(step);auto wall=c.Pop(Status::Partial);
    Require((wall.flags&4)==0&&((wall.flags>>8)&255)==127,"downward motion hitting a wall is not a landing and reports its contact normal");
    c.backend.normal={0,0,1};step=c.CommandFor(Op::Step);step.source=Source::Fluid;step.delta[0]=0;step.delta[2]=-8;c.Send(step);auto floor=c.Pop(Status::Partial);
    Require((floor.flags&4)&&((floor.flags>>24)&255)==127,"actual upward floor normal distinguishes physical ground contact");
    Case damage;reserve=damage.CommandFor();reserve.source=Source::Fluid;damage.Send(reserve);
    for(auto part:{1u,2u,4u})damage.engine.Original(part);
    damage.Tick();damage.Pop(Status::Prepared);auto release=damage.CommandFor(Op::Release);release.source=Source::Fluid;damage.Send(release);
    damage.backend.effects=true;auto hurt=damage.CommandFor(Op::Damage);hurt.source=Source::WorldEffect;hurt.action=EffectTag("minecraft:fall");damage.Send(hurt);damage.Pop(Status::Applied);
    damage.Restore();auto final=damage.Pop(Status::Released);Require(final.restored==7&&final.health==45,"handback queued before world damage confirms actual HP and original ticks after the native hit");
}
}
int main(){HandshakeAndReplay();BoundsAndInvalidation();SweepFailures();Lifetime();Capacity();GeometryBarrier();MultipleTargets();ReleaseObservation();PhysicalBinding();WorldEffects();FluidRenewal();MachinePriority();ContactNormals();std::printf("world motion arbiter PASS %u assertions\n",checks);}
