#include "sifucraft_world_motion.h"
#include "bridge/world_motion_mailbox.h"
#include "combat/world_motion_ledger.h"
#include <array>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <fstream>
#include <filesystem>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
using namespace sifucraft::world_motion;
void Check(bool value,const char* why){if(!value)throw std::runtime_error(why);}
std::vector<std::uint8_t> Hex(const char* path){
    std::ifstream file(path);std::vector<std::uint8_t> out;std::string word;
    while(file>>word){Check(word.size()==2,"golden hex width");out.push_back(static_cast<std::uint8_t>(std::stoul(word,nullptr,16)));}return out;
}
Command Sample(){
    Command c{};c.op=Op::Reserve;c.source=Source::Machine;c.id=11;c.issued_ms=1000;c.source_hi=21;c.source_lo=22;c.action=23;c.tick=24;
    c.save=31;c.map=32;c.shape_revision=5;c.world=7;c.target=42;c.spawn=2;c.duration_ms=500;c.position[0]=10;c.position[1]=20;c.position[2]=90;
    c.health=60;c.radius=35;c.half_height=92;c.block[0]=-1;c.block[1]=64;c.block[2]=5;c.next_progress=.5;c.state_hash=41;c.section_revision=6;Seal(c);return c;
}
int Echo(std::uint64_t mc,std::uint64_t native,unsigned count){
    Mailbox mailbox;Ledger ledger;const auto end=GetTickCount64()+15000;unsigned received=0,effects=0;
    while(received<count&&GetTickCount64()<end){
        if(!mailbox.Open(mc,native)){Sleep(1);continue;}
        mailbox.Status(7,kMotionCapability|kPlayerMachineCapability,GetTickCount64(),(73ull<<32)|19,5,0,PlayerInfo{{101,-202,303},35,90,180});
        if(!mailbox.CanReply()){Sleep(1);continue;}
        Command command{};bool valid=false;
        if(!mailbox.Read(&command,&valid)){Sleep(1);continue;}
        Check(valid,"echo receives validated Java packet");
        Reply reply{};auto decision=ledger.Begin(command);
        if(decision.decision==Ledger::Decision::Replay)reply=decision.reply;
        else {
            Check(decision.decision==Ledger::Decision::Fresh,"echo unique or exact replay");
            reply.id=command.id;reply.at_ms=GetTickCount64();reply.world=command.world;reply.target=command.target;reply.spawn=command.spawn;
            reply.status=Status::Unsupported;reply.health=command.health;reply.source_hash=command.payload_hash;Seal(reply);
            Check(ledger.Finish(command.id,reply),"echo caches exact final receipt");++effects;
        }
        Check(mailbox.Write(reply),"echo retains receipt capacity before consuming");++received;
    }
    Check(received==count,"echo deadline");std::printf("World motion OS mapping echo PASS packets=%u unique_handlers=%u\n",received,effects);return 0;
}
int main(int argc,char** argv){try{
    if(argc==5&&std::strcmp(argv[1],"--echo")==0)return Echo(std::strtoull(argv[2],nullptr,10),std::strtoull(argv[3],nullptr,10),static_cast<unsigned>(std::strtoul(argv[4],nullptr,10)));
    auto c=Sample();Check(Valid(c),"golden reserve");Check(argc==3,"two golden fixture paths");
    Command query{};query.op=Op::Coverage;query.source=Source::WorldEffect;query.id=1;query.issued_ms=1000;query.source_hi=1;query.action=1;query.save=31;query.map=32;query.world=7;query.position[0]=-16800;Seal(query);Check(Valid(query),"finite read-only coverage without actor binding");
    for(int fault=0;fault<5;fault++){auto bad=query;if(fault==0)bad.target=1;if(fault==1)bad.delta[0]=1;if(fault==2)bad.position[0]=INFINITY;if(fault==3)bad.source=Source::Machine;if(fault==4)bad.actor_stamp=1;Seal(bad);Check(!Valid(bad),"coverage rejects movement, nonfinite and actor payloads");}
    const PlayerInfo player_info{{101,-202,303},35,90,180};Check(Valid(player_info),"actual-sized player root metadata");
    for(int failure=0;failure<6;++failure){auto bad=player_info;
        if(failure<3)bad.position[failure]=std::numeric_limits<float>::quiet_NaN();
        if(failure==3)bad.radius=9;
        if(failure==4)bad.half_height=34;
        if(failure==5)bad.health=0;
        Check(!Valid(bad),"malformed player geometry/health cannot advertise ready");
    }
    auto bytes=Hex(argv[1]);Check(bytes.size()==sizeof(c)&&std::memcmp(bytes.data(),&c,sizeof(c))==0,"independent command golden bytes");
    for(int i=0;i<9;++i){auto bad=c;
        switch(i){
            case 0:bad.position[0]=std::numeric_limits<float>::quiet_NaN();break;
            case 1:bad.duration_ms=501;break;
            case 2:bad.source_hi=bad.source_lo=0;break;
            case 3:bad.spawn=0;break;
            case 4:bad.flags=2;break;
            case 5:bad.state_hash=0;break;
            case 6:bad.next_progress=2;break;
            case 7:bad.lease=1;break;
            default:bad.op=static_cast<Op>(99);break;
        }
        Seal(bad);Check(!Valid(bad),"malformed reserve with correct digest");
    }
    auto step=c;step.op=Op::Step;step.lease=71;step.component_token=72;step.delta[2]=10;Seal(step);Check(Valid(step),"bounded vertical machine step");
    auto pose=c;pose.op=Op::Step;pose.source=Source::Passenger;pose.lease=71;pose.duration_ms=0;pose.component_token=72;pose.facing=-175.5;Seal(pose);
    auto pose_file=(std::filesystem::path(argv[1]).parent_path()/"world_motion_passenger_command.hex").string();
    auto pose_bytes=Hex(pose_file.c_str());Check(Valid(pose)&&pose_bytes.size()==sizeof(pose)&&std::memcmp(pose_bytes.data(),&pose,sizeof(pose))==0,"independent passenger pose golden includes signed heading and full digest");
    for(int failure=0;failure<4;++failure){auto bad=pose;
        if(failure==0)bad.facing=std::numeric_limits<float>::quiet_NaN();
        if(failure==1)bad.facing=180.001;
        if(failure==2)bad.reserved=1;
        if(failure==3)bad.source=Source::Fluid;
        Seal(bad);Check(!Valid(bad),"malformed or wrong-source pose command cannot reach actor APIs");
    }
    step.delta[2]=10.1f;Seal(step);Check(!Valid(step),"oversized step");step.delta[2]=6;step.delta[0]=6;Seal(step);Check(!Valid(step),"machine axis mismatch");
    step.source=Source::Fluid;Seal(step);Check(Valid(step),"bounded three dimensional fluid step");step.delta[1]=6;Seal(step);Check(!Valid(step),"diagonal length limit");
    auto damage=c;damage.op=Op::Damage;damage.source=Source::WorldEffect;damage.amount=3;Seal(damage);Check(Valid(damage),"scoped world damage packet");
    auto player=c;player.flags=kPlayer;player.target=player.spawn=0;player.actor_stamp=73;Seal(player);Check(Valid(player),"player lifetime stamp");
    player.actor_stamp=0;Seal(player);Check(!Valid(player),"player cannot use unbound pawn lifetime");
    auto broken=c;broken.position[0]=11;Check(!Valid(broken),"packet digest protects mutation");
    Reply r{};r.id=11;r.lease=12;r.native_frame=100;r.at_ms=1100;r.world=7;r.target=42;r.spawn=2;r.status=Status::Released;r.actor_serial=99;r.restored=7;
    r.component_token=55;r.source_hash=66;r.position[0]=10;r.position[1]=20;r.position[2]=90;r.delta[0]=10;r.health=60;r.shape_revision=9;r.root_radius=35;r.root_half_height=90;Seal(r);
    bytes=Hex(argv[2]);Check(Valid(r)&&bytes.size()==sizeof(r)&&std::memcmp(bytes.data(),&r,sizeof(r))==0,"independent reply golden bytes");
    auto unexecuted=r;unexecuted.status=Status::Busy;unexecuted.flags=kStepNotExecuted;
    for(auto& d:unexecuted.delta)d=0;
    Seal(unexecuted);Check(Valid(unexecuted),"explicit fresh pre-sweep rejection receipt");
    for(int mutation=0;mutation<3;++mutation){auto bad=unexecuted;
        if(mutation==0)bad.status=Status::Applied;
        if(mutation==1)bad.delta[0]=.01f;
        if(mutation==2)bad.flags|=1;
        Seal(bad);Check(!Valid(bad),"contradictory execution proof rejected despite valid digest");
    }
    auto bad_root=r;bad_root.root_half_height=34;Seal(bad_root);Check(!Valid(bad_root),"physical capsule dimensions reject a correctly hashed malformed reply");
    bad_root=r;bad_root.root_radius=36;Check(!Valid(bad_root),"physical capsule metadata protected by reply digest");
    alignas(64) std::array<std::uint8_t,kMappingBytes> memory{};
    auto& h=*reinterpret_cast<Header*>(memory.data());h.magic=kMagic;h.version=kVersion;h.mapping_bytes=kMappingBytes;h.slot_bytes=kSlotBytes;
    h.mc_session=11;h.native_session=22;h.layout_hash=kLayoutHash;
    Check(Matches(memory.data(),11,22)&&!Matches(memory.data(),12,22),"mailbox session boundary");
    h.version=5;Check(!Matches(memory.data(),11,22),"old protocol rejected despite unchanged mapping size");h.version=kVersion;
    auto slots=reinterpret_cast<CommandSlot*>(memory.data()+kHeaderBytes);
    for(unsigned i=0;i<kSlots;++i){slots[i].state=Ready;slots[i].sequence=kSlots-i;slots[i].value=c;slots[i].value.id=kSlots-i;Seal(slots[i].value);}
    bool valid=false;Command received{};
    for(unsigned id=1;id<=kSlots;++id)Check(PopCommand(memory.data(),11,22,&received,&valid)&&valid&&received.id==id,"receive oldest request without latest-frame loss");
    Check(!PopCommand(memory.data(),11,22,&received,&valid),"all requests acknowledged locally");
    slots[0].state=Writing;Check(!PopCommand(memory.data(),11,22,&received,&valid),"partial producer is never consumed");
    slots[0].state=Ready;slots[0].value=c;slots[0].sequence=c.id;slots[0].value.payload_hash^=1;
    Check(PopCommand(memory.data(),11,22,&received,&valid)&&!valid&&slots[0].state==Free,"bad request releases slot without application");
    for(unsigned i=0;i<kSlots;++i)Check(PushReply(memory.data(),11,22,r),"reliable receipt ring fill");
    auto replies=reinterpret_cast<ReplySlot*>(memory.data()+kHeaderBytes+kSlots*kSlotBytes);auto first_reply=replies[0].value;
    Check(!ReplySpace(memory.data(),11,22)&&!PushReply(memory.data(),11,22,r)&&std::memcmp(&first_reply,&replies[0].value,sizeof(r))==0,"full receipt ring retains pending results");
    replies[0].state=Free;Check(ReplySpace(memory.data(),11,22)&&PushReply(memory.data(),11,22,r),"receipt reader returns bounded capacity");
    Ledger ledger;Check(ledger.Begin(c).decision==Ledger::Decision::Fresh&&ledger.Begin(c).decision==Ledger::Decision::Pending,"pending work cannot execute twice");
    auto conflict=c;conflict.health=59;Seal(conflict);Check(ledger.Begin(conflict).decision==Ledger::Decision::Conflict,"same id cannot change payload");
    r.source_hash=c.payload_hash;Seal(r);Check(ledger.Finish(c.id,r)&&ledger.Begin(c).decision==Ledger::Decision::Replay,"final receipt replay");
    auto changed=r;changed.delta[0]=7;Seal(changed);Check(!ledger.Finish(c.id,changed),"applied receipt never changes");
    ledger.Reset();
    for(unsigned id=1;id<=Ledger::kCapacity;++id){auto next=c;next.id=id;Seal(next);Check(ledger.Begin(next).decision==Ledger::Decision::Fresh,"pending ledger capacity");}
    auto later=c;later.id=Ledger::kCapacity+1;Seal(later);Check(ledger.Begin(later).decision==Ledger::Decision::Full,"never evict pending to accept new work");
    auto first=c;first.id=1;Seal(first);r.id=1;r.source_hash=first.payload_hash;Seal(r);
    Check(ledger.Finish(1,r)&&ledger.Begin(later).decision==Ledger::Decision::Fresh&&ledger.Begin(first).decision==Ledger::Decision::Stale,"evicted completed id stays stale");
    std::printf("World motion protocol PASS layout=%016llX command/reply golden, malformed, scope, 3D length and player stamp\n",static_cast<unsigned long long>(kLayoutHash));return 0;
}catch(const std::exception& e){std::fprintf(stderr,"world motion FAIL: %s\n",e.what());return 1;}}
