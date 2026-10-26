#include "combat/world_motion_service.h"
#include <windows.h>
#include <atomic>
#include <cstring>
#include <deque>
#include "blocks/block_mirror.h"
#include "combat/ai_tick_gate.h"
#include "combat/world_motion_engine.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "ue/transform.h"

namespace sifucraft::world_motion {
namespace {
namespace motion=combat::motion;
namespace off=gen::off;
SRWLOCK g_lock=SRWLOCK_INIT;
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
std::uint64_t g_frame=0;
unsigned PartIndex(motion::Part part){return part==motion::Part::Movement?0:part==motion::Part::AI?1:2;}
class NativeBackend final:public Backend {
public:
    void Slot(std::size_t index){slot_=index;}
    bool Bind(const Command& c,Body* out)override {
        if(motion::CheckWorld(g_base,g_current.actors,GetTickCount64()))return false;
        const auto& targets=g_current.actors.targets;
        const auto it=std::find_if(targets.begin(),targets.end(),[&](const auto& t){return t.id==c.target&&t.spawn==c.spawn;});
        if(it==targets.end())return false;
        const auto movement=mem::ReadPtr(it->actor+off::ACharacter::CharacterMovement);
        const auto id=ue::IdentityOf(movement);
        target_={it->actor,movement,it->id,it->spawn,it->identity.serial,id.index,id.serial};
        if(motion::CheckBody(g_current.actors,target_,&sample_,true)||
           motion::CheckPeers(g_base,g_current.actors,target_,&peers_,true)||!combat::ai_gate::Install(g_base,peers_))return false;
        gate_=true;++g_gate_users;
        // Opaque identity token, never an address supplied back to dereference.
        const std::uint64_t identity[]={g_current.actors.scope.native_session,g_current.actors.scope.peer_session,
            target_.id,target_.spawn,static_cast<std::uint64_t>(target_.serial),static_cast<std::uint64_t>(target_.movement_index),
            static_cast<std::uint64_t>(target_.movement_serial),static_cast<std::uint64_t>(peers_.ai.index),
            static_cast<std::uint64_t>(peers_.ai.serial),static_cast<std::uint64_t>(peers_.behavior.index),static_cast<std::uint64_t>(peers_.behavior.serial)};
        token_=Hash(identity,sizeof(identity));if(!token_)token_=1;
        g_wrong_thread[slot_].store(0);g_watches[slot_][0].store(movement);g_watches[slot_][1].store(peers_.ai.pointer);g_watches[slot_][2].store(peers_.behavior.pointer);
        *out=Convert(sample_);
        SC_LOG("[world-motion] observe target=%u/%u/%d token=%016llX thread=%lu",target_.id,target_.spawn,target_.serial,token_,GetCurrentThreadId());
        return true;
    }
    bool Identity()const {
        motion::Peers peers;
        return target_.valid()&&ue::IdentityOf(target_.actor)==ue::ObjectIdentity{static_cast<int>(target_.id),target_.serial}&&
            ue::IdentityOf(target_.movement)==ue::ObjectIdentity{target_.movement_index,target_.movement_serial}&&
            mem::ReadPtr(target_.movement+off::UActorComponent::OwnerPrivate)==target_.actor&&
            mem::ReadPtr(target_.actor+off::ACharacter::CharacterMovement)==target_.movement&&
            ue::IdentityOf(peers_.ai.pointer)==ue::ObjectIdentity{peers_.ai.index,peers_.ai.serial}&&
            ue::IdentityOf(peers_.behavior.pointer)==ue::ObjectIdentity{peers_.behavior.index,peers_.behavior.serial}&&
            !motion::CheckPeers(g_base,g_current.actors,target_,&peers,false)&&peers==peers_;
    }
    bool Check(Body* out)override {
        if(g_wrong_thread[slot_].load()||!Identity()||motion::CheckWorld(g_base,g_current.actors,GetTickCount64()))return false;
        motion::Peers peers;
        if(motion::CheckBody(g_current.actors,target_,&sample_,false,false)||
           motion::CheckPeers(g_base,g_current.actors,target_,&peers,true)||peers!=peers_)return false;
        *out=Convert(sample_);return true;
    }
    Sweep Move(const Body&,const std::array<float,3>& delta)override {
        motion::SweepResult result;
        const bool valid=motion::SweepBody(g_current.actors,target_,sample_,{delta[0],delta[1],delta[2]},&result);
        if(result.invoked)sample_=result.body;
        return {Convert(sample_),result.invoked,valid,result.returned,result.blocking,result.penetrating};
    }
    void Forget()override {
        for(auto& watch:g_watches[slot_])watch.store(0);
        if(gate_){gate_=false;if(g_gate_users&&!--g_gate_users&&!combat::ai_gate::Uninstall())SC_LOG("[world-motion] cleanup slot changed; all filters cleared");}
        target_={};peers_={};sample_={};token_=0;
    }
private:
    Body Convert(const motion::BodySample& s)const{return {target_.serial,token_,{s.position.x,s.position.y,s.position.z},s.hp,s.radius,s.half};}
    motion::Target target_;motion::Peers peers_;motion::BodySample sample_;std::uint64_t token_=0;std::size_t slot_=0;bool gate_=false;
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
void Exchange(Context context,Mailbox& mailbox) {
    const auto scope=context.actors.scope;
    AcquireSRWLockExclusive(&g_lock);
    const bool changed=g_io_native!=scope.native_session||g_io_peer!=scope.peer_session;
    if(changed){g_commands.clear();g_replies.clear();g_io_native=scope.native_session;g_io_peer=scope.peer_session;}
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
    const auto stamp=(std::uint64_t(static_cast<std::uint32_t>(g_published.actors.pawn_identity.index))<<32)|static_cast<std::uint32_t>(g_published.actors.pawn_identity.serial);
    mailbox.Status(scope.generation,g_ready.load()&&!changed?kMotionCapability:0,GetTickCount64(),stamp,
        blocks::ShapesAppliedRevision(),g_flags.load());
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
    const bool ready=g_current.enabled&&g_current.actors.enabled&&g_current.save&&g_current.map&&blocks::ShapesReady()&&
        g_current.actors.updated_ms<=now&&now-g_current.actors.updated_ms<=200;
    const bool settled=blocks::ShapesSettled();
    g_flags.store(flags);g_ready.store(ready&&settled);
    const Environment env{scope.native_session,scope.peer_session,g_current.save,g_current.map,blocks::ShapesAppliedRevision(),++g_frame,scope.generation,flags,ready,settled};
    g_fleet.Update(env,now);
    for(std::size_t i=0;i<kTargets;++i){
        auto& engine=g_fleet.at(i);
        if(g_wrong_thread[i].load())engine.CallbackThreadLost();
        if(engine.phase()!=Engine::Phase::Free&&!g_backends[i].Identity())engine.Lost();
    }
    while(!commands.empty()&&g_fleet.Submit(commands.front(),now))commands.pop_front();
    g_fleet.Update(env,now);
    AcquireSRWLockExclusive(&g_lock);
    if(g_io_native==scope.native_session&&g_io_peer==scope.peer_session) {
        // Any unaccepted commands precede the next runtime batch.
        while(!commands.empty()){g_commands.push_front(commands.back());commands.pop_back();}
        Reply reply;
        while(g_replies.size()<64&&g_fleet.Pop(&reply)) {
            SC_LOG("[world-motion] reply id=%llu lease=%llu target=%u/%u status=%u actual=(%.3f,%.3f,%.3f) restored=%u",reply.id,reply.lease,reply.target,reply.spawn,static_cast<unsigned>(reply.status),reply.delta[0],reply.delta[1],reply.delta[2],reply.restored);
            g_replies.push_back(reply);
        }
    }
    ReleaseSRWLockExclusive(&g_lock);
}
bool Skip(std::uintptr_t component,motion::Part part) {
    if(!component)return false;
    for(std::size_t i=0;i<kTargets;++i)if(component==g_watches[i][PartIndex(part)].load()){
        if(GetCurrentThreadId()!=g_thread.load()){g_wrong_thread[i].fetch_or(static_cast<unsigned>(part));return false;}
        return g_fleet.at(i).Skip(GetTickCount64());
    }
    return false;
}
void AfterOriginal(std::uintptr_t component,motion::Part part) {
    if(!component)return;
    for(std::size_t i=0;i<kTargets;++i)if(component==g_watches[i][PartIndex(part)].load()){
        if(GetCurrentThreadId()!=g_thread.load()){g_wrong_thread[i].fetch_or(static_cast<unsigned>(part));return;}
        if(g_backends[i].Identity())g_fleet.at(i).Original(static_cast<unsigned>(part));else g_fleet.at(i).Lost();
        return;
    }
}
}
