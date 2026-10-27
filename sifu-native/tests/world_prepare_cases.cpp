#include "render/world_compositor.h"
#include <array>
#include <cstdio>
#include <cstring>
// The two CTest executables compile this corpus with SIMD and scalar Prepare.
// Compare every output field and all generated Point bytes via a stable hash.
int main(){
    sifucraft::world_image::Frame frame;
    auto& initial=frame.header;initial.captured_ms=1;initial.world_generation=initial.anchor_epoch=1;
    initial.cm_per_block=100;initial.sifu_frame=1;initial.projection[0]=1;initial.projection[5]=1.5f;
    initial.projection[10]=-1.002f;initial.projection[11]=-1;initial.projection[14]=-.2002f;
    for(unsigned i=0;i<4;i++)initial.view_rotation[i*5]=1;
    const auto original=frame.header;sifucraft::render::WorldCompositor renderer;unsigned test=0;
    const std::array<std::array<unsigned,2>,11> sizes{{{1,1},{3,2},{4,5},{15,17},{16,16},{17,15},{31,33},{63,65},{320,180},{1919,17},{1920,1080}}};
    for(const auto size:sizes)for(unsigned flags=0;flags<4;flags++)for(unsigned pattern=0;pattern<6;pattern++){
        auto& h=frame.header;h=original;h.width=size[0];h.height=size[1];h.stride=h.width*4;h.payload_bytes=h.stride*h.height*2;h.frame_id=++test;
        h.flags=sifucraft::world_image::kPremultiplied|(flags&1?sifucraft::world_image::kZeroToOne:0)|(flags&2?sifucraft::world_image::kBottomUp:0);
        h.projection[0]=h.projection[5]*h.height/h.width;
        if(flags&1)for(int column=0;column<4;column++)h.projection[column*4+2]=.5f*(h.projection[column*4+2]+h.projection[column*4+3]);
        frame.pixels.assign(h.payload_bytes,0);const auto plane=std::size_t(h.stride)*h.height;unsigned random=0x15263748;
        constexpr unsigned exceptional[]={0x7fc12345,0x7f812345,0xff800000,0x7f800000,0x80000000,0,0xbf000000,0x3f800001,0x3f800000,1,0x3e000000,0x3f000000};
        for(unsigned y=0;y<h.height;y++)for(unsigned x=0;x<h.width;x++){
            random=random*1664525u+1013904223u;unsigned rgba=0xffaabbcc;float depth=.45f;
            switch(pattern){
                case 1:depth=.1f+.6f*x/h.width+.2f*y/h.height;rgba=0x7faabbcc;break;
                case 2:depth=x<h.width/2?.3f:.6f;rgba=x%17==0||y%19==0?0:(x&1?0x01aabbcc:0xfeaabbcc);break;
                case 3:{auto bits=exceptional[(x+y*h.width)%std::size(exceptional)];std::memcpy(&depth,&bits,4);if(random&1)rgba=0;break;}
                case 4:rgba=0;std::memcpy(&depth,exceptional+(x+y)%4,4);break;
                case 5:depth=.2f+.6f*(random&0xffff)/65535.f;break;
            }
            auto at=std::size_t(y)*h.stride+x*4;std::memcpy(frame.pixels.data()+at,&rgba,4);std::memcpy(frame.pixels.data()+plane+at,&depth,4);
        }
        const auto result=renderer.Prepare(frame);if(FAILED(result))return 3;
        std::printf("%u %ux%u flags=%u pattern=%u pixels=%zu missing=%zu patches=%zu hash=%016llX\n",test,h.width,h.height,flags,pattern,renderer.pixels(),renderer.missing_depth(),renderer.patches(),static_cast<unsigned long long>(renderer.geometryHash()));
    }
}
