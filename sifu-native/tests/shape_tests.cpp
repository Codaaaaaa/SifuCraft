#include "bridge/shape_reader.h"
#include "blocks/owned_serial.h"
#include "blocks/shape_plan.h"
#include <atomic>
#include <array>
#include <cstring>
#include <cstdio>
#include <fstream>
#include <limits>
#include <stdexcept>
using namespace sifucraft::shape_data;
void Check(bool value, const char* why) { if (!value) throw std::runtime_error(why); }
int main(int argc, char** argv) {
    try {
        {
            using namespace sifucraft::blocks;
            std::map<ShapeKey,Box> wanted;
            std::map<ShapeKey,ShapeExisting> existing;
            const Box box{{0,0,0},{100,100,100},1};
            // Sixty-four synchronous piston parts change source keys each half-cycle.
            // Existing desired boxes and foreign identities must never become donors.
            for(int i=0;i<64;++i){existing[{0,0,0,i}]={box,true};wanted[{0,0,0,i+100}]=box;}
            existing[{0,0,0,1000}]={box,true};wanted[{0,0,0,1000}]=box;
            existing[{0,0,0,1001}]={box,false};
            auto plan=PlanShapeWork(wanted,existing);
            Check(plan.spawns.empty()&&plan.updates.size()==64&&plan.destroys==std::vector<ShapeKey>{{0,0,0,1001}},"piston fleet reuses obsolete owned boxes without actor churn");
            std::map<ShapeKey,int> sources,targets;
            for(const auto& update:plan.updates){Check(update.from[3]<64&&update.wanted.key[3]>=100&&update.wanted.key[3]<164,"only obsolete donors and missing targets");++sources[update.from];++targets[update.wanted.key];}
            Check(sources.size()==64&&targets.size()==64,"reuse is one-to-one");
            existing.clear();wanted.clear();existing[{0,0,0,1}]={box,true};wanted[{16,0,0,2}]=box;
            plan=PlanShapeWork(wanted,existing);
            Check(plan.updates.empty()&&plan.spawns.size()==1&&plan.destroys.size()==1,"no cross-section reuse");
            existing.clear();wanted.clear();existing[{0,0,0,1}]={box,false};existing[{0,0,0,2}]={box,true};wanted[{0,0,0,1}]=box;
            plan=PlanShapeWork(wanted,existing);
            Check(plan.updates.empty()&&plan.spawns.size()==1&&plan.destroys.size()==1,"invalid target keeps bounded identity replacement path");
        }
        std::int32_t serial=0;
        Check(sifucraft::blocks::MatchOwnedSerial(7,serial,7,0),"unallocated owned serial");
        Check(sifucraft::blocks::MatchOwnedSerial(7,serial,7,12) && serial==12,"lazy serial promotion");
        Check(!sifucraft::blocks::MatchOwnedSerial(7,serial,7,13) && serial==12,"reused serial rejected");
        Check(!sifucraft::blocks::MatchOwnedSerial(7,serial,8,12),"reused index rejected");
        Box b{{0,25,0},{100,75,50},1};
        Section section{-16,32,0,1,7,Hash(&b,sizeof(b))};
        std::vector<std::uint8_t> payload(sizeof(section)+sizeof(b));
        std::memcpy(payload.data(),&section,sizeof(section)); std::memcpy(payload.data()+sizeof(section),&b,sizeof(b));
        FrameHeader f{}; f.state=Ready; f.payload_bytes=payload.size(); f.revision=3; f.captured_ms=1000;
        f.world=9; f.sections=1; f.save_key=77; std::strcpy(f.map,"fixture");
        Frame out;
        Check(Decode(f,payload.data(),payload.size(),&out),"full snapshot");
        auto prior = out.header.revision;
        auto broken=payload; broken.back()^=1;
        Check(!Decode(f,broken.data(),broken.size(),&out) && out.header.revision==prior,"bad digest cannot partly replace state");
        Check(!Decode(f,payload.data(),payload.size()-1,&out),"truncation");
        auto count=f; count.sections=2; Check(!Decode(count,payload.data(),payload.size(),&out),"incomplete sections");
        auto duplicate=payload; duplicate.insert(duplicate.end(),payload.begin(),payload.end());
        count.payload_bytes=duplicate.size();
        Check(!Decode(count,duplicate.data(),duplicate.size(),&out) && out.header.revision==prior,"duplicate section cannot partly replace state");
        auto invalid=payload; auto invalid_box=b; invalid_box.min[0]=std::numeric_limits<float>::quiet_NaN();
        auto invalid_section=section; invalid_section.hash=Hash(&invalid_box,sizeof(invalid_box));
        std::memcpy(invalid.data(),&invalid_section,sizeof(invalid_section));
        std::memcpy(invalid.data()+sizeof(invalid_section),&invalid_box,sizeof(invalid_box));
        Check(!Decode(f,invalid.data(),invalid.size(),&out) && out.header.revision==prior,"NaN with valid digest rejected atomically");
        for(auto bad_key:{0ull,1ull<<44,65536ull}) {
            auto bad=b;bad.source_key=bad_key;auto header=section;header.hash=Hash(&bad,sizeof(bad));
            std::memcpy(invalid.data(),&header,sizeof(header));std::memcpy(invalid.data()+sizeof(header),&bad,sizeof(bad));
            Check(!Decode(f,invalid.data(),invalid.size(),&out),"invalid stable source key with valid digest rejected");
        }
        std::array<Box,2> repeated{b,b};auto duplicated=section;duplicated.boxes=2;duplicated.hash=Hash(repeated.data(),sizeof(repeated));
        std::vector<std::uint8_t> repeated_bytes(sizeof(Section)+sizeof(repeated));
        std::memcpy(repeated_bytes.data(),&duplicated,sizeof(duplicated));std::memcpy(repeated_bytes.data()+sizeof(Section),repeated.data(),sizeof(repeated));
        auto repeated_frame=f;repeated_frame.payload_bytes=repeated_bytes.size();
        Check(!Decode(repeated_frame,repeated_bytes.data(),repeated_bytes.size(),&out),"duplicate stable source cannot alias owned collision components");
        std::vector<std::uint8_t> memory(kMappingBytes);
        auto& h=*reinterpret_cast<Header*>(memory.data()); h={}; h.magic=kMagic; h.version=kVersion; h.mapping_bytes=kMappingBytes;
        h.slot_bytes=kSlotBytes; h.mc_session=11; h.sifu_session=22; h.layout_hash=kLayoutHash;
        auto* slot=reinterpret_cast<FrameHeader*>(memory.data()+sizeof(Header)); *slot=f;
        std::memcpy(reinterpret_cast<std::uint8_t*>(slot)+sizeof(f),payload.data(),payload.size());
        std::uint64_t last=0;
        h.version=1;Check(!Consume(memory.data(),11,22,9,"fixture",1001,&last,&out),"old 24B shapes cannot be interpreted as keyed 32B boxes");h.version=kVersion;
        Check(!Consume(memory.data(),11,22,8,"fixture",1001,&last,&out),"wrong world");
        *slot=f; Check(!Consume(memory.data(),11,22,9,"other",1001,&last,&out),"wrong map");
        *slot=f; Check(!Consume(memory.data(),12,22,9,"fixture",1001,&last,&out),"wrong session");
        *slot=f; Check(!Consume(memory.data(),11,22,9,"fixture",4000,&last,&out),"stale capture");
        *slot=f; Check(!Consume(memory.data(),11,22,9,"fixture",999,&last,&out),"future capture");
        *slot=f; slot->state=Writing; Check(!Consume(memory.data(),11,22,9,"fixture",1001,&last,&out),"incomplete producer");
        *slot=f; Check(Consume(memory.data(),11,22,9,"fixture",1001,&last,&out) && last==3 && slot->state==Free,"claimed/decoded/released");
        *slot=f; Check(!Consume(memory.data(),11,22,9,"fixture",1001,&last,&out),"duplicate snapshot");
        FrameHeader empty=f; empty.revision=4; empty.payload_bytes=0; empty.sections=0;
        *slot=empty; Check(Consume(memory.data(),11,22,9,"fixture",1001,&last,&out) && out.parts.empty(),"empty full snapshot removes all old collision");
        auto nan=b; nan.min[0]=std::numeric_limits<float>::quiet_NaN(); Check(!ValidBox(nan),"NaN shape");
        if (argc>1) {
            std::ifstream input(argv[1],std::ios::binary);
            std::vector<std::uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
            Check(bytes.size()>=sizeof(f),"Java golden missing");
            std::memcpy(&f,bytes.data(),sizeof(f));
            Check(Decode(f,bytes.data()+sizeof(f),bytes.size()-sizeof(f),&out) && out.parts[0].section.x==-16 && out.parts[0].boxes[0].max[2]==50,"Java/C++ golden ABI");
        }
        std::printf("Shape protocol PASS layout=%016llX malformed/scope/stale/duplicate/deletion/ownership/golden\n",(unsigned long long)kLayoutHash);
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr,"shape test FAIL: %s\n",e.what()); return 1; }
}
