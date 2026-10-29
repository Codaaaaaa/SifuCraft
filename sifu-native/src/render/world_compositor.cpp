#include "render/world_compositor.h"
#include "render/pipeline_state.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <d3dcompiler.h>
#if !defined(SIFUCRAFT_SCALAR_PREPARE) && (defined(__SSE2__) || defined(_M_X64))
#include <emmintrin.h>
#endif
#include "generated/sifu_build.g.h"

namespace sifucraft::render {
namespace {
constexpr char shader[] = R"hlsl(
Texture2D<float> sceneDepth : register(t0);
ByteAddressBuffer nativeView : register(t1);
Texture2D<float4> sourceColor : register(t2);
cbuffer World : register(b0) {
    row_major float4x4 inverseMC;
    float4 cameraScale;
    float4 imageTarget;
    float4 allocation;
    float4 fogColor;
    float4 fogRanges;
};
float4 field(uint offset) { return asfloat(nativeView.Load4(offset)); }
float4x4 worldToClip() {
    return float4x4(field(WORLD_CLIP), field(WORLD_CLIP+16), field(WORLD_CLIP+32), field(WORLD_CLIP+48));
}
float2 jitter() { return field(VIEW_CLIP+32).xy - field(NOAA_CLIP+32).xy; }
float2 outputScale() {
    float aspect = abs(field(NOAA_CLIP+16).y / field(NOAA_CLIP).x);
    float targetAspect = imageTarget.z / imageTarget.w;
    return aspect > targetAspect ? float2(1,targetAspect/aspect) : float2(aspect/targetAspect,1);
}
bool validView() {
    float4 size = field(VIEW_SIZE), bufferSize = field(BUFFER_SIZE), origin = field(VIEW_ORIGIN);
    return all(isfinite(size)) && all(isfinite(bufferSize)) && all(isfinite(origin.xy)) && all(isfinite(jitter())) &&
        all(size.xy > 0) && all(bufferSize.xy == allocation.xy) && all(origin.xy >= 0) &&
        all(origin.xy + size.xy <= bufferSize.xy) && all(abs(size.xy * size.zw - 1) < .0001) &&
        all(abs(bufferSize.xy * bufferSize.zw - 1) < .0001) && all(isfinite(outputScale())) && all(outputScale() > 0);
}
struct Pixel { float4 position : SV_Position; float2 source : TEXCOORD0; };
Pixel vertexMain(float4 source : SOURCE, float4 surface : CORNERS, uint corner : SV_VertexID) {
    const float2 corners[6] = {float2(0,0),float2(1,0),float2(0,1),
                              float2(0,1),float2(1,0),float2(1,1)};
    Pixel result;
    result.position = float4(0,0,0,-1); result.source = 0;
    if (!validView()) return result;
    const uint indices[6] = {0,1,2,2,1,3};
    // Integer grid edges and shared depths produce bit-identical vertices on both
    // sides of a continuous surface; independent constant-depth splats leave cracks.
    float2 sourceAt = source.xy + corners[corner] * source.zw;
    float2 ndc = sourceAt * 2 / imageTarget.xy - 1;
    ndc.y *= allocation.z;
    float4 mc = mul(inverseMC, float4(ndc, surface[indices[corner]], 1));
    if (!all(isfinite(mc)) || abs(mc.w) < 1e-9) return result;
    float3 relative = mc.xyz / mc.w;
    float3 ue = cameraScale.xyz + relative.xzy * cameraScale.w;
    float4 position = mul(float4(ue,1), worldToClip());
    if (!all(isfinite(position))) return result;
    position.xy -= jitter() * position.w;
    position.xy *= outputScale();
    result.position = position;
    result.source = sourceAt;
    return result;
}
float linearDepth(float z) {
    float4 row2 = field(VIEW_CLIP+32), row3 = field(VIEW_CLIP+48);
    return (row3.z - z * row3.w) / (z * row2.w - row2.z);
}
float4 pixelMain(Pixel pixel) : SV_Target {
    float2 uv = (pixel.position.xy / imageTarget.zw - .5) / outputScale() + .5 + jitter() * float2(.5,-.5);
    if (any(uv < 0) || any(uv >= 1)) discard;
    int2 at = int2(floor(field(VIEW_ORIGIN).xy + uv * field(VIEW_SIZE).xy));
    float scene = sceneDepth.Load(int3(at,0));
    if (!isfinite(scene) || scene < 0 || scene > 1) discard;
    float distance = linearDepth(pixel.position.z);
    if (!isfinite(distance) || distance <= 0) discard;
    if (scene > 0 && distance > linearDepth(scene) + 1.0) discard;
    if (any(pixel.source < 0) || any(pixel.source >= imageTarget.xy)) discard;
    return sourceColor.Load(int3(int2(floor(pixel.source)),0));
}
float4 fogVertex(uint id : SV_VertexID) : SV_Position {
    float2 uv=float2((id<<1)&2,id&2);
    return float4(uv*float2(2,-2)+float2(-1,1),0,1);
}
float fogValue(float distance,float start,float end){
    if(distance<=start)return 0;
    if(distance>=end)return 1;
    return (distance-start)/(end-start);
}
float4 fogPixel(float4 pixel : SV_Position) : SV_Target {
    if(!validView())discard;
    float2 uv=(pixel.xy/imageTarget.zw-.5)/outputScale()+.5+jitter()*float2(.5,-.5);
    if(any(uv<0)||any(uv>=1))discard;
    int2 at=int2(floor(field(VIEW_ORIGIN).xy+uv*field(VIEW_SIZE).xy));
    float z=sceneDepth.Load(int3(at,0));
    if(!isfinite(z)||z<0||z>1)discard;
    float4 p0=field(VIEW_CLIP),p1=field(VIEW_CLIP+16),p2=field(VIEW_CLIP+32);
    float4x4 w=worldToClip();
    if(!all(isfinite(p0))||!all(isfinite(p1))||!all(isfinite(p2))||abs(p0.x)<1e-6||abs(p1.y)<1e-6||abs(p2.w)<1e-6)discard;
    float3 forward=float3(w[0][3],w[1][3],w[2][3])/p2.w;
    float3 right=(float3(w[0][0],w[1][0],w[2][0])-forward*p2.x)/p0.x;
    float3 up=(float3(w[0][1],w[1][1],w[2][1])-forward*p2.y)/p1.y;
    if(!all(isfinite(forward))||!all(isfinite(right))||!all(isfinite(up))||
        abs(dot(forward,forward)-1)>.002||abs(dot(right,right)-1)>.002||abs(dot(up,up)-1)>.002||
        abs(dot(forward,right))>.002||abs(dot(forward,up))>.002||abs(dot(right,up))>.002)discard;
    float value=1;
    if(z>0){
        float distance=linearDepth(z);
        if(!isfinite(distance)||distance<=0)discard;
        float2 ndc=uv*float2(2,-2)+float2(-1,1);
        float3 relative=(forward+right*((ndc.x-p2.x)/p0.x)+up*((ndc.y-p2.y)/p1.y))*distance/100;
        float spherical=length(relative),cylindrical=max(length(relative.xy),abs(relative.z));
        value=max(fogValue(spherical,fogRanges.x,fogRanges.y),fogValue(cylindrical,fogRanges.z,fogRanges.w));
    }
    float alpha=value*fogColor.a;
    return float4(fogColor.rgb*alpha,alpha);
}
)hlsl";
bool InverseViewProjection(const world_image::FrameHeader& h, float* output) {
    double m[4][8]{};
    for (int r = 0; r < 4; ++r) for (int c = 0; c < 4; ++c) {
        for (int k = 0; k < 4; ++k) m[r][c] += double(h.projection[k * 4 + r]) * h.view_rotation[c * 4 + k];
        m[r][c + 4] = r == c;
    }
    for (int c = 0; c < 4; ++c) {
        int pivot = c;
        for (int r = c + 1; r < 4; ++r) if (std::fabs(m[r][c]) > std::fabs(m[pivot][c])) pivot = r;
        if (std::fabs(m[pivot][c]) < 1e-10) return false;
        if (pivot != c) for (int i = 0; i < 8; ++i) std::swap(m[c][i], m[pivot][i]);
        const auto scale = m[c][c]; for (double& v : m[c]) v /= scale;
        for (int r = 0; r < 4; ++r) if (r != c) {
            const auto factor = m[r][c]; for (int i = 0; i < 8; ++i) m[r][i] -= factor * m[c][i];
        }
    }
    for (int r = 0; r < 4; ++r) for (int c = 0; c < 4; ++c) {
        output[r * 4 + c] = static_cast<float>(m[r][c + 4]);
        if (!std::isfinite(output[r * 4 + c])) return false;
    }
    return true;
}
HRESULT Compile(const char* entry, const char* profile, Com<ID3DBlob>& result) {
    namespace f = gen::off::FViewUniformShaderParameters;
    const std::string values[] = {std::to_string(f::WorldToClip), std::to_string(f::ViewToClip), std::to_string(f::ViewToClipNoAA),
        std::to_string(f::ViewSizeAndInvSize), std::to_string(f::BufferSizeAndInvSize), std::to_string(f::ViewRectMin)};
    const D3D_SHADER_MACRO macros[] = {{"WORLD_CLIP", values[0].c_str()}, {"VIEW_CLIP", values[1].c_str()}, {"NOAA_CLIP", values[2].c_str()},
        {"VIEW_SIZE", values[3].c_str()}, {"BUFFER_SIZE", values[4].c_str()}, {"VIEW_ORIGIN", values[5].c_str()}, {nullptr,nullptr}};
    Com<ID3DBlob> errors;
    auto hr = D3DCompile(shader, sizeof(shader) - 1, "sifucraft_world", macros, nullptr, entry, profile,
                         D3DCOMPILE_OPTIMIZATION_LEVEL3, 0, result.put(), errors.put());
    if (FAILED(hr) && errors) std::fprintf(stderr, "World shader %s: %s\n", entry, static_cast<const char*>(errors->GetBufferPointer()));
    return hr;
}
}
void WorldCompositor::ResetGpu() {
    gpu_timer_.Reset();
    instances_.reset(); constants_buffer_.reset(); depth_.reset(); raster_.reset(); blend_.reset(); layout_.reset();
    pixel_.reset(); vertex_.reset();fog_vertex_.reset();fog_pixel_.reset(); context_.reset(); device_.reset(); capacity_ = 0; upload_ = true;
    color_view_.reset(); color_texture_.reset(); color_width_ = color_height_ = 0;
}
void WorldCompositor::Reset() { ResetGpu(); points_.clear(); source_corners_.clear(); source_distances_.clear(); source_depths_.clear(); source_color_.clear(); prepared_id_ = 0; pixel_count_ = missing_depth_ = 0; constants_ = {}; }
HRESULT WorldCompositor::Prepare(const world_image::Frame& frame) {
    const auto& h = frame.header;
    if (h.frame_id == prepared_id_) return S_OK;
    points_.clear(); prepared_id_ = 0; pixel_count_ = missing_depth_ = 0; upload_ = true;
    if (!world_image::ValidFrame(h,h.captured_ms) || frame.pixels.size()!=h.payload_bytes || !InverseViewProjection(h,constants_.inverse_mc)) return E_INVALIDARG;
    std::copy(std::begin(h.camera_cm),std::end(h.camera_cm),constants_.camera_scale); constants_.camera_scale[3]=h.cm_per_block;
    constants_.image_target[0]=float(h.width); constants_.image_target[1]=float(h.height);
    std::copy(std::begin(h.fog_color),std::end(h.fog_color),constants_.fog_color);
    std::copy(std::begin(h.fog_ranges),std::end(h.fog_ranges),constants_.fog_ranges);
    const bool bottom_up=(h.flags&world_image::kBottomUp)!=0;
    constants_.allocation[2]=bottom_up?1.f:-1.f;
    const auto plane=std::size_t(h.stride)*h.height;
    constexpr UINT tile=16;
    const UINT columns=(h.width+tile-1)/tile,rows=(h.height+tile-1)/tile;
    std::vector<UINT> occupied(std::size_t(columns)*rows);
    source_depths_.resize(std::size_t(h.width)*h.height);
    auto depth=[&](int x,int y) {
        if(x<0||y<0||x>=int(h.width)||y>=int(h.height))return 0.f;
        const float z=source_depths_[std::size_t(y)*h.width+x];
        if(z<=0)return 0.f;
        return (h.flags&world_image::kZeroToOne)?z:2*z-1;
    };
    // A separate validity check keeps legitimate negative NDC depths distinct from absent pixels.
    auto valid=[&](int x,int y) {
        if(x<0||y<0||x>=int(h.width)||y>=int(h.height))return false;
        return source_depths_[std::size_t(y)*h.width+x]>0;
    };
    for(UINT y=0;y<h.height;++y){
        UINT x=0;
#if !defined(SIFUCRAFT_SCALAR_PREPARE) && (defined(__SSE2__) || defined(_M_X64))
        // One tile is a multiple of four pixels. Ordered SIMD comparisons have
        // exactly the scalar finite/(0,1] validity rule, including NaN/Inf.
        constexpr unsigned popcount[]={0,1,1,2,1,2,2,3,1,2,2,3,2,3,3,4};
        for(;x+4<=h.width;x+=4){
            const auto at=std::size_t(y)*h.stride+x*4;__m128i rgba;std::memcpy(&rgba,frame.pixels.data()+at,sizeof(rgba));
            const auto absent=_mm_cmpeq_epi32(_mm_srli_epi32(rgba,24),_mm_setzero_si128());
            const auto alpha=(~unsigned(_mm_movemask_ps(_mm_castsi128_ps(absent))))&15u;
            auto* cached=source_depths_.data()+std::size_t(y)*h.width+x;
            if(!alpha){_mm_storeu_ps(cached,_mm_setzero_ps());continue;}
            __m128i bits;std::memcpy(&bits,frame.pixels.data()+plane+at,sizeof(bits));
            const auto nonfinite=_mm_cmpeq_epi32(_mm_and_si128(bits,_mm_set1_epi32(0x7f800000)),_mm_set1_epi32(0x7f800000));
            const auto z=_mm_castsi128_ps(_mm_andnot_si128(nonfinite,bits)); // avoid even signalling-NaN FP comparisons
            const auto validDepth=_mm_and_ps(_mm_cmpgt_ps(z,_mm_setzero_ps()),_mm_cmple_ps(z,_mm_set1_ps(1.f)));
            _mm_storeu_ps(cached,_mm_and_ps(z,_mm_andnot_ps(_mm_castsi128_ps(absent),validDepth)));
            const auto present=unsigned(_mm_movemask_ps(validDepth))&alpha;
            missing_depth_+=popcount[alpha]-popcount[present];pixel_count_+=popcount[present];
            occupied[std::size_t(y/tile)*columns+x/tile]+=popcount[present];
        }
#endif
        for(;x<h.width;++x){
        const auto at=std::size_t(y)*h.stride+x*4;
        auto& cached=source_depths_[std::size_t(y)*h.width+x];cached=0;
        if(!frame.pixels[at+3])continue;
        float z;std::memcpy(&z,frame.pixels.data()+plane+at,4);
        if(!std::isfinite(z)||z<=0||z>1){++missing_depth_;continue;}
        cached=z;
        ++pixel_count_;++occupied[std::size_t(y/tile)*columns+x/tile];
        }
    }
    if(!pixel_count_){source_color_.clear();prepared_id_=h.frame_id;return S_OK;}
    source_color_.assign(frame.pixels.begin(),frame.pixels.begin()+plane);
    source_distances_.resize(std::size_t(h.width)*h.height);
    source_corners_.resize(std::size_t(h.width+1)*(h.height+1));
    if(!++corner_stamp_){for(auto& c:source_corners_)c.stamp=0;for(auto& d:source_distances_)d.stamp=0;++corner_stamp_;}
    const auto* m=constants_.inverse_mc;
    const float scale_x=2.f/h.width,scale_y=(bottom_up?2.f:-2.f)/h.height;
    auto ray=[&](float x,float y,float z){
        const float nx=x*scale_x-1,ny=y*scale_y+(bottom_up?-1.f:1.f);
        return std::array<float,4>{m[0]*nx+m[1]*ny+m[2]*z+m[3],m[4]*nx+m[5]*ny+m[6]*z+m[7],
            m[8]*nx+m[9]*ny+m[10]*z+m[11],m[12]*nx+m[13]*ny+m[14]*z+m[15]};
    };
    auto distance=[&](int x,int y){
        auto& d=source_distances_[std::size_t(y)*h.width+x];
        if(d.stamp!=corner_stamp_){const auto p=ray(x+.5f,y+.5f,depth(x,y));d.value=std::sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2])/std::abs(p[3]);if(!std::isfinite(d.value))d.value=0;d.stamp=corner_stamp_;}
        return d.value;
    };
    const float span_x=std::min(.1f,16.f/(std::abs(h.projection[0])*h.width)),span_y=std::min(.1f,16.f/(std::abs(h.projection[5])*h.height));
    const float diagonal=std::hypot(span_x,span_y);
    // Cache one canonical result per source corner. Adjacent tiles and fine pixels share
    // exactly the same endpoints; disconnected depths retain their own corner values.
    auto corner=[&](int x,int y)->const Corner&{
        auto& c=source_corners_[std::size_t(y)*(h.width+1)+x];
        if(c.stamp==corner_stamp_)return c;
        c.stamp=corner_stamp_;
        const int xs[]={x-1,x,x-1,x},ys[]={y-1,y-1,y,y};
        bool used[4];float ds[4]{},zs[4]{};int roots[]={0,1,2,3};
        for(int i=0;i<4;++i){used[i]=valid(xs[i],ys[i]);if(used[i]){ds[i]=distance(xs[i],ys[i]);zs[i]=depth(xs[i],ys[i]);}}
        if(used[0]&&used[1]&&used[2]&&used[3]){
            const float low=std::min({ds[0],ds[1],ds[2],ds[3]}),high=std::max({ds[0],ds[1],ds[2],ds[3]});
            if(low>0&&high-low<=low*std::min(span_x,span_y)){
                const float z=(zs[0]+zs[1]+zs[2]+zs[3])*.25f;for(float& value:c.depths)value=z;return c;
            }
        }
        auto root=[&](int i){while(roots[i]!=i)i=roots[i];return i;};
        auto join=[&](int a,int b,float span){if(used[a]&&used[b]&&ds[a]>0&&ds[b]>0&&std::abs(ds[a]-ds[b])<=std::min(ds[a],ds[b])*span)roots[root(b)]=root(a);};
        join(0,1,span_x);join(0,2,span_y);join(0,3,diagonal);join(1,2,diagonal);join(1,3,span_y);join(2,3,span_x);
        float sums[4]{};int counts[4]{};
        for(int i=0;i<4;++i)if(used[i]){const int r=root(i);sums[r]+=zs[i];++counts[r];}
        for(int i=0;i<4;++i){c.depths[i]=0;if(!used[i])continue;const int r=root(i);float z=sums[r]/counts[r];
            if(counts[r]==3){if(used[0]&&used[3]&&root(0)==r&&root(3)==r)z=(zs[0]+zs[3])*.5f;
                else if(used[1]&&used[2]&&root(1)==r&&root(2)==r)z=(zs[1]+zs[2])*.5f;}
            c.depths[i]=z;
        }
        return c;
    };
    auto patch=[&](UINT x,UINT y,UINT w,UINT height){
        return Point{float(x),float(y),float(w),float(height),{corner(x,y).depths[3],corner(x+w,y).depths[2],
            corner(x,y+height).depths[1],corner(x+w,y+height).depths[0]}};
    };
    const float column_z=std::sqrt(m[2]*m[2]+m[6]*m[6]+m[10]*m[10]);
    auto emit=[&](auto&& self,UINT x,UINT y,UINT w,UINT height,UINT count)->void{
        if(!count)return;
        if(count<w*height){
            UINT x0=x+w,y0=y+height,x1=x,y1=y;
            for(UINT iy=y;iy<y+height;++iy)for(UINT ix=x;ix<x+w;++ix)if(valid(ix,iy)){x0=std::min(x0,ix);y0=std::min(y0,iy);x1=std::max(x1,ix);y1=std::max(y1,iy);}
            x=x0;y=y0;w=x1-x0+1;height=y1-y0+1;
        }
        bool merge=w>1&&height>1&&count==w*height;
        Point p{};
        if(merge){
            p=patch(x,y,w,height);
            // Device depth is affine over a planar surface. Bound the approximation in
            // centimetres along the source rays, rather than using a fixed Z-buffer epsilon.
            const float dx=(p.corners[1]-p.corners[0])/w,dy=(p.corners[2]-p.corners[0])/height;
            const float corner_error=std::abs(p.corners[3]-(p.corners[0]+dx*w+dy*height));
            float error=0,min_w=std::numeric_limits<float>::max(),max_w=0,max_ray=0,first_w=0;
            for(int i=0;i<4;++i){const auto r=ray(float(x+(i&1)*w),float(y+(i>>1)*height),p.corners[i]);
                if(!i)first_w=r[3];else if(first_w*r[3]<=0)merge=false;
                min_w=std::min(min_w,std::abs(r[3]));max_w=std::max(max_w,std::abs(r[3]));max_ray=std::max(max_ray,std::sqrt(r[0]*r[0]+r[1]*r[1]+r[2]*r[2]));}
#if !defined(SIFUCRAFT_SCALAR_PREPARE) && (defined(__SSE2__) || defined(_M_X64))
            __m128 residual=_mm_setzero_ps();
            const auto halfPixels=_mm_set_ps(3.5f,2.5f,1.5f,.5f),vDx=_mm_set1_ps(dx),vDepth=_mm_set1_ps(p.corners[0]);
#endif
            for(UINT iy=0;iy<height;++iy){
                UINT ix=0;
#if !defined(SIFUCRAFT_SCALAR_PREPARE) && (defined(__SSE2__) || defined(_M_X64))
                const auto vDy=_mm_set1_ps(dy*(iy+.5f));
                for(;ix+4<=w;ix+=4){
                    __m128 z;std::memcpy(&z,frame.pixels.data()+plane+std::size_t(y+iy)*h.stride+(x+ix)*4,sizeof(z));
                    if(!(h.flags&world_image::kZeroToOne))z=_mm_sub_ps(_mm_mul_ps(z,_mm_set1_ps(2.f)),_mm_set1_ps(1.f));
                    const auto px=_mm_add_ps(_mm_set1_ps(float(ix)),halfPixels);
                    const auto expected=_mm_add_ps(_mm_add_ps(vDepth,_mm_mul_ps(vDx,px)),vDy);
                    const auto difference=_mm_andnot_ps(_mm_set1_ps(-0.f),_mm_sub_ps(z,expected));
                    residual=_mm_max_ps(residual,difference);
                }
#endif
                for(;ix<w;++ix){
                float z;std::memcpy(&z,frame.pixels.data()+plane+std::size_t(y+iy)*h.stride+(x+ix)*4,4);
                if(!(h.flags&world_image::kZeroToOne))z=2*z-1;
                const float expected=p.corners[0]+dx*(ix+.5f)+dy*(iy+.5f);
                error=std::max(error,std::abs(z-expected));
                }
            }
#if !defined(SIFUCRAFT_SCALAR_PREPARE) && (defined(__SSE2__) || defined(_M_X64))
            float lanes[4];std::memcpy(lanes,&residual,sizeof(residual));for(float value:lanes)error=std::max(error,value);
#endif
            // Homogeneous coordinates are affine over the candidate rectangle; corner
            // extrema plus the measured depth residual bound every interior denominator.
            error+=corner_error;min_w-=std::abs(m[14])*error;max_w+=std::abs(m[14])*error;max_ray+=column_z*error;
            merge=merge&&min_w>1e-8f&&error*(column_z*max_w+std::abs(m[14])*max_ray)*h.cm_per_block/(min_w*min_w)<=.05f;
        }
        if(merge){points_.push_back(p);return;}
        // A silhouette or two different surfaces need not force all 256 source
        // pixels to become instances. Reuse the same conservative plane test on
        // smaller rectangles, retaining canonical corners and every source texel.
        if(w==1||height==1||(w<=2&&height<=2)){
            for(UINT iy=y;iy<y+height;++iy)for(UINT ix=x;ix<x+w;++ix)if(valid(ix,iy))points_.push_back(patch(ix,iy,1,1));
            return;
        }
        const UINT left=w>1?w/2:w,top=height>1?height/2:height;
        for(UINT oy=0;oy<height;oy+=top)for(UINT ox=0;ox<w;ox+=left){
            const UINT cw=std::min(left,w-ox),ch=std::min(top,height-oy);UINT present=0;
            for(UINT iy=y+oy;iy<y+oy+ch;++iy)for(UINT ix=x+ox;ix<x+ox+cw;++ix)present+=valid(ix,iy);
            self(self,x+ox,y+oy,cw,ch,present);
        }
    };
    for(UINT ty=0;ty<rows;++ty)for(UINT tx=0;tx<columns;++tx){
        const UINT x=tx*tile,y=ty*tile;
        emit(emit,x,y,std::min(tile,h.width-x),std::min(tile,h.height-y),occupied[std::size_t(ty)*columns+tx]);
    }
    prepared_id_=h.frame_id;return S_OK;
}

