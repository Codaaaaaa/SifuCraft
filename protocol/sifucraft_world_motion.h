#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>

// Independent reliable commands. Module-owned shared memory, never a game UObject layout.
namespace sifucraft::world_motion {
inline constexpr std::uint32_t kMagic=0x4D574353,kVersion=1,kSlots=32,kSlotBytes=256,kHeaderBytes=128;
inline constexpr std::uint32_t kMappingBytes=kHeaderBytes+2*kSlots*kSlotBytes;
enum SlotState:std::uint32_t { Free,Writing,Ready,Reading };
enum class Op:std::uint32_t { Reserve=1,Step,Release,Cancel,Damage };
enum class Source:std::uint32_t { Machine=1,Fluid,WorldEffect };
enum class Status:std::uint32_t { Pending,Prepared,Applied,Released,Blocked,Partial,Cancelled,Invalid,Stale,Busy,Conflict,Unsupported,TargetLost,TimedOut };
inline constexpr std::uint32_t kPlayer=1;
inline constexpr std::uint32_t kPaused=1,kScripted=2;
inline constexpr std::uint64_t kMotionCapability=1,kDamageCapability=2,kPauseCapability=4;
struct Header {
    std::uint32_t magic,version,mapping_bytes,slot_bytes;
    std::uint64_t mc_session,native_session,layout_hash,capabilities,native_ms;
    std::uint32_t world,world_flags;
    std::uint64_t reply_sequence,mc_ms,status_sequence,player_stamp,shape_revision,reserved_tail[3];
};
struct Command {
    Op op;Source source;
    std::uint64_t id,lease,issued_ms,source_hi,source_lo,action,tick,save,map,shape_revision;
    std::uint32_t world,target,spawn,duration_ms;
    float position[3],delta[3],health,radius,half_height; // Unreal centimetres, Z up
    std::uint32_t flags;
    std::int32_t block[3]; // source BlockPos uses Minecraft axes
    float progress,next_progress,amount;
    std::uint64_t state_hash,section_revision,actor_stamp,component_token,payload_hash;
};
struct Reply {
    std::uint64_t id,lease,native_frame,at_ms;
    std::uint32_t world,target,spawn;Status status;
    std::int32_t actor_serial;std::uint32_t restored;
    std::uint64_t component_token,source_hash;
    float position[3],delta[3],health;
    std::uint32_t flags;
    std::uint64_t shape_revision,payload_hash,reserved;
};
struct alignas(64) CommandSlot { std::uint32_t state,reserved;std::uint64_t sequence;Command value;std::uint8_t pad[32]; };
struct alignas(64) ReplySlot { std::uint32_t state,reserved;std::uint64_t sequence;Reply value;std::uint8_t pad[112]; };
static_assert(sizeof(Header)==128&&sizeof(Command)==208&&sizeof(Reply)==128);
static_assert(sizeof(CommandSlot)==256&&sizeof(ReplySlot)==256);
static_assert(offsetof(Command,position)==104&&offsetof(Command,flags)==140&&offsetof(Command,actor_stamp)==184&&offsetof(Command,payload_hash)==200);
static_assert(offsetof(Reply,position)==72&&offsetof(Reply,payload_hash)==112&&offsetof(Header,status_sequence)==80);
inline constexpr std::uint32_t kLayout[]={kMagic,kVersion,kSlots,kSlotBytes,kHeaderBytes,kMappingBytes,208,128,
    0,4,8,12,16,24,32,40,48,56,60,64,72,80,88,96,104,
    0,4,8,16,24,32,40,48,56,64,72,80,88,92,96,100,104,116,128,132,136,140,144,156,160,164,168,176,184,192,200,
    0,8,16,24,32,36,40,44,48,52,56,64,72,84,96,100,104,112,120,Free,Writing,Ready,Reading,
    1,2,3,4,5,1,2,3,0,1,2,3,4,5,6,7,8,9,10,11,12,13,kPlayer,kPaused,kScripted,1,2,4};
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
    if(op<1||op>5||source<1||source>3||!c.id||!c.issued_ms||!(c.source_hi|c.source_lo)||!c.action||!c.save||!c.map||!c.world||
       (c.flags&~kPlayer)||((c.flags&kPlayer)?(c.target||c.spawn||!c.actor_stamp):(!c.target||c.target>0x7fffffffu||!c.spawn))||
       c.duration_ms>500||!std::isfinite(c.health)||c.health<0||!std::isfinite(c.amount)||c.amount<0||c.amount>1e7f||
       !std::isfinite(c.radius)||!std::isfinite(c.half_height)||c.radius<10||c.radius>100||c.half_height<c.radius||c.half_height>200||
       !std::isfinite(c.progress)||!std::isfinite(c.next_progress)||c.progress<0||c.next_progress<c.progress||c.next_progress>1)return false;
    double length=0;int axes=0;
    for(int i=0;i<3;++i){if(!std::isfinite(c.position[i])||!std::isfinite(c.delta[i]))return false;length+=double(c.delta[i])*c.delta[i];axes+=c.delta[i]!=0;}
    if(c.op==Op::Reserve&&(!c.duration_ms||length!=0||c.lease))return false;
    if(c.op==Op::Step&&(!c.lease||!c.component_token||length<=0||length>100.002||(c.source==Source::Machine&&axes!=1)))return false;
    if((c.op==Op::Release||c.op==Op::Cancel)&&(!c.lease||length!=0))return false;
    if(c.op==Op::Damage&&(c.amount<=0||length!=0))return false;
    if(c.source==Source::Machine&&(!c.state_hash||!c.section_revision))return false;
    return c.payload_hash==Hash(&c,offsetof(Command,payload_hash));
}
inline bool Valid(const Reply& r){
    if(!r.id||!r.at_ms||!r.world||static_cast<std::uint32_t>(r.status)>13||r.reserved||r.restored>7||
       !std::isfinite(r.health)||r.health<0)return false;
    for(int i=0;i<3;++i)if(!std::isfinite(r.position[i])||!std::isfinite(r.delta[i]))return false;
    return r.payload_hash==Hash(&r,offsetof(Reply,payload_hash));
}
}
