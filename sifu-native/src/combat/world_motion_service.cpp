#include "combat/world_motion_service.h"
#include <windows.h>
#include <atomic>
#include <cstring>
#include <deque>
#include "blocks/block_mirror.h"
#include "combat/ai_tick_gate.h"
#include "combat/base_sync.h"
#include "combat/player_world_probe.h"
#include "combat/player_idle_gate.h"
#include "combat/damage.h"
#include "combat/world_motion_engine.h"
#include "combat/explosion_ledger.h"
#include "combat/explosion_ai_state.h"
#include "combat/target_retention.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "collision/coverage_query.h"
#include "hooks/game_hooks.h"
#include "input/input_router.h"
#include "probe/combat_sample.h"
#include "ue/transform.h"

namespace sifucraft::world_motion {
namespace {
namespace motion=combat::motion;
namespace off=gen::off;
SRWLOCK g_lock=SRWLOCK_INIT;
TargetRetention g_retained;
Context g_published,g_current;
std::deque<Command> g_commands;
std::deque<Reply> g_replies;
std::uint64_t g_io_native=0,g_io_peer=0;
std::atomic<DWORD> g_thread{0};
constexpr std::size_t kTargets=16;
std::array<std::atomic<unsigned>,kTargets> g_wrong_thread{};
std::array<std::array<std::atomic<std::uintptr_t>,3>,kTargets> g_watches{};
unsigned g_gate_users=0; // game thread only, shared forwarding-hook ownership
std::atomic<std::uint32_t> g_flags{kScripted};
std::atomic<bool> g_ready{false};
std::uintptr_t g_base=0;
ExplosionLedger g_explosions; // game thread; intentionally independent of peer-session transport ledger
std::atomic<bool> g_explosion_room{true};
struct PlayerPublication {motion::Scope scope;std::uint64_t stamp=0,at=0,save=0,map=0;PlayerInfo info;};
PlayerPublication g_player_publication; // g_lock protects runtime publication
std::uint64_t Stamp(ue::ObjectIdentity id){return (std::uint64_t(static_cast<std::uint32_t>(id.index))<<32)|static_cast<std::uint32_t>(id.serial);}
const char* PlayerBody(motion::Target* target,motion::BodySample* sample,bool geometry){
    const auto& c=g_current.actors;
    if(!c.pawn_identity.valid()||ue::IdentityOf(c.pawn)!=c.pawn_identity)return "player-identity";
    const auto movement=mem::ReadPtr(c.pawn+off::ACharacter::CharacterMovement);const auto id=ue::IdentityOf(movement);
    *target={c.pawn,movement,static_cast<std::uint32_t>(c.pawn_identity.index),1,c.pawn_identity.serial,id.index,id.serial};
    if(const auto why=motion::CheckPlayerBody(c,*target,sample,geometry))return why;
    if(!ue::IdentityOf(sample->root).valid())return "player-root-identity";
    return nullptr;
}
unsigned PartIndex(motion::Part part){return part==motion::Part::Movement?0:part==motion::Part::AI?1:2;}
class NativeBackend final:public Backend {
public:
    void Slot(std::size_t index){slot_=index;}
    bool QueryCoverage(const Command& c)override{return collision::coverage::Request(g_current.actors.scope.world,c.world,{c.position[0],c.position[1],c.position[2]});}
    bool SupportsPlayer(Source source)const override{return source==Source::Machine||source==Source::Fluid;}
    bool Bind(const Command& c,Body* out)override {
        if(motion::CheckWorld(g_base,g_current.actors,GetTickCount64()))return false;
        player_=(c.flags&kPlayer)!=0;
        explosion_=c.source==Source::Explosion;
        if(player_)return BindPlayer(c,out);
        const auto& targets=g_current.actors.targets;
        const auto it=std::find_if(targets.begin(),targets.end(),[&](const auto& t){return t.id==c.target&&t.spawn==c.spawn;});
        if(it==targets.end())return false;
        const auto movement=mem::ReadPtr(it->actor+off::ACharacter::CharacterMovement);
        const auto id=ue::IdentityOf(movement);
        target_={it->actor,movement,it->id,it->spawn,it->identity.serial,id.index,id.serial};
        if(explosion_){
            g_explosions.World(g_current.actors.scope.native_session,g_current.actors.scope.generation);
            if(!g_explosions.EligibleMotion(c,target_.serial))return Refuse("explosion-unproven");
        }
        // Original terrain and MC platforms share the actual root sweep. Do not
        // infer eligibility from UE Walking alone: Sifu's verified idle grunts use
        // mode None(0), including the successful M1/M3 live fixtures.
        if(const auto why=motion::CheckBody(g_current.actors,target_,&sample_,true,false))return Refuse(why);
        if(const auto why=motion::CheckPeers(g_base,g_current.actors,target_,&peers_,!explosion_))return Refuse(why);
        if(explosion_&&!ExplosionPeers(true))return Refuse("explosion-ai-state");
        hit_=mem::ReadPtr(target_.actor+off::AFightingCharacter::m_HitCapsule);hit_id_=ue::IdentityOf(hit_);
        if(!hit_id_.valid()||ue::OuterOf(hit_)!=target_.actor)return false;
        if(!combat::ai_gate::Install(g_base,peers_))return false;
        mode_=sample_.mode;
        gate_=true;++g_gate_users;
        // Opaque identity token, never an address supplied back to dereference.
        const std::uint64_t identity[]={g_current.actors.scope.native_session,g_current.actors.scope.peer_session,
            target_.id,target_.spawn,static_cast<std::uint64_t>(target_.serial),static_cast<std::uint64_t>(target_.movement_index),
            static_cast<std::uint64_t>(target_.movement_serial),static_cast<std::uint64_t>(peers_.ai.index),
            static_cast<std::uint64_t>(peers_.ai.serial),static_cast<std::uint64_t>(peers_.behavior.index),static_cast<std::uint64_t>(peers_.behavior.serial)};
        token_=Hash(identity,sizeof(identity));if(!token_)token_=1;
        g_wrong_thread[slot_].store(0);g_watches[slot_][0].store(movement);g_watches[slot_][1].store(peers_.ai.pointer);g_watches[slot_][2].store(peers_.behavior.pointer);
        if(!Convert(sample_,out))return false;
        retained_world_=g_current.actors.scope.generation;
        AcquireSRWLockExclusive(&g_lock);const bool retained=g_retained.Keep(retained_world_,target_.id,target_.spawn,GetTickCount64());ReleaseSRWLockExclusive(&g_lock);
        if(!retained)return false;
        if(explosion_){
            const auto& p=out->position;
            SC_LOG("[world-explosion] reserve expected=(%.3f,%.3f,%.3f) actual=(%.3f,%.3f,%.3f) HP=%.3f/%.3f capsule=%.3f,%.3f/%.3f,%.3f serial=%llu/%d",
                c.position[0],c.position[1],c.position[2],p[0],p[1],p[2],c.health,out->health,c.radius,c.half_height,out->radius,out->half,c.actor_stamp,out->serial);
        }
        SC_LOG("[world-motion] observe target=%u/%u/%d token=%016llX thread=%lu",target_.id,target_.spawn,target_.serial,token_,GetCurrentThreadId());
        return true;
    }
    bool Bound(const Command& c)override {
        return !explosion_||g_explosions.Motion(c,target_.serial);
    }
    bool Identity()const {
        if(player_)return target_.valid()&&g_current.actors.pawn==target_.actor&&
            g_current.actors.pawn_identity==ue::ObjectIdentity{static_cast<int>(target_.id),target_.serial}&&
            ue::IdentityOf(target_.actor)==g_current.actors.pawn_identity&&
            ue::IdentityOf(target_.movement)==ue::ObjectIdentity{target_.movement_index,target_.movement_serial}&&
            mem::ReadPtr(target_.movement+off::UActorComponent::OwnerPrivate)==target_.actor&&
            mem::ReadPtr(target_.actor+off::ACharacter::CharacterMovement)==target_.movement&&
            mem::ReadPtr(target_.actor+off::AActor::RootComponent)==hit_&&
            mem::ReadPtr(target_.actor+off::ACharacter::CapsuleComponent)==hit_&&
            mem::ReadPtr(target_.actor+off::AFightingCharacter::m_HealthComponent)==sample_.health&&
            ue::IdentityOf(hit_)==hit_id_&&ue::OuterOf(hit_)==target_.actor;
        motion::Peers peers;
        return target_.valid()&&ue::IdentityOf(target_.actor)==ue::ObjectIdentity{static_cast<int>(target_.id),target_.serial}&&
            ue::IdentityOf(target_.movement)==ue::ObjectIdentity{target_.movement_index,target_.movement_serial}&&
            mem::ReadPtr(target_.movement+off::UActorComponent::OwnerPrivate)==target_.actor&&
            mem::ReadPtr(target_.actor+off::ACharacter::CharacterMovement)==target_.movement&&
            mem::ReadPtr(target_.actor+off::AFightingCharacter::m_HitCapsule)==hit_&&ue::IdentityOf(hit_)==hit_id_&&ue::OuterOf(hit_)==target_.actor&&
            ue::IdentityOf(peers_.ai.pointer)==ue::ObjectIdentity{peers_.ai.index,peers_.ai.serial}&&
            ue::IdentityOf(peers_.behavior.pointer)==ue::ObjectIdentity{peers_.behavior.index,peers_.behavior.serial}&&
            !motion::CheckPeers(g_base,g_current.actors,target_,&peers,false)&&peers==peers_;
    }
    bool Check(Body* out)override {
        if(g_wrong_thread[slot_].load()||!Identity()||motion::CheckWorld(g_base,g_current.actors,GetTickCount64()))return false;
        if(player_){if(motion::CheckPlayerBody(g_current.actors,target_,&sample_,false)||sample_.mode!=mode_)return false;return Convert(sample_,out);}
        motion::Peers peers;
        if(motion::CheckBody(g_current.actors,target_,&sample_,false,false)||
           sample_.mode!=mode_||motion::CheckPeers(g_base,g_current.actors,target_,&peers,!explosion_)||peers!=peers_||
           (explosion_&&!ExplosionPeers(false)))return false;
        return Convert(sample_,out);
    }
    bool Observe(Body* out)override{
        if(!Identity()||motion::CheckWorld(g_base,g_current.actors,GetTickCount64())||
           mem::ReadPtr(target_.actor+off::AActor::RootComponent)!=sample_.root||
           mem::ReadPtr(target_.actor+off::AFightingCharacter::m_HealthComponent)!=sample_.health)return false;
        probe::HealthSample health;
        if(!ue::ReadWorldTransform(sample_.root,&sample_.position,&sample_.rotation)||!probe::ReadHealthSample(sample_.health,&health))return false;
        sample_.hp=health.health;return Convert(sample_,out);
    }
    Sweep Move(const Body&,const std::array<float,3>& delta)override {
        motion::SweepResult result;
        const bool valid=motion::SweepBody(g_current.actors,target_,sample_,{delta[0],delta[1],delta[2]},&result,player_);
        if(result.invoked)sample_=result.body;
        Body body;const bool anchor=Convert(sample_,&body);
        return {body,result.invoked,valid&&anchor,result.returned,result.blocking,result.penetrating,{result.normal.x,result.normal.y,result.normal.z}};
    }
    Sweep MovePose(const Body&,const std::array<float,3>& delta,float facing)override {
        motion::SweepResult result;
        const bool valid=motion::SweepBody(g_current.actors,target_,sample_,{delta[0],delta[1],delta[2]},&result,false,&facing);
        if(result.invoked)sample_=result.body;
        Body body;const bool anchor=Convert(sample_,&body);
        return {body,result.invoked,valid&&anchor,result.returned,result.blocking,result.penetrating,{result.normal.x,result.normal.y,result.normal.z}};
    }
    Effect Hurt(const Command& c)override{
        Effect out;
        if(!combat::damage::WorldReady())return out;
        if(motion::CheckWorld(g_base,g_current.actors,GetTickCount64())){out.status=Status::Busy;return out;}
        const auto& targets=g_current.actors.targets;
        const auto it=std::find_if(targets.begin(),targets.end(),[&](const auto& t){return t.id==c.target&&t.spawn==c.spawn;});
        if(it==targets.end()){out.status=Status::TargetLost;return out;}
        const auto target=*it;out.serial=target.identity.serial;
        const auto hit=mem::ReadPtr(target.actor+off::AFightingCharacter::m_HitCapsule);ue::FVector position;
        if(!ue::IsLive(hit)||ue::OuterOf(hit)!=target.actor||!ue::ReadWorldLocation(hit,&position)){out.status=Status::TargetLost;return out;}
        out.position={position.x,position.y,position.z};
        const bool blast=ExplosionLedger::DamageType(c.action);
        if(blast){
            g_explosions.World(g_current.actors.scope.native_session,g_current.actors.scope.generation);
            const auto claim=g_explosions.Begin(c,&out);
            if(claim==ExplosionLedger::Decision::Cached){
                if(out.serial!=target.identity.serial)out.status=Status::TargetLost;
                return out;
            }
            if(claim!=ExplosionLedger::Decision::Fresh){out.status=claim==ExplosionLedger::Decision::Conflict?Status::Conflict:Status::Busy;return out;}
        }
        const auto application=combat::damage::ApplyWorld(g_base,g_current.actors.scope.world,g_current.actors.scope.generation,
            g_current.actors.scope.peer_session,c.id,target,c.amount);
        const auto& result=application.result;
        const auto outcome=static_cast<protocol::DamageOutcome>(result.result);
        out.health=result.health_after;out.invoked=application.invoked;
        out.flags=result.result<<8;
        out.status=outcome==protocol::DamageOutcome::Applied?Status::Applied:
            (outcome==protocol::DamageOutcome::Invincible||outcome==protocol::DamageOutcome::Dead)?Status::Blocked:
            outcome==protocol::DamageOutcome::Unavailable?Status::Unsupported:Status::TargetLost;
        if(blast)g_explosions.Complete(c,out);
        SC_LOG("[world-effect] id=%llu target=%u/%u type=%016llX mc_final=%.3f native_lost=%.3f HP_after=%.3f outcome=%u",c.id,c.target,c.spawn,c.action,c.amount,result.sifu_damage,result.health_after,result.result);
        return out;
    }
    void Forget()override {
        if(!player_){AcquireSRWLockExclusive(&g_lock);g_retained.Release(retained_world_,target_.id,target_.spawn,GetTickCount64());ReleaseSRWLockExclusive(&g_lock);}retained_world_=0;
        for(auto& watch:g_watches[slot_])watch.store(0);
        if(gate_){gate_=false;if(g_gate_users&&!--g_gate_users&&!combat::ai_gate::Uninstall())SC_LOG("[world-motion] cleanup slot changed; all filters cleared");}
        target_={};peers_={};sample_={};token_=0;hit_=0;hit_id_={};player_=false;explosion_=false;
    }
private:
    bool ExplosionPeers(bool capture){
        // A signed, already-resolved blast may leave the AI out of idle after
        // its hit order finishes. Preserve that state; only the same unchanged
        // running, unpaused peers can be held. Orders/montages/root motion,
        // velocity, HP and identity still go through the strict body checks.
        namespace bits=gen::bits::UBehaviorTreeComponent;
        const auto ai=peers_.ai.pointer,bt=peers_.behavior.pointer;
        std::uint8_t running=0,paused=0,out=255,force=255;
        if(!mem::Read(bt+bits::bIsRunning_Offset,&running)||!mem::Read(bt+bits::bIsPaused_Offset,&paused)||
            !mem::Read(ai+off::UAIFightingComponent::m_bIsOutOfIdle,&out)||out>1||
            !mem::Read(ai+off::UAIFightingComponent::m_bForceActionHandlerTick,&force)||force||
            !((running>>bits::bIsRunning_Shift)&1)||((paused>>bits::bIsPaused_Shift)&1))return false;
        const ExplosionAiState state{out,force,static_cast<std::uint8_t>((running>>bits::bIsRunning_Shift)&1),static_cast<std::uint8_t>((paused>>bits::bIsPaused_Shift)&1)};
        if(!state.eligible())return false;
        if(capture)explosion_ai_=state;
        return state.retains(explosion_ai_);
    }
    bool explosion_=false;ExplosionAiState explosion_ai_;
    bool BindPlayer(const Command& c,Body* out){
        if(c.actor_stamp!=Stamp(g_current.actors.pawn_identity))return Refuse("player-stamp");
        if(const auto why=PlayerBody(&target_,&sample_,true))return Refuse(why);
        hit_=sample_.root;hit_id_=ue::IdentityOf(hit_);mode_=sample_.mode;
        const std::uint64_t identity[]={g_current.actors.scope.native_session,g_current.actors.scope.peer_session,c.actor_stamp,
            static_cast<std::uint64_t>(target_.movement_index),static_cast<std::uint64_t>(target_.movement_serial),
            static_cast<std::uint64_t>(hit_id_.index),static_cast<std::uint64_t>(hit_id_.serial)};
        token_=Hash(identity,sizeof(identity));if(!token_)token_=1;
        if(!Convert(sample_,out))return false;
        g_wrong_thread[slot_].store(0);g_watches[slot_][0].store(target_.movement);g_watches[slot_][1].store(0);g_watches[slot_][2].store(0);
        SC_LOG("[world-player] observe pawn=%u/%d root=%d/%d token=%016llX thread=%lu",target_.id,target_.serial,hit_id_.index,hit_id_.serial,token_,GetCurrentThreadId());return true;
    }
    bool Refuse(const char* reason){
        static unsigned logged=0;if(logged<32){++logged;SC_LOG("[world-motion] qualification REFUSE target=%u/%u class=%s reason=%s mode=%d",target_.id,target_.spawn,ue::ClassName(target_.actor).c_str(),reason,sample_.mode);}return false;
    }
    bool Convert(const motion::BodySample& s,Body* out)const{
        if(player_){
            if(s.root!=hit_||!hit_id_.valid()||ue::IdentityOf(hit_)!=hit_id_)return false;
            *out={target_.serial,token_,{s.position.x,s.position.y,s.position.z},s.hp,s.radius,s.half,{0,0,0},s.radius,s.half};return true;
        }
        // EnemyFrame and the MC reference body use the hit capsule anchor. The
        // swept root capsule is a different component (90cm vs 92cm in this build).
        // Read the real child transform after every sweep/original Tick; never
        // invent a constant offset or send the mirror's hit pose to the actor root.
        if(!hit_id_.valid()||ue::IdentityOf(hit_)!=hit_id_||mem::ReadPtr(target_.actor+off::AFightingCharacter::m_HitCapsule)!=hit_)return false;
        ue::Affine transform;ue::FVector position;
        if(!ue::ReadComponentToWorld(hit_,&transform)||!ue::ReadWorldLocation(hit_,&position))return false;
        const auto scale=transform.MinAxisScale();
        const auto radius=mem::ReadOr<float>(hit_+off::UCapsuleComponent::CapsuleRadius,NAN)*scale;
        const auto half=mem::ReadOr<float>(hit_+off::UCapsuleComponent::CapsuleHalfHeight,NAN)*scale;
        *out={target_.serial,token_,{position.x,position.y,position.z},s.hp,radius,half,
            {s.position.x-position.x,s.position.y-position.y,s.position.z-position.z},s.radius,s.half};return true;
    }
    motion::Target target_;motion::Peers peers_;motion::BodySample sample_;std::uint64_t token_=0;std::size_t slot_=0;bool gate_=false,player_=false;int mode_=-1;
    std::uintptr_t hit_=0;ue::ObjectIdentity hit_id_;std::uint32_t retained_world_=0;
};
std::array<NativeBackend,kTargets> g_backends;
std::array<Backend*,kTargets> BackendPointers(){
    std::array<Backend*,kTargets> pointers{};
    for(std::size_t i=0;i<kTargets;++i){g_backends[i].Slot(i);pointers[i]=&g_backends[i];}
    return pointers;
}
Fleet<kTargets> g_fleet(BackendPointers());
std::uint32_t Flags(const motion::Context& c) {
    const auto level=mem::ReadPtr(c.scope.world+off::UWorld::PersistentLevel);
    const auto settings=mem::ReadPtr(level+off::ULevel::WorldSettings);
    if(ue::IsLive(settings)&&mem::ReadOr<std::uintptr_t>(settings+off::AWorldSettings::PauserPlayerState,1))return kPaused;
    const auto reason=motion::CheckWorld(g_base,c,GetTickCount64());
    return reason?kScripted:0;
}
}
PublishedTargets RetainedTargets(std::uint32_t world,std::uint64_t now){
    AcquireSRWLockShared(&g_lock);PublishedTargets targets{g_retained.Active(world),g_retained.Snapshot(world,now)};ReleaseSRWLockShared(&g_lock);return targets;
}
void Exchange(Context context,Mailbox& mailbox) {
    const auto scope=context.actors.scope;
    AcquireSRWLockExclusive(&g_lock);
    const bool changed=g_io_native!=scope.native_session||g_io_peer!=scope.peer_session;
    if(changed){g_commands.clear();g_replies.clear();g_retained.Reset();g_player_publication={};g_io_native=scope.native_session;g_io_peer=scope.peer_session;}
    g_published=std::move(context);
    if(!scope.peer_session){mailbox.Close();g_ready.store(false);ReleaseSRWLockExclusive(&g_lock);return;}
    if(!mailbox.Open(scope.peer_session,scope.native_session)){ReleaseSRWLockExclusive(&g_lock);return;}
    while(!g_replies.empty()&&mailbox.Write(g_replies.front()))g_replies.pop_front();
    // Backpressure reaches the producer before consuming another reliable command.
    while(g_commands.size()<32&&g_replies.size()<32&&mailbox.CanReply()) {
        Command command;bool valid=false;if(!mailbox.Read(&command,&valid))break;
        if(valid)g_commands.push_back(command);
        else SC_LOG("[world-motion] malformed command discarded");
    }
    const auto stamp=Stamp(g_published.actors.pawn_identity);
    // H1 may stop while UE is truly paused. Runtime must still report the real
    // PauserPlayerState, so MC does not run scheduled world ticks during that pause.
    const auto level=mem::ReadPtr(scope.world+off::UWorld::PersistentLevel);
    const auto settings=mem::ReadPtr(level+off::ULevel::WorldSettings);
    const bool settings_live=ue::IsLive(settings);
    const auto flags=(g_flags.load()&~kPaused)|(settings_live&&mem::ReadOr<std::uintptr_t>(settings+off::AWorldSettings::PauserPlayerState,0)?kPaused:0);
    const auto now=GetTickCount64();
    const bool player_ready=g_ready.load()&&!flags&&g_player_publication.scope==scope&&g_player_publication.stamp==stamp&&
        g_player_publication.save==g_published.save&&g_player_publication.map==g_published.map&&g_player_publication.at<=now&&now-g_player_publication.at<=200&&
        input::Router().owner()==protocol::InputOwner::Sifu&&!hooks::PoseActive();
    const auto capabilities=changed?0ull:((g_ready.load()?kMotionCapability:0)|(g_published.enabled&&g_published.actors.enabled&&combat::damage::WorldReady()?kDamageCapability:0)|(settings_live?kPauseCapability:0)|(player_ready?(kPlayerMachineCapability|kPlayerFluidCapability):0)|
        (g_explosion_room.load()&&g_ready.load()&&g_published.enabled&&g_published.actors.enabled&&combat::damage::WorldReady()?kExplosionCapability:0)|
        (g_ready.load()&&g_published.enabled&&g_published.actors.enabled?kPassengerCapability:0)|
        (g_published.enabled&&g_published.actors.enabled?kCoverageCapability:0));
    mailbox.Status(scope.generation,capabilities,now,stamp,blocks::ShapesAppliedRevision(),flags,player_ready?g_player_publication.info:PlayerInfo{});
    ReleaseSRWLockExclusive(&g_lock);
}
void ApplyOnGameThread(std::uintptr_t base) {
    DWORD zero=0;const auto thread=GetCurrentThreadId();g_thread.compare_exchange_strong(zero,thread);
    if(g_thread.load()!=thread){for(auto& wrong:g_wrong_thread)wrong.fetch_or(8);return;}
    g_base=base;
    std::deque<Command> commands;
    AcquireSRWLockExclusive(&g_lock);
    g_current=g_published;
    if(g_replies.size()<32)commands.swap(g_commands);
    ReleaseSRWLockExclusive(&g_lock);
    const auto now=GetTickCount64();const auto flags=Flags(g_current.actors);
    const auto& scope=g_current.actors.scope;
    g_explosions.World(scope.native_session,scope.generation);
    const bool ready=g_current.enabled&&g_current.actors.enabled&&g_current.save&&g_current.map&&blocks::ShapesReady()&&
        g_current.actors.updated_ms<=now&&now-g_current.actors.updated_ms<=200;
    const bool settled=blocks::ShapesSettled();
    g_flags.store(flags);g_ready.store(ready&&settled);
    collision::coverage::Peer(scope.peer_session);
    if(ready&&settled&&!flags){
        const auto capsule=mem::ReadPtr(g_current.actors.pawn+off::ACharacter::CapsuleComponent);
        if(ue::IsLive(capsule)&&ue::OuterOf(capsule)==g_current.actors.pawn)
            collision::coverage::Probe(base,scope.world,scope.generation,g_current.actors.pawn,collision::ReadResponses(capsule),now);
    }
    base_sync::Context(base,&g_current.actors,g_current.enabled);
    const Environment env{scope.native_session,scope.peer_session,g_current.save,g_current.map,blocks::ShapesAppliedRevision(),g_current.actors.native_frame,scope.generation,flags,ready,settled};
    if(input::Router().owner()==protocol::InputOwner::Minecraft)for(std::size_t i=0;i<kTargets;++i)g_fleet.at(i).PlayerControlWillTransfer();
    g_fleet.Update(env,now);
    for(std::size_t i=0;i<kTargets;++i){
        auto& engine=g_fleet.at(i);
        if(g_wrong_thread[i].load())engine.CallbackThreadLost();
        if(engine.phase()!=Engine::Phase::Free&&!g_backends[i].Identity())engine.Lost();
    }
    for(unsigned n=0;n<8&&!commands.empty()&&g_fleet.Submit(commands.front(),now);++n)commands.pop_front();
    const bool room=g_explosions.size()<65536;
    if(g_explosion_room.exchange(room)!=room)SC_LOG("[world-explosion] event ledger %s entries=%llu",room?"available":"full: new TNT events paused",static_cast<unsigned long long>(g_explosions.size()));
    g_fleet.Update(env,now);
    PlayerPublication player;
    if(ready&&settled&&!flags){
        motion::Target target;motion::BodySample body;
        const auto reason=PlayerBody(&target,&body,false);
        if(!reason)player={scope,Stamp(g_current.actors.pawn_identity),now,g_current.save,g_current.map,{{body.position.x,body.position.y,body.position.z},body.radius,body.half,body.hp}};
        static const char* previous=nullptr;static unsigned logged=0;
        if(input::Router().owner()==protocol::InputOwner::Sifu&&!hooks::PoseActive()&&reason!=previous&&logged<32){
            previous=reason;++logged;SC_LOG("[world-player] eligibility=%s pawn=%d/%d",reason?reason:"ready",g_current.actors.pawn_identity.index,g_current.actors.pawn_identity.serial);
            // Read-only, opt-in diagnosis of a real command interrupting a player
            // lease. PDB: m_OrderList is TArray<TSharedPtr<OrderBase,1>> (16B).
            if(g_current.observe_player&&reason&&std::strcmp(reason,"orders")==0){
                const auto order=mem::ReadPtr(g_current.actors.pawn+off::ABaseCharacter::m_OrderComponent);
                if(ue::IsLive(order)&&mem::ReadPtr(order+off::UActorComponent::OwnerPrivate)==g_current.actors.pawn){
                    // Local PDB DelayedOrderCommand: 48B; m_Order+8,
                    // m_OrderType+24 (FOrderType.m_eOrderType+0), command byte+0.
                    const std::uint32_t lists[]={off::OrderManager::m_OrderList,off::OrderManager::m_DelayedOrderCommand,off::OrderManager::m_DelayedOrderCommandDuringPrediction};
                    for(int kind=0;kind<3;++kind){
                        mem::RawArray list{};
                        if(!mem::ReadArray(order+off::UOrderComponent::m_OrderManager+lists[kind],&list)||list.num>64)continue;
                        for(int i=0;i<std::min(list.num,3);++i){
                            const auto entry=list.data+std::uintptr_t(i)*(kind?48:16);
                            const auto item=mem::ReadPtr(entry+(kind?8:0)),vtable=mem::ReadPtr(item);
                            SC_LOG("[world-player-order] list=%d count=%d index=%d command=%u type=%u order=%llX vtable_rva=%llX",kind,list.num,i,
                                kind?mem::ReadOr<std::uint8_t>(entry,255):255,kind?mem::ReadOr<std::uint8_t>(entry+24,255):255,
                                static_cast<unsigned long long>(item),static_cast<unsigned long long>(vtable>=base?vtable-base:0));
                        }
                    }
                }
            }
        }
    }
    AcquireSRWLockExclusive(&g_lock);
    if(g_io_native==scope.native_session&&g_io_peer==scope.peer_session) {
        g_player_publication=player;
        // Any unaccepted commands precede the next runtime batch.
        while(!commands.empty()){g_commands.push_front(commands.back());commands.pop_back();}
        Reply reply;
        while(g_replies.size()<64&&g_fleet.Pop(&reply)) {
            SC_LOG("[world-motion] reply id=%llu lease=%llu target=%u/%u status=%u actual=(%.3f,%.3f,%.3f) restored=%u flags=0x%08X",reply.id,reply.lease,reply.target,reply.spawn,static_cast<unsigned>(reply.status),reply.delta[0],reply.delta[1],reply.delta[2],reply.restored,reply.flags);
            g_replies.push_back(reply);
        }
    }
    ReleaseSRWLockExclusive(&g_lock);
    const auto movement=mem::ReadPtr(g_current.actors.pawn+off::ACharacter::CharacterMovement);
    bool leased=false;
    for(std::size_t i=0;i<kTargets;++i)if(movement&&g_watches[i][PartIndex(motion::Part::Movement)].load()==movement&&g_fleet.at(i).phase()!=Engine::Phase::Free)leased=true;
    const bool fresh=g_current.actors.updated_ms<=now&&now-g_current.actors.updated_ms<=200;
    player_idle::Update(base,g_current.actors,g_current.enabled&&g_current.actors.enabled&&fresh&&(hooks::PoseActive()||leased));
}
bool Skip(std::uintptr_t component,motion::Part part) {
    if(!component)return false;
    for(std::size_t i=0;i<kTargets;++i)if(component==g_watches[i][PartIndex(part)].load()){
        if(GetCurrentThreadId()!=g_thread.load()){g_wrong_thread[i].fetch_or(static_cast<unsigned>(part));return false;}
        return g_fleet.at(i).Skip(GetTickCount64());
    }
    return false;
}
void PoseOwnedMovement(std::uintptr_t component){
    base_sync::PoseOwned(component);
    if(GetCurrentThreadId()!=g_thread.load()||input::Router().owner()!=protocol::InputOwner::Minecraft||!hooks::PoseActive())return;
    for(std::size_t i=0;i<kTargets;++i)if(component==g_watches[i][0].load()&&g_backends[i].Identity())g_fleet.at(i).PlayerPoseOwned();
}
void BeforeOriginalMovement(std::uintptr_t component){
    base_sync::BeforeOriginal(component);
    if(GetCurrentThreadId()==g_thread.load())player_probe::Before(g_base,g_current.actors,component,g_current.observe_player);
}
void AfterOriginal(std::uintptr_t component,motion::Part part) {
    if(!component)return;
    if(part==motion::Part::Movement&&GetCurrentThreadId()==g_thread.load())player_probe::After(g_base,g_current.actors,component,g_current.observe_player);
    for(std::size_t i=0;i<kTargets;++i)if(component==g_watches[i][PartIndex(part)].load()){
        if(GetCurrentThreadId()!=g_thread.load()){g_wrong_thread[i].fetch_or(static_cast<unsigned>(part));return;}
        if(g_backends[i].Identity())g_fleet.at(i).Original(static_cast<unsigned>(part));else g_fleet.at(i).Lost();
        return;
    }
}
}