HRESULT WorldCompositor::Initialize(ID3D11Device* device) {
    if (device_.get() == device && constants_buffer_) return S_OK;
    ResetGpu(); device->AddRef(); device_.reset(device);
    HRESULT hr;
    Com<ID3D11DeviceContext> context; device->GetImmediateContext(context.put());
    if (FAILED(hr = context->QueryInterface(__uuidof(ID3D11DeviceContext1), reinterpret_cast<void**>(context_.put())))) return hr;
    Com<ID3DBlob> code;
    if (FAILED(hr = Compile("vertexMain", "vs_5_0", code))) return hr;
    if (FAILED(hr = device->CreateVertexShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, vertex_.put()))) return hr;
    const D3D11_INPUT_ELEMENT_DESC input[] = {{"SOURCE",0,DXGI_FORMAT_R32G32B32A32_FLOAT,0,0,D3D11_INPUT_PER_INSTANCE_DATA,1},
                                            {"CORNERS",0,DXGI_FORMAT_R32G32B32A32_FLOAT,0,16,D3D11_INPUT_PER_INSTANCE_DATA,1}};
    if (FAILED(hr = device->CreateInputLayout(input, 2, code->GetBufferPointer(), code->GetBufferSize(), layout_.put()))) return hr;
    if (FAILED(hr = Compile("pixelMain", "ps_5_0", code))) return hr;
    if (FAILED(hr = device->CreatePixelShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, pixel_.put()))) return hr;
    if(FAILED(hr=Compile("fogVertex","vs_5_0",code))||FAILED(hr=device->CreateVertexShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,fog_vertex_.put())))return hr;
    if(FAILED(hr=Compile("fogPixel","ps_5_0",code))||FAILED(hr=device->CreatePixelShader(code->GetBufferPointer(),code->GetBufferSize(),nullptr,fog_pixel_.put())))return hr;
    D3D11_BLEND_DESC blend{}; auto& rt = blend.RenderTarget[0]; rt.BlendEnable = TRUE;
    rt.SrcBlend = D3D11_BLEND_ONE; rt.DestBlend = D3D11_BLEND_INV_SRC_ALPHA; rt.BlendOp = D3D11_BLEND_OP_ADD;
    rt.SrcBlendAlpha = D3D11_BLEND_ZERO; rt.DestBlendAlpha = D3D11_BLEND_ONE; rt.BlendOpAlpha = D3D11_BLEND_OP_ADD;
    rt.RenderTargetWriteMask = D3D11_COLOR_WRITE_ENABLE_ALL;
    if (FAILED(hr = device->CreateBlendState(&blend, blend_.put()))) return hr;
    D3D11_RASTERIZER_DESC raster{}; raster.FillMode = D3D11_FILL_SOLID; raster.CullMode = D3D11_CULL_NONE; raster.DepthClipEnable = TRUE;
    if (FAILED(hr = device->CreateRasterizerState(&raster, raster_.put()))) return hr;
    D3D11_DEPTH_STENCIL_DESC depth{}; depth.DepthEnable = FALSE; depth.DepthFunc = D3D11_COMPARISON_ALWAYS;
    if (FAILED(hr = device->CreateDepthStencilState(&depth, depth_.put()))) return hr;
    D3D11_BUFFER_DESC constants{}; constants.ByteWidth = sizeof(Constants); constants.Usage = D3D11_USAGE_DYNAMIC;
    constants.BindFlags = D3D11_BIND_CONSTANT_BUFFER; constants.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
    return device->CreateBuffer(&constants, nullptr, constants_buffer_.put());
}
HRESULT WorldCompositor::Draw(ID3D11Device* device, ID3D11Texture2D* backbuffer, const WorldScene& scene) {
    if (!device || !backbuffer) return E_INVALIDARG;
    if (!hasContent() || !scene.depth() || !scene.parameters()) return S_FALSE;
    auto hr = Initialize(device); if (FAILED(hr)) { ResetGpu(); return hr; }
    if(!points_.empty()){
    if (capacity_ < points_.size() || !instances_) {
        UINT capacity=std::max(1024u,capacity_);while(capacity<points_.size())capacity*=2;
        D3D11_BUFFER_DESC desc{}; desc.ByteWidth = capacity * sizeof(Point); desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_VERTEX_BUFFER; desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        Com<ID3D11Buffer> buffer;
        if (FAILED(hr = device->CreateBuffer(&desc, nullptr, buffer.put()))) return hr;
        instances_=std::move(buffer);capacity_=capacity;upload_=true;
    }
    const UINT width=UINT(constants_.image_target[0]),height=UINT(constants_.image_target[1]);
    if(!color_view_||color_width_!=width||color_height_!=height){
        D3D11_TEXTURE2D_DESC d{};d.Width=width;d.Height=height;d.MipLevels=d.ArraySize=d.SampleDesc.Count=1;
        d.Format=DXGI_FORMAT_R8G8B8A8_UNORM;d.Usage=D3D11_USAGE_DYNAMIC;d.BindFlags=D3D11_BIND_SHADER_RESOURCE;d.CPUAccessFlags=D3D11_CPU_ACCESS_WRITE;
        Com<ID3D11Texture2D> texture;Com<ID3D11ShaderResourceView> view;
        if(FAILED(hr=device->CreateTexture2D(&d,nullptr,texture.put()))||FAILED(hr=device->CreateShaderResourceView(texture.get(),nullptr,view.put())))return hr;
        color_texture_=std::move(texture);color_view_=std::move(view);color_width_=width;color_height_=height;upload_=true;
    }
    if (upload_) {
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(hr = context_->Map(instances_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) return hr;
        std::memcpy(mapped.pData, points_.data(), points_.size() * sizeof(Point)); context_->Unmap(instances_.get(), 0);
        if(FAILED(hr=context_->Map(color_texture_.get(),0,D3D11_MAP_WRITE_DISCARD,0,&mapped)))return hr;
        for(UINT y=0;y<height;++y)std::memcpy(static_cast<std::uint8_t*>(mapped.pData)+std::size_t(y)*mapped.RowPitch,source_color_.data()+std::size_t(y)*width*4,width*4);
        context_->Unmap(color_texture_.get(),0);upload_=false;
    }
    }
    D3D11_TEXTURE2D_DESC desc{}; backbuffer->GetDesc(&desc);
    if (desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM && desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM &&
        desc.Format != DXGI_FORMAT_R8G8B8A8_UNORM_SRGB && desc.Format != DXGI_FORMAT_B8G8R8A8_UNORM_SRGB &&
        desc.Format != DXGI_FORMAT_R10G10B10A2_UNORM) return DXGI_ERROR_UNSUPPORTED;
    constants_.image_target[2] = float(desc.Width); constants_.image_target[3] = float(desc.Height);
    constants_.allocation[0] = float(scene.width()); constants_.allocation[1] = float(scene.height());
    D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(hr = context_->Map(constants_buffer_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) return hr;
    std::memcpy(mapped.pData, &constants_, sizeof(constants_)); context_->Unmap(constants_buffer_.get(), 0);
    Com<ID3D11RenderTargetView> target;
    if (FAILED(hr = device->CreateRenderTargetView(backbuffer, nullptr, target.put()))) return hr;
    PipelineState isolate(context_.get()); if (!isolate.ready()) return DXGI_ERROR_UNSUPPORTED;
    auto* rtv = target.get(); context_->OMSetRenderTargets(1, &rtv, nullptr);
    context_->OMSetBlendState(blend_.get(), nullptr, 0xffffffffu); context_->OMSetDepthStencilState(depth_.get(), 0);
    context_->RSSetState(raster_.get()); const D3D11_VIEWPORT viewport{0,0,float(desc.Width),float(desc.Height),0,1};
    context_->RSSetViewports(1, &viewport); context_->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_TRIANGLELIST);
    context_->IASetInputLayout(layout_.get()); auto* instance = instances_.get(); UINT stride = sizeof(Point), offset = 0;
    context_->IASetVertexBuffers(0, 1, &instance, &stride, &offset);
    context_->VSSetShader(vertex_.get(), nullptr, 0); context_->PSSetShader(pixel_.get(), nullptr, 0);
    auto* cb = constants_buffer_.get(); context_->VSSetConstantBuffers(0, 1, &cb); context_->PSSetConstantBuffers(0, 1, &cb);
    ID3D11ShaderResourceView* views[] = {scene.depth(), scene.parameters(),color_view_.get()};
    context_->VSSetShaderResources(0, 3, views); context_->PSSetShaderResources(0, 3, views);
    const bool timed=gpu_timer_.Begin(device,context_.get());
    if(constants_.fog_color[3]>0){
        context_->IASetInputLayout(nullptr);
        context_->VSSetShader(fog_vertex_.get(),nullptr,0);context_->PSSetShader(fog_pixel_.get(),nullptr,0);
        context_->Draw(3,0);
        context_->IASetInputLayout(layout_.get());context_->VSSetShader(vertex_.get(),nullptr,0);context_->PSSetShader(pixel_.get(),nullptr,0);
    }
    if(!points_.empty())context_->DrawInstanced(6, UINT(points_.size()), 0, 0);
    if(timed)gpu_timer_.End(context_.get());
    return S_OK;
}
}
