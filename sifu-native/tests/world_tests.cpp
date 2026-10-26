#include "bridge/world_reader.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <limits>
using namespace sifucraft::world_image;
namespace {
int failures = 0;
void Check(bool ok, const char* why) { if (!ok) { std::printf("FAIL %s\n", why); ++failures; } }
FrameHeader* Put(std::vector<std::uint8_t>& memory, int slot, std::uint64_t id) {
    auto* f = reinterpret_cast<FrameHeader*>(memory.data() + 64 + slot * kSlotBytes);
    *f = {}; f->state = Ready; f->width = 4; f->height = 2; f->stride = 16;
    f->frame_id = id; f->captured_ms = 1000; f->world_generation = 3; f->anchor_epoch = 4;
    f->flags = kPremultiplied | kBottomUp | kZeroToOne; f->payload_bytes = 64; f->cm_per_block = 100;
    f->projection[0] = f->projection[5] = 1; f->projection[11] = -1;
    f->view_rotation[0] = f->view_rotation[5] = f->view_rotation[10] = f->view_rotation[15] = 1;
    f->sifu_frame = 99;
    std::memset(reinterpret_cast<std::uint8_t*>(f) + 256, int(id), 32);
    std::memset(reinterpret_cast<std::uint8_t*>(f) + 288, int(id + 1), 32);
    return f;
}
}
int main() {
    std::vector<std::uint8_t> memory(kMappingBytes);
    auto& global = *reinterpret_cast<Header*>(memory.data());
    global = {kMagic, kVersion, kMappingBytes, kMaxWidth, kMaxHeight, kSlotBytes, kSlots, kRgba8Depth32, 1, 2, kLayoutHash, 0};
    Frame result; std::uint64_t last = 0;
    Check(MappingName(1, 2) == L"Local\\SifuCraft.World.0000000000000001.0000000000000002", "world mapping ABI");
    Put(memory, 0, 7); Put(memory, 1, 9); Put(memory, 2, 8);
    Check(Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result) && last == 9 && result.pixels.size() == 64 &&
          result.pixels[0] == 9 && result.pixels[32] == 10 && result.header.sifu_frame == 99, "newest coherent camera/color/depth frame");
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result), "never replay an accepted world frame");
    Put(memory, 0, 10)->world_generation = 8; Put(memory, 1, 11)->anchor_epoch = 8;
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result), "reject stale world/anchor pairs");
    Put(memory, 0, 12)->captured_ms = 849; Put(memory, 1, 13)->captured_ms = 1101;
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result), "age checked from source capture time, including future clocks");
    Put(memory, 0, 14)->projection[3] = std::numeric_limits<float>::quiet_NaN(); Put(memory, 1, 15)->payload_bytes = 63;
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result), "reject malformed projection and incomplete plane pair");
    auto* held = Put(memory, 0, 16); held->state = Reading;
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result) && held->state == Reading, "claimed slot untouched");
    Put(memory, 1, 17); ++global.layout_hash;
    Check(!Consume(memory.data(), 1, 2, 3, 4, 1100, &last, &result), "foreign layout cannot enter world renderer");
    std::printf("World transport %s schema=%u layout=%016llX (%d failures)\n", failures ? "FAIL" : "PASS", kVersion,
                static_cast<unsigned long long>(kLayoutHash), failures);
    return failures ? 1 : 0;
}
