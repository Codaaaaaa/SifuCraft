#pragma once
#include <string>
#include <vector>
#include "sifucraft_world.h"
#include "bridge/shared_mapping.h"
namespace sifucraft::world_image {
struct Frame { FrameHeader header{}; std::vector<std::uint8_t> pixels; };
std::wstring MappingName(std::uint64_t mc, std::uint64_t sifu);
bool Consume(std::uint8_t* memory, std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, std::uint32_t epoch,
             std::uint64_t now, std::uint64_t* last_id, Frame* frame);
class Reader {
public:
    bool Read(std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, std::uint32_t epoch, std::uint64_t now, Frame* frame);
    void Close();
private:
    bridge::SharedMapping mapping_;
    std::uint64_t mc_ = 0, sifu_ = 0, last_id_ = 0;
};
}
