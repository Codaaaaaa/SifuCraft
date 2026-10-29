#pragma once
#include <string>
#include <vector>
#include "bridge/shared_mapping.h"
#include "sifucraft_shapes.h"
namespace sifucraft::shape_data {
struct Part { Section section{}; std::vector<Box> boxes; };
struct Frame { FrameHeader header{}; std::vector<Part> parts; std::uint64_t source_mc = 0, source_sifu = 0; };
std::wstring MappingName(std::uint64_t mc, std::uint64_t sifu);
bool Decode(const FrameHeader& header, const std::uint8_t* payload, std::size_t size, Frame* output);
bool Consume(std::uint8_t* memory, std::uint64_t mc, std::uint64_t sifu, std::uint32_t world,
             const std::string& map, std::uint64_t now, std::uint64_t* last, Frame* output);
class Reader {
public:
    bool Read(std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, const std::string& map,
              std::uint64_t now, Frame* output);
    void Acknowledge(const Frame& frame, bool ready, std::uint64_t now, std::uint64_t applied_revision = 0);
    void Close();
private:
    bridge::SharedMapping mapping_;
    std::uint64_t mc_ = 0, sifu_ = 0, last_ = 0;
};
}
