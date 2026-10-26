#include "bridge/shape_reader.h"
#include <atomic>
#include <cstdio>
#include <cstring>
#include <set>
#include <tuple>
namespace sifucraft::shape_data {
std::wstring MappingName(std::uint64_t mc, std::uint64_t sifu) {
    wchar_t name[100]{};
    std::swprintf(name, 100, L"Local\\SifuCraft.Shapes.%016llX.%016llX", (unsigned long long)mc, (unsigned long long)sifu);
    return name;
}
bool Decode(const FrameHeader& h, const std::uint8_t* p, std::size_t size, Frame* out) {
    if (!p || !out || size != h.payload_bytes || size > kPayloadBytes || h.sections > kMaxSections ||
        !h.revision || !h.save_key || !h.world || !h.map[0] || !std::memchr(h.map,0,sizeof(h.map))) return false;
    Frame candidate; candidate.header = h;
    std::set<std::tuple<int,int,int>> keys;
    std::size_t at = 0, boxes = 0;
    for (std::uint32_t i = 0; i < h.sections; ++i) {
        if (size - at < sizeof(Section)) return false;
        Part part; std::memcpy(&part.section,p+at,sizeof(Section)); at += sizeof(Section);
        const auto& s = part.section;
        if (!s.revision || s.boxes > kMaxBoxes - boxes || s.boxes > (size-at)/sizeof(Box) ||
            std::abs(static_cast<std::int64_t>(s.x)) > 1048576 || std::abs(static_cast<std::int64_t>(s.y)) > 1048576 ||
            std::abs(static_cast<std::int64_t>(s.z)) > 1048576 || !keys.emplace(s.x,s.y,s.z).second) return false;
        boxes += s.boxes;
        part.boxes.resize(s.boxes); if (s.boxes) std::memcpy(part.boxes.data(),p+at,s.boxes*sizeof(Box));
        if (Hash(p+at,s.boxes*sizeof(Box)) != s.hash) return false;
        for (const auto& box : part.boxes) if (!ValidBox(box)) return false;
        at += s.boxes*sizeof(Box); candidate.parts.push_back(std::move(part));
    }
    if (at != size) return false;
    *out = std::move(candidate); return true;
}
bool Consume(std::uint8_t* mem, std::uint64_t mc, std::uint64_t sifu, std::uint32_t world,
             const std::string& map, std::uint64_t now, std::uint64_t* last, Frame* out) {
    if (!mem || !mc || !sifu || !last || !out) return false;
    auto& h = *reinterpret_cast<Header*>(mem);
    if (std::atomic_ref(h.magic).load(std::memory_order_acquire) != kMagic || h.version != kVersion ||
        h.mapping_bytes != kMappingBytes || h.slot_bytes != kSlotBytes || h.mc_session != mc ||
        h.sifu_session != sifu || h.layout_hash != kLayoutHash) return false;
    bool found = false;
    for (unsigned i = 0; i < kSlots; ++i) {
        auto* f = reinterpret_cast<FrameHeader*>(mem + sizeof(Header) + i*kSlotBytes);
        std::uint32_t expected = Ready;
        if (!std::atomic_ref(f->state).compare_exchange_strong(expected,Reading,std::memory_order_acquire)) continue;
        try {
            if (f->revision > *last && f->world == world && f->captured_ms && now >= f->captured_ms &&
                now - f->captured_ms <= 2000 && std::memchr(f->map,0,sizeof(f->map)) && map == f->map &&
                f->payload_bytes <= kPayloadBytes) {
                Frame candidate;
                if (Decode(*f,reinterpret_cast<std::uint8_t*>(f)+sizeof(FrameHeader),f->payload_bytes,&candidate)) {
                    candidate.source_mc = mc; candidate.source_sifu = sifu;
                    *last = f->revision; *out = std::move(candidate); found = true;
                }
            }
        } catch (...) { std::atomic_ref(f->state).store(Free,std::memory_order_release); throw; }
        std::atomic_ref(f->state).store(Free,std::memory_order_release);
    }
    return found;
}
bool Reader::Read(std::uint64_t mc, std::uint64_t sifu, std::uint32_t world, const std::string& map,
                  std::uint64_t now, Frame* output) {
    if (!mc || !sifu) { Close(); return false; }
    if (mc != mc_ || sifu != sifu_) {
        Close(); auto name = MappingName(mc,sifu);
        if (!mapping_.Open(name.c_str(),kMappingBytes)) return false;
        mc_ = mc; sifu_ = sifu;
    }
    return Consume(mapping_.data(),mc,sifu,world,map,now,&last_,output);
}
void Reader::Acknowledge(const Frame& f, bool ready, std::uint64_t now, std::uint64_t applied_revision) {
    if (!mapping_.data()) return;
    auto& h = *reinterpret_cast<Header*>(mapping_.data());
    if (std::atomic_ref(h.magic).load(std::memory_order_acquire) != kMagic) return;
    auto seq = std::atomic_ref(h.ack_seq); auto n = seq.load(std::memory_order_relaxed);
    seq.fetch_add(1,std::memory_order_acq_rel);
    h.applied_revision = ready ? (applied_revision ? applied_revision : f.header.revision) : 0; h.world = f.header.world; h.ready = ready ? 1 : 0;
    h.map_hash = Hash(f.header.map,std::strlen(f.header.map)); h.save_key = f.header.save_key; h.ack_ms = now;
    seq.store(n+2,std::memory_order_release);
}
void Reader::Close() { mapping_.Close(); mc_ = sifu_ = last_ = 0; }
}
