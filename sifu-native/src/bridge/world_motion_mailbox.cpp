#include "bridge/world_motion_mailbox.h"
#include <atomic>
#include <cstdio>
#include <cwchar>
#include <cstring>
#include <limits>
namespace sifucraft::world_motion {
std::wstring MappingName(std::uint64_t mc,std::uint64_t native){
    wchar_t name[128];std::swprintf(name,128,L"Local\\SifuCraft.WorldMotion.%016llX.%016llX",static_cast<unsigned long long>(mc),static_cast<unsigned long long>(native));return name;
}
bool Matches(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native){
    if(!memory||!mc||!native)return false;
    auto& h=*reinterpret_cast<Header*>(memory);
    return std::atomic_ref(h.magic).load(std::memory_order_acquire)==kMagic&&h.version==kVersion&&
        h.mapping_bytes==kMappingBytes&&h.slot_bytes==kSlotBytes&&h.mc_session==mc&&h.native_session==native&&h.layout_hash==kLayoutHash;
}
bool PopCommand(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native,Command* out,bool* valid){
    if(!out||!valid||!Matches(memory,mc,native))return false;
    auto slots=reinterpret_cast<CommandSlot*>(memory+kHeaderBytes);unsigned found=kSlots;std::uint64_t first=std::numeric_limits<std::uint64_t>::max();
    for(unsigned i=0;i<kSlots;++i)if(std::atomic_ref(slots[i].state).load(std::memory_order_acquire)==Ready&&(found==kSlots||slots[i].sequence<first)){
        found=i;first=slots[i].sequence;
    }
    if(found==kSlots)return false;
    auto& slot=slots[found];std::uint32_t expected=Ready;
    if(!std::atomic_ref(slot.state).compare_exchange_strong(expected,Reading,std::memory_order_acquire))return false;
    std::memcpy(out,&slot.value,sizeof(Command));*valid=!slot.reserved&&slot.sequence==out->id&&Valid(*out);
    std::atomic_ref(slot.state).store(Free,std::memory_order_release);return true;
}
bool ReplySpace(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native){
    if(!Matches(memory,mc,native))return false;
    auto slots=reinterpret_cast<ReplySlot*>(memory+kHeaderBytes+kSlots*kSlotBytes);
    for(unsigned i=0;i<kSlots;++i)if(std::atomic_ref(slots[i].state).load(std::memory_order_acquire)==Free)return true;
    return false;
}
bool PushReply(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native,const Reply& reply){
    if(!Matches(memory,mc,native)||!Valid(reply))return false;
    auto slots=reinterpret_cast<ReplySlot*>(memory+kHeaderBytes+kSlots*kSlotBytes);auto& header=*reinterpret_cast<Header*>(memory);
    for(unsigned i=0;i<kSlots;++i){
        auto& slot=slots[i];std::uint32_t expected=Free;
        if(!std::atomic_ref(slot.state).compare_exchange_strong(expected,Writing,std::memory_order_acquire))continue;
        slot.reserved=0;slot.sequence=++header.reply_sequence;slot.value=reply;
        std::atomic_ref(slot.state).store(Ready,std::memory_order_release);return true;
    }
    return false;
}
bool Mailbox::Open(std::uint64_t mc,std::uint64_t native){
    if(!mc||!native){Close();return false;}
    if(mc_!=mc||native_!=native){Close();if(!mapping_.Open(MappingName(mc,native).c_str(),kMappingBytes))return false;mc_=mc;native_=native;}
    return Matches(mapping_.data(),mc_,native_);
}
bool Mailbox::Read(Command* out,bool* valid){return PopCommand(mapping_.data(),mc_,native_,out,valid);}
bool Mailbox::Write(const Reply& reply){return PushReply(mapping_.data(),mc_,native_,reply);}
bool Mailbox::CanReply(){return ReplySpace(mapping_.data(),mc_,native_);}
void Mailbox::Status(std::uint32_t world,std::uint64_t caps,std::uint64_t now,std::uint64_t stamp,std::uint64_t revision,std::uint32_t flags,PlayerInfo player){
    if(!Matches(mapping_.data(),mc_,native_))return;
    constexpr auto player_caps=kPlayerMachineCapability|kPlayerFluidCapability;
    if((caps&player_caps)&&(!Valid(player)||static_cast<std::int32_t>(stamp>>32)<=0||static_cast<std::int32_t>(stamp)<=0))caps&=~player_caps;
    if(!(caps&player_caps))player={};
    auto& h=*reinterpret_cast<Header*>(mapping_.data());auto seq=std::atomic_ref(h.status_sequence);
    auto start=seq.load(std::memory_order_relaxed)&~1ull;seq.store(start+1,std::memory_order_seq_cst);
    h.world=world;h.world_flags=flags;h.capabilities=caps;h.native_ms=now;h.player_stamp=stamp;h.shape_revision=revision;h.player=player;seq.store(start+2,std::memory_order_release);
}
void Mailbox::Close(){mapping_.Close();mc_=native_=0;}
}
