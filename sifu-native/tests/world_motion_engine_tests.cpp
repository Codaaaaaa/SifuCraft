#include <cstdio>
#include <cstdlib>
#include <cstring>
#include "combat/world_motion_engine.h"
#include "combat/target_retention.h"
#include "combat/pose_fall_origin.h"
#include "combat/explosion_ledger.h"
#include "combat/explosion_ai_state.h"
using namespace sifucraft::world_motion;
namespace {
unsigned checks=0;
void Require(bool value,const char* label){++checks;if(!value){std::fprintf(stderr,"FAIL %s\n",label);std::exit(1);}}
struct Fake final:Backend {
    Body body{19,818,{0,0,90},60,30,90};
    int binds=0,claims=0,moves=0,forgets=0,hurts=0,queries=0,impulses=0;bool valid=true,bind=true,block=false,penetrate=false,returned=true,read=true,effects=false;
    Effect Impulse(const Command&)override{impulses++;return {Status::Applied,false,body.serial,body.health,body.position,0};}
    bool QueryCoverage(const Command&)override{queries++;return true;}
    float fraction=1,last_facing=0;bool invoked=true,player=false,player_fluid=false;
    std::array<float,3> normal{};
    bool Bind(const Command& c,Body* out)override{++binds;*out=body;return bind&&(!(c.flags&kPlayer)||c.actor_stamp==((73ull<<32)|static_cast<std::uint32_t>(body.serial)));}
    bool Bound(const Command&)override{++claims;return true;}
    bool SupportsPlayer(Source source)const override{return player&&(source==Source::Machine||(player_fluid&&source==Source::Fluid));}
    bool Check(Body* out)override{*out=body;return valid;}
    Sweep Move(const Body&,const std::array<float,3>& d)override{++moves;if(invoked)for(int i=0;i<3;++i)body.position[i]+=d[i]*fraction;return {body,invoked,read,returned,block,penetrate,normal};}
    Sweep MovePose(const Body& b,const std::array<float,3>& d,float facing)override{last_facing=facing;return Move(b,d);}
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
    {Case rejected;auto reserve=rejected.CommandFor();reserve.position[0]=2;rejected.Send(reserve);rejected.Pop(Status::TargetLost);
     Require(rejected.backend.claims==0&&rejected.backend.moves==0,"pose rejection cannot consume the source motion claim");}
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
    for(int failure=0;failure<7;++failure){
        Case c;c.Acquire();
        switch(failure){case 0:c.backend.block=true;c.backend.fraction=0;break;
        case 1:c.backend.block=true;c.backend.fraction=.25f;break;case 2:c.backend.fraction=.5f;break;
        case 3:c.backend.returned=false;break;case 4:c.backend.penetrate=true;break;case 5:c.backend.read=false;break;case 6:c.backend.invoked=false;c.backend.fraction=0;break;}
        auto step=c.CommandFor(Op::Step);c.Send(step);auto r=c.Pop(failure==0?Status::Blocked:failure>=4?Status::TargetLost:Status::Partial);
        Require(r.delta[0]==10*c.backend.fraction,"failure still reports actual engine readback");
        Require(!(r.flags&kStepNotExecuted),"entering backend never certifies an unexecuted request, even with zero observed displacement");
        Require(!c.engine.Skip(c.now),"blocked/partial/penetrating lease immediately releases");
        c.Send(step);c.Pop(r.status);Require(c.backend.moves==1,"failed sweep retry not reapplied");c.Restore();
    }
}
void UnexecutedCredits(){
    for(int reason=0;reason<3;++reason){
        Case c;c.Acquire();auto step=c.CommandFor(Op::Step);
        if(reason==0)c.env.flags=kPaused;
        if(reason==1)c.env.settled=false;
        if(reason==2)step.shape_revision++;
        c.Tick();c.Send(step);auto r=c.Pop(reason==2?Status::Stale:Status::Busy);
        Require(r.flags==kStepNotExecuted&&c.backend.moves==0,"fresh rejection certifies no backend entry");
        c.env.flags=0;c.env.settled=true;c.Tick();c.Send(step);auto retry=c.Pop(r.status);
        Require(!std::memcmp(&r,&retry,sizeof(r))&&c.backend.moves==0,"rejected request retry retains the original zero-execution receipt");
    }
    Case c;c.Acquire();auto step=c.CommandFor(Op::Step);c.Send(step);auto r=c.Pop(Status::Applied);
    Require(!(r.flags&kStepNotExecuted)&&c.backend.moves==1,"successful motion never returns a credit");
    for(unsigned i=0;i<Ledger::kCapacity+1;++i){c.Send(c.CommandFor(Op::Damage));c.Pop(Status::Unsupported);}
    c.Send(step);r=c.Pop(Status::Stale);
    Require(!(r.flags&kStepNotExecuted)&&c.backend.moves==1,"retired request may already have executed and cannot authorize a refund");
    step.health--;c.Send(step);r=c.Pop(Status::Stale);
    Require(!(r.flags&kStepNotExecuted),"retired changed payload cannot invent an execution proof");
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
void PausedHandback(){
    Case c;c.Acquire();c.Send(c.CommandFor(Op::Step));c.Pop(Status::Applied);c.env.revision++;
    c.env.flags=kPaused;c.Tick();auto release=c.CommandFor(Op::Release);c.Send(release);c.Empty();
    c.now+=15000;c.Tick();Require(c.engine.phase()==Engine::Phase::Releasing&&!c.engine.Skip(c.now)&&c.backend.moves==1,"true pause retains only forwarding observation, never movement authority");
    c.Send(release);c.Empty();auto step=c.CommandFor(Op::Step);c.Send(step);auto denied=c.Pop(Status::Busy);
    Require(denied.flags==kStepNotExecuted&&c.backend.moves==1,"pending stop during pause cannot authorize another step");
    c.env.flags=0;c.Tick();c.engine.Original(1);c.engine.Original(2);c.now+=499;c.Tick();c.Empty();
    Require(c.engine.phase()==Engine::Phase::Releasing&&!c.engine.Skip(c.now),"resume window waits for the third actual original without skipping any tick");
    c.engine.Original(4);c.Tick();auto r=c.Pop(Status::Released);
    Require(r.restored==7&&c.engine.phase()==Engine::Phase::Free&&c.backend.moves==1,"owner release confirms complete actual handback after a 15s pause");
    c.Send(release);auto replay=c.Pop(Status::Released);Require(!std::memcmp(&r,&replay,sizeof(r)),"long-paused stop has one immutable final receipt");
    Case expired;expired.Acquire();expired.env.flags=kPaused;expired.Tick();expired.Send(expired.CommandFor(Op::Release));expired.Empty();
    expired.now+=15000;expired.Tick();expired.env.flags=0;expired.Tick();expired.now+=500;expired.Tick();r=expired.Pop(Status::TimedOut);
    Require(r.restored==0&&expired.engine.phase()==Engine::Phase::Free,"missing original ticks still time out 500ms after actual resume");
    Case lost;lost.Acquire();lost.env.flags=kPaused;lost.Tick();lost.Send(lost.CommandFor(Op::Release));lost.engine.Lost();lost.Tick();r=lost.Pop(Status::TargetLost);
    Require(lost.engine.phase()==Engine::Phase::Free,"destroyed identity during pause cannot report successful actor handback");
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
    for(auto name:{"minecraft:magic","minecraft:wither"}){
        Case periodic;periodic.backend.effects=true;auto effect=periodic.CommandFor(Op::Damage);effect.source=Source::WorldEffect;effect.action=EffectTag(name);effect.amount=1;
        periodic.Send(effect);periodic.Pop(Status::Applied);periodic.Send(effect);periodic.Pop(Status::Applied);
        Require(periodic.backend.hurts==1&&periodic.backend.body.health==57,"periodic effect uses existing H7 path and deduplicates retries");
    }
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
void ExplosionMotion(){
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Explosion;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();c.Pop(Status::Prepared);
    for(int i=0;i<20;i++){
        auto step=c.CommandFor(Op::Step);step.source=Source::Explosion;
        step.delta[0]=6;step.delta[2]=6;c.Send(step);auto reply=c.Pop(Status::Applied);
        Require(reply.delta[2]==6,"explosion retains vertical force, not horizontal-only feedback");
        c.Send(step);c.Pop(Status::Applied);
    }
    Require(c.backend.moves==20&&c.backend.body.position[0]==120&&c.backend.body.position[2]==210,"explosion trajectory has no machine-contact 101cm cap and no replay displacement");
    c.now+=250;auto renewal=c.CommandFor(Op::Renew);renewal.source=Source::Explosion;c.Send(renewal);c.Pop(Status::Applied);
    Require(c.engine.Skip(c.now),"explosion uses the same bounded physical/AI lease and fresh renewals");
    c.backend.block=true;c.backend.fraction=0;auto step=c.CommandFor(Op::Step);step.source=Source::Explosion;c.Send(step);c.Pop(Status::Blocked);
    Require(c.engine.Skip(c.now),"collision retains the continuous authority for original velocity reconciliation");
    c.now+=500;c.Tick();Require(!c.engine.Skip(c.now),"missing explosion heartbeat always releases by its deadline");c.Restore();
    Case damage;damage.backend.effects=true;auto hurt=damage.CommandFor(Op::Damage);hurt.source=Source::WorldEffect;hurt.action=EffectTag("minecraft:explosion");
    damage.Send(hurt);damage.Pop(Status::Applied);damage.Send(hurt);damage.Pop(Status::Applied);
    Require(damage.backend.hurts==1&&damage.backend.body.health==45,"explosion final damage has an independent replay-protected HP result");
}
void ExplosionEventIdentity(){
    {Case wind;auto proof=wind.CommandFor(Op::Impulse);proof.source=Source::WorldEffect;proof.action=EffectTag("minecraft:wind_charge");proof.amount=0;proof.lease=proof.duration_ms=proof.actor_stamp=proof.component_token=proof.state_hash=proof.section_revision=0;
     wind.Send(proof);wind.Pop(Status::Applied);wind.Send(proof);wind.Pop(Status::Applied);
     Require(wind.backend.impulses==1&&wind.backend.hurts==0&&wind.backend.moves==0&&wind.backend.body.health==60,"wind proof observes without damage or motion and retries once");
     auto unknown=proof;unknown.id++;unknown.action=EffectTag("minecraft:explosion");wind.Send(unknown);wind.Pop(Status::Unsupported);Require(wind.backend.impulses==1,"only wind has a damage-free proof operation");
     ExplosionLedger ledger;ledger.World(100,2);Effect cached;Require(ledger.Begin(proof,&cached)==ExplosionLedger::Decision::Fresh,"zero-damage wind event begins");
     ledger.Complete(proof,Effect{Status::Applied,false,19,60,{0,0,90},0});proof.health=60;
     Require(ledger.Wind(proof)&&!ledger.Motion(proof,20)&&ledger.Motion(proof,19)&&!ledger.Motion(proof,19),"wind proof binds the activation and permits one physical claim");}
    ExplosionLedger ledger;ledger.World(100,2);Case c;
    auto command=c.CommandFor(Op::Damage);command.source=Source::WorldEffect;command.action=EffectTag("minecraft:explosion");
    Effect result;Require(ledger.Begin(command,&result)==ExplosionLedger::Decision::Fresh,"one blast effect is reserved before native damage");
    Require(!ledger.Motion(command,19),"no impulse before its HP decision");
    Effect applied{Status::Applied,true,19,45,{0,0,90},0};ledger.Complete(command,applied);
    command.id+=100; // a restarted MC transport has new ids, but the TNT UUID is unchanged
    Require(ledger.Begin(command,&result)==ExplosionLedger::Decision::Cached&&!result.invoked&&result.health==45,"new transport id still receives the original blast HP result without invocation");
    auto altered=command;altered.amount++;
    Require(ledger.Begin(altered,&result)==ExplosionLedger::Decision::Conflict,"same blast UUID cannot change final damage");
    auto motion=command;motion.source=Source::Explosion;motion.health=45;
    Require(!ledger.Motion(motion,20),"pooled actor serial cannot consume another actor's blast impulse");
    Require(ledger.EligibleMotion(motion,19)&&ledger.EligibleMotion(motion,19),"read-only blast qualification does not consume the motion permission");
    Require(ledger.Motion(motion,19)&&!ledger.Motion(motion,19),"blast motion has its own at-most-once execution bit");
    ledger.World(100,2);Require(ledger.Begin(command,&result)==ExplosionLedger::Decision::Cached,"peer reconnect preserves the event ledger in the same native world");
    ledger.World(100,3);Require(ledger.Begin(command,&result)==ExplosionLedger::Decision::Fresh,"new world starts a new activation namespace");
    for(std::uint8_t idle:{0u,1u}){
        ExplosionAiState stable{idle,0,1,0};Require(stable.eligible()&&stable.retains(stable),"signed blast can retain either unchanged idle flag with a running unpaused brain");
        auto changed=stable;changed.out_idle=1-idle;Require(!changed.retains(stable),"new AI phase revokes the signed blast lease");
        changed=stable;changed.force=1;Require(!changed.retains(stable),"forced native handler always takes priority");
        changed=stable;changed.running=0;Require(!changed.retains(stable),"stopped native behavior cannot be acquired");
        changed=stable;changed.paused=1;Require(!changed.retains(stable),"paused native behavior cannot be acquired");
    }
}
void PassengerPose(){
    {Case ride;auto reserve=ride.CommandFor();reserve.source=Source::Passenger;ride.Send(reserve);for(auto part:{1u,2u,4u})ride.engine.Original(part);ride.Tick();ride.Pop(Status::Prepared);
     std::optional<Command> retired;
     for(int i=0;i<1000;i++){
        ride.now++;ride.Tick();if(i%100==0){auto renew=ride.CommandFor(Op::Renew);renew.source=Source::Passenger;ride.Send(renew);ride.Pop(Status::Applied);}
        auto step=ride.CommandFor(Op::Step);step.source=Source::Passenger;step.delta[0]=1;if(!retired)retired=step;ride.Send(step);ride.Pop(Status::Applied);
     }
     Require(ride.backend.moves==1000&&ride.engine.Skip(ride.now),"one passenger lease survives repeated bounded-ledger eviction while renewing");
     ride.Send(*retired);ride.Pop(Status::Stale);Require(ride.backend.moves==1000,"retired passenger command cannot replay a move after ledger turnover");}
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Passenger;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);c.Tick();c.Pop(Status::Prepared);
    auto turn=c.CommandFor(Op::Step);turn.source=Source::Passenger;turn.delta[0]=0;turn.facing=15;
    c.Send(turn);auto receipt=c.Pop(Status::Applied);
    Require(c.backend.last_facing==15&&receipt.delta[0]==0&&c.backend.moves==1,"passenger yaw-only step uses the explicit pose backend without translation");
    c.Send(turn);c.Pop(Status::Applied);Require(c.backend.moves==1,"replayed passenger rotation is not invoked twice");
    auto seat=c.CommandFor(Op::Step);seat.source=Source::Passenger;seat.delta[0]=6;seat.delta[2]=6;seat.facing=30;
    c.Send(seat);c.Pop(Status::Applied);Require(c.backend.last_facing==30&&c.backend.body.position[2]==96,"seat follow preserves requested yaw and vertical movement");
    auto heartbeat=c.CommandFor(Op::Renew);heartbeat.source=Source::Passenger;c.Send(heartbeat);c.Pop(Status::Applied);
    c.backend.block=true;c.backend.fraction=.25f;
    seat=c.CommandFor(Op::Step);seat.source=Source::Passenger;seat.delta[0]=8;c.Send(seat);
    auto partial=c.Pop(Status::Partial);
    Require(partial.delta[0]==2&&c.engine.Skip(c.now),"a passenger obstruction reports actual partial travel and retains short ownership");
    c.backend.fraction=0;seat=c.CommandFor(Op::Step);seat.source=Source::Passenger;c.Send(seat);c.Pop(Status::Blocked);
    Require(c.engine.Skip(c.now),"a stationary passenger contact does not repeatedly restore and reacquire its AI");
    auto release=c.CommandFor(Op::Cancel);release.source=Source::Passenger;c.Send(release);c.Empty();c.Restore();c.Pop(Status::Cancelled);
    Require(c.backend.body.position[0]==8&&c.backend.body.position[2]==96,"passenger release restores all originals without rewinding its seat pose");
}
void RenewalDuringPublication(){
    for(auto source:{Source::Fluid,Source::Explosion,Source::Passenger}){
        Case delayed;auto first=delayed.CommandFor();first.source=source;delayed.Send(first);
        for(auto part:{1u,2u,4u})delayed.engine.Original(part);delayed.Tick();delayed.Pop(Status::Prepared);
        delayed.now=1400;delayed.Tick();auto late=delayed.CommandFor(Op::Renew);late.source=source;late.issued_ms=1150;
        delayed.Send(late);delayed.Pop(Status::Applied);Require(delayed.backend.moves==0&&delayed.engine.Skip(delayed.now),"delayed non-moving heartbeat can retain an unexpired qualified lease");
        delayed.now=1649;delayed.Tick();delayed.Send(late);delayed.Pop(Status::Applied);
        delayed.now=1650;delayed.Tick();Require(!delayed.engine.Skip(delayed.now),"late heartbeat and exact retry expire at authorization+500ms, never receipt+500ms");delayed.Restore();
        Case expired;first=expired.CommandFor();first.source=source;expired.Send(first);for(auto part:{1u,2u,4u})expired.engine.Original(part);expired.Tick();expired.Pop(Status::Prepared);
        expired.now=1600;late=expired.CommandFor(Op::Renew);late.source=source;late.issued_ms=1200;expired.Send(late);expired.Pop(Status::TimedOut);
        Require(!expired.engine.Skip(expired.now),"a delayed heartbeat cannot resurrect expired ownership even before the next Update");expired.Restore();
        Case motion;first=motion.CommandFor();first.source=source;motion.Send(first);for(auto part:{1u,2u,4u})motion.engine.Original(part);motion.Tick();motion.Pop(Status::Prepared);
        motion.now=1300;auto oldStep=motion.CommandFor(Op::Step);oldStep.source=source;oldStep.issued_ms=1000;motion.Send(oldStep);motion.Pop(Status::Stale);
        Require(motion.backend.moves==0,"the heartbeat exception never permits an old movement command");
    }
    Case c;auto reserve=c.CommandFor();reserve.source=Source::Fluid;c.Send(reserve);
    for(auto part:{1u,2u,4u})c.engine.Original(part);
    c.Tick();c.Pop(Status::Prepared);
    c.now+=250;c.env.settled=false;c.env.revision++;c.Tick();
    auto renewal=c.CommandFor(Op::Renew);renewal.source=Source::Fluid;renewal.shape_revision--;
    c.Send(renewal);c.Pop(Status::Applied);
    Require(c.backend.moves==0&&c.engine.Skip(c.now),"fresh identity heartbeat survives an unsettled collision epoch without movement");
    auto step=c.CommandFor(Op::Step);step.source=Source::Fluid;c.Send(step);c.Pop(Status::Busy);
    Require(c.backend.moves==0&&c.engine.Skip(c.now),"heartbeat does not enable movement before exact geometry is settled");
    c.now+=450;c.env.revision++;c.Tick();Require(c.engine.Skip(c.now),"publication can span the old lease while fresh bounded heartbeat is present");
    renewal=c.CommandFor(Op::Renew);renewal.source=Source::Fluid;renewal.shape_revision=1;c.Send(renewal);c.Pop(Status::Applied);
    c.now+=500;c.Tick();Require(!c.engine.Skip(c.now),"no geometry-independent heartbeat evades its absolute 500ms deadline");c.Restore();
    Case changed;reserve=changed.CommandFor();reserve.source=Source::Fluid;changed.Send(reserve);
    for(auto part:{1u,2u,4u})changed.engine.Original(part);
    changed.Tick();changed.Pop(Status::Prepared);changed.env.settled=false;changed.backend.body.health--;
    renewal=changed.CommandFor(Op::Renew);renewal.source=Source::Fluid;changed.Send(renewal);changed.Pop(Status::TargetLost);
    Require(!changed.engine.Skip(changed.now),"publication heartbeat cannot retain an actor with changed HP");changed.Restore();
}
void RetainedPublication(){
    TargetRetention retained;Require(!retained.Keep(0,3,4,1000),"publication requires a live world identity");
    Require(retained.Keep(2,3,4,1000),"lease retains its exact activation");
    Require(retained.Snapshot(2,100000)==std::vector<std::uint64_t>{TargetRetention::Key(3,4)},"active publication is independent of distance and time");
    Require(retained.Snapshot(3,1000).empty(),"retained targets do not cross worlds");
    retained.Release(2,3,5,1000);Require(retained.Snapshot(2,1600).size()==1,"different pool activation cannot release the live target");
    retained.Release(2,3,4,1000);retained.Release(2,3,4,1500);
    Require(retained.Active(2).empty()&&retained.Snapshot(2,1500).size()==1,"active targets and handoff grace have distinct publication priorities");
    Require(retained.Snapshot(2,1599).size()==1&&retained.Snapshot(2,1600).empty(),"handoff grace expires exactly and a repeated release never extends it");
    for(unsigned i=1;i<=TargetRetention::kCapacity;i++)Require(retained.Keep(2,i+10,1,2000),"bounded retained publication slots");
    Require(!retained.Keep(2,900,1,2000)&&retained.Snapshot(2,2000).size()==TargetRetention::kCapacity,"capacity pressure cannot evict any active lease");
    retained.Release(2,11,1,2000);Require(!retained.Keep(2,900,1,2599),"unexpired handoff remains protected under pressure");
    Require(retained.Keep(2,900,1,2600),"expired handoff slot can be reused");retained.Reset();Require(retained.Snapshot(2,2600).empty(),"peer replacement clears retained publication");
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
void PlayerMovementOnly(){
    Case denied;auto command=denied.CommandFor();command.flags=kPlayer;command.target=command.spawn=0;command.actor_stamp=(73ull<<32)|19;
    denied.Send(command);denied.Pop(Status::Unsupported);Require(denied.backend.binds==0,"backend must explicitly support a player source before binding");
    for(auto source:{Source::Fluid,Source::WorldEffect}){
        Case restricted;restricted.backend.player=true;auto c=restricted.CommandFor();c.flags=kPlayer;c.target=c.spawn=0;c.actor_stamp=(73ull<<32)|19;c.source=source;
        restricted.Send(c);restricted.Pop(Status::Unsupported);Require(restricted.backend.binds==0,"machine permission does not admit player fluids or damage");
    }
    Case c;c.backend.player=true;
    auto playerCommand=[&](Op op){auto value=c.CommandFor(op);value.flags=kPlayer;value.target=value.spawn=0;if(op==Op::Reserve)value.actor_stamp=(73ull<<32)|19;return value;};
    c.Send(playerCommand(Op::Reserve));c.engine.Original(2);c.engine.Original(4);c.Tick();c.Empty();
    Require(!c.engine.Skip(c.now),"unrelated AI ticks cannot satisfy player preflight");
    c.engine.Original(1);c.Tick();c.Pop(Status::Prepared);Require(c.engine.Skip(c.now),"only original player movement is required to acquire");
    auto forged=playerCommand(Op::Step);forged.actor_stamp=(73ull<<32)|19;c.Send(forged);c.Pop(Status::Stale);Require(c.backend.moves==0,"later player commands require the actual bound serial/token, not an initial stamp replay");
    auto step=playerCommand(Op::Step);c.Send(step);auto applied=c.Pop(Status::Applied);Require(applied.delta[0]==10&&c.backend.moves==1,"player uses the same bounded actual sweep");
    c.env.revision++;c.Tick();c.Send(playerCommand(Op::Release));c.engine.Original(2);c.engine.Original(4);c.Tick();c.Empty();
    Require(!c.engine.Skip(c.now),"player release forwards movement immediately");
    c.engine.Original(1);c.Tick();auto reply=c.Pop(Status::Released);Require(reply.restored==1&&c.engine.phase()==Engine::Phase::Free,"player confirms movement without waiting for nonexistent AI components");
    Case wrong;wrong.backend.player=true;command=wrong.CommandFor();command.flags=kPlayer;command.target=command.spawn=0;command.actor_stamp=(74ull<<32)|19;
    wrong.Send(command);wrong.Pop(Status::TargetLost);Require(wrong.engine.phase()==Engine::Phase::Free,"backend rejects a changed player index even with the same serial");
    for(int failure=0;failure<3;++failure){
        Case lost;lost.backend.player=true;auto reserve=lost.CommandFor();reserve.flags=kPlayer;reserve.target=reserve.spawn=0;reserve.actor_stamp=(73ull<<32)|19;
        lost.Send(reserve);lost.engine.Original(1);lost.Tick();lost.Pop(Status::Prepared);
        if(failure==0)lost.now+=501;else if(failure==1)lost.backend.body.health--;else lost.backend.valid=false;
        lost.Tick();Require(!lost.engine.Skip(lost.now)&&lost.engine.phase()==Engine::Phase::Releasing,"player timeout/HP/identity changes immediately stop filtering");
        lost.engine.Original(2);lost.engine.Original(4);lost.Tick();Require(lost.engine.phase()==Engine::Phase::Releasing,"unrelated component callbacks cannot claim player restoration");
        lost.engine.Original(1);lost.Tick();Require(lost.engine.phase()==Engine::Phase::Free,"player restoration frees its slot without AI state writes");
    }
    Case paused;paused.backend.player=true;auto reserve=paused.CommandFor();reserve.flags=kPlayer;reserve.target=reserve.spawn=0;reserve.actor_stamp=(73ull<<32)|19;
    paused.Send(reserve);paused.engine.Original(1);paused.Tick();paused.Pop(Status::Prepared);paused.env.flags=kPaused;paused.Tick();
    auto release=paused.CommandFor(Op::Release);release.flags=kPlayer;release.target=release.spawn=0;paused.Send(release);paused.Empty();
    paused.now+=5000;paused.Tick();Require(!paused.engine.Skip(paused.now)&&paused.engine.phase()==Engine::Phase::Releasing,"a true player pause retains only observation, never movement authority");
    paused.env.flags=0;paused.Tick();paused.engine.Original(1);paused.Tick();Require(paused.Pop(Status::Released).restored==1,"player resumes and proves its one original tick after pause");
}

void PlayerFluidAndPoseOwner(){
    Case fluid;fluid.backend.player=fluid.backend.player_fluid=true;
    auto pc=[&](Op op){auto c=fluid.CommandFor(op);c.source=Source::Fluid;c.flags=kPlayer;c.target=c.spawn=0;
        c.duration_ms=op==Op::Reserve?200:op==Op::Renew?500:0;
        if(op==Op::Reserve)c.actor_stamp=(73ull<<32)|19;return c;};
    fluid.Send(pc(Op::Reserve));fluid.engine.Original(1);fluid.Tick();fluid.Pop(Status::Prepared);
    fluid.Send(pc(Op::Step));Require(fluid.Pop(Status::Applied).delta[0]==10,"player fluid uses the actual bounded sweep");
    fluid.now+=170;fluid.Tick();fluid.Send(pc(Op::Renew));fluid.Pop(Status::Applied);
    fluid.now+=50;fluid.Tick();Require(fluid.engine.Skip(fluid.now),"fresh player fluid renewal extends only its short deadline");
    fluid.engine.PlayerPoseOwned();fluid.Tick();fluid.Empty();
    Require(!fluid.engine.Skip(fluid.now)&&fluid.engine.phase()==Engine::Phase::Releasing,"MC pose owner immediately removes player filtering and retains only bounded confirmation");
    fluid.backend.body.position[0]+=5;fluid.now+=20;fluid.Tick();fluid.Send(pc(Op::Release));fluid.Tick();
    auto handed=fluid.Pop(Status::Released);Require(handed.restored==1&&(handed.flags&kPlayerPoseHandoff)&&handed.position[0]==15,"handoff observes the MC-controlled root without claiming an original Native tick");
    Require(fluid.engine.phase()==Engine::Phase::Free,"explicit pose handoff frees its slot");
    Case original;original.backend.player=true;auto reserve=original.CommandFor();reserve.flags=kPlayer;reserve.target=reserve.spawn=0;reserve.actor_stamp=(73ull<<32)|19;
    original.Send(reserve);original.engine.Original(1);original.Tick();original.Pop(Status::Prepared);original.engine.PlayerControlWillTransfer();original.engine.Original(1);original.Tick();original.Empty();
    Require(!original.engine.Skip(original.now)&&original.engine.phase()==Engine::Phase::Releasing,"an original tick during F8 anchoring retains bounded stop confirmation without owning motion");
    auto stop=original.CommandFor(Op::Release);stop.flags=kPlayer;stop.target=stop.spawn=0;original.Send(stop);original.Tick();auto observed=original.Pop(Status::Released);
    Require(observed.restored==1&&!(observed.flags&kPlayerPoseHandoff),"actual original tick and MC-pose proof remain distinguishable");
    Case bounded;bounded.backend.player=true;auto c=bounded.CommandFor();c.flags=kPlayer;c.target=c.spawn=0;c.actor_stamp=(73ull<<32)|19;
    bounded.Send(c);bounded.engine.Original(1);bounded.Tick();bounded.Pop(Status::Prepared);bounded.engine.PlayerPoseOwned();
    bounded.now+=1001;bounded.Tick();Require(bounded.engine.phase()==Engine::Phase::Free,"missing pose handoff ACK never retains the binding beyond its bounded observation window");
    Case npc;npc.Send(npc.CommandFor());for(unsigned part:{1u,2u,4u})npc.engine.Original(part);npc.Tick();npc.Pop(Status::Prepared);
    npc.engine.PlayerPoseOwned();npc.Tick();Require(npc.engine.Skip(npc.now),"the player pose owner cannot release an NPC lease");
}

}
int main(){
    {
        Case test;Command query{};query.op=Op::Coverage;query.source=Source::WorldEffect;query.id=1;query.issued_ms=test.now;query.source_hi=1;query.action=1;query.save=30;query.map=40;query.world=2;query.position[0]=-16800;Seal(query);
        test.Send(query);test.Pop(Status::Applied);test.Send(query);test.Pop(Status::Applied);
        Require(test.backend.queries==1&&test.backend.binds==0&&test.backend.moves==0&&test.backend.hurts==0&&test.engine.phase()==Engine::Phase::Free,"read-only spatial query replay never binds or moves an actor");
        auto bad=query;bad.id=2;bad.map=41;test.Send(bad);test.Pop(Status::Stale);Require(test.backend.queries==1,"wrong map query never reaches backend");
        bad=query;bad.id=3;bad.target=7;test.Send(bad);test.Pop(Status::Invalid);Require(test.backend.queries==1,"coverage cannot carry an actor target");
        Case leased;leased.Acquire();query.id=2;leased.Send(query);auto receipt=leased.Pop(Status::Applied);
        Require(leased.engine.Skip(leased.now)&&leased.backend.binds==1&&leased.backend.moves==0&&receipt.component_token==0&&receipt.health==0,"query during a movement lease preserves ownership and leaks no bound actor metadata");
    }
    float origin=0;
    Require(TranslateFallOrigin(922,922,90,&origin)&&origin==90,"MC return from slime apex cannot become an 832cm native fall");
    Require(TranslateFallOrigin(1000,900,500,&origin)&&origin-500==100,"taking MC ownership preserves the pre-existing 100cm fall");
    Require(origin-400==200,"subsequent genuine native descent still accumulates fall distance");
    Require(TranslateFallOrigin(origin,500,1500,&origin)&&origin-1500==100,"upward MC translation preserves genuine fall distance too");
    Require(TranslateFallOrigin(origin,1500,900,&origin)&&origin==1000,"MC translation roundtrip preserves the original native fall origin");
    for(auto bad:{NAN,INFINITY,-INFINITY}){
        Require(!TranslateFallOrigin(bad,90,90,&origin),"reject invalid native fall origin");
        Require(!TranslateFallOrigin(90,bad,90,&origin),"reject invalid old pose");
        Require(!TranslateFallOrigin(90,90,bad,&origin),"reject invalid actual pose");
    }
    Require(!TranslateFallOrigin(1e9f,-1e9f,1e9f,&origin),"reject unreasonable translated origin without writing");
    Require(!TranslateFallOrigin(90,90,90,nullptr),"reject missing cache output");
    HandshakeAndReplay();BoundsAndInvalidation();SweepFailures();UnexecutedCredits();Lifetime();PausedHandback();Capacity();GeometryBarrier();MultipleTargets();ReleaseObservation();PhysicalBinding();WorldEffects();FluidRenewal();ExplosionMotion();ExplosionEventIdentity();PassengerPose();RenewalDuringPublication();RetainedPublication();MachinePriority();ContactNormals();PlayerMovementOnly();PlayerFluidAndPoseOwner();std::printf("world motion arbiter PASS %u assertions\n",checks);
}
