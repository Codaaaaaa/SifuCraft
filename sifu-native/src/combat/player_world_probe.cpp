#include "combat/player_world_probe.h"
#include <windows.h>
#include <cmath>
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/game_hooks.h"
#include "input/input_router.h"
#include "probe/combat_sample.h"
#include "ue/transform.h"

namespace sifucraft::world_motion::player_probe {
namespace {
namespace off=gen::off;
struct Sample {
    std::uintptr_t component=0,root=0;
    ue::ObjectIdentity pawn,movement;
    ue::FVector pending{},last{},acceleration{},velocity{},position{};
    ue::FRotator rotation{};
    int mode=0;
    float hp=0,radius=0,half=0;const char* qualification="unobserved";
};
Sample pending;
combat::motion::Scope scope;
ue::ObjectIdentity pawn;
std::uint64_t began=0,next=0;
unsigned samples=0;
bool Finite(ue::FVector v){return std::isfinite(v.x)&&std::isfinite(v.y)&&std::isfinite(v.z);}
bool Current(std::uintptr_t base,const combat::motion::Context& c,std::uintptr_t component,bool enabled){
    return enabled&&input::Router().owner()==protocol::InputOwner::Sifu&&!hooks::PoseActive()&&
        !combat::motion::CheckWorld(base,c,GetTickCount64())&&ue::IdentityOf(c.pawn)==c.pawn_identity&&
        component==mem::ReadPtr(c.pawn+off::ACharacter::CharacterMovement)&&hooks::MovementHookOwns(component)&&
        mem::ReadPtr(component+off::UActorComponent::OwnerPrivate)==c.pawn&&
        mem::ReadPtr(component+off::UCharacterMovementComponent::CharacterOwner)==c.pawn;
}
}
void Before(std::uintptr_t base,const combat::motion::Context& c,std::uintptr_t component,bool enabled){
    if(!Current(base,c,component,enabled))return;
    pending={};const auto now=GetTickCount64();
    if(scope!=c.scope||pawn!=c.pawn_identity){scope=c.scope;pawn=c.pawn_identity;began=now;next=0;samples=0;}
    if(now-began>=45000||now<next||samples>=240)return;
    next=now+50;
    Sample s;s.root=mem::ReadPtr(c.pawn+off::AActor::RootComponent);
    if(!ue::IsLive(s.root)||mem::ReadPtr(c.pawn+off::ACharacter::CapsuleComponent)!=s.root||
        !ue::ReadWorldTransform(s.root,&s.position,&s.rotation)||!Finite(s.position))return;
    if(!mem::Read(c.pawn+off::APawn::ControlInputVector,&s.pending)||!Finite(s.pending)||
        !mem::Read(c.pawn+off::APawn::LastControlInputVector,&s.last)||!Finite(s.last)||
        !mem::Read(component+off::UMovementComponent::Velocity,&s.velocity)||!Finite(s.velocity)||
        !mem::Read(component+off::UCharacterMovementComponent::Acceleration,&s.acceleration)||!Finite(s.acceleration))return;
    probe::HealthSample health;
    if(!probe::ReadHealthSample(mem::ReadPtr(c.pawn+off::AFightingCharacter::m_HealthComponent),&health))return;
    s.component=component;s.pawn=c.pawn_identity;s.movement=ue::IdentityOf(component);s.hp=health.health;
    s.mode=mem::ReadOr<std::uint8_t>(component+off::UCharacterMovementComponent::MovementMode,255);
    const combat::motion::Target target{c.pawn,component,static_cast<std::uint32_t>(s.pawn.index),1,s.pawn.serial,s.movement.index,s.movement.serial};
    combat::motion::BodySample body;
    const auto reason=combat::motion::CheckPlayerBody(c,target,&body,true);
    s.qualification=reason?reason:"ready";s.radius=body.radius;s.half=body.half;
    if(s.movement.valid())pending=s;
}
void After(std::uintptr_t base,const combat::motion::Context& c,std::uintptr_t component,bool enabled){
    if(component!=pending.component||!pending.component)return;
    auto s=pending;pending={};
    if(!Current(base,c,component,enabled)||c.pawn_identity!=s.pawn||ue::IdentityOf(component)!=s.movement||
        mem::ReadPtr(c.pawn+off::AActor::RootComponent)!=s.root)return;
    ue::FVector position{},velocity{},input{};probe::HealthSample health;
    if(!ue::ReadWorldLocation(s.root,&position)||!Finite(position)||
        !mem::Read(component+off::UMovementComponent::Velocity,&velocity)||!Finite(velocity)||
        !mem::Read(c.pawn+off::APawn::ControlInputVector,&input)||!Finite(input)||
        !probe::ReadHealthSample(mem::ReadPtr(c.pawn+off::AFightingCharacter::m_HealthComponent),&health))return;
    ++samples;
    SC_LOG("[player-world-observe] n=%u pawn=%d/%d mode=%d->%u hp=%.3f->%.3f yaw=%.3f pending=(%.4f,%.4f,%.4f)->(%.4f,%.4f,%.4f) last=(%.4f,%.4f,%.4f) accel=(%.3f,%.3f,%.3f) velocity=(%.3f,%.3f,%.3f)->(%.3f,%.3f,%.3f) actual=(%.5f,%.5f,%.5f) eligibility=%s radius=%.3f half=%.3f",
        samples,s.pawn.index,s.pawn.serial,s.mode,mem::ReadOr<std::uint8_t>(component+off::UCharacterMovementComponent::MovementMode,255),s.hp,health.health,s.rotation.yaw,
        s.pending.x,s.pending.y,s.pending.z,input.x,input.y,input.z,s.last.x,s.last.y,s.last.z,s.acceleration.x,s.acceleration.y,s.acceleration.z,
        s.velocity.x,s.velocity.y,s.velocity.z,velocity.x,velocity.y,velocity.z,position.x-s.position.x,position.y-s.position.y,position.z-s.position.z,s.qualification,s.radius,s.half);
}
}
