#include "render/pipeline_state.h"
namespace sifucraft::render {
PipelineState::PipelineState(ID3D11DeviceContext1* c) : context_(c) {
    // SOGetTargets exposes no offsets. Refuse the uncommon active-SO case rather
    // than change host stream-output cursor semantics.
    ID3D11Buffer* streams[4]{}; c->SOGetTargets(4, streams);
    bool stream_output = false;
    for (auto* buffer : streams) if (buffer) { stream_output = true; buffer->Release(); }
    if (stream_output) return;
    Com<ID3D11Device> device; c->GetDevice(device.put());
    uav_count_ = device->GetFeatureLevel() >= D3D_FEATURE_LEVEL_11_1 ? 64 : 8;
    c->VSGetShader(vs_.put(), vs_classes_.objects.data(), &vs_classes_.count);
    c->PSGetShader(ps_.put(), ps_classes_.objects.data(), &ps_classes_.count);
    c->GSGetShader(gs_.put(), gs_classes_.objects.data(), &gs_classes_.count);
    c->HSGetShader(hs_.put(), hs_classes_.objects.data(), &hs_classes_.count);
    c->DSGetShader(ds_.put(), ds_classes_.objects.data(), &ds_classes_.count);
    for (int stage = 0; stage < 6; ++stage) {
        ID3D11ShaderResourceView* values[128]{};
        switch (stage) {
            case 0: c->VSGetShaderResources(0,128,values); break;
            case 1: c->PSGetShaderResources(0,128,values); break;
            case 2: c->GSGetShaderResources(0,128,values); break;
            case 3: c->HSGetShaderResources(0,128,values); break;
            case 4: c->DSGetShaderResources(0,128,values); break;
            case 5: c->CSGetShaderResources(0,128,values); break;
        }
        for (UINT slot = 0; slot < 128; ++slot) resources_[stage][slot].reset(values[slot]);
    }
    ID3D11RenderTargetView* targets[8]{}; ID3D11UnorderedAccessView* uavs[64]{};
    c->OMGetRenderTargetsAndUnorderedAccessViews(8, targets, dsv_.put(), 0, uav_count_, uavs);
    for (UINT slot = 0; slot < 8; ++slot) { targets_[slot].reset(targets[slot]); if (targets[slot]) target_count_ = slot + 1; }
    for (UINT slot = 0; slot < uav_count_; ++slot) om_uavs_[slot].reset(uavs[slot]);
    c->CSGetUnorderedAccessViews(0, uav_count_, uavs);
    for (UINT slot = 0; slot < uav_count_; ++slot) cs_uavs_[slot].reset(uavs[slot]);
    c->VSGetConstantBuffers1(0,1,vs_cb_.put(),&vs_first_,&vs_count_);
    c->PSGetConstantBuffers1(0,1,ps_cb_.put(),&ps_first_,&ps_count_);
    c->IAGetVertexBuffers(0,1,vertex_buffer_.put(),&vertex_stride_,&vertex_offset_);
    c->IAGetInputLayout(layout_.put()); c->IAGetPrimitiveTopology(&topology_);
    c->PSGetSamplers(0,1,sampler_.put());
    c->OMGetBlendState(blend_.put(),blend_factor_,&sample_mask_); c->OMGetDepthStencilState(depth_.put(),&stencil_ref_);
    c->RSGetState(raster_.put()); c->RSGetViewports(&viewport_count_,viewports_.data()); c->RSGetScissorRects(&scissor_count_,scissors_.data());
    c->GetPredication(predicate_.put(),&predicate_value_);
    c->SetPredication(nullptr,FALSE);
    c->GSSetShader(nullptr,nullptr,0); c->HSSetShader(nullptr,nullptr,0); c->DSSetShader(nullptr,nullptr,0);
    // Unbind UAVs before an RTV can implicitly remove aliases; counters are kept.
    UINT keep[64]; for (auto& value : keep) value = UINT(-1);
    ID3D11UnorderedAccessView* empty[64]{};
    c->OMSetRenderTargetsAndUnorderedAccessViews(0,nullptr,nullptr,0,uav_count_,empty,keep);
    c->CSSetUnorderedAccessViews(0,uav_count_,empty,keep);
    ready_ = true;
}
PipelineState::~PipelineState() {
    if (!ready_) return;
    auto* c = context_;
    c->OMSetRenderTargets(0,nullptr,nullptr);
    ID3D11ShaderResourceView* empty[128]{};
    c->VSSetShaderResources(0,128,empty); c->PSSetShaderResources(0,128,empty);
    UINT keep[64]; for (auto& value : keep) value = UINT(-1);
    ID3D11UnorderedAccessView* uavs[64]{};
    for (UINT slot=0;slot<uav_count_;++slot) uavs[slot]=cs_uavs_[slot].get();
    c->CSSetUnorderedAccessViews(0,uav_count_,uavs,keep);
    ID3D11RenderTargetView* targets[8]{};
    for (UINT slot=0;slot<8;++slot) targets[slot]=targets_[slot].get();
    for (UINT slot=0;slot<uav_count_;++slot) uavs[slot]=om_uavs_[slot].get();
    c->OMSetRenderTargetsAndUnorderedAccessViews(target_count_,targets,dsv_.get(),target_count_,uav_count_-target_count_,uavs+target_count_,keep);
    for (int stage=0;stage<6;++stage) {
        ID3D11ShaderResourceView* values[128]{};
        for (UINT slot=0;slot<128;++slot) values[slot]=resources_[stage][slot].get();
        switch(stage) {
            case 0: c->VSSetShaderResources(0,128,values);break;
            case 1: c->PSSetShaderResources(0,128,values);break;
            case 2: c->GSSetShaderResources(0,128,values);break;
            case 3: c->HSSetShaderResources(0,128,values);break;
            case 4: c->DSSetShaderResources(0,128,values);break;
            case 5: c->CSSetShaderResources(0,128,values);break;
        }
    }
    auto* vs_cb=vs_cb_.get(); auto* ps_cb=ps_cb_.get();
    c->VSSetConstantBuffers1(0,1,&vs_cb,&vs_first_,&vs_count_); c->PSSetConstantBuffers1(0,1,&ps_cb,&ps_first_,&ps_count_);
    c->VSSetShader(vs_.get(),vs_classes_.objects.data(),vs_classes_.count);
    c->PSSetShader(ps_.get(),ps_classes_.objects.data(),ps_classes_.count);
    c->GSSetShader(gs_.get(),gs_classes_.objects.data(),gs_classes_.count);
    c->HSSetShader(hs_.get(),hs_classes_.objects.data(),hs_classes_.count);
    c->DSSetShader(ds_.get(),ds_classes_.objects.data(),ds_classes_.count);
    auto* vertex=vertex_buffer_.get(); auto* sampler=sampler_.get();
    c->IASetVertexBuffers(0,1,&vertex,&vertex_stride_,&vertex_offset_); c->IASetInputLayout(layout_.get()); c->IASetPrimitiveTopology(topology_);
    c->PSSetSamplers(0,1,&sampler); c->OMSetBlendState(blend_.get(),blend_factor_,sample_mask_); c->OMSetDepthStencilState(depth_.get(),stencil_ref_);
    c->RSSetState(raster_.get()); c->RSSetViewports(viewport_count_,viewports_.data()); c->RSSetScissorRects(scissor_count_,scissors_.data());
    c->SetPredication(predicate_.get(),predicate_value_);
}
}
