#pragma once
#include <string>
#include "bridge/shared_mapping.h"
#include "sifucraft_world_motion.h"
namespace sifucraft::world_motion {
std::wstring MappingName(std::uint64_t mc,std::uint64_t native);
bool Matches(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native);
// Single Native consumer/producer; malformed slots are released, never executed.
bool PopCommand(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native,Command* out,bool* valid);
bool PushReply(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native,const Reply& reply);
bool ReplySpace(std::uint8_t* memory,std::uint64_t mc,std::uint64_t native);
class Mailbox {
public:
    bool Open(std::uint64_t mc,std::uint64_t native);
    bool Read(Command* out,bool* valid);
    bool Write(const Reply& reply);
    bool CanReply();
    void Status(std::uint32_t world,std::uint64_t capabilities,std::uint64_t now,std::uint64_t player_stamp,std::uint64_t shape_revision,std::uint32_t flags=0,PlayerInfo player={});
    void Close();
private:
    bridge::SharedMapping mapping_;
    std::uint64_t mc_=0,native_=0;
};
}
