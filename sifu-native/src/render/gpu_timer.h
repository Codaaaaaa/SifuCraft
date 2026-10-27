#pragma once
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <d3d11.h>
#include "render/com_ptr.h"

namespace sifucraft::render {
// Render-thread queries; Runtime reads atomic counters. Never flush or wait for
// a result. Sampling loss is preferable to stalling the game's immediate context.
class GpuTimer {
public:
    struct Stats {std::uint64_t samples=0,total_ns=0,peak_ns=0,disjoint=0,skipped=0,errors=0;};
    Stats stats()const{return {samples_.load(),total_ns_.load(),peak_ns_.load(),disjoint_.load(),skipped_.load(),errors_.load()};}
    void Reset(){
        for(auto& slot:slots_){slot.start.reset();slot.end.reset();slot.frequency.reset();slot.pending=false;}
        device_.reset();active_=-1;next_=0;draws_=0;disabled_=false;
        samples_=0;total_ns_=0;peak_ns_=0;disjoint_=0;skipped_=0;errors_=0;
    }
    bool Begin(ID3D11Device* device,ID3D11DeviceContext* context){
        if(!device||!context)return false;
        if(device_.get()!=device){Reset();device->AddRef();device_.reset(device);}
        Poll(context);
        if(disabled_||active_>=0||(draws_++%16)!=0)return false;
        auto& slot=slots_[next_];if(slot.pending){skipped_++;return false;}
        if(!slot.frequency){
            D3D11_QUERY_DESC desc{D3D11_QUERY_TIMESTAMP_DISJOINT,0};
            HRESULT hr=device->CreateQuery(&desc,slot.frequency.put());desc.Query=D3D11_QUERY_TIMESTAMP;
            if(SUCCEEDED(hr))hr=device->CreateQuery(&desc,slot.start.put());
            if(SUCCEEDED(hr))hr=device->CreateQuery(&desc,slot.end.put());
            if(FAILED(hr)){disabled_=true;errors_++;return false;}
        }
        context->Begin(slot.frequency.get());context->End(slot.start.get());active_=static_cast<int>(next_);return true;
    }
    void End(ID3D11DeviceContext* context){
        if(active_<0)return;
        auto& slot=slots_[static_cast<std::size_t>(active_)];
        context->End(slot.end.get());context->End(slot.frequency.get());slot.pending=true;
        next_=(static_cast<std::size_t>(active_)+1)%slots_.size();active_=-1;
    }
private:
    void Poll(ID3D11DeviceContext* context){
        for(auto& slot:slots_)if(slot.pending){
            D3D11_QUERY_DATA_TIMESTAMP_DISJOINT frequency{};UINT64 start=0,end=0;
            const auto flags=D3D11_ASYNC_GETDATA_DONOTFLUSH;
            auto hr=context->GetData(slot.frequency.get(),&frequency,sizeof(frequency),flags);
            if(hr==S_FALSE)continue;
            if(FAILED(hr)){slot.pending=false;errors_++;continue;}
            if(frequency.Disjoint||!frequency.Frequency){slot.pending=false;disjoint_++;continue;}
            hr=context->GetData(slot.start.get(),&start,sizeof(start),flags);
            if(hr==S_FALSE)continue;
            if(FAILED(hr)){slot.pending=false;errors_++;continue;}
            hr=context->GetData(slot.end.get(),&end,sizeof(end),flags);
            if(hr==S_FALSE)continue;
            slot.pending=false;
            if(FAILED(hr)){errors_++;continue;}
            const long double ns=end>=start?(end-start)*1e9L/frequency.Frequency:-1;
            if(ns<0||!std::isfinite(ns)||ns>60e9L){disjoint_++;continue;}
            const auto elapsed=static_cast<std::uint64_t>(std::llround(ns));
            total_ns_.fetch_add(elapsed);if(elapsed>peak_ns_.load())peak_ns_=elapsed;samples_++;
        }
    }
    struct Slot {Com<ID3D11Query> start,end,frequency;bool pending=false;};
    std::array<Slot,8> slots_{};Com<ID3D11Device> device_;
    int active_=-1;std::size_t next_=0;std::uint64_t draws_=0;bool disabled_=false;
    std::atomic<std::uint64_t> samples_{0},total_ns_{0},peak_ns_{0},disjoint_{0},skipped_{0},errors_{0};
};
}
