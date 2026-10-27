#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <deque>
#include <optional>
#include <memory>
#include "combat/world_motion_ledger.h"
#include "combat/world_effect_types.h"

namespace sifucraft::world_motion {
struct Environment {
    std::uint64_t native=0,peer=0,save=0,map=0,revision=0,frame=0;
    std::uint32_t world=0,flags=0;
    bool ready=false,settled=true;
};
struct Body {
    std::int32_t serial=0;
    std::uint64_t token=0;
    std::array<float,3> position{};
    float health=0,radius=0,half=0;
    std::array<float,3> root_offset{};
    float root_radius=0,root_half=0;
};
struct Sweep {
    Body body;
    bool invoked=false,valid=false,returned=false,blocking=false,penetrating=false;
    std::array<float,3> normal{};
};
struct Effect {
    Status status=Status::Unsupported;bool invoked=false;
    std::int32_t serial=0;float health=0;std::array<float,3> position{};std::uint32_t flags=0;
};
class Backend {
public:
    virtual ~Backend()=default;
    virtual bool Bind(const Command&,Body*)=0;
    virtual bool Check(Body*)=0;
    virtual bool Observe(Body* body){return Check(body);} // final original-Tick pose, without requiring an idle AI
    virtual bool SupportsPlayer(Source)const{return false;}
    virtual Sweep Move(const Body&,const std::array<float,3>&)=0;
    virtual Effect Hurt(const Command&){return {};}
    // Clears only this service's filters. It never rewrites an actor's old pose/state.
    virtual void Forget()=0;
};
// Pure game-thread arbiter. One short lease at a time; contention is explicit Busy.
// Pending reserve/release and completed replies remain bounded and replayable.
class Engine {
public:
    enum class Phase { Free,Acquiring,Owned,Releasing };
    explicit Engine(Backend& backend,Ledger* shared=nullptr):backend_(backend),owned_(shared?nullptr:std::make_unique<Ledger>()),ledger_(shared?shared:owned_.get()){}
    void Update(Environment environment,std::uint64_t now) {
        const bool peer=env_.native!=environment.native||env_.peer!=environment.peer;
        if(peer) {backend_.Forget();phase_=Phase::Free;if(owned_)ledger_->Reset();out_.clear();reserve_.reset();release_.reset();}
        env_=environment;
        if(phase_==Phase::Free)return;
        if(!Scope(source_)||!env_.ready||env_.flags)BeginRelease(Status::TargetLost);
        else if(now>=deadline_)BeginRelease(Status::TimedOut);
        if(phase_==Phase::Acquiring||phase_==Phase::Owned) {
            Body current;
            if(!backend_.Check(&current)||!SameBody(current,body_))BeginRelease(Status::TargetLost);
            else body_=current;
        }
        if(phase_==Phase::Acquiring&&observed_==Parts()&&reserve_&&Room()) {
            phase_=Phase::Owned;Finish(*reserve_,Status::Prepared,now);reserve_.reset();
        }
        if(phase_==Phase::Releasing&&reserve_&&Room()) {Finish(*reserve_,reason_,now);reserve_.reset();}
        // Pausing cannot prolong movement ownership (Releasing always forwards).
        // It can postpone observing original world ticks, which do not run in
        // a paused world. Give that observation its own bounded resume window.
        if(phase_==Phase::Releasing){
            if(env_.flags&kPaused)restore_paused_=true;
            else if(restore_paused_){restore_deadline_=now+500;restore_paused_=false;}
        }
        const bool restore_timeout=phase_==Phase::Releasing&&!(env_.flags&kPaused)&&now>=restore_deadline_;
        if(phase_==Phase::Releasing&&(restored_==Parts()||restore_timeout)&&(!release_||Room())) {
            if(restore_timeout&&restored_!=Parts())reason_=Status::TimedOut;
            bool observed=false;
            if(restored_==Parts()){
                Body sample;
                if(!identity_lost_&&backend_.Observe(&sample)&&ValidBody(sample)&&sample.serial==body_.serial&&sample.token==body_.token){body_=sample;observed=true;}
                else reason_=Status::TargetLost;
            }
            if(release_) {Finish(*release_,release_->op==Op::Release&&observed?Status::Released:reason_,now);release_.reset();}
            backend_.Forget();phase_=Phase::Free;
        }
    }
    bool Submit(const Command& command,std::uint64_t now) {
        if(!Room())return false;
        const auto result=ledger_->Begin(command);
        switch(result.decision) {
        case Ledger::Decision::Pending:return true;
        case Ledger::Decision::Replay:out_.push_back(result.reply);return true;
        case Ledger::Decision::Conflict:Queue(command,Status::Conflict,now);return true;
        case Ledger::Decision::Stale:Queue(command,Status::Stale,now);return true;
        case Ledger::Decision::Full:Queue(command,Status::Busy,now);return true;
        case Ledger::Decision::Invalid:Queue(command,Status::Invalid,now);return true;
        case Ledger::Decision::Fresh:break;
        }
        if(!Scope(command)||command.issued_ms>now||now-command.issued_ms>200) {Reject(command,Status::Stale,now);return true;}
        // Releasing authority needs no ready movement world. A real pause must
        // not reject the owner's stop request as Busy.
        if((command.op==Op::Cancel||command.op==Op::Release)&&phase_!=Phase::Free&&Owns(command)){
            if(release_){Reject(command,Status::Busy,now);return true;}
            if(command.op==Op::Release&&(!env_.settled||command.shape_revision!=env_.revision||command.shape_revision<release_revision_)){Reject(command,Status::Stale,now);return true;}
            release_=command;BeginRelease(command.op==Op::Cancel?Status::Cancelled:Status::Released);return true;
        }
        if(!env_.ready||env_.flags) {Reject(command,Status::Busy,now);return true;}
        if((command.flags&kPlayer)&&!backend_.SupportsPlayer(command.source)) {Reject(command,Status::Unsupported,now);return true;}
        if(command.op==Op::Damage){
            if(command.source!=Source::WorldEffect||!EnvironmentEffect(command.action)){Reject(command,Status::Unsupported,now);return true;}
            const auto effect=backend_.Hurt(command);
            if(effect.invoked&&phase_!=Phase::Free)BeginRelease(Status::TargetLost);
            auto reply=Make(command,effect.status,now);reply.actor_serial=effect.serial;reply.health=effect.health;reply.flags=effect.flags;
            for(int i=0;i<3;++i){reply.position[i]=effect.position[i];reply.delta[i]=0;}
            Seal(reply);if(ledger_->Finish(command.id,reply))out_.push_back(reply);return true;
        }
        if(command.op==Op::Reserve) {
            if(phase_!=Phase::Free) {
                if(source_.source==Source::Fluid&&command.source==Source::Machine&&command.target==source_.target&&command.spawn==source_.spawn&&command.flags==source_.flags)
                    BeginRelease(Status::Cancelled);
                Reject(command,Status::Busy,now);return true;
            }
            if(!env_.settled){Reject(command,Status::Busy,now);return true;}
            if(command.shape_revision!=env_.revision||now-command.issued_ms>=command.duration_ms) {Reject(command,Status::Stale,now);return true;}
            Body body;
            if(!backend_.Bind(command,&body)||!Expected(command,body)) {
                backend_.Forget();Reject(command,Status::TargetLost,now);return true;
            }
            source_=command;body_=body;deadline_=command.issued_ms+command.duration_ms;
            observed_=restored_=0;identity_lost_=false;spent_=0;last_tick_=command.tick;last_progress_=command.progress;release_revision_=command.shape_revision;reason_=Status::Released;
            phase_=Phase::Acquiring;reserve_=command;return true;
        }
        if(phase_==Phase::Free||!Owns(command)) {Reject(command,Status::Stale,now);return true;}
        if(phase_!=Phase::Owned) {Reject(command,Status::Busy,now);return true;}
        Body current;
        if(now>=deadline_||!backend_.Check(&current)||!SameBody(current,body_)) {
            BeginRelease(now>=deadline_?Status::TimedOut:Status::TargetLost);Reject(command,reason_,now);return true;
        }
        if(command.tick<last_tick_||command.progress<last_progress_||!Expected(command,current)) {Reject(command,Status::Stale,now);return true;}
        if(command.op==Op::Renew){
            // Identity/HP/actual pose and a fresh heartbeat retain authority.
            // This operation performs no sweep and need not wait for collision
            // publication. Movement still requires the exact settled epoch below.
            if(command.source!=Source::Fluid||now-command.issued_ms>=command.duration_ms){Reject(command,Status::Stale,now);return true;}
            deadline_=std::max(deadline_,command.issued_ms+command.duration_ms);last_tick_=command.tick;
            Finish(command,Status::Applied,now);return true;
        }
        if(!env_.settled){Reject(command,Status::Busy,now);return true;}
        if(command.shape_revision!=env_.revision){Reject(command,Status::Stale,now);return true;}
        const double requested=Length(command.delta);
        if(command.source==Source::Machine&&spent_+requested>101.0001) {Reject(command,Status::Invalid,now);BeginRelease(Status::Invalid);return true;}
        // Book the request before the engine call. A partial sweep never refunds or retries it.
        spent_+=requested;last_tick_=command.tick;last_progress_=command.progress;
        const auto moved=backend_.Move(current,{command.delta[0],command.delta[1],command.delta[2]});
        std::array<float,3> delta{};
        if(moved.invoked)for(int i=0;i<3;++i)delta[i]=moved.body.position[i]-current.position[i];
        auto status=Status::Applied;
        if(!moved.invoked||!moved.valid||moved.penetrating||Length(delta.data())>requested+0.1||!ValidBody(moved.body)||
           moved.body.health!=current.health||moved.body.serial!=current.serial||moved.body.token!=current.token)status=Status::TargetLost;
        else if(moved.blocking)status=Length(delta.data())<0.001?Status::Blocked:Status::Partial;
        else if(!moved.returned||Distance(moved.body.position,current.position,command.delta)>0.1)status=Status::Partial;
        if(moved.invoked&&ValidBody(moved.body))body_=moved.body;
        if(moved.invoked&&command.source==Source::Machine)release_revision_=std::max(release_revision_,command.shape_revision+1);
        std::uint32_t contact=(moved.blocking?1u:0u)|(moved.penetrating?2u:0u);
        if(moved.blocking){
            if(command.delta[2]<0&&moved.normal[2]>.5f)contact|=4;
            for(int i=0;i<3;i++)if(std::isfinite(moved.normal[i]))contact|=std::uint32_t(static_cast<std::uint8_t>(static_cast<std::int8_t>(std::lround(std::clamp(moved.normal[i],-1.f,1.f)*127))))<<(8+i*8);
        }
        Finish(command,status,now,delta,contact);
        // Continuous media reconcile a valid blocked/partial sweep and retain
        // their short heartbeat lease. Machines cannot commit through a blocker.
        if(status!=Status::Applied&&(command.source!=Source::Fluid||status==Status::TargetLost))BeginRelease(status);
        return true;
    }
    // Called before each hooked Tick. A world/identity/HP change releases immediately.
    bool Skip(std::uint64_t now) {
        if(phase_!=Phase::Owned)return false;
        Body current;
        if(now>=deadline_||!env_.ready||env_.flags||!backend_.Check(&current)||!SameBody(current,body_)) {
            BeginRelease(now>=deadline_?Status::TimedOut:Status::TargetLost);return false;
        }
        return true;
    }
    void Original(unsigned part) {
        if(part!=1&&part!=2&&part!=4)return;
        if(!(part&Parts()))return;
        if(phase_==Phase::Acquiring)observed_|=part;
        else if(phase_==Phase::Releasing)restored_|=part;
    }
    // Destroyed/replaced components cannot produce their old original Tick again.
    void Lost() {BeginRelease(Status::TargetLost);identity_lost_=true;restored_=Parts();}
    void CallbackThreadLost(){BeginRelease(Status::TargetLost);}
    bool Pop(Reply* reply){if(out_.empty())return false;*reply=out_.front();out_.pop_front();return true;}
    bool Room()const{return out_.size()<60;}
    Phase phase()const{return phase_;}
    unsigned restored()const{return restored_;}
    const Body& body()const{return body_;}
    bool Target(const Command& command)const{return phase_!=Phase::Free&&source_.target==command.target&&source_.spawn==command.spawn&&source_.flags==command.flags;}
private:
    unsigned Parts()const{return source_.flags&kPlayer?1u:7u;}
    static double Length(const float* value){double n=0;for(int i=0;i<3;++i)n+=double(value[i])*value[i];return std::sqrt(n);}
    static double Distance(const std::array<float,3>& a,const std::array<float,3>& b,const float* delta=nullptr){float d[3];for(int i=0;i<3;++i)d[i]=a[i]-b[i]-(delta?delta[i]:0);return Length(d);}
    static bool ValidBody(const Body& b){
        return b.serial>0&&b.token&&std::all_of(b.position.begin(),b.position.end(),[](float v){return std::isfinite(v);})&&
            std::isfinite(b.health)&&b.health>0&&std::isfinite(b.radius)&&std::isfinite(b.half)&&b.radius>=10&&b.radius<=100&&b.half>=b.radius&&b.half<=200&&
            std::all_of(b.root_offset.begin(),b.root_offset.end(),[](float v){return std::isfinite(v);})&&Length(b.root_offset.data())<=100&&
            std::isfinite(b.root_radius)&&std::isfinite(b.root_half)&&((b.root_radius==0&&b.root_half==0)||
                (b.root_radius>=10&&b.root_radius<=100&&b.root_half>=b.root_radius&&b.root_half<=200));
    }
    static bool SameBody(const Body& a,const Body& b){return ValidBody(a)&&a.serial==b.serial&&a.token==b.token&&a.health==b.health&&
        std::fabs(a.radius-b.radius)<0.5&&std::fabs(a.half-b.half)<0.5&&Distance(a.position,b.position)<=1&&
        std::fabs(a.root_radius-b.root_radius)<0.5&&std::fabs(a.root_half-b.root_half)<0.5&&Distance(a.root_offset,b.root_offset)<=.1;}
    static bool Expected(const Command& c,const Body& b){
        // Player reserve binds the complete header stamp (index/serial) in the
        // backend. Later commands use its serial plus the opaque binding token.
        const auto stamp=(c.flags&kPlayer)&&c.op==Op::Reserve?static_cast<std::uint32_t>(c.actor_stamp):c.actor_stamp;
        return ValidBody(b)&&(!c.actor_stamp||stamp==static_cast<std::uint64_t>(b.serial))&&(!c.component_token||c.component_token==b.token)&&
            c.health==b.health&&std::fabs(c.radius-b.radius)<0.5&&std::fabs(c.half_height-b.half)<0.5&&Distance(b.position,{c.position[0],c.position[1],c.position[2]})<=1;
    }
    bool Scope(const Command& c)const{return env_.native&&env_.peer&&c.world==env_.world&&c.save==env_.save&&c.map==env_.map;}
    bool Owns(const Command& c)const{return c.lease==source_.id&&c.source==source_.source&&c.source_hi==source_.source_hi&&c.source_lo==source_.source_lo&&
        c.action==source_.action&&c.target==source_.target&&c.spawn==source_.spawn&&c.flags==source_.flags&&
        (c.source!=Source::Machine||(c.state_hash==source_.state_hash&&std::equal(c.block,c.block+3,source_.block)))&&
        c.actor_stamp==static_cast<std::uint64_t>(body_.serial)&&c.component_token==body_.token;}
    void BeginRelease(Status reason){
        if(phase_==Phase::Free||phase_==Phase::Releasing)return;
        phase_=Phase::Releasing;restored_=0;reason_=reason;restore_deadline_=deadline_+500;restore_paused_=(env_.flags&kPaused)!=0;
    }
    Reply Make(const Command& c,Status status,std::uint64_t now,std::array<float,3> delta={},std::uint32_t flags=0)const{
        Reply r{};r.id=c.id;r.lease=c.op==Op::Reserve?c.id:c.lease;r.native_frame=env_.frame;r.at_ms=std::max<std::uint64_t>(now,1);
        r.world=c.world;r.target=c.target;r.spawn=c.spawn;r.status=status;r.source_hash=c.payload_hash;r.shape_revision=env_.revision;
        if(phase_!=Phase::Free&&c.target==source_.target&&c.spawn==source_.spawn){
            r.actor_serial=body_.serial;r.component_token=body_.token;r.health=body_.health;r.restored=restored_;
            for(int i=0;i<3;++i){r.position[i]=body_.position[i];r.delta[i]=status==Status::Prepared?body_.root_offset[i]:delta[i];}r.flags=flags;
            r.root_radius=body_.root_radius?body_.root_radius:body_.radius;r.root_half_height=body_.root_half?body_.root_half:body_.half;
        }
        Seal(r);return r;
    }
    void Queue(const Command& c,Status s,std::uint64_t now){out_.push_back(Make(c,s,now));}
    void Reject(const Command& c,Status s,std::uint64_t now){Finish(c,s,now,{},c.op==Op::Step?kStepNotExecuted:0);}
    void Finish(const Command& c,Status s,std::uint64_t now,std::array<float,3> delta={},std::uint32_t a=0,std::uint32_t b=0){
        const auto reply=Make(c,s,now,delta,a|b);if(ledger_->Finish(c.id,reply))out_.push_back(reply);
    }
    Backend& backend_;Environment env_;std::unique_ptr<Ledger> owned_;Ledger* ledger_;Phase phase_=Phase::Free;
    Command source_{};Body body_{};std::optional<Command> reserve_,release_;
    std::uint64_t deadline_=0,last_tick_=0,release_revision_=0,restore_deadline_=0;double spent_=0;float last_progress_=0;unsigned observed_=0,restored_=0;
    bool restore_paused_=false,identity_lost_=false;
    Status reason_=Status::Released;std::deque<Reply> out_;
};

// Per-activation leases with ONE deduplication history for every target and operation.
// A replay routed to another/free slot still cannot execute its side effect again.
template<std::size_t Capacity> class Fleet {
public:
    explicit Fleet(const std::array<Backend*,Capacity>& backends){for(std::size_t i=0;i<Capacity;++i)slots_[i]=std::make_unique<Engine>(*backends[i],&ledger_);}
    void Update(const Environment& env,std::uint64_t now){
        if(native_!=env.native||peer_!=env.peer){ledger_.Reset();native_=env.native;peer_=env.peer;}
        for(auto& engine:slots_)engine->Update(env,now);
    }
    bool Submit(const Command& command,std::uint64_t now){
        for(auto& engine:slots_)if(engine->Target(command))return engine->Submit(command,now);
        for(auto& engine:slots_)if(engine->phase()==Engine::Phase::Free&&engine->Room())return engine->Submit(command,now);
        for(auto& engine:slots_)if(engine->Room())return engine->Submit(command,now); // explicit Busy/replay/conflict
        return false;
    }
    bool Pop(Reply* reply){
        for(std::size_t i=0;i<Capacity;++i){auto slot=(next_+i)%Capacity;if(slots_[slot]->Pop(reply)){next_=(slot+1)%Capacity;return true;}}
        return false;
    }
    Engine& at(std::size_t slot){return *slots_.at(slot);}
private:
    Ledger ledger_;std::array<std::unique_ptr<Engine>,Capacity> slots_;
    std::uint64_t native_=0,peer_=0;std::size_t next_=0;
};
}
