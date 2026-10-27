#include "combat/player_idle_gate.h"
#include <array>
#include <cstring>
#include "core/log.h"
#include "core/safe_memory.h"
#include "probe/combat_sample.h"
#include "ue/objects.h"

namespace sifucraft::world_motion::player_idle {
namespace {
namespace off=gen::off;
struct Owner {
    std::uintptr_t world=0,actor=0,component=0,handler=0,instance=0;
    ue::ObjectIdentity actor_id,component_id,instance_id;
};
Owner held;
bool suppressed=false,checked=false,enabled=false;
std::uint64_t stopped=0,resumed=0;
bool Schema(std::uintptr_t base){
    if(checked)return enabled;
    checked=true;
    constexpr std::array<std::uint8_t,14> reset{0x40,0x53,0x48,0x83,0xec,0x20,0x48,0x8d,0x99,0x90,0,0,0,0x48};
    constexpr std::array<std::uint8_t,13> start{0x48,0x89,0x5c,0x24,0x20,0x55,0x48,0x81,0xec,0xb0,0,0,0};
    std::array<std::uint8_t,reset.size()> actual{};
    std::array<std::uint8_t,start.size()> actual_start{};
    enabled=mem::Copy(actual.data(),base+gen::rva::FidgetResetTimer,actual.size())&&actual==reset&&
        mem::Copy(actual_start.data(),base+gen::rva::FidgetStartTimer,actual_start.size())&&actual_start==start;
    SC_LOG("[player-idle] selfcheck %s ResetFidgetTimer=%X TryToStart=%X",enabled?"PASS":"REFUSE",gen::rva::FidgetResetTimer,gen::rva::FidgetStartTimer);
    return enabled;
}
bool Read(std::uintptr_t base,const combat::motion::Context& c,Owner* out){
    const auto actor=c.pawn;
    if(!c.scope.world||mem::ReadPtr(base+gen::rva::GWorld)!=c.scope.world||!ue::IsLive(actor)||
       ue::IdentityOf(actor)!=c.pawn_identity||ue::ClassName(actor)!="BP_FightingPlayer_C"||
       mem::ReadPtr(ue::OuterOf(actor)+off::ULevel::OwningWorld)!=c.scope.world)return false;
    probe::HealthSample health;
    if(!probe::ReadHealthSample(mem::ReadPtr(actor+off::AFightingCharacter::m_HealthComponent),&health)||health.health<=0||health.down)return false;
    const auto pooled=mem::ReadOr<std::uint8_t>(actor+off::ASCCharacter::m_bIsPooled,255);
    const auto active=mem::ReadOr<std::uint8_t>(actor+off::ASCCharacter::m_bPooledActorActive,255);
    if(pooled>1||active>1||(pooled&&!active))return false;
    const auto component=mem::ReadPtr(actor+off::ACharacter::CharacterMovement);
    if(!ue::IsLive(component)||!ue::IsA(component,ue::FindClass("FightingMovementComponent"))||
       ue::OuterOf(component)!=actor||mem::ReadPtr(component+off::UActorComponent::OwnerPrivate)!=actor)return false;
    const auto asm_component=mem::ReadPtr(actor+off::AFightingCharacter::m_ASMComponent);
    if(!ue::IsLive(asm_component)||!ue::IsA(asm_component,ue::FindClass("ASMComponent"))||
       ue::OuterOf(asm_component)!=actor||mem::ReadPtr(asm_component+off::UActorComponent::OwnerPrivate)!=actor)return false;
    const auto manager=mem::ReadPtr(asm_component+off::UASMComponent::m_ActionStateManager);
    if(!manager||ue::ResolveWeak(manager+off::ActionStateManager::m_Owner)!=actor)return false;
    const std::uintptr_t states[]={mem::ReadPtr(manager+off::SCActionStateManager::m_currentState),
        manager+off::SCActionStateManager::m_PreState,manager+off::SCActionStateManager::m_PostState};
    std::uintptr_t process=0;
    for(auto state:states){
        if(!state||ue::ResolveWeak(state+off::SCActionState::m_owner)!=actor)continue;
        mem::RawArray processes{};
        if(!mem::ReadArray(state+off::SCActionState::m_actionProcesses,&processes)||processes.num>64)return false;
        for(int i=0;i<processes.num;++i){
            const auto candidate=mem::ReadPtr(processes.data+std::uintptr_t(i)*sizeof(std::uintptr_t));
            if(mem::ReadPtr(candidate)!=base+gen::rva::FidgetProcessVtable)continue;
            if(ue::ResolveWeak(candidate+off::SCActionProcess::m_Owner)!=actor||
               ue::ResolveWeak(candidate+off::APFidget::m_MovementComponent)!=component)return false;
            if(process&&process!=candidate)return false;
            process=candidate;
        }
    }
    if(!process)return false;
    const auto handler=process+off::APFidget::m_fidgetPlayerHandler;
    const auto instance=mem::ReadPtr(c.scope.world+off::UWorld::OwningGameInstance);
    if(ue::ResolveWeak(handler+off::FFidgetPlayerHandler::m_ComponentOwner)!=component||!ue::IsLive(instance)||
       ue::ResolveWeak(handler+off::FFidgetPlayerHandler::m_GameInstance)!=instance)return false;
    *out={c.scope.world,actor,component,handler,instance,c.pawn_identity,ue::IdentityOf(component),ue::IdentityOf(instance)};
    return out->component_id.valid()&&out->instance_id.valid();
}
bool Same(const Owner& a,const Owner& b){
    return a.world==b.world&&a.actor==b.actor&&a.component==b.component&&a.handler==b.handler&&a.instance==b.instance&&
        a.actor_id==b.actor_id&&a.component_id==b.component_id&&a.instance_id==b.instance_id;
}
}
void Update(std::uintptr_t base,const combat::motion::Context& context,bool controlled){
    if(!controlled&&!suppressed)return;
    if(!Schema(base))return;
    Owner current;
    if(!Read(base,context,&current)){held={};suppressed=false;return;}
    if(suppressed&&!Same(held,current)){held={};suppressed=false;}
    using Call=void(*)(std::uintptr_t);
    if(controlled){
        held=current;suppressed=true;
        const auto handle=mem::ReadOr<std::uint64_t>(current.handler+off::FFidgetPlayerHandler::m_LaunchFidgetTimerHandle,0);
        if(handle){
            reinterpret_cast<Call>(base+gen::rva::FidgetResetTimer)(current.handler);
            if(stopped++<12)SC_LOG("[player-idle] ambient timer held pawn=%d/%d handle=%llX",current.actor_id.index,current.actor_id.serial,static_cast<unsigned long long>(handle));
        }
    }else if(suppressed){
        // Re-evaluate the native conditions. Never restore an old timer deadline.
        suppressed=false;held={};
        reinterpret_cast<Call>(base+gen::rva::FidgetStartTimer)(current.handler);
        if(resumed++<12)SC_LOG("[player-idle] native timer conditions resumed pawn=%d/%d handle=%llX",current.actor_id.index,current.actor_id.serial,
            static_cast<unsigned long long>(mem::ReadOr<std::uint64_t>(current.handler+off::FFidgetPlayerHandler::m_LaunchFidgetTimerHandle,0)));
    }
}
}
