#include "combat/motion_probe.h"
#include <windows.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <sstream>
#include <string>
#include "combat/sweep_params.h"
#include "combat/ai_tick_gate.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/game_hooks.h"
#include "input/input_router.h"
#include "probe/combat_sample.h"
#include "probe/probe.h"
#include "ue/function_call.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::combat::motion {
namespace {
namespace off = gen::off;
SRWLOCK g_lock = SRWLOCK_INIT;
Context g_context;
struct Command {
    unsigned action = 0;
    std::uint64_t id = 0, created = 0, native = 0, peer = 0;
    std::uint32_t world = 0, target = 0, spawn = 0;
    std::int32_t serial = 0;
    Step step;
};
Command g_pending;
std::atomic<bool> g_configured{false}, g_enabled{false};
std::atomic<bool> g_motion_enabled{false},g_ai_enabled{false};
bool g_motion_startup=false,g_ai_startup=false;
std::atomic<DWORD> g_thread{0};
std::atomic<std::uintptr_t> g_watch{0};
std::atomic<std::uintptr_t> g_ai_watch{0},g_tree_watch{0};
std::atomic<unsigned> g_wrong_threads{0};
std::uintptr_t g_base = 0;
// All state below is exclusively H1/H5 game-thread owned.
Context g_current;
Lease g_lease;
ProbeBudget g_budget;
ProbeBudget g_hold_budget;
Target g_target;
Peers g_peers;
bool g_ai_observing=false;
float g_hold_hp=0;
struct Seen { std::uint64_t originals=0,skips=0,last_ms=0; };
std::array<Seen,3> g_seen{};
ue::FVector g_last_position{};
std::uint64_t g_original_ticks = 0, g_skips = 0, g_next_log = 0;
bool g_failed = false;
struct Timing { std::uint64_t n=0, total=0, peak=0; };
Timing g_h1_time,g_h5_time,g_ai_time;
struct Timer {
    Timing& timing; LARGE_INTEGER start{},frequency{};
    explicit Timer(Timing& t):timing(t) { QueryPerformanceFrequency(&frequency); QueryPerformanceCounter(&start); }
    ~Timer() {
        LARGE_INTEGER end; QueryPerformanceCounter(&end);
        const auto us=static_cast<std::uint64_t>((end.QuadPart-start.QuadPart)*1000000/frequency.QuadPart);
        ++timing.n; timing.total+=us; timing.peak=std::max(timing.peak,us);
    }
};
struct Monitor { Target target; ue::FVector position; float hp; std::uint64_t started, next; Kind kind=Kind::Sweep; };
Monitor g_monitor{};
ue::FunctionCall g_move;
SweepParams g_params;

bool Finite(const ue::FVector& v) { return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z); }
float Distance(const ue::FVector& a,const ue::FVector& b) {
    return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));
}
bool Empty(std::uintptr_t at) {
    mem::RawArray a; return mem::ReadArray(at,&a) && a.num==0;
}
bool Same(const Target& target) {
    return target.valid() && ue::IdentityOf(target.actor)==ue::ObjectIdentity{static_cast<int>(target.id),target.serial} &&
        ue::IdentityOf(target.movement)==ue::ObjectIdentity{target.movement_index,target.movement_serial};
}
bool CurrentTarget(const Context& c,const Target& target) {
    return std::any_of(c.targets.begin(),c.targets.end(),[&](const auto& t) {
        return t.actor==target.actor && t.id==target.id && t.spawn==target.spawn && t.identity.serial==target.serial;
    });
}
const char* WorldSafeImpl(std::uintptr_t base,const Context& c,std::uint64_t now) {
    if(!c.enabled || !c.scope.valid() || !c.grid || c.updated_ms>now || now-c.updated_ms>200) return "context";
    if(mem::ReadPtr(base+gen::rva::GWorld)!=c.scope.world ||
       ue::IdentityOf(c.scope.world)!=ue::ObjectIdentity{c.scope.world_index,c.scope.world_serial}) return "world";
    const auto player=probe::FindPlayerObjects(c.scope.world);
    if(player.pawn!=c.pawn || ue::IdentityOf(c.pawn)!=c.pawn_identity || !ue::IsLive(player.controller)) return "player";
    const auto level=mem::ReadPtr(c.scope.world+off::UWorld::PersistentLevel);
    const auto settings=mem::ReadPtr(level+off::ULevel::WorldSettings);
    if(!ue::IsLive(settings) || mem::ReadOr<std::uintptr_t>(settings+off::AWorldSettings::PauserPlayerState,1)) return "pause";
    namespace bits=gen::bits::APlayerController;
    const auto controller=player.controller;
    if(mem::ReadOr<std::uint8_t>(controller+off::AController::IgnoreMoveInput,255) ||
       mem::ReadOr<std::uint8_t>(controller+off::AController::IgnoreLookInput,255) ||
       ((mem::ReadOr<std::uint8_t>(controller+bits::bCinematicMode_Offset,255)>>bits::bCinematicMode_Shift)&1) ||
       ((mem::ReadOr<std::uint8_t>(controller+bits::bShowMouseCursor_Offset,255)>>bits::bShowMouseCursor_Shift)&1)) return "script-or-menu";
    static std::uintptr_t pawn_class=0;
    static std::int32_t disabled=-1;
    const auto cls=ue::ClassOf(c.pawn);
    if(cls!=pawn_class) { pawn_class=cls; disabled=ue::FindPropertyOffset(cls,"b_InputsAreDisabled"); }
    if(disabled<0 || mem::ReadOr<std::uint8_t>(c.pawn+disabled,255) ||
       ue::AttachParentOf(mem::ReadPtr(c.pawn+off::AActor::RootComponent))) return "script";
    return nullptr;
}
const char* WorldSafe(const Context& c,std::uint64_t now) {
    return g_enabled.load()?WorldSafeImpl(g_base,c,now):"disabled";
}
using Sample=BodySample;
bool CurrentPlayer(const Context& c,const Target& t){return t.actor==c.pawn&&ue::ObjectIdentity{static_cast<int>(t.id),t.serial}==c.pawn_identity;}
const char* Inspect(const Context& c,const Target& t,Sample* s,bool geometry,bool ground=true,bool player=false) {
    if(!Same(t) || (player?!CurrentPlayer(c,t):!CurrentTarget(c,t)||t.actor==c.pawn) || !ue::IsLive(t.actor) || !ue::IsLive(t.movement)) return "identity";
    if(player&&(input::Router().owner()!=protocol::InputOwner::Sifu||hooks::PoseActive()))return "player-input-owner";
    const auto cls=ue::ClassName(t.actor);
    if(player?cls!="BP_FightingPlayer_C":cls!="BP_AICharacter_Grunt_M_HD1_Fighter_21_C" && cls!="BP_AICharacter_Grunt_M_HD1_Fighter_11_C") return "class-not-allowed";
    const auto pooled=mem::ReadOr<std::uint8_t>(t.actor+off::ASCCharacter::m_bIsPooled,255);
    const auto active=mem::ReadOr<std::uint8_t>(t.actor+off::ASCCharacter::m_bPooledActorActive,255);
    if(pooled>1 || active>1 || (pooled && !active)) return "pooled";
    const auto faction=mem::ReadOr<std::uint8_t>(t.actor+off::AFightingCharacter::m_eFaction,255);
    if(faction>=gen::value::EFactionsEnums::Count || (!player&&
       (faction==gen::value::EFactionsEnums::TMP_Neutral||faction==mem::ReadOr<std::uint8_t>(c.pawn+off::AFightingCharacter::m_eFaction,255)))) return "faction";
    s->health=mem::ReadPtr(t.actor+off::AFightingCharacter::m_HealthComponent);
    probe::HealthSample health;
    if(!ue::IsLive(s->health) || !probe::ReadHealthSample(s->health,&health) || health.health<=0 || health.down) return "health";
    s->hp=health.health;
    s->root=mem::ReadPtr(t.actor+off::AActor::RootComponent);
    const auto mesh=mem::ReadPtr(t.actor+off::ACharacter::Mesh);
    if(!ue::IsLive(s->root) || !ue::IsLive(mesh) || s->root!=mem::ReadPtr(t.actor+off::ACharacter::CapsuleComponent) ||
       ue::AttachParentOf(s->root) || ue::OuterOf(s->root)!=t.actor ||
       mem::ReadPtr(t.actor+off::ACharacter::CharacterMovement)!=t.movement ||
       mem::ReadPtr(t.movement+off::UActorComponent::OwnerPrivate)!=t.actor ||
       mem::ReadPtr(t.movement+off::UMovementComponent::UpdatedComponent)!=s->root ||
       mem::ReadPtr(t.movement+off::UMovementComponent::UpdatedPrimitive)!=s->root || !hooks::MovementHookOwns(t.movement)) return "root-or-hook";
    namespace actor_bits=gen::bits::AActor;
    std::uint8_t actor_flags=0;
    if(!mem::Read(t.actor+actor_bits::bActorEnableCollision_Offset,&actor_flags) ||
       !((actor_flags>>actor_bits::bActorEnableCollision_Shift)&1)) return "actor-collision";
    for(auto primitive:{s->root,mesh}) {
        namespace body_bits=gen::bits::FBodyInstanceCore;
        const auto body=primitive+off::UPrimitiveComponent::BodyInstance;
        const auto physics=mem::ReadOr<std::uint8_t>(body+body_bits::bSimulatePhysics_Offset,255);
        if((physics>>body_bits::bSimulatePhysics_Shift)&1) return "physics-or-ragdoll";
        if(primitive==s->root) {
            const auto enabled=mem::ReadOr<std::uint8_t>(body+off::FBodyInstance::CollisionEnabled,255);
            if(enabled!=1 && enabled!=3) return "root-query-disabled";
        }
    }
    s->mode=mem::ReadOr<std::uint8_t>(t.movement+off::UCharacterMovementComponent::MovementMode,255);
    if(s->mode>2 || mem::ReadOr<std::uint8_t>(t.movement+off::UFightingMovementComponent::m_bIsRushing,255)) return "mode-or-rush";
    if(player&&s->mode!=1&&s->mode!=2)return "player-not-walking";
    const auto order=mem::ReadPtr(t.actor+off::ABaseCharacter::m_OrderComponent);
    if(!ue::IsLive(order)) return "order-component";
    const auto manager=order+off::UOrderComponent::m_OrderManager;
    for(auto offset:{off::OrderManager::m_OrderList,off::OrderManager::m_DelayedOrderCommand,off::OrderManager::m_DelayedOrderCommandDuringPrediction})
        if(!Empty(manager+offset)) return "orders";
    for(auto offset:{off::UFightingMovementComponent::m_LocalResyncRequest,off::UFightingMovementComponent::m_LocalTimedoutResyncRequest,off::UFightingMovementComponent::m_ResyncRequestOnReplica})
        if(!Empty(t.movement+offset)) return "resync";
    const auto anim=mem::ReadPtr(mesh+off::USkeletalMeshComponent::AnimScriptInstance);
    if(!ue::IsLive(anim) || mem::ReadOr<std::uintptr_t>(mesh+off::USkeletalMeshComponent::ParallelAnimationEvaluationTask,1) ||
       mem::ReadOr<std::uintptr_t>(mesh+off::USkeletalMeshComponent::ParallelBlendPhysicsCompletionTask,1)) return "parallel-animation";
    for(auto offset:{off::UAnimInstance::MontageInstances,off::UAnimInstance::RootMotionBlendQueue}) if(!Empty(anim+offset)) return "montage";
    if(mem::ReadOr<std::uintptr_t>(anim+off::UAnimInstance::RootMotionMontageInstance,1)) return "root-montage";
    for(auto params:{anim+off::UAnimInstance::ExtractedRootMotion,t.movement+off::UCharacterMovementComponent::RootMotionParams})
        if(mem::ReadOr<std::uint8_t>(params+off::FRootMotionMovementParams::bHasRootMotion,255)) return "root-motion";
    const auto sources=t.movement+off::UCharacterMovementComponent::CurrentRootMotion;
    if(!Empty(sources+off::FRootMotionSourceGroup::RootMotionSources) || !Empty(sources+off::FRootMotionSourceGroup::PendingAddRootMotionSources)) return "root-source";
    ue::FVector pending{};
    if(!mem::Read(t.movement+off::UCharacterMovementComponent::PendingLaunchVelocity,&pending) || !Finite(pending) ||
       pending.x || pending.y || pending.z) return "pending-launch";
    ue::Affine transform;
    if(!ue::ReadWorldTransform(s->root,&s->position,&s->rotation) || !Finite(s->position) ||
       !std::isfinite(s->rotation.pitch) || !std::isfinite(s->rotation.yaw) || !std::isfinite(s->rotation.roll) ||
       !ue::ReadComponentToWorld(s->root,&transform) || !mem::Read(t.movement+off::UMovementComponent::Velocity,&s->velocity) ||
       !Finite(s->velocity) || std::fabs(s->velocity.x)+std::fabs(s->velocity.y)+std::fabs(s->velocity.z)>1) return "pose-or-velocity";
    if(player){
        ue::FVector acceleration{},last{};
        if(!mem::Read(t.movement+off::UCharacterMovementComponent::Acceleration,&acceleration)||!Finite(acceleration)||
           !mem::Read(t.actor+off::APawn::LastControlInputVector,&last)||!Finite(last)||
           std::fabs(acceleration.x)+std::fabs(acceleration.y)+std::fabs(acceleration.z)>1||
           std::fabs(last.x)+std::fabs(last.y)+std::fabs(last.z)>1e-5f)return "player-locomotion-input";
    }
    const float scale=transform.MinAxisScale();
    s->radius=mem::ReadOr<float>(s->root+off::UCapsuleComponent::CapsuleRadius,NAN)*scale;
    s->half=mem::ReadOr<float>(s->root+off::UCapsuleComponent::CapsuleHalfHeight,NAN)*scale;
    if(!std::isfinite(s->radius) || !std::isfinite(s->half) || s->radius<10 || s->radius>100 || s->half<s->radius || s->half>200) return "capsule";
    const float feet=s->position.z-s->half;
    if(ground&&(!c.grid || !collision::HasFloorSupport(*c.grid,{s->position.x,s->position.y,feet}))) return "ground";
    // Exclude uncertain start overlaps conservatively. A 1/8-cell grid cannot
    // prove root capsule clearance; the actual engine sweep remains authoritative.
    if(geometry) for(int x=collision::CellIndex(s->position.x-s->radius);x<=collision::CellIndex(s->position.x+s->radius);++x)
        for(int y=collision::CellIndex(s->position.y-s->radius);y<=collision::CellIndex(s->position.y+s->radius);++y)
            for(int z=collision::CellIndex(feet+collision::kCellCm);z<=collision::CellIndex(s->position.z+s->half);++z)
                if(c.grid->Get(x,y,z)) return "start-grid-overlap";
    return Same(t)?nullptr:"identity-changed";
}

