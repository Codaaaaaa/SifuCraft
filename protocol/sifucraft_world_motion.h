#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

// Independent reliable commands. Module-owned shared memory, never a game UObject layout.
namespace sifucraft::world_motion {
inline constexpr std::uint32_t kMagic=0x4D574353,kVersion=8,kSlots=32,kSlotBytes=256,kHeaderBytes=128;
inline constexpr std::uint32_t kMappingBytes=kHeaderBytes+2*kSlots*kSlotBytes;
enum SlotState:std::uint32_t { Free,Writing,Ready,Reading };
enum class Op:std::uint32_t { Reserve=1,Step,Release,Cancel,Damage,Renew };
enum class Source:std::uint32_t { Machine=1,Fluid,WorldEffect,Explosion,Passenger };
inline constexpr bool Continuous(Source source){return source==Source::Fluid||source==Source::Explosion||source==Source::Passenger;}
enum class Status:std::uint32_t { Pending,Prepared,Applied,Released,Blocked,Partial,Cancelled,Invalid,Stale,Busy,Conflict,Unsupported,TargetLost,TimedOut };
inline constexpr std::uint32_t kPlayer=1;
inline constexpr std::uint32_t kStepNotExecuted=8; // rejected Fresh STEP, before any backend.Move; never a retired ledger id
inline constexpr std::uint32_t kPlayerPoseHandoff=16; // movement authority observed in the existing MC-player H5 owner, not an original Native tick
inline constexpr std::uint32_t kPaused=1,kScripted=2;
inline constexpr std::uint64_t kMotionCapability=1,kDamageCapability=2,kPauseCapability=4,kPlayerMachineCapability=8,kPlayerFluidCapability=16;
inline constexpr std::uint64_t kExplosionCapability=32,kPassengerCapability=64;
struct PlayerInfo {float position[3]{},radius=0,half_height=0,health=0;};
static_assert(sizeof(PlayerInfo)==24);
inline bool Valid(const PlayerInfo& p){
    return std::isfinite(p.position[0])&&std::isfinite(p.position[1])&&std::isfinite(p.position[2])&&
        std::isfinite(p.radius)&&p.radius>=10&&p.radius<=100&&std::isfinite(p.half_height)&&p.half_height>=p.radius&&p.half_height<=200&&std::isfinite(p.health)&&p.health>0;
}
struct Header {
    std::uint32_t magic,version,mapping_bytes,slot_bytes;
    std::uint64_t mc_session,native_session,layout_hash,capabilities,native_ms;
    std::uint32_t world,world_flags;
    std::uint64_t reply_sequence,mc_ms,status_sequence,player_stamp,shape_revision;
    PlayerInfo player;
};
struct Command {
    Op op;Source source;
    std::uint64_t id,lease,issued_ms,source_hi,source_lo,action,tick,save,map,shape_revision;
    std::uint32_t world,target,spawn,duration_ms;
    float position[3],delta[3],health,radius,half_height; // Unreal centimetres, Z up
    std::uint32_t flags;
    std::int32_t block[3]; // source BlockPos uses Minecraft axes
    float progress,next_progress,amount;
    std::uint64_t state_hash,section_revision,actor_stamp,component_token;
    float facing;std::uint32_t reserved; // Passenger STEP UE yaw, degrees [-180,180]; all other sources zero
    std::uint64_t payload_hash;
};
struct Reply {
    std::uint64_t id,lease,native_frame,at_ms;
    std::uint32_t world,target,spawn;Status status;
    std::int32_t actor_serial;std::uint32_t restored;
    std::uint64_t component_token,source_hash;
    float position[3],delta[3],health; // Prepared.delta is root-centre minus hit-centre; other statuses report actual displacement.
    std::uint32_t flags;
    std::uint64_t shape_revision;
    float root_radius,root_half_height;
    std::uint64_t payload_hash;
};
struct alignas(64) CommandSlot { std::uint32_t state,reserved;std::uint64_t sequence;Command value;std::uint8_t pad[24]; };
struct alignas(64) ReplySlot { std::uint32_t state,reserved;std::uint64_t sequence;Reply value;std::uint8_t pad[112]; };
static_assert(sizeof(Header)==128&&sizeof(Command)==216&&sizeof(Reply)==128);
static_assert(offsetof(Header,player)==104&&offsetof(PlayerInfo,radius)==12&&offsetof(PlayerInfo,half_height)==16&&offsetof(PlayerInfo,health)==20);
static_assert(sizeof(CommandSlot)==256&&sizeof(ReplySlot)==256);
static_assert(offsetof(Command,position)==104&&offsetof(Command,flags)==140&&offsetof(Command,actor_stamp)==184&&offsetof(Command,facing)==200&&offsetof(Command,payload_hash)==208);
static_assert(offsetof(Reply,position)==72&&offsetof(Reply,root_radius)==112&&offsetof(Reply,payload_hash)==120&&offsetof(Header,status_sequence)==80);
inline constexpr std::uint32_t kLayout[]={kMagic,kVersion,kSlots,kSlotBytes,kHeaderBytes,kMappingBytes,216,128,
    0,4,8,12,16,24,32,40,48,56,60,64,72,80,88,96,104,
    0,4,8,16,24,32,40,48,56,64,72,80,88,92,96,100,104,116,128,132,136,140,144,156,160,164,168,176,184,192,200,204,208,
    0,8,16,24,32,36,40,44,48,52,56,64,72,84,96,100,104,112,116,120,Free,Writing,Ready,Reading,
    1,2,3,4,5,6,1,2,3,0,1,2,3,4,5,6,7,8,9,10,11,12,13,kPlayer,kPaused,kScripted,1,2,4,kStepNotExecuted,8,104,108,112,116,120,124,16,kPlayerPoseHandoff,4,32,5,64};
inline constexpr std::uint64_t kLayoutHash=[] {
    std::uint64_t h=14695981039346656037ull;
    for(auto v:kLayout)for(int shift=0;shift<32;shift+=8){h^=(v>>shift)&255u;h*=1099511628211ull;}
    return h;
}();
inline std::uint64_t Hash(const void* data,std::size_t size){
    auto p=static_cast<const std::uint8_t*>(data);std::uint64_t h=14695981039346656037ull;
    for(std::size_t i=0;i<size;++i){h^=p[i];h*=1099511628211ull;}return h;
}
inline void Seal(Command& c){c.payload_hash=Hash(&c,offsetof(Command,payload_hash));}
inline void Seal(Reply& r){r.payload_hash=Hash(&r,offsetof(Reply,payload_hash));}
inline bool Valid(const Command& c){
    const auto op=static_cast<std::uint32_t>(c.op),source=static_cast<std::uint32_t>(c.source);
    if(op<1||op>6||source<1||source>5||!c.id||!c.issued_ms||!(c.source_hi|c.source_lo)||!c.action||!c.save||!c.map||!c.world||
       (c.flags&~kPlayer)||((c.flags&kPlayer)?(c.target||c.spawn||!c.actor_stamp):(!c.target||c.target>0x7fffffffu||!c.spawn))||
       c.duration_ms>500||!std::isfinite(c.health)||c.health<0||!std::isfinite(c.amount)||c.amount<0||c.amount>1e7f||
       !std::isfinite(c.radius)||!std::isfinite(c.half_height)||c.radius<10||c.radius>100||c.half_height<c.radius||c.half_height>200||
       !std::isfinite(c.progress)||!std::isfinite(c.next_progress)||c.progress<0||c.next_progress<c.progress||c.next_progress>1)return false;
    if(c.reserved||!std::isfinite(c.facing)||std::fabs(c.facing)>180||((c.source!=Source::Passenger||c.op!=Op::Step)&&c.facing!=0))return false;
    double length=0;int axes=0;
    for(int i=0;i<3;++i){if(!std::isfinite(c.position[i])||!std::isfinite(c.delta[i]))return false;length+=double(c.delta[i])*c.delta[i];axes+=c.delta[i]!=0;}
    if(c.op==Op::Reserve&&(!c.duration_ms||length!=0||c.lease))return false;
    if(c.op==Op::Step&&(!c.lease||!c.component_token||(length==0&&c.source!=Source::Passenger)||length>100.002||(c.source==Source::Machine&&axes!=1)))return false;
    if((c.op==Op::Release||c.op==Op::Cancel)&&(!c.lease||length!=0))return false;
    if(c.op==Op::Damage&&(c.amount<=0||length!=0))return false;
    if(c.op==Op::Renew&&(!Continuous(c.source)||!c.lease||!c.component_token||!c.duration_ms||length!=0))return false;
    if(c.source==Source::Machine&&(!c.state_hash||!c.section_revision))return false;
    return c.payload_hash==Hash(&c,offsetof(Command,payload_hash));
}
inline bool Valid(const Reply& r){
    if(!r.id||!r.at_ms||!r.world||static_cast<std::uint32_t>(r.status)>13||r.restored>7||
       !std::isfinite(r.health)||r.health<0)return false;
    if((r.flags&kStepNotExecuted)&&(r.flags!=kStepNotExecuted||static_cast<std::uint32_t>(r.status)<6||r.delta[0]!=0||r.delta[1]!=0||r.delta[2]!=0))return false;
    if((r.flags&kPlayerPoseHandoff)&&(r.target||r.spawn||r.restored!=1||r.actor_serial<=0||!r.component_token||
       (r.status!=Status::Released&&r.status!=Status::Cancelled&&r.status!=Status::TargetLost&&r.status!=Status::TimedOut)||r.delta[0]!=0||r.delta[1]!=0||r.delta[2]!=0))return false;
    if(!std::isfinite(r.root_radius)||!std::isfinite(r.root_half_height)||
       ((r.root_radius!=0||r.root_half_height!=0)&&(r.root_radius<10||r.root_radius>100||r.root_half_height<r.root_radius||r.root_half_height>200))||
       (r.status==Status::Prepared&&r.root_radius==0))return false;
    for(int i=0;i<3;++i)if(!std::isfinite(r.position[i])||!std::isfinite(r.delta[i]))return false;
    return r.payload_hash==Hash(&r,offsetof(Reply,payload_hash));
}
}
