#pragma once
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>

// Explicit local developer tool, separate from gameplay movement/effect credentials.
namespace sifucraft::npc_debug {
inline constexpr std::uint32_t kMagic=0x444E4353,kVersion=1;
enum class Op:std::uint32_t { Move=1,Spawn=2,Clear=3 };
enum class Status:std::uint32_t { Pending=0,Applied=1,Refused=2,Failed=3 };
struct Request {
    std::uint32_t magic=kMagic,version=kVersion;
    Op op{};std::uint32_t kind=0;
    std::uint64_t id=0,issued_ms=0,native=0,peer=0;
    std::uint32_t world=0,target=0,spawn=0,reserved=0;
    float feet[3]{};std::uint32_t reserved2=0;
    std::uint64_t map=0,save=0;
};
struct Reply {
    std::uint32_t magic=kMagic,version=kVersion;
    Status status=Status::Pending;Op op{};
    std::uint64_t id=0,native=0,peer=0;
    std::uint32_t world=0,target=0,spawn=0,reserved=0;
    float feet[3]{},health=0;
    char reason[56]{};
};
static_assert(sizeof(Request)==96&&offsetof(Request,feet)==64&&offsetof(Request,map)==80);
static_assert(sizeof(Reply)==128&&offsetof(Reply,feet)==56&&offsetof(Reply,reason)==72);
inline bool Valid(const Request& r){
    if(r.magic!=kMagic||r.version!=kVersion||!r.id||!r.issued_ms||!r.native||!r.peer||!r.world||!r.map||!r.save||r.reserved||r.reserved2)return false;
    for(auto v:r.feet)if(!std::isfinite(v)||std::fabs(v)>10000000)return false;
    if(r.op==Op::Move)return !r.kind&&r.target&&r.spawn;
    if(r.op==Op::Spawn)return (r.kind==1||r.kind==2)&&!r.target&&!r.spawn;
    return r.op==Op::Clear&&!r.kind&&!r.target&&!r.spawn&&!r.feet[0]&&!r.feet[1]&&!r.feet[2];
}
inline bool Fresh(const Request& r,std::uint64_t now){return r.issued_ms<=now&&now-r.issued_ms<=2000;}
inline bool Same(const Request& a,const Request& b){return std::memcmp(&a,&b,sizeof(a))==0;}
}