Component Token(std::uintptr_t pointer) {
    const auto id=ue::IdentityOf(pointer);return {pointer,id.index,id.serial};
}
const char* InspectPeers(std::uintptr_t base,const Context& c,const Target& t,Peers* peers,bool idle) {
    if(!Same(t)||!CurrentTarget(c,t))return "ai-target";
    const auto ai=mem::ReadPtr(t.actor+off::AFightingCharacter::m_AIComponent);
    const auto controller=mem::ReadPtr(t.actor+off::APawn::Controller);
    if(!ue::IsLive(ai)||ue::OuterOf(ai)!=t.actor||mem::ReadPtr(ai+off::UActorComponent::OwnerPrivate)!=t.actor||
       !ue::IsA(ai,ue::FindClass("AIFightingComponent"))||!ue::IsLive(controller)||
       !ue::IsA(controller,ue::FindClass("AIController"))||mem::ReadPtr(controller+off::AController::Pawn)!=t.actor)return "ai-owner";
    // Sifu lazily fills the AI cache. Use the already-linked Controller brain and
    // close both reverse weak-reference paths, without calling the cache-writing getter.
    const auto bt=mem::ReadPtr(controller+off::AAIController::BrainComponent);
    if(!ue::IsLive(bt)||!ue::IsA(bt,ue::FindClass("AIBehaviorTreeComponent"))||ue::OuterOf(bt)!=controller||
       mem::ReadPtr(bt+off::UActorComponent::OwnerPrivate)!=controller||
       ue::ResolveWeak(bt+off::USCBehaviorTreeComponent::m_OwnerAiComponent)!=ai||
       ue::ResolveWeak(bt+off::UAIBehaviorTreeComponent::m_CharacterOwner)!=t.actor)return "behavior-owner";
    const auto cached=ue::ResolveWeak(ai+off::USCAIComponent::m_CachedBehaviorTreeComponent);
    if(cached&&cached!=bt)return "behavior-cache-mismatch";
    if(mem::ReadPtr(ue::OuterOf(controller)+off::ULevel::OwningWorld)!=c.scope.world)return "ai-world";
    *peers={Token(ai),Token(bt)};
    if(!peers->valid()||!ai_gate::Matches(base,*peers))return "ai-identity-or-slot";
    if(idle) {
        namespace bits=gen::bits::UBehaviorTreeComponent;
        std::uint8_t running=0,paused=0;
        if(!mem::Read(bt+bits::bIsRunning_Offset,&running)||!mem::Read(bt+bits::bIsPaused_Offset,&paused)||
           !((running>>bits::bIsRunning_Shift)&1)||((paused>>bits::bIsPaused_Shift)&1))return "behavior-not-running";
        if(mem::ReadOr<std::uint8_t>(ai+off::UAIFightingComponent::m_bIsOutOfIdle,255)||
           mem::ReadOr<std::uint8_t>(ai+off::UAIFightingComponent::m_bForceActionHandlerTick,255))return "ai-not-idle";
    }
    return nullptr;
}

