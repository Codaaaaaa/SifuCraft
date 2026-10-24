#include "bridge/overlay_reader.h"

#include <atomic>
#include <cstdio>
#include <cstring>
#include <thread>

using namespace sifucraft::overlay;
namespace {
int failures = 0;
void Check(bool condition, const char* message) {
    if (!condition) { std::printf("FAIL %s\n", message); ++failures; }
}
FrameHeader* Slot(std::vector<std::uint8_t>& memory, int i) {
    return reinterpret_cast<FrameHeader*>(memory.data() + 64 + i * kSlotBytes);
}
void Put(std::vector<std::uint8_t>& memory, int i, std::uint64_t id, std::uint32_t world = 3, std::uint32_t epoch = 4) {
    auto* slot = Slot(memory, i);
    *slot = {Writing, 4, 2, 16, id, world, epoch, kPremultiplied | kBottomUp, 32, {}};
    std::memset(reinterpret_cast<std::uint8_t*>(slot) + 64, static_cast<int>(id & 255), 32);
    std::atomic_ref(slot->state).store(Ready, std::memory_order_release);
}
}
int main() {
    std::vector<std::uint8_t> memory(kMappingBytes, 0);
    auto& header = *reinterpret_cast<Header*>(memory.data());
    header = {kMagic, kVersion, kMappingBytes, kMaxWidth, kMaxHeight, kSlotBytes, kSlots, kRgba8, 1, 2, kLayoutHash, 0};
    Frame result;
    std::uint64_t last = 0;
    Check(MappingName(1, 2) == L"Local\\SifuCraft.Overlay.0000000000000001.0000000000000002", "mapping name ABI");
    Put(memory, 0, 7); Put(memory, 1, 9); Put(memory, 2, 8);
    Check(Consume(memory.data(), 1, 2, 3, 4, &last, &result) && last == 9 && result.pixels[0] == 9, "consume newest complete frame");
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "never consume the same image twice");
    Put(memory, 0, 10, 99); Put(memory, 1, 11, 3, 99);
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "reject old world and anchor epoch");
    Put(memory, 0, 12);
    Check(!Consume(memory.data(), 9, 2, 3, 4, &last, &result), "reject foreign session");
    Slot(memory, 0)->width = kMaxWidth + 1;
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "reject oversized source before copying");
    Put(memory, 0, 13); Slot(memory, 0)->payload_bytes = kPixels + 1;
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "reject malformed stride and payload length");
    Put(memory, 0, 14); Slot(memory, 0)->state = Writing;
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "never inspect or free a writing slot");
    Check(Slot(memory, 0)->state == Writing, "producer ownership preserved");
    Slot(memory, 0)->state = Free;
    header.layout_hash ^= 1;
    Check(!Consume(memory.data(), 1, 2, 3, 4, &last, &result), "refuse incompatible schema");
    header.layout_hash = kLayoutHash;

    // Race an actual producer against the consumer, replacing unread slots. Whole frames carry
    // one byte pattern, so any overwritten/torn image is detected independently of metadata.
    std::atomic<bool> done = false;
    std::thread producer([&] {
        for (std::uint64_t id = 20; id <= 20000; ++id) {
            auto* slot = Slot(memory, static_cast<int>(id % kSlots));
            auto state = std::atomic_ref(slot->state).load(std::memory_order_acquire);
            if ((state == Free || state == Ready) &&
                std::atomic_ref(slot->state).compare_exchange_strong(state, Writing, std::memory_order_acquire)) {
                const FrameHeader metadata{Writing, 4, 2, 16, id, 3, 4, kPremultiplied, 32, {}};
                std::memcpy(reinterpret_cast<std::uint8_t*>(slot) + 4,
                            reinterpret_cast<const std::uint8_t*>(&metadata) + 4, sizeof(metadata) - 4);
                std::memset(reinterpret_cast<std::uint8_t*>(slot) + 64, static_cast<int>(id & 255), 32);
                std::atomic_ref(slot->state).store(Ready, std::memory_order_release);
            }
        }
        done.store(true, std::memory_order_release);
    });
    unsigned received = 0;
    do {
        if (Consume(memory.data(), 1, 2, 3, 4, &last, &result)) {
            ++received;
            for (auto pixel : result.pixels) Check(pixel == (last & 255), "race: image is untorn");
        }
    } while (!done.load(std::memory_order_acquire));
    producer.join();
    if (Consume(memory.data(), 1, 2, 3, 4, &last, &result)) ++received;
    Check(received > 0, "concurrent transport makes progress");
    std::printf("Overlay reader %s; layout=%016llX concurrent_frames=%u\n", failures ? "FAIL" : "PASS",
                static_cast<unsigned long long>(kLayoutHash), received);
    return failures ? 1 : 0;
}
