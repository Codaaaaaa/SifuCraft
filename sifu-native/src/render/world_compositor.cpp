#include "render/world_compositor.h"
#include "render/pipeline_state.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <d3dcompiler.h>
#include "generated/sifu_build.g.h"

namespace sifucraft::render {
namespace {
constexpr char shader[] = R"hlsl(
Texture2D<float> sceneDepth : register(t0);
ByteAddressBuffer nativeView : register(t1);
cbuffer World : register(b0) {
    row_major float4x4 inverseMC;
    float4 cameraScale;
    float4 imageTarget;
    float4 allocation;
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
struct Pixel { float4 position : SV_Position; nointerpolation float4 color : COLOR0; };
Pixel vertexMain(float3 source : SOURCE, uint color : COLOR, float4 surface : CORNERS, uint corner : SV_VertexID) {
    const float2 corners[6] = {float2(-.5,-.5),float2(.5,-.5),float2(-.5,.5),
                              float2(-.5,.5),float2(.5,-.5),float2(.5,.5)};
    Pixel result;
    result.position = float4(0,0,0,-1); result.color = 0;
    if (!validView()) return result;
    const uint indices[6] = {0,1,2,2,1,3};
    // Integer grid edges and shared depths produce bit-identical vertices on both
    // sides of a continuous surface; independent constant-depth splats leave cracks.
    float2 ndc = (source.xy + .5 + corners[corner]) * 2 / imageTarget.xy - 1;
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
    result.color = float4(color & 255, (color >> 8) & 255, (color >> 16) & 255, color >> 24) / 255.0;
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
    return pixel.color;
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
    instances_.reset(); constants_buffer_.reset(); depth_.reset(); raster_.reset(); blend_.reset(); layout_.reset();
    pixel_.reset(); vertex_.reset(); context_.reset(); device_.reset(); capacity_ = 0; upload_ = true;
}
void WorldCompositor::Reset() { ResetGpu(); points_.clear(); source_indices_.clear(); source_distances_.clear(); prepared_id_ = 0; missing_depth_ = 0; constants_ = {}; }
HRESULT WorldCompositor::Prepare(const world_image::Frame& frame) {
    const auto& h = frame.header;
    if (h.frame_id == prepared_id_) return S_OK;
    points_.clear(); prepared_id_ = 0; missing_depth_ = 0; upload_ = true;
    if (!world_image::ValidFrame(h, h.captured_ms) || frame.pixels.size() != h.payload_bytes || !InverseViewProjection(h, constants_.inverse_mc))
        return E_INVALIDARG;
    std::copy(std::begin(h.camera_cm), std::end(h.camera_cm), constants_.camera_scale); constants_.camera_scale[3] = h.cm_per_block;
    constants_.image_target[0] = float(h.width); constants_.image_target[1] = float(h.height);
    const bool bottom_up = (h.flags & world_image::kBottomUp) != 0;
    constants_.allocation[2] = bottom_up ? 1.f : -1.f;
    constexpr auto invalid = std::numeric_limits<std::uint32_t>::max();
    source_indices_.resize(std::size_t(h.width) * h.height); source_distances_.clear();
    UINT min_x=h.width,min_y=h.height,max_x=0,max_y=0;
    const auto plane = std::size_t(h.stride) * h.height;
    for (UINT y = 0; y < h.height; ++y) for (UINT x = 0; x < h.width; ++x) {
        const auto at = std::size_t(y) * h.stride + x * 4;
        auto& index = source_indices_[std::size_t(y) * h.width + x]; index = invalid;
        if (!frame.pixels[at + 3]) continue;
        float z; std::memcpy(&z, frame.pixels.data() + plane + at, 4);
        if (!std::isfinite(z) || z <= 0 || z > 1) { ++missing_depth_; continue; }
        std::uint32_t color; std::memcpy(&color, frame.pixels.data() + at, 4);
        const float ndc_x = 2 * (x + .5f) / h.width - 1;
        const float ndc_y = bottom_up ? 2 * (y + .5f) / h.height - 1 : 1 - 2 * (y + .5f) / h.height;
        const float ndc_z = (h.flags & world_image::kZeroToOne) ? z : 2 * z - 1;
        const auto* m = constants_.inverse_mc;
        const float rx=m[0]*ndc_x+m[1]*ndc_y+m[2]*ndc_z+m[3], ry=m[4]*ndc_x+m[5]*ndc_y+m[6]*ndc_z+m[7];
        const float rz=m[8]*ndc_x+m[9]*ndc_y+m[10]*ndc_z+m[11], rw=m[12]*ndc_x+m[13]*ndc_y+m[14]*ndc_z+m[15];
        const float distance=std::sqrt(rx*rx+ry*ry+rz*rz)/std::abs(rw);
        index=static_cast<std::uint32_t>(points_.size());
        points_.push_back({float(x),float(y),ndc_z,color,{ndc_z,ndc_z,ndc_z,ndc_z}});
        source_distances_.push_back(std::isfinite(distance) ? distance : 0.f);
        min_x=std::min(min_x,x);min_y=std::min(min_y,y);max_x=std::max(max_x,x);max_y=std::max(max_y,y);
    }
    // Process each occupied grid corner once. Only locally continuous depth samples
    // share a corner; alpha holes and depth-separated surfaces retain separate edges.
    const float span_x=std::min(.1f,16.f/(std::abs(h.projection[0])*h.width));
    const float span_y=std::min(.1f,16.f/(std::abs(h.projection[5])*h.height));
    const float span_diagonal=std::hypot(span_x,span_y);
    auto at=[&](int x,int y){return x<0||y<0||x>=int(h.width)||y>=int(h.height) ? invalid : source_indices_[std::size_t(y)*h.width+x];};
    // Corner slots follow source grid order; the shader flips the entire Y
    // coordinate for top-down images, including each local corner offset.
    const int corner_index[]={3,2,1,0};
    if(!points_.empty())for(UINT y=min_y;y<=max_y+1;++y)for(UINT x=min_x;x<=max_x+1;++x){
        const std::uint32_t ids[]={at(int(x)-1,int(y)-1),at(int(x),int(y)-1),at(int(x)-1,int(y)),at(int(x),int(y))};
        if(ids[0]==invalid&&ids[1]==invalid&&ids[2]==invalid&&ids[3]==invalid)continue;
        if(ids[0]!=invalid&&ids[1]!=invalid&&ids[2]!=invalid&&ids[3]!=invalid){
            const float low=std::min({source_distances_[ids[0]],source_distances_[ids[1]],source_distances_[ids[2]],source_distances_[ids[3]]});
            const float high=std::max({source_distances_[ids[0]],source_distances_[ids[1]],source_distances_[ids[2]],source_distances_[ids[3]]});
            if(low>0&&high-low<=low*std::min(span_x,span_y)){
                const float value=(points_[ids[0]].z+points_[ids[1]].z+points_[ids[2]].z+points_[ids[3]].z)*.25f;
                for(int i=0;i<4;++i)points_[ids[i]].corners[corner_index[i]]=value;
                continue;
            }
        }
        int roots[]={0,1,2,3};
        auto root=[&](int i){while(roots[i]!=i)i=roots[i];return i;};
        auto join=[&](int a,int b,float span){
            if(ids[a]==invalid||ids[b]==invalid)return;
            const float da=source_distances_[ids[a]],db=source_distances_[ids[b]];
            if(da>0&&db>0&&std::abs(da-db)<=std::min(da,db)*span)roots[root(b)]=root(a);
        };
        join(0,1,span_x);join(0,2,span_y);join(0,3,span_diagonal);
        join(1,2,span_diagonal);join(1,3,span_y);join(2,3,span_x);
        float sums[4]{};int counts[4]{};
        for(int i=0;i<4;++i)if(ids[i]!=invalid){const int r=root(i);sums[r]+=points_[ids[i]].z;++counts[r];}
        for(int i=0;i<4;++i)if(ids[i]!=invalid){
            const int r=root(i);float value=sums[r]/counts[r];
            if(counts[r]==3){
                if(ids[0]!=invalid&&ids[3]!=invalid&&root(0)==r&&root(3)==r)value=(points_[ids[0]].z+points_[ids[3]].z)*.5f;
                else if(ids[1]!=invalid&&ids[2]!=invalid&&root(1)==r&&root(2)==r)value=(points_[ids[1]].z+points_[ids[2]].z)*.5f;
            }
            points_[ids[i]].corners[corner_index[i]]=value;
        }
    }
    prepared_id_ = h.frame_id; return S_OK;
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
    const D3D11_INPUT_ELEMENT_DESC input[] = {{"SOURCE",0,DXGI_FORMAT_R32G32B32_FLOAT,0,0,D3D11_INPUT_PER_INSTANCE_DATA,1},
                                            {"COLOR",0,DXGI_FORMAT_R32_UINT,0,12,D3D11_INPUT_PER_INSTANCE_DATA,1},
                                            {"CORNERS",0,DXGI_FORMAT_R32G32B32A32_FLOAT,0,16,D3D11_INPUT_PER_INSTANCE_DATA,1}};
    if (FAILED(hr = device->CreateInputLayout(input, 3, code->GetBufferPointer(), code->GetBufferSize(), layout_.put()))) return hr;
    if (FAILED(hr = Compile("pixelMain", "ps_5_0", code))) return hr;
    if (FAILED(hr = device->CreatePixelShader(code->GetBufferPointer(), code->GetBufferSize(), nullptr, pixel_.put()))) return hr;
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
    if (!prepared_id_ || points_.empty() || !scene.depth() || !scene.parameters()) return S_FALSE;
    auto hr = Initialize(device); if (FAILED(hr)) { ResetGpu(); return hr; }
    if (capacity_ < points_.size() || !instances_) {
        instances_.reset(); capacity_ = 0;
        D3D11_BUFFER_DESC desc{}; desc.ByteWidth = UINT(points_.size() * sizeof(Point)); desc.Usage = D3D11_USAGE_DYNAMIC;
        desc.BindFlags = D3D11_BIND_VERTEX_BUFFER; desc.CPUAccessFlags = D3D11_CPU_ACCESS_WRITE;
        if (FAILED(hr = device->CreateBuffer(&desc, nullptr, instances_.put()))) return hr;
        capacity_ = UINT(points_.size()); upload_ = true;
    }
    if (upload_) {
        D3D11_MAPPED_SUBRESOURCE mapped{};
        if (FAILED(hr = context_->Map(instances_.get(), 0, D3D11_MAP_WRITE_DISCARD, 0, &mapped))) return hr;
        std::memcpy(mapped.pData, points_.data(), points_.size() * sizeof(Point)); context_->Unmap(instances_.get(), 0); upload_ = false;
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
    ID3D11ShaderResourceView* views[] = {scene.depth(), scene.parameters()};
    context_->VSSetShaderResources(0, 2, views); context_->PSSetShaderResources(0, 2, views);
    context_->DrawInstanced(6, UINT(points_.size()), 0, 0);
    return S_OK;
}
}