bool SamePeers() {
    Peers peers;return !InspectPeers(g_base,g_current,g_target,&peers,false)&&peers==g_peers;
}
void DisarmAi() {
    g_ai_watch.store(0);g_tree_watch.store(0);
    if(g_ai_observing) {
        g_lease.Abandon();g_watch.store(0);g_target={};g_original_ticks=0;
    }
    g_ai_observing=false;g_peers={};g_seen={};g_wrong_threads.store(0);
    if(!ai_gate::Uninstall()) {g_failed=true;SC_LOG("[npc-ai] cleanup REFUSED slot changed; all target filters cleared");}
}

bool ResolveMove() {
    // Native UFunction metadata may have no allocated weak serial. Resolve and
    // verify its registration, declaring class and complete layout on every request;
    // never cache it using an unassigned serial. Actor/movement serials stay mandatory.
    const auto actor=ue::FindClass("Actor");
    if(!actor || !g_move.Resolve(actor,"K2_SetActorLocationAndRotation")) return false;
    const auto fn=g_move.function();
    const auto props=ue::ListProperties(fn);
    if(!ue::IsRegistered(fn) || ue::OuterOf(fn)!=actor || ue::ClassName(fn)!="Function" ||
       !(mem::ReadOr<std::uint32_t>(fn+off::UFunction::FunctionFlags,0)&0x400u) || props.size()!=6) return false;
    SweepParams layout; layout.size=g_move.params_size();
    const std::array<const char*,6> names{"NewLocation","NewRotation","bSweep","SweepHitResult","bTeleport","ReturnValue"};
    for(const auto& p:props) {
        const auto found=std::find(names.begin(),names.end(),p.name);
        if(found==names.end() || p.array_dim!=1) return false;
        const auto i=static_cast<int>(found-names.begin());
        if(layout.offset[i]!=-1 || p.element_size!=SweepParams::widths[i]) return false;
        layout.offset[i]=p.offset;
        if(i==SweepParams::Location || i==SweepParams::Rotation || i==SweepParams::Hit) {
            if(p.type!="StructProperty" || p.detail!=(i==0?"Vector":i==1?"Rotator":"HitResult")) return false;
            const auto structure=mem::ReadPtr(p.field+off::FStructProperty::Struct);
            if(!ue::IsRegistered(structure) || mem::ReadOr<int>(structure+off::UStruct::PropertiesSize,0)!=p.element_size) return false;
        } else if(p.type!="BoolProperty" || mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::FieldSize,0)!=1 ||
                  mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteOffset,255)!=0 ||
                  mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteMask,0)!=1 ||
                  mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::FieldMask,0)!=255) return false;
    }
    if(!layout.valid()) return false;
    g_params=layout;
    static bool logged=false;
    if(!logged) SC_LOG("[npc-motion] sweep selfcheck PASS size=%d loc=%d rot=%d sweep=%d hit=%d/%u teleport=%d return=%d",
        layout.size,layout.offset[0],layout.offset[1],layout.offset[2],layout.offset[3],gen::size::FHitResult,layout.offset[4],layout.offset[5]);
    logged=true;
    return true;
}
void Release(const char* reason) {
    if(g_lease.state()==State::Machine || g_lease.state()==State::AcquirePending) {
        g_lease.Release();
        SC_LOG("[npc-motion] release id=%llu reason=%s skipped=%llu original_ticks=%llu",g_lease.request().id,reason,g_skips,g_original_ticks);
        if(g_lease.request().kind==Kind::AIHold) SC_LOG("[npc-ai] release id=%llu reason=%s deadline=%llu now=%llu skipped=%llu/%llu/%llu original=%llu/%llu/%llu",
            g_lease.request().id,reason,g_lease.deadline(),GetTickCount64(),g_seen[0].skips,g_seen[1].skips,g_seen[2].skips,
            g_seen[0].originals,g_seen[1].originals,g_seen[2].originals);
    }
}
bool HoldSafe(std::uint64_t now) {
    Sample sample; Peers peers;
    const char* reason=WorldSafe(g_current,now);
    if(!reason&&!g_ai_enabled.load())reason="ai-disabled";
    if(!reason&&g_wrong_threads.load())reason="callback-thread";
    if(!reason)reason=Inspect(g_current,g_target,&sample,false);
    if(!reason)reason=InspectPeers(g_base,g_current,g_target,&peers,true);
    if(!reason&&peers!=g_peers)reason="ai-components-changed";
    if(!reason&&(sample.hp!=g_hold_hp||Distance(sample.position,g_last_position)>1))reason="hp-or-displacement";
    if(!reason&&now>=g_lease.deadline())reason="deadline";
    if(reason)Release(reason);
    return !reason;
}
void Original(Part part) {
    const auto i=part==Part::Movement?0:part==Part::AI?1:2;
    ++g_seen[i].originals;g_seen[i].last_ms=GetTickCount64();
    const auto before=g_lease.state();g_lease.OriginalTick(g_target,part,g_peers);
    if(before==State::ReleasePending&&g_lease.state()==State::NativeFree)
        SC_LOG("[npc-ai] restored all original Ticks id=%llu target=%u/%u mask=%u original=%llu/%llu/%llu skipped=%llu/%llu/%llu",
            g_lease.request().id,g_target.id,g_target.spawn,g_lease.restored(),g_seen[0].originals,g_seen[1].originals,g_seen[2].originals,
            g_seen[0].skips,g_seen[1].skips,g_seen[2].skips);
}
void Hold(const Command& cmd,const Context& c,const Target& target,const Sample& sample,std::uint64_t now) {
    if(!g_ai_observing||target!=g_target||!ai_gate::Installed()||!SamePeers()||g_wrong_threads.load()||
       std::any_of(g_seen.begin(),g_seen.end(),[&](const Seen& s){return !s.originals||s.last_ms>now||now-s.last_ms>500;})) {
        SC_LOG("[npc-ai] refused hold id=%llu reason=forwarding-preflight",cmd.id);return;
    }
    Request request{c.scope,target,cmd.id,cmd.created,100,{},Kind::AIHold,g_peers};
    const auto result=g_lease.Begin(request,c.scope,now);
    if(result.replay||result.outcome!=Outcome::Ready) {
        SC_LOG("[npc-ai] refused hold id=%llu outcome=%d replay=%d",cmd.id,static_cast<int>(result.outcome),result.replay);return;
    }
    if(!g_hold_budget.Spend(now)) {g_lease.Complete(cmd.id,Outcome::Budget);return;}
    g_hold_hp=sample.hp;g_last_position=sample.position;
    g_lease.Complete(cmd.id,Outcome::Applied); // no game function call or movement suppression
    g_monitor={target,sample.position,sample.hp,now,now+1000,Kind::AIHold};
    SC_LOG("[npc-ai] hold id=%llu target=%u/%u/%d thread=%lu duration=100ms deadline=%llu HP=%.3f movement=original calls=%u original=%llu/%llu/%llu",
        cmd.id,target.id,target.spawn,target.serial,GetCurrentThreadId(),g_lease.deadline(),sample.hp,g_hold_budget.calls,
        g_seen[0].originals,g_seen[1].originals,g_seen[2].originals);
}
void Move(const Command& cmd,const Context& c,const Target& target,const Sample& before,std::uint64_t now) {
    Request r{c.scope,target,cmd.id,cmd.created,100,cmd.step,Kind::Sweep,{}};
    const auto reserved=g_lease.Begin(r,c.scope,now);
    if(reserved.replay || reserved.outcome!=Outcome::Ready) {
        SC_LOG("[npc-motion] request id=%llu outcome=%d replay=%d",cmd.id,static_cast<int>(reserved.outcome),reserved.replay); return;
    }
    if(!g_original_ticks || !g_budget.Spend(now)) {
        g_lease.Complete(cmd.id,Outcome::Budget);
        SC_LOG("[npc-motion] refused request id=%llu reason=probe-budget",cmd.id); return;
    }
    std::array<std::uint8_t,512> bytes{};
    const ue::FVector wanted{before.position.x+cmd.step.x,before.position.y+cmd.step.y,before.position.z};
    if(!g_params.Encode(bytes,wanted,before.rotation)) { g_lease.Complete(cmd.id,Outcome::Invalid); return; }
    LARGE_INTEGER start,end,freq; QueryPerformanceFrequency(&freq); QueryPerformanceCounter(&start);
    g_move.Invoke(target.actor,bytes.data()); // approved M1 only, H1; no direct memory writes
    QueryPerformanceCounter(&end);
    ue::FVector actual{}; ue::FRotator rotation{};
    const bool read=Same(target) && ue::ReadWorldTransform(before.root,&actual,&rotation) && Finite(actual);
    const auto hit=bytes.data()+g_params.offset[SweepParams::Hit];
    namespace bits=gen::bits::FHitResult;
    const bool blocked=(hit[bits::bBlockingHit_Offset]>>bits::bBlockingHit_Shift)&1;
    const bool penetrating=(hit[bits::bStartPenetrating_Offset]>>bits::bStartPenetrating_Shift)&1;
    float time=NAN; std::memcpy(&time,hit+off::FHitResult::Time,sizeof(time));
    const bool returned=bytes[g_params.offset[SweepParams::Return]]!=0;
    Outcome outcome=Outcome::Applied;
    if(!read) outcome=Outcome::Invalid;
    else if(penetrating) outcome=Outcome::Penetrating;
    else if(blocked) outcome=Outcome::Blocked;
    else if(!returned || Distance(actual,wanted)>0.1f) outcome=Outcome::Partial;
    const float x=read?actual.x-before.position.x:0,y=read?actual.y-before.position.y:0,z=read?actual.z-before.position.z:0;
    const auto hp=mem::ReadOr<float>(before.health+off::UHealthComponent::m_fHealth,NAN);
    if(!read || !std::isfinite(hp) || hp!=before.hp || Distance(actual,before.position)>10.1f ||
       std::fabs(z)>0.1f || std::fabs(rotation.pitch-before.rotation.pitch)>0.01f ||
       std::fabs(rotation.yaw-before.rotation.yaw)>0.01f || std::fabs(rotation.roll-before.rotation.roll)>0.01f) {
        outcome=Outcome::Invalid; g_failed=true;
    }
    g_lease.Complete(cmd.id,outcome,x,y,z); g_last_position=actual;
    const auto us=(end.QuadPart-start.QuadPart)*1000000/freq.QuadPart;
    SC_LOG("[npc-motion] result id=%llu target=%u/%u/%d requested=(%.3f,%.3f,0) actual=(%.3f,%.3f,%.3f) outcome=%d returned=%d blocking=%d penetrating=%d hit_time=%.6f HP=%.3f->%.3f cpu_us=%lld over_budget=%d duration=100ms calls=%u",
        cmd.id,target.id,target.spawn,target.serial,cmd.step.x,cmd.step.y,x,y,z,static_cast<int>(outcome),returned,blocked,penetrating,time,before.hp,hp,us,us>500,g_budget.calls);
    g_monitor={target,actual,hp,now,now+1000};
}
}

