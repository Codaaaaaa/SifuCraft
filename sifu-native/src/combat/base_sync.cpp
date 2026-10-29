#include "combat/base_sync.h"
#include "combat/pose_fall_origin.h"
#include <windows.h>
#include <atomic>
#include <algorithm>
#include <cmath>
#include <map>
#include "blocks/block_mirror.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/game_hooks.h"
#include "probe/combat_sample.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::world_motion::base_sync {
namespace {
namespace off=gen::off;
namespace bits=gen::bits::UCharacterMovementComponent;
const combat::motion::Context* g_context=nullptr;
std::uintptr_t g_base=0;DWORD g_thread=0;bool g_enabled=false,g_failed=false;
std::atomic<std::uintptr_t> g_pose_owned{0};
struct PoseOwner {std::uintptr_t actor=0,root=0;ue::ObjectIdentity actor_id,movement_id,root_id;};
PoseOwner g_pose_owner;
combat::motion::Scope g_scope;
struct Seen {ue::ObjectIdentity actor,movement,base;std::uint64_t version=0;};
std::map<std::uintptr_t,Seen> g_seen;
std::uintptr_t g_checked_class=0;
unsigned g_logged=0;
bool Finite(const ue::FVector& p){return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z);}
float Distance(const ue::FVector& a,const ue::FVector& b){return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));}
bool Schema(std::uintptr_t movement){
    const auto table=mem::ReadPtr(movement);
    if(table!=g_base+gen::hook::MovementTick.vftable||mem::ReadPtr(table+gen::slot::UCharacterMovementComponent::SaveBaseLocation)!=g_base+gen::fn::SaveBaseLocation)return false;
    const auto cls=ue::ClassOf(movement);if(cls==g_checked_class)return true;
    constexpr auto mask=1u<<bits::bForceNextFloorCheck_Shift;
    for(const auto& p:ue::ListProperties(cls))if(p.name=="bForceNextFloorCheck"){
        if(p.type!="BoolProperty"||p.offset!=bits::bForceNextFloorCheck_Offset||p.array_dim!=1||p.element_size!=1||
           mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteOffset,255)!=0||
           mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::FieldSize,0)!=1||
           mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::ByteMask,0)!=mask||
           mem::ReadOr<std::uint8_t>(p.field+off::FBoolProperty::FieldMask,0)!=mask)return false;
        g_checked_class=cls;SC_LOG("[world-base] selfcheck PASS SaveBaseLocation slot=%X; floor-check bit=%X/%u",gen::slot::UCharacterMovementComponent::SaveBaseLocation,bits::bForceNextFloorCheck_Offset,bits::bForceNextFloorCheck_Shift);return true;
    }
    return false;
}
void ReturnPlayerFloor(std::uintptr_t movement){
    if(!g_context||GetCurrentThreadId()!=g_thread||g_pose_owned.load()!=movement)return;
    const auto owner=g_pose_owner;g_pose_owner={};g_pose_owned.store(0);
    const auto& c=*g_context;
    if(!owner.actor||owner.actor!=c.pawn||owner.actor_id!=c.pawn_identity||
       ue::IdentityOf(owner.actor)!=owner.actor_id||ue::IdentityOf(movement)!=owner.movement_id||ue::IdentityOf(owner.root)!=owner.root_id||
       mem::ReadPtr(movement+off::UActorComponent::OwnerPrivate)!=owner.actor||
       mem::ReadPtr(owner.actor+off::ACharacter::CharacterMovement)!=movement||
       mem::ReadPtr(owner.actor+off::AActor::RootComponent)!=owner.root||
       mem::ReadPtr(owner.actor+off::ACharacter::CapsuleComponent)!=owner.root||ue::AttachParentOf(owner.root)||
       mem::ReadPtr(movement+off::UMovementComponent::UpdatedComponent)!=owner.root)return;
    const auto table=mem::ReadPtr(movement);
    const auto function=mem::ReadPtr(table+gen::slot::UMovementComponent::OnTeleported);
    if(table!=g_base+gen::hook::MovementTick.vftable||function!=g_base+gen::fn::MovementOnTeleported)return;
    ue::FVector before{},after{};probe::HealthSample hp,after_hp;
    const auto health=mem::ReadPtr(owner.actor+off::AFightingCharacter::m_HealthComponent);
    if(!ue::ReadWorldLocation(owner.root,&before)||!Finite(before)||!probe::ReadHealthSample(health,&hp)||hp.health<=0||hp.down)return;
    // K2_SetActorLocationAndRotation does not notify the movement component.
    // Recompute its floor/base once after MC pose ownership, including disconnect.
    // Let the original implementation choose the mode; never manufacture a floor
    // or clear Sifu's fall/damage state by writing private movement fields.
    reinterpret_cast<void(*)(void*)>(function)(reinterpret_cast<void*>(movement));
    const bool valid=ue::IdentityOf(owner.actor)==owner.actor_id&&ue::IdentityOf(movement)==owner.movement_id&&ue::IdentityOf(owner.root)==owner.root_id&&
        ue::ReadWorldLocation(owner.root,&after)&&Finite(after)&&Distance(before,after)<.1f&&
        probe::ReadHealthSample(health,&after_hp)&&after_hp.health==hp.health;
    SC_LOG("[world-base] player pose handback floor=%s actor=%d/%d movement=%d/%d HP=%.3f mode=%u",valid?"PASS":"FAIL",owner.actor_id.index,owner.actor_id.serial,
        owner.movement_id.index,owner.movement_id.serial,hp.health,mem::ReadOr<std::uint8_t>(movement+off::UCharacterMovementComponent::MovementMode,255));
}
}
void Context(std::uintptr_t base,const combat::motion::Context* context,bool enabled){
    g_base=base;g_context=context;g_thread=GetCurrentThreadId();g_enabled=enabled;
    if(context&&g_scope!=context->scope){g_seen.clear();g_scope=context->scope;}
}
void PoseOwned(std::uintptr_t movement){
    if(!g_context||GetCurrentThreadId()!=g_thread)return;
    const auto& c=*g_context;const auto actor=mem::ReadPtr(movement+off::UActorComponent::OwnerPrivate);
    if(actor!=c.pawn||ue::IdentityOf(actor)!=c.pawn_identity)return;
    const auto root=mem::ReadPtr(actor+off::AActor::RootComponent);
    const auto movement_id=ue::IdentityOf(movement),root_id=ue::IdentityOf(root);
    if(!movement_id.valid()||!root_id.valid())return;
    g_pose_owner={actor,root,c.pawn_identity,movement_id,root_id};g_pose_owned.store(movement);
}
void TranslatePlayerPose(std::uintptr_t actor,float before_z,float after_z){
    if(!g_context||GetCurrentThreadId()!=g_thread||before_z==after_z)return;
    const auto& c=*g_context;
    if(actor!=c.pawn||ue::IdentityOf(actor)!=c.pawn_identity)return;
    const auto movement=mem::ReadPtr(actor+off::ACharacter::CharacterMovement);
    const auto root=mem::ReadPtr(actor+off::AActor::RootComponent);
    if(!ue::IsLive(movement)||!ue::IsLive(root)||ue::AttachParentOf(root)||
       mem::ReadPtr(actor+off::ACharacter::CapsuleComponent)!=root||
       mem::ReadPtr(movement+off::UActorComponent::OwnerPrivate)!=actor||
       mem::ReadPtr(movement+off::UMovementComponent::UpdatedComponent)!=root||
       mem::ReadPtr(movement)!=g_base+gen::hook::MovementTick.vftable)return;
    const auto at=movement+off::UFightingMovementComponent::m_fInitialHeight;
    float initial=0,translated=0;
    if(!mem::Read(at,&initial)||!TranslateFallOrigin(initial,before_z,after_z,&translated))return;
    *reinterpret_cast<volatile float*>(at)=translated;
    static unsigned logged=0;
    if(std::fabs(after_z-before_z)>100&&logged++<12)SC_LOG("[world-base] player pose fall-origin translation dz=%.3f origin=%.3f->%.3f native_fall_cm=%.3f",after_z-before_z,initial,translated,initial-before_z);
}
void BeforeOriginal(std::uintptr_t movement){
    ReturnPlayerFloor(movement);
    if(!g_enabled||g_failed||!g_context||GetCurrentThreadId()!=g_thread||!hooks::MovementHookOwns(movement))return;
    const auto& c=*g_context;
    const auto actor=mem::ReadPtr(movement+off::UActorComponent::OwnerPrivate);
    if(!ue::IsLive(actor)||!ue::IsA(actor,ue::FindClass("FightingCharacter")))return;
    const auto actor_id=ue::IdentityOf(actor),movement_id=ue::IdentityOf(movement);
    if(!actor_id.valid()||!movement_id.valid())return;
    const bool current=actor==c.pawn?actor_id==c.pawn_identity:std::any_of(c.targets.begin(),c.targets.end(),[&](const auto& t){return t.actor==actor&&t.identity==actor_id;});
    if(!current||mem::ReadPtr(actor+off::ACharacter::CharacterMovement)!=movement||mem::ReadPtr(movement+off::UCharacterMovementComponent::CharacterOwner)!=actor)return;
    const auto base=mem::ReadPtr(actor+off::ACharacter::BasedMovement+off::FBasedMovementInfo::MovementBase);
    const auto version=blocks::OwnedBaseVersion(base);if(!version)return;
    const auto base_id=ue::IdentityOf(base);if(!base_id.valid())return;
    const bool handover=g_pose_owned.load()==movement;
    const auto old=g_seen.find(movement);
    if(!handover&&old!=g_seen.end()&&old->second.actor==actor_id&&old->second.movement==movement_id&&old->second.base==base_id&&old->second.version==version)return;
    if(combat::motion::CheckWorld(g_base,c,GetTickCount64()))return;
    const auto root=mem::ReadPtr(actor+off::AActor::RootComponent);
    if(!ue::IsLive(root)||ue::AttachParentOf(root)||mem::ReadPtr(actor+off::ACharacter::CapsuleComponent)!=root||
       mem::ReadPtr(movement+off::UMovementComponent::UpdatedComponent)!=root||mem::ReadPtr(movement+off::UMovementComponent::UpdatedPrimitive)!=root)return;
    ue::FName bone{};if(!mem::Read(actor+off::ACharacter::BasedMovement+off::FBasedMovementInfo::BoneName,&bone)||bone.comparison_index||bone.number)return;
    const auto mode=mem::ReadOr<std::uint8_t>(movement+off::UCharacterMovementComponent::MovementMode,255);if(mode!=1&&mode!=2)return;
    probe::HealthSample health;const auto health_component=mem::ReadPtr(actor+off::AFightingCharacter::m_HealthComponent);
    if(!probe::ReadHealthSample(health_component,&health)||health.health<=0||health.down)return;
    ue::FVector position{},base_position{};ue::FRotator rotation{};
    if(!ue::ReadWorldTransform(root,&position,&rotation)||!Finite(position)||!ue::ReadWorldLocation(base,&base_position)||!Finite(base_position))return;
    if(!Schema(movement)){g_failed=true;SC_LOG("[world-base] REFUSE schema/slot; no cache or floor flag write");return;}
    using Save=void(*)(void*);
    const auto function=mem::ReadPtr(mem::ReadPtr(movement)+gen::slot::UCharacterMovementComponent::SaveBaseLocation);
    reinterpret_cast<Save>(function)(reinterpret_cast<void*>(movement));
    ue::FVector after{},saved{};ue::FRotator after_rotation{};probe::HealthSample after_health;
    const bool identities=ue::IdentityOf(actor)==actor_id&&ue::IdentityOf(movement)==movement_id&&ue::IdentityOf(base)==base_id&&
        mem::ReadPtr(actor+off::ACharacter::BasedMovement+off::FBasedMovementInfo::MovementBase)==base&&blocks::OwnedBaseVersion(base)==version;
    if(!identities||!ue::ReadWorldTransform(root,&after,&after_rotation)||!Finite(after)||Distance(after,position)>.1f||
       std::fabs(after_rotation.pitch-rotation.pitch)>.01f||std::fabs(after_rotation.yaw-rotation.yaw)>.01f||std::fabs(after_rotation.roll-rotation.roll)>.01f||
       !probe::ReadHealthSample(health_component,&after_health)||after_health.health!=health.health||
       mem::ReadOr<std::uint8_t>(movement+off::UCharacterMovementComponent::MovementMode,255)!=mode||
       !mem::Read(movement+off::UCharacterMovementComponent::OldBaseLocation,&saved)||!Finite(saved)||Distance(saved,base_position)>.1f){
        g_failed=true;SC_LOG("[world-base] REFUSE cache readback; actor pose is never rolled back");return;
    }
    std::uint8_t flags=0;const auto at=movement+bits::bForceNextFloorCheck_Offset;
    if(!mem::Read(at,&flags))return;
    // One-shot native floor recheck, preserving all unrelated flags; original Tick consumes it.
    *reinterpret_cast<volatile std::uint8_t*>(at)=flags|static_cast<std::uint8_t>(1u<<bits::bForceNextFloorCheck_Shift);
    if(g_seen.size()>=256)g_seen.clear();
    g_seen[movement]={actor_id,movement_id,base_id,version};
    if(handover)g_pose_owned.store(0);
    if(g_logged<12){++g_logged;SC_LOG("[world-base] sync PASS actor=%d/%d movement=%d/%d base=%d/%d version=%llu handover=%d pose_delta_cm=%.3f HP=%.3f floor_recheck=1",actor_id.index,actor_id.serial,movement_id.index,movement_id.serial,base_id.index,base_id.serial,version,handover,Distance(after,position),after_health.health);}
}
}
