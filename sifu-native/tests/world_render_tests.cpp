#include "render/world_compositor.h"
#include "render/compositor.h"
#include "generated/sifu_build.g.h"
#include <cstdio>
#include <cstring>
#include <limits>
#include <array>
#include <cmath>
#include <chrono>
#include <fstream>
using namespace sifucraft;
using render::Com;
namespace {
int failures = 0;
void Check(bool ok, const char* why) { std::printf("%s %s\n", ok ? "ok" : "FAIL", why); if (!ok) ++failures; }
std::vector<std::uint8_t> Read(ID3D11Device* device, ID3D11DeviceContext* context, ID3D11Texture2D* texture) {
    D3D11_TEXTURE2D_DESC d{}; texture->GetDesc(&d); d.Usage = D3D11_USAGE_STAGING; d.BindFlags = d.MiscFlags = 0; d.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
    Com<ID3D11Texture2D> staging; if (FAILED(device->CreateTexture2D(&d, nullptr, staging.put()))) return {};
    context->CopyResource(staging.get(), texture); D3D11_MAPPED_SUBRESOURCE mapped{};
    if (FAILED(context->Map(staging.get(), 0, D3D11_MAP_READ, 0, &mapped))) return {};
    std::vector<std::uint8_t> result(d.Width * d.Height * 4);
    for (UINT y = 0; y < d.Height; ++y) std::memcpy(result.data() + y * d.Width * 4, static_cast<char*>(mapped.pData) + y * mapped.RowPitch, d.Width * 4);
    context->Unmap(staging.get(), 0); return result;
}
bool Color(const std::vector<std::uint8_t>& p, int x, int y, int r, int g, int b) {
    const auto at = (y * 8 + x) * 4;
    return p.size() == 128 && std::abs(int(p[at]) - r) <= 2 && std::abs(int(p[at + 1]) - g) <= 2 &&
        std::abs(int(p[at + 2]) - b) <= 2 && p[at + 3] == 255;
}
// A sloping opaque surface must stay closed when a newer native camera reprojects an
// older source image. Sparse dots and front-facing planes do not exercise this failure.
void MovingSurface(ID3D11Device* device, ID3D11DeviceContext* context) {
    constexpr UINT width=96,height=64;
    namespace f = gen::off::FViewUniformShaderParameters;
    std::vector<std::uint8_t> parameters(8192);
    auto put=[&](std::size_t offset,float value){std::memcpy(parameters.data()+offset,&value,4);};
    for(auto offset:{f::ViewToClip,f::ViewToClipNoAA}){put(offset,1);put(offset+20,1.5f);put(offset+44,1);put(offset+56,10);}
    put(f::ViewSizeAndInvSize,width);put(f::ViewSizeAndInvSize+4,height);put(f::ViewSizeAndInvSize+8,1.f/width);put(f::ViewSizeAndInvSize+12,1.f/height);
    put(f::BufferSizeAndInvSize,width);put(f::BufferSizeAndInvSize+4,height);put(f::BufferSizeAndInvSize+8,1.f/width);put(f::BufferSizeAndInvSize+12,1.f/height);
    D3D11_BUFFER_DESC cb_desc{};cb_desc.ByteWidth=8192;cb_desc.Usage=D3D11_USAGE_DEFAULT;cb_desc.BindFlags=D3D11_BIND_CONSTANT_BUFFER;
    D3D11_SUBRESOURCE_DATA cb_data{parameters.data(),0,0};Com<ID3D11Buffer> cb;
    Check(SUCCEEDED(device->CreateBuffer(&cb_desc,&cb_data,cb.put())),"moving-surface parameter buffer");
    D3D11_TEXTURE2D_DESC d{};d.Width=width;d.Height=height;d.MipLevels=d.ArraySize=d.SampleDesc.Count=1;
    d.Format=DXGI_FORMAT_R32_TYPELESS;d.Usage=D3D11_USAGE_DEFAULT;d.BindFlags=D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_DEPTH_STENCIL;
    std::vector<float> native_z(width*height,0);D3D11_SUBRESOURCE_DATA z_data{native_z.data(),width*4,0};Com<ID3D11Texture2D> depth;
    device->CreateTexture2D(&d,&z_data,depth.put());
    D3D11_SHADER_RESOURCE_VIEW_DESC sd{};sd.Format=DXGI_FORMAT_R32_FLOAT;sd.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2D;sd.Texture2D.MipLevels=1;
    Com<ID3D11ShaderResourceView> srv;device->CreateShaderResourceView(depth.get(),&sd,srv.put());
    render::DepthIdentity identity{reinterpret_cast<std::uintptr_t>(depth.get()),reinterpret_cast<std::uintptr_t>(srv.get()),width,height};
    d.Format=DXGI_FORMAT_R8G8B8A8_UNORM;d.BindFlags=D3D11_BIND_RENDER_TARGET;Com<ID3D11Texture2D> image;
    device->CreateTexture2D(&d,nullptr,image.put());Com<ID3D11RenderTargetView> rtv;device->CreateRenderTargetView(image.get(),nullptr,rtv.put());
    world_image::Frame frame;auto& h=frame.header;
    h={world_image::Ready,width,height,width*4,1,1000,3,4,world_image::kPremultiplied|world_image::kBottomUp|world_image::kZeroToOne,width*height*8};
    h.cm_per_block=100;h.sifu_frame=7;h.projection[0]=1;h.projection[5]=1.5f;h.projection[11]=-1;h.projection[14]=.1f;
    for(int i=0;i<4;++i)h.view_rotation[i*5]=1;
    frame.pixels.resize(h.payload_bytes);
    auto source=[&](bool hole,bool translucent){
        for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x){
            const auto at=(std::size_t(y)*width+x)*4;
            const float u=2*(x+.5f)/width-1,v=2*(y+.5f)/height-1;
            const bool clear=hole&&std::abs(u)<.25f&&std::abs(v)<.25f;
            frame.pixels[at]=frame.pixels[at+3]=clear?0:translucent?128:255;
            const float z=.04f+.012f*u+.008f*v;std::memcpy(frame.pixels.data()+width*height*4+at,&z,4);
        }
    };
    source(false,false);render::WorldCompositor world;render::WorldScene scene;
    Check(world.Prepare(frame)==S_OK,"prepare a densely sampled tilted plane");
    std::array<float,16> matrix{};
    auto camera=[&](float tx,float tz,float yaw,float pitch){
        const float sy=std::sin(yaw),cy=std::cos(yaw),sp=std::sin(pitch),cp=std::cos(pitch);
        matrix={cy,1.5f*sy*sp,0,-sy*cp, -sy,1.5f*cy*sp,0,-cy*cp, 0,1.5f*cp,0,sp,
            -cy*tx,-1.5f*(sy*sp*tx+cp*tz),10,sy*cp*tx-sp*tz};
        std::memcpy(parameters.data()+f::WorldToClip,matrix.data(),64);
        context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);scene.Capture(context,identity,srv.get(),cb.get(),8192);
    };
    auto project=[&](float u,float v){
        const float distance=.1f/(.04f+.012f*u+.008f*v);
        const float point[]={100*u*distance,-100*distance,100*v*distance/1.5f,1};
        float clip[4]{};for(int c=0;c<4;++c)for(int r=0;r<4;++r)clip[c]+=point[r]*matrix[r*4+c];
        return std::array<float,2>{(clip[0]/clip[3]+1)*width*.5f,(1-clip[1]/clip[3])*height*.5f};
    };
    auto inside=[&](float x,float y,float extent){
        const std::array<std::array<float,2>,4> polygon={project(-extent,-extent),project(extent,-extent),project(extent,extent),project(-extent,extent)};
        bool positive=false,negative=false;
        for(int i=0;i<4;++i){const auto& a=polygon[i];const auto& b=polygon[(i+1)%4];const float cross=(b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]);positive|=cross>1e-4f;negative|=cross< -1e-4f;}
        return !(positive&&negative);
    };
    const float background[]={.2f,.4f,.6f,1};
    const float motions[][4]={{0,0,0,0},{30,0,0,0},{75,20,0,0},{45,15,.2f,.1f},{-60,-20,-.25f,-.12f}};
    for(const auto& motion:motions){
        camera(motion[0],motion[1],motion[2],motion[3]);context->ClearRenderTargetView(rtv.get(),background);
        Check(world.Draw(device,image.get(),scene)==S_OK,"draw a tilted plane with a newer translated/rotated native camera");
        const auto pixels=Read(device,context,image.get());unsigned tested=0,holes=0;
        for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x)if(inside(x+.5f,y+.5f,.55f)){
            ++tested;const auto at=(y*width+x)*4;if(pixels[at]<250||pixels[at+1]>2||pixels[at+2]>2)++holes;
        }
        std::printf("surface motion tx=%.0f tz=%.0f yaw=%.2f pitch=%.2f interior=%u holes=%u\n",motion[0],motion[1],motion[2],motion[3],tested,holes);
        Check(tested>100&&holes==0,"continuous opaque surface has no interior see-through pixels under camera motion");
    }
    camera(45,15,.2f,.1f);source(false,true);++h.frame_id;world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);
    auto pixels=Read(device,context,image.get());unsigned translucent_errors=0;
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x)if(inside(x+.5f,y+.5f,.55f)){
        const auto at=(y*width+x)*4;if(std::abs(int(pixels[at])-153)>2||std::abs(int(pixels[at+1])-51)>2||std::abs(int(pixels[at+2])-76)>2)++translucent_errors;
    }
    Check(translucent_errors==0,"shared surface edges neither crack nor double-blend translucent pixels");
    source(true,false);++h.frame_id;world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);pixels=Read(device,context,image.get());
    unsigned filled_hole=0;
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x)if(inside(x+.5f,y+.5f,.1f))if(pixels[(y*width+x)*4]>53)++filled_hole;
    Check(filled_hole==0,"a genuine transparent source opening stays open after reprojection");
    source(false,false);++h.frame_id;world.Prepare(frame);
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x)native_z[y*width+x]=x<width/2?.1f:0;
    context->UpdateSubresource(depth.get(),0,nullptr,native_z.data(),width*4,0);scene.Capture(context,identity,srv.get(),cb.get(),8192);
    context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);pixels=Read(device,context,image.get());unsigned wall_leaks=0;
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width/2;++x)if(pixels[(y*width+x)*4]>53)++wall_leaks;
    Check(wall_leaks==0,"continuous reconstruction still respects the native wall depth");
    // Depth-separated surfaces must not be joined across a real visibility gap.
    std::fill(native_z.begin(),native_z.end(),0);context->UpdateSubresource(depth.get(),0,nullptr,native_z.data(),width*4,0);
    camera(60,0,0,0);
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x){
        const auto at=(std::size_t(y)*width+x)*4;const bool foreground=x<width/2;
        frame.pixels[at]=foreground?255:0;frame.pixels[at+1]=foreground?0:255;frame.pixels[at+3]=255;
        const float z=foreground?.05f:.1f/12;std::memcpy(frame.pixels.data()+width*height*4+at,&z,4);
    }
    ++h.frame_id;world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);pixels=Read(device,context,image.get());
    const auto gap=(32*width+39)*4;
    Check(std::abs(int(pixels[gap])-51)<=2&&std::abs(int(pixels[gap+1])-102)<=2&&std::abs(int(pixels[gap+2])-153)<=2,
          "do not stretch geometry across a depth discontinuity to conceal newly exposed background");
    // Row orientation is independent of the surface connectivity construction.
    h.flags&=~world_image::kBottomUp;
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x){
        const auto at=(std::size_t(y)*width+x)*4;frame.pixels[at]=frame.pixels[at+3]=255;frame.pixels[at+1]=0;
        const float u=2*(x+.5f)/width-1,v=1-2*(y+.5f)/height;const float z=.04f+.012f*u+.008f*v;
        std::memcpy(frame.pixels.data()+width*height*4+at,&z,4);
    }
    camera(-60,-20,-.25f,-.12f);++h.frame_id;world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);pixels=Read(device,context,image.get());
    unsigned top_down_holes=0;
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x)if(inside(x+.5f,y+.5f,.55f)&&pixels[(y*width+x)*4]<250)++top_down_holes;
    Check(top_down_holes==0,"top-down sources also share continuous surface edges under motion");
    // Full-resolution texture variation must survive geometry coalescing, including alpha.
    camera(0,0,0,0);
    for(UINT y=0;y<height;++y)for(UINT x=0;x<width;++x){
        const auto at=(std::size_t(y)*width+x)*4;frame.pixels[at]=16*(x%7);frame.pixels[at+1]=16*(y%7);frame.pixels[at+2]=0;frame.pixels[at+3]=128;
        const float z=.04f;std::memcpy(frame.pixels.data()+width*height*4+at,&z,4);
    }
    ++h.frame_id;world.Prepare(frame);
    Check(world.pixels()==width*height && world.patches()==((width+15)/16)*((height+15)/16),"flat dense surface coalesces geometry without reducing source resolution");
    context->ClearRenderTargetView(rtv.get(),background);world.Draw(device,image.get(),scene);pixels=Read(device,context,image.get());
    unsigned texture_errors=0;
    for(UINT y=2;y<height-2;++y)for(UINT x=2;x<width-2;++x){const auto at=(y*width+x)*4;
        if(std::abs(int(pixels[at])-int(16*(x%7)+25))>2||std::abs(int(pixels[at+1])-int(16*(y%7)+51))>2||std::abs(int(pixels[at+2])-76)>2)++texture_errors;}
    Check(texture_errors==0,"coalesced translucent patches retain every source texel and blend only once");
}
int Benchmark(const char* path) {
    world_image::Frame frame;std::ifstream input(path,std::ios::binary);
    if(!input.read(reinterpret_cast<char*>(&frame.header),sizeof(frame.header))||
        !world_image::ValidFrame(frame.header,frame.header.captured_ms))return 2;
    frame.pixels.resize(frame.header.payload_bytes);
    if(!input.read(reinterpret_cast<char*>(frame.pixels.data()),frame.pixels.size()))return 2;
    render::WorldCompositor world;double total=0,peak=0;
    for(int i=0;i<32;++i){
        ++frame.header.frame_id;const auto start=std::chrono::steady_clock::now();
        if(FAILED(world.Prepare(frame)))return 2;
        const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
        if(i>=2){total+=ms;peak=std::max(peak,ms);}
    }
    std::printf("CPU world Prepare: source=%ux%u pixels=%zu average_ms=%.3f peak_ms=%.3f (30 warm samples)\n",frame.header.width,frame.header.height,world.pixels(),total/30,peak);
    return 0;
}
}
int main(int argc,char** argv) {
    if(argc==3&&std::strcmp(argv[1],"--bench")==0)return Benchmark(argv[2]);
    auto window = CreateWindowExW(0, L"STATIC", L"SifuCraft world test", WS_OVERLAPPEDWINDOW, 0,0,64,64,nullptr,nullptr,GetModuleHandleW(nullptr),nullptr);
    DXGI_SWAP_CHAIN_DESC swap{}; swap.BufferDesc.Width = 8; swap.BufferDesc.Height = 4; swap.BufferDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swap.SampleDesc.Count = swap.BufferCount = 1; swap.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT; swap.OutputWindow = window; swap.Windowed = TRUE;
    Com<IDXGISwapChain> chain; Com<ID3D11Device> device; Com<ID3D11DeviceContext> context;
    auto hr = D3D11CreateDeviceAndSwapChain(nullptr,D3D_DRIVER_TYPE_WARP,nullptr,0,nullptr,0,D3D11_SDK_VERSION,&swap,chain.put(),device.put(),nullptr,context.put());
    Check(SUCCEEDED(hr), "independent world WARP swap chain"); if (FAILED(hr)) return 1;
    render::WorldCompositor world; render::Compositor hud;
    {
        namespace f = gen::off::FViewUniformShaderParameters;
        std::vector<std::uint8_t> parameters(8192);
        auto put = [&](std::size_t offset, float value) { std::memcpy(parameters.data()+offset, &value, 4); };
        const float world_clip[] = {1,0,0,0, 0,0,0,-1, 0,2,0,0, 0,0,10,0};
        std::memcpy(parameters.data()+f::WorldToClip, world_clip, sizeof(world_clip));
        for (auto offset : {f::ViewToClip, f::ViewToClipNoAA}) {
            put(offset,1); put(offset+20,2); put(offset+44,1); put(offset+56,10);
        }
        put(f::ViewRectMin,2); put(f::ViewRectMin+4,1);
        put(f::ViewSizeAndInvSize,8); put(f::ViewSizeAndInvSize+4,4); put(f::ViewSizeAndInvSize+8,.125f); put(f::ViewSizeAndInvSize+12,.25f);
        put(f::BufferSizeAndInvSize,12); put(f::BufferSizeAndInvSize+4,8); put(f::BufferSizeAndInvSize+8,1.f/12); put(f::BufferSizeAndInvSize+12,.125f);
        D3D11_BUFFER_DESC cb_desc{}; cb_desc.ByteWidth = UINT(parameters.size()); cb_desc.Usage = D3D11_USAGE_DEFAULT; cb_desc.BindFlags = D3D11_BIND_CONSTANT_BUFFER;
        D3D11_SUBRESOURCE_DATA cb_data{parameters.data(),0,0}; Com<ID3D11Buffer> cb;
        device->CreateBuffer(&cb_desc,&cb_data,cb.put());
        std::vector<float> z(12*8,1);
        for (int y=1;y<5;++y) for(int x=2;x<10;++x) z[y*12+x] = x<6 ? .05f : .02f;
        D3D11_TEXTURE2D_DESC source_desc{}; source_desc.Width=12; source_desc.Height=8; source_desc.MipLevels=source_desc.ArraySize=source_desc.SampleDesc.Count=1;
        source_desc.Format=DXGI_FORMAT_R32_TYPELESS; source_desc.Usage=D3D11_USAGE_DEFAULT; source_desc.BindFlags=D3D11_BIND_SHADER_RESOURCE|D3D11_BIND_DEPTH_STENCIL;
        D3D11_SUBRESOURCE_DATA source_data{z.data(),12*4,0}; Com<ID3D11Texture2D> native_depth;
        device->CreateTexture2D(&source_desc,&source_data,native_depth.put());
        D3D11_SHADER_RESOURCE_VIEW_DESC srv_desc{}; srv_desc.Format=DXGI_FORMAT_R32_FLOAT; srv_desc.ViewDimension=D3D11_SRV_DIMENSION_TEXTURE2D; srv_desc.Texture2D.MipLevels=1;
        Com<ID3D11ShaderResourceView> srv; device->CreateShaderResourceView(native_depth.get(),&srv_desc,srv.put());
        render::DepthIdentity identity{reinterpret_cast<std::uintptr_t>(native_depth.get()),reinterpret_cast<std::uintptr_t>(srv.get()),12,8};
        render::WorldScene scene;
        Check(scene.Capture(context.get(),identity,srv.get(),cb.get(),8192)==S_OK,"copy getter-owned depth and view parameters into independent GPU resources");
        world_image::Frame frame; auto& h=frame.header;
        h.width=8;h.height=4;h.stride=32;h.frame_id=1;h.captured_ms=1000;h.world_generation=3;h.anchor_epoch=4;
        h.flags=world_image::kPremultiplied|world_image::kBottomUp|world_image::kZeroToOne;h.payload_bytes=256;h.cm_per_block=100;h.sifu_frame=7;
        h.projection[0]=1;h.projection[5]=2;h.projection[11]=-1;h.projection[14]=.1f;
        h.view_rotation[0]=h.view_rotation[5]=h.view_rotation[10]=h.view_rotation[15]=1;
        frame.pixels.resize(256);
        for(int i=0;i<32;++i){frame.pixels[i*4]=frame.pixels[i*4+3]=128;float value=1.f/30;std::memcpy(frame.pixels.data()+128+i*4,&value,4);}
        Com<ID3D11Texture2D> image; chain->GetBuffer(0,__uuidof(ID3D11Texture2D),reinterpret_cast<void**>(image.put()));
        Com<ID3D11RenderTargetView> rtv; device->CreateRenderTargetView(image.get(),nullptr,rtv.put());
        D3D11_TEXTURE2D_DESC sentinel_desc{};image->GetDesc(&sentinel_desc);Com<ID3D11Texture2D> sentinel;
        device->CreateTexture2D(&sentinel_desc,nullptr,sentinel.put());Com<ID3D11RenderTargetView> sentinel_rtv;
        device->CreateRenderTargetView(sentinel.get(),nullptr,sentinel_rtv.put());auto* before=sentinel_rtv.get();context->OMSetRenderTargets(1,&before,nullptr);
        const D3D11_VIEWPORT old_viewport{1,2,3,4,.1f,.9f};context->RSSetViewports(1,&old_viewport);context->IASetPrimitiveTopology(D3D11_PRIMITIVE_TOPOLOGY_LINELIST);
        const float background[]={.2f,.4f,.6f,1};context->ClearRenderTargetView(rtv.get(),background);
        Check(world.Prepare(frame)==S_OK&&world.pixels()==32,"compact actual visible device-depth pixels");
        hr=world.Draw(device.get(),image.get(),scene);if(FAILED(hr))std::printf("world draw hr=0x%08lX\n",static_cast<unsigned long>(hr));
        Check(hr==S_OK,"compile world shaders and reproject source pixels");
        auto pixels=Read(device.get(),context.get(),image.get());
        Check(Color(pixels,1,1,51,102,153)&&Color(pixels,6,1,153,51,76),"wall occludes behind pixels, front pixels blend with scene using padded ViewRect");
        Com<ID3D11RenderTargetView> restored;context->OMGetRenderTargets(1,restored.put(),nullptr);
        D3D11_VIEWPORT after{};UINT count=1;context->RSGetViewports(&count,&after);D3D11_PRIMITIVE_TOPOLOGY topology{};context->IAGetPrimitiveTopology(&topology);
        Check(restored.get()==before&&std::memcmp(&after,&old_viewport,sizeof(after))==0&&topology==D3D11_PRIMITIVE_TOPOLOGY_LINELIST,"restore exact host render target, viewport and topology");
        put(f::ViewToClip+32,.25f);put(f::WorldToClip+16,-.25f);context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);
        scene.Capture(context.get(),identity,srv.get(),cb.get(),8192);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device.get(),image.get(),scene);
        pixels=Read(device.get(),context.get(),image.get());
        Check(Color(pixels,2,1,51,102,153)&&Color(pixels,3,1,153,51,76),"remove native TAA jitter from output but retain it when sampling scene depth");
        put(f::ViewToClip+32,0);put(f::WorldToClip+16,0);context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);
        scene.Capture(context.get(),identity,srv.get(),cb.get(),8192);
        ++h.frame_id;frame.pixels[6*4]=0;frame.pixels[6*4+1]=255;frame.pixels[6*4+3]=255;
        world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device.get(),image.get(),scene);
        pixels=Read(device.get(),context.get(),image.get());Check(Color(pixels,6,3,0,255,0)&&Color(pixels,6,0,153,51,76),"bottom-up world is reprojected with correct vertical orientation");
        overlay::Frame overlay;overlay.header={overlay::Ready,8,4,32,1,3,4,overlay::kPremultiplied,128,0,0,0,0,1,0};overlay.pixels.resize(128);
        overlay.pixels[2]=overlay.pixels[3]=255;hud.Draw(device.get(),image.get(),overlay,true);
        Check(Color(Read(device.get(),context.get(),image.get()),0,0,0,0,255),"HUD remains visible above native world depth");
        put(f::BufferSizeAndInvSize,99);context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);
        scene.Capture(context.get(),identity,srv.get(),cb.get(),8192);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device.get(),image.get(),scene);
        Check(Color(Read(device.get(),context.get(),image.get()),6,1,51,102,153),"GPU rejects mismatched current view parameters instead of stretching depth");
        put(f::BufferSizeAndInvSize,12);context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);
        std::fill(z.begin(),z.end(),0);context->UpdateSubresource(native_depth.get(),0,nullptr,z.data(),12*4,0);scene.Capture(context.get(),identity,srv.get(),cb.get(),8192);
        std::fill(frame.pixels.begin(),frame.pixels.end(),0);++h.frame_id;h.camera_cm[0]=150;
        int point=1*8+2;frame.pixels[point*4]=frame.pixels[point*4+3]=255;float depth=1.f/30;std::memcpy(frame.pixels.data()+128+point*4,&depth,4);
        world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device.get(),image.get(),scene);pixels=Read(device.get(),context.get(),image.get());
        Check(Color(pixels,4,2,255,0,0)&&Color(pixels,2,2,51,102,153),"source camera offset moves world geometry instead of pasting old screen coordinates");
        ++h.frame_id;h.camera_cm[0]=0;
        for(int i=0;i<32;++i){frame.pixels[i*4]=frame.pixels[i*4+3]=128;float value=1.f/30;std::memcpy(frame.pixels.data()+128+i*4,&value,4);}
        put(f::ViewToClip+20,1);put(f::ViewToClipNoAA+20,1);put(f::WorldToClip+36,1);
        context->UpdateSubresource(cb.get(),0,nullptr,parameters.data(),0,0);scene.Capture(context.get(),identity,srv.get(),cb.get(),8192);
        world.Prepare(frame);context->ClearRenderTargetView(rtv.get(),background);world.Draw(device.get(),image.get(),scene);pixels=Read(device.get(),context.get(),image.get());
        Check(Color(pixels,0,2,51,102,153)&&Color(pixels,4,2,153,51,76),"fit the native projection inside a differently shaped output without drawing into letterbox bars");
        ++h.frame_id;std::fill(frame.pixels.begin()+128,frame.pixels.end(),0);world.Prepare(frame);
        Check(world.pixels()==0&&world.missing_depth()==32&&world.Draw(device.get(),image.get(),scene)==S_FALSE,"missing source depth cannot leave a stale world image");
    }
    MovingSurface(device.get(),context.get());
    context->OMSetRenderTargets(0,nullptr,nullptr);
    Check(SUCCEEDED(chain->ResizeBuffers(1,16,8,DXGI_FORMAT_R8G8B8A8_UNORM,0)),"world and HUD states do not retain swap-chain buffers across resize");
    DestroyWindow(window);std::printf("World GPU compositor %s (%d failures)\n",failures?"FAIL":"PASS",failures);return failures?1:0;
}