const char* CheckWorld(std::uintptr_t base,const Context& c,std::uint64_t now) { return WorldSafeImpl(base,c,now); }
const char* CheckBody(const Context& c,const Target& t,BodySample* s,bool geometry,bool ground) { return Inspect(c,t,s,geometry,ground); }
const char* CheckPlayerBody(const Context& c,const Target& t,BodySample* s,bool geometry) { return Inspect(c,t,s,geometry,false,true); }
const char* CheckPeers(std::uintptr_t base,const Context& c,const Target& t,Peers* p,bool idle) { return InspectPeers(base,c,t,p,idle); }
bool SweepBody(const Context& c,const Target& t,const BodySample& before,const ue::FVector& delta,SweepResult* result,bool player) {
    *result={};
    const float length=Distance(delta,{});
    if(!Finite(delta)||length<=0||length>10.0001f||!Same(t)||!ResolveMove())return false;
    std::array<std::uint8_t,512> bytes{};
    const ue::FVector wanted{before.position.x+delta.x,before.position.y+delta.y,before.position.z+delta.z};
    if(!g_params.Encode(bytes,wanted,before.rotation))return false;
    result->invoked=true;
    g_move.Invoke(t.actor,bytes.data());
    const auto hit=bytes.data()+g_params.offset[SweepParams::Hit];
    namespace bits=gen::bits::FHitResult;
    result->returned=bytes[g_params.offset[SweepParams::Return]]!=0;
    result->blocking=(hit[bits::bBlockingHit_Offset]>>bits::bBlockingHit_Shift)&1;
    result->penetrating=(hit[bits::bStartPenetrating_Offset]>>bits::bStartPenetrating_Shift)&1;
    static_assert(off::FHitResult::ImpactNormal+sizeof(ue::FVector)<=gen::size::FHitResult);
    std::memcpy(&result->normal,hit+off::FHitResult::ImpactNormal,sizeof(result->normal));
    const float norm=Distance(result->normal,{});
    if(!Finite(result->normal)||norm<.9f||norm>1.1f)result->normal={};
    // Inspect may reject a new overlap/command. Always retain the actual read-back
    // when identity survived; never report the requested endpoint as an observation.
    auto& after=result->body;after=before;
    const bool pose=Same(t)&&ue::ReadWorldTransform(before.root,&after.position,&after.rotation)&&Finite(after.position);
    probe::HealthSample health;
    const bool hp=Same(t)&&probe::ReadHealthSample(before.health,&health)&&std::isfinite(health.health);
    if(hp)after.hp=health.health;
    if(!pose||!hp)return false;
    if(Distance(after.position,before.position)>length+0.1f||after.hp!=before.hp||
       std::fabs(after.rotation.pitch-before.rotation.pitch)>0.01f||
       std::fabs(after.rotation.yaw-before.rotation.yaw)>0.01f||
       std::fabs(after.rotation.roll-before.rotation.roll)>0.01f)return false;
    return player?CurrentPlayer(c,t):CurrentTarget(c,t);
}
void Configure(bool enabled,bool ai_enabled) {
    g_motion_startup=enabled;g_ai_startup=ai_enabled;
    g_motion_enabled.store(enabled);g_ai_enabled.store(ai_enabled);
    g_configured.store(enabled||ai_enabled); g_enabled.store(enabled||ai_enabled);
    if(enabled||ai_enabled) SC_LOG("[npc-motion] diagnostic enabled motion=%d ai=%d; observe only until explicit addressed request; initial cap=100ms/4 calls",enabled,ai_enabled);
}
void UpdateContext(Context context) {
    if(!g_configured.load()) return;
    static std::uint64_t next_poll=0,last_id=0;
    Command command;
    const auto now=GetTickCount64();
    if(now>=next_poll) {
        next_poll=now+50;
        const auto root=log::RootDirectory();
        g_motion_enabled.store(g_motion_startup&&GetFileAttributesW((root+L"\\npc-motion-probe").c_str())!=INVALID_FILE_ATTRIBUTES);
        g_ai_enabled.store(g_ai_startup&&GetFileAttributesW((root+L"\\npc-ai-probe").c_str())!=INVALID_FILE_ATTRIBUTES);
        g_enabled.store(g_motion_enabled.load()||g_ai_enabled.load());
        if(g_enabled.load()) if(FILE* file=_wfopen((root+L"\\npc-motion-request.txt").c_str(),L"rb")) {
            std::array<char,1025> line{}; const auto size=std::fread(line.data(),1,line.size(),file); std::fclose(file);
            if(size>0 && size<line.size()) {
                std::istringstream input(std::string(line.data(),size)); std::string extra;
                if(input>>command.action>>command.id>>command.created>>command.native>>command.peer>>command.world>>command.target>>command.spawn>>command.serial>>command.step.x>>command.step.y &&
                   !(input>>extra) && command.action<=3 && command.id>last_id) last_id=command.id;
                else command={};
            }
        }
    }
    context.enabled=context.enabled && g_enabled.load();
    AcquireSRWLockExclusive(&g_lock);
    g_context=std::move(context);
    if(command.id && !g_pending.id) g_pending=command;
    else if(command.id) SC_LOG("[npc-motion] rejected queue full id=%llu",command.id);
    ReleaseSRWLockExclusive(&g_lock);
}
void ApplyOnGameThread(std::uintptr_t base) {
    if(!g_configured.load()) return;
    const auto thread=GetCurrentThreadId();
    DWORD zero=0; g_thread.compare_exchange_strong(zero,thread);
    if(g_thread.load()!=thread) return;
    Timer timer(g_h1_time);
    g_base=base;
    Command command;
    AcquireSRWLockShared(&g_lock); const Context c=g_context; ReleaseSRWLockShared(&g_lock);
    AcquireSRWLockExclusive(&g_lock); command=g_pending; g_pending={}; ReleaseSRWLockExclusive(&g_lock);
    const auto now=GetTickCount64();
    if(c.scope!=g_current.scope) { Release("scope-changed");DisarmAi();g_lease.Reset(); g_watch.store(0); g_target={}; g_monitor={}; g_original_ticks=0; }
    g_current=c;
    if(g_ai_observing&&!g_ai_enabled.load()) {Release("ai-disabled");DisarmAi();SC_LOG("[npc-ai] disabled; target filters cleared and hook pair removed");}
    const char* unsafe=WorldSafe(c,now);
    if(unsafe || g_failed) {
        Release(unsafe?unsafe:"probe-stopped");
        if(command.id) SC_LOG("[npc-motion] refused request id=%llu reason=%s",command.id,unsafe?unsafe:"probe-stopped");
        return;
    }
    static bool signature_prepared=false;
    if(g_motion_enabled.load()&&!signature_prepared) signature_prepared=ResolveMove(); // read-only warm-up
    if(g_watch.load() && (!Same(g_target) || !CurrentTarget(c,g_target))) {
        Release("target-lost");DisarmAi(); g_lease.Abandon(); g_watch.store(0); g_target={}; g_original_ticks=0;
    }
    if(g_ai_observing&&!SamePeers()) {Release("ai-components-lost");DisarmAi();}
    if(g_lease.state()==State::Machine || g_lease.state()==State::AcquirePending) {
        if(g_lease.request().kind==Kind::AIHold)HoldSafe(now);
        else {
            Sample sample; const char* reason=Inspect(c,g_target,&sample,false);
            if(!g_motion_enabled.load())reason="motion-disabled";
            if(reason || Distance(sample.position,g_last_position)>1) Release(reason?reason:"external-displacement");
            if(now>=g_lease.deadline()) Release("deadline");
        }
    }
    if(g_monitor.target.valid() && now>=g_monitor.next) {
        if(Same(g_monitor.target) && CurrentTarget(c,g_monitor.target)) {
            const auto root=mem::ReadPtr(g_monitor.target.actor+off::AActor::RootComponent);
            ue::FVector position{};
            const auto health=mem::ReadPtr(g_monitor.target.actor+off::AFightingCharacter::m_HealthComponent);
            const auto hp=mem::ReadOr<float>(health+off::UHealthComponent::m_fHealth,NAN);
            if(ue::ReadWorldLocation(root,&position)) SC_LOG("[npc-motion] monitor elapsed=%llums target=%u/%u drift=%.3fcm HP=%.3f original_ticks=%llu skipped=%llu state=%d",
                now-g_monitor.started,g_monitor.target.id,g_monitor.target.spawn,Distance(position,g_monitor.position),hp,g_original_ticks,g_skips,static_cast<int>(g_lease.state()));
            if(g_monitor.kind==Kind::AIHold)SC_LOG("[npc-ai] monitor mask=%u original=%llu/%llu/%llu skipped=%llu/%llu/%llu wrong_threads=%u",
                g_lease.restored(),g_seen[0].originals,g_seen[1].originals,g_seen[2].originals,g_seen[0].skips,g_seen[1].skips,g_seen[2].skips,g_wrong_threads.load());
        }
        g_monitor.next=now+1000; if(now-g_monitor.started>=5000) g_monitor={};
    }
    if(now>=g_next_log) {
        g_next_log=now+2000;
        SC_LOG("[npc-motion] scope native=%llu peer=%llu world=%u thread=%lu targets=%zu watched=%u original=%llu skipped=%llu state=%d",
            c.scope.native_session,c.scope.peer_session,c.scope.generation,thread,c.targets.size(),g_target.id,g_original_ticks,g_skips,static_cast<int>(g_lease.state()));
        SC_LOG("[npc-motion] diagnostic_elapsed cumulative H1_n=%llu avg_us=%llu peak_us=%llu H5_n=%llu avg_us=%llu peak_us=%llu (includes idle/invalid scopes; wall clock, not GPU)",
            g_h1_time.n,g_h1_time.n?g_h1_time.total/g_h1_time.n:0,g_h1_time.peak,
            g_h5_time.n,g_h5_time.n?g_h5_time.total/g_h5_time.n:0,g_h5_time.peak);
        if(g_ai_observing)SC_LOG("[npc-ai] forwarding target=%u/%u ai=%d/%d tree=%d/%d thread=%lu original=%llu/%llu/%llu skipped=%llu/%llu/%llu wrong_threads=%u",
            g_target.id,g_target.spawn,g_peers.ai.index,g_peers.ai.serial,g_peers.behavior.index,g_peers.behavior.serial,thread,
            g_seen[0].originals,g_seen[1].originals,g_seen[2].originals,g_seen[0].skips,g_seen[1].skips,g_seen[2].skips,g_wrong_threads.load());
        if(g_ai_observing)SC_LOG("[npc-ai] decision_validation n=%llu avg_us=%llu peak_us=%llu (module checks only; excludes original Tick)",
            g_ai_time.n,g_ai_time.n?g_ai_time.total/g_ai_time.n:0,g_ai_time.peak);
        for(const auto& t:c.targets) {
            const auto movement=mem::ReadPtr(t.actor+off::ACharacter::CharacterMovement); const auto id=ue::IdentityOf(movement);
            Target target{t.actor,movement,t.id,t.spawn,t.identity.serial,id.index,id.serial}; Sample sample;
            const auto reason=Inspect(c,target,&sample,true);
            SC_LOG("[npc-motion] qualification target=%u/%u/%d class=%s reason=%s mode=%d loc=(%.2f,%.2f,%.2f)",t.id,t.spawn,t.identity.serial,ue::ClassName(t.actor).c_str(),reason?reason:"eligible",sample.mode,sample.position.x,sample.position.y,sample.position.z);
            if(g_ai_enabled.load()) {Peers peers;const auto ai_reason=InspectPeers(g_base,c,target,&peers,true);
                SC_LOG("[npc-ai] qualification target=%u/%u/%d reason=%s ai=%d/%d tree=%d/%d",t.id,t.spawn,t.identity.serial,ai_reason?ai_reason:"eligible",peers.ai.index,peers.ai.serial,peers.behavior.index,peers.behavior.serial);}
        }
    }
    if(!command.id) return;
    if(command.native!=c.scope.native_session || command.peer!=c.scope.peer_session || command.world!=c.scope.generation ||
       command.created>now || now-command.created>1000 || (command.action==1 && !command.step.valid()) ||
       (command.action!=1&&(command.step.x!=0||command.step.y!=0)) ||
       (command.action<2?!g_motion_enabled.load():!g_ai_enabled.load())) {
        SC_LOG("[npc-motion] refused request id=%llu reason=scope-age-or-step",command.id); return;
    }
    const auto found=std::find_if(c.targets.begin(),c.targets.end(),[&](const auto& t){ return t.id==command.target && t.spawn==command.spawn && t.identity.serial==command.serial; });
    if(found==c.targets.end()) { SC_LOG("[npc-motion] refused request id=%llu reason=target",command.id); return; }
    const auto movement=mem::ReadPtr(found->actor+off::ACharacter::CharacterMovement); const auto id=ue::IdentityOf(movement);
    Target target{found->actor,movement,found->id,found->spawn,found->identity.serial,id.index,id.serial}; Sample sample;
    const auto reason=Inspect(c,target,&sample,true);
    if(reason) { SC_LOG("[npc-motion] refused request id=%llu reason=%s",command.id,reason); return; }
    if(command.action>=2) {
        Peers peers;const auto ai_reason=InspectPeers(g_base,c,target,&peers,true);
        if(ai_reason) {SC_LOG("[npc-ai] refused request id=%llu reason=%s",command.id,ai_reason);return;}
        if(command.action==3) {Hold(command,c,target,sample,now);return;}
        if(g_lease.state()!=State::NativeFree) {SC_LOG("[npc-ai] refused observe while leased");return;}
        DisarmAi();
        if(g_failed||!ai_gate::Install(base,peers)) {DisarmAi();g_failed=true;SC_LOG("[npc-ai] refused hook pair; stopped");return;}
        g_target=target;g_peers=peers;g_original_ticks=0;g_skips=0;g_seen={};g_wrong_threads.store(0);g_ai_observing=true;
        g_watch.store(movement);g_ai_watch.store(peers.ai.pointer);g_tree_watch.store(peers.behavior.pointer);
        SC_LOG("[npc-ai] observe target=%u/%u/%d thread=%lu ai=%d/%d tree=%d/%d forwarding only, no movement call",
            target.id,target.spawn,target.serial,thread,peers.ai.index,peers.ai.serial,peers.behavior.index,peers.behavior.serial);return;
    }
    if(command.action==0) {
        if(g_lease.state()!=State::NativeFree) { SC_LOG("[npc-motion] refused observe while leased"); return; }
        DisarmAi();if(g_failed)return;
        g_target=target; g_original_ticks=0; g_skips=0; g_watch.store(movement);
        SC_LOG("[npc-motion] observe target=%u/%u/%d thread=%lu, no movement call",target.id,target.spawn,target.serial,thread); return;
    }
    if(g_ai_observing || target!=g_target || !g_original_ticks || !ResolveMove()) { SC_LOG("[npc-motion] refused request id=%llu reason=observation-or-signature",command.id); return; }
    Move(command,c,target,sample,now);
}
bool SkipMovement(std::uintptr_t component) {
    if(component!=g_watch.load())return false;
    if(GetCurrentThreadId()!=g_thread.load()) {g_wrong_threads.fetch_or(1);return false;}
    Timer timer(g_h5_time);
    if(g_ai_observing) {if(g_lease.state()==State::Machine)HoldSafe(GetTickCount64());return false;}
    if(!g_motion_enabled.load()) { Release("disabled"); return false; }
    if(g_lease.state()!=State::Machine) return false;
    const auto now=GetTickCount64(); Sample sample;
    const auto world=WorldSafe(g_current,now);
    const auto reason=world?world:Inspect(g_current,g_target,&sample,false);
    if(reason || now>=g_lease.deadline() || Distance(sample.position,g_last_position)>1) {
        Release(reason?reason:"deadline-or-displacement"); return false;
    }
    ++g_skips; return true; // approved M2, one validated component only
}
void AfterOriginalMovement(std::uintptr_t component) {
    if(component!=g_watch.load() || GetCurrentThreadId()!=g_thread.load() || !Same(g_target) || !CurrentTarget(g_current,g_target)) return;
    ++g_original_ticks;
    if(g_ai_observing) {if(SamePeers())Original(Part::Movement);return;}
    const auto before=g_lease.state(); g_lease.OriginalTick(g_target);
    if(before==State::ReleasePending && g_lease.state()==State::NativeFree)
        SC_LOG("[npc-motion] restored original Tick target=%u/%u original_ticks=%llu skipped=%llu",g_target.id,g_target.spawn,g_original_ticks,g_skips);
}
bool SkipDecision(std::uintptr_t component,Part part) {
    const auto watched=part==Part::AI?g_ai_watch.load():g_tree_watch.load();
    if(component!=watched||!watched)return false;
    if(GetCurrentThreadId()!=g_thread.load()) {g_wrong_threads.fetch_or(static_cast<unsigned>(part));return false;}
    Timer timer(g_ai_time);
    if(!g_ai_observing||g_lease.state()!=State::Machine||g_lease.request().kind!=Kind::AIHold||!HoldSafe(GetTickCount64()))return false;
    ++g_seen[part==Part::AI?1:2].skips;return true;
}
void AfterOriginalDecision(std::uintptr_t component,Part part) {
    const auto watched=part==Part::AI?g_ai_watch.load():g_tree_watch.load();
    if(component!=watched||!watched)return;
    if(GetCurrentThreadId()!=g_thread.load()) {g_wrong_threads.fetch_or(static_cast<unsigned>(part));return;}
    if(g_ai_observing&&SamePeers())Original(part);
}
}  // namespace sifucraft::combat::motion
