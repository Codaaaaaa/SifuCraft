#pragma once

#include <string>
#include <vector>

#include "bridge/shared_mapping.h"
#include "sifucraft_overlay.h"

namespace sifucraft::overlay {

struct Frame {
    FrameHeader header{};
    std::vector<std::uint8_t> pixels;
};

std::wstring MappingName(std::uint64_t mc_session, std::uint64_t sifu_session);

// Claims ownership before reading slot metadata. Drops old/foreign frames and never waits for
// the producer. On success the returned pixels are a private copy, safe during texture upload.
bool Consume(std::uint8_t* memory, std::uint64_t mc_session, std::uint64_t sifu_session,
             std::uint32_t world, std::uint32_t epoch, std::uint64_t* last_id, Frame* result);

class Reader {
public:
    bool Read(std::uint64_t mc_session, std::uint64_t sifu_session, std::uint32_t world,
              std::uint32_t epoch, Frame* frame);
    void Close();
    void SetViewport(std::uint32_t width, std::uint32_t height);
private:
    bridge::SharedMapping mapping_;
    std::uint64_t mc_session_ = 0, sifu_session_ = 0, last_id_ = 0;
};

} // namespace sifucraft::overlay
