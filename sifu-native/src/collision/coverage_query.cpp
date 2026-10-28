#include "collision/coverage_query.h"
#include <array>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <deque>
#include "collision/owned_scene.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/function_call.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::collision::coverage {
namespace {
namespace off=gen::off;
std::atomic<std::shared_ptr<const Sample>> published;
std::atomic<std::uint64_t> requested_epoch{1};
std::uint64_t next_probe=0;
struct Pending {std::uintptr_t world;std::uint32_t generation;ue::FVector center;};
std::deque<Pending> pending;
std::uintptr_t active_world=0;std::uint32_t active_generation=0;
ue::FVector local_center{};bool local_valid=false;std::uint64_t local_epoch=0;
struct Layout {const char* name;int offset,width;const char* kind;};
constexpr Layout parameters[]={
    {"WorldContextObject",0,8,"ObjectProperty"},{"BoxPos",8,12,"StructProperty"},{"Extent",20,12,"StructProperty"},
    {"ObjectTypes",32,16,"ArrayProperty"},{"ComponentClassFilter",48,8,"ClassProperty"},
    {"ActorsToIgnore",56,16,"ArrayProperty"},{"OutComponents",72,16,"ArrayProperty"},{"ReturnValue",88,1,"BoolProperty"}};
bool Signature(ue::FunctionCall& call,std::uintptr_t cls){
    if(!call.Resolve(cls,"BoxOverlapComponents")||call.params_size()!=96||!ue::IsRegistered(call.function())||ue::OuterOf(call.function())!=cls||
       mem::ReadOr<std::uint16_t>(call.function()+off::UFunction::ParmsSize,0)!=89||
       (mem::ReadOr<std::uint32_t>(call.function()+off::UFunction::FunctionFlags,0)&0x2400)!=0x2400)return false;
    const auto properties=ue::ListProperties(call.function());if(properties.size()!=std::size(parameters))return false;
    for(const auto& spec:parameters){
        auto at=std::find_if(properties.begin(),properties.end(),[&](const auto& p){return p.name==spec.name;});
        if(at==properties.end()||at->offset!=spec.offset||at->element_size!=spec.width||at->array_dim!=1||at->type!=spec.kind)return false;
        if(at->type=="StructProperty"&&at->detail!="Vector")return false;
        if(at->type=="ArrayProperty"){
            auto inner=mem::ReadPtr(at->field+off::FArrayProperty::Inner);
            if(!inner||mem::ReadOr<int>(inner+off::FProperty::ElementSize,0)!=(spec.offset==32?1:8)||
               at->detail!=(spec.offset==32?"ByteProperty":"ObjectProperty<"+std::string(spec.offset==56?"Actor":"PrimitiveComponent")+">"))return false;
        }
        if(at->type=="BoolProperty"&&(mem::ReadOr<std::uint8_t>(at->field+off::FBoolProperty::FieldSize,0)!=1||
           mem::ReadOr<std::uint8_t>(at->field+off::FBoolProperty::ByteMask,0)!=1||mem::ReadOr<std::uint8_t>(at->field+off::FBoolProperty::FieldMask,0)!=255))return false;
    }
    return true;
}
struct Output {
    std::uintptr_t data=0,base=0;
    void Free(){if(data){auto allocation=data;data=0;reinterpret_cast<void(*)(void*)>(base+gen::rva::CoverageArrayFree)(reinterpret_cast<void*>(allocation));}}
    ~Output(){Free();}
};
template<class T>void Put(std::array<std::uint8_t,96>& params,int offset,const T& value){std::memcpy(params.data()+offset,&value,sizeof(value));}
}
std::shared_ptr<const Sample> Latest(){return published.load();}
void Invalidate(){requested_epoch.fetch_add(1);published.store(nullptr);}
bool Request(std::uintptr_t world,std::uint32_t generation,ue::FVector center){
    if(!world||!generation)return false;
    for(auto v:{center.x,center.y,center.z})if(!std::isfinite(v)||std::fabs(v)>10000000)return false;
    if(active_world!=world||active_generation!=generation){pending.clear();active_world=world;active_generation=generation;local_valid=false;}
    for(const auto& old:pending)if(std::fabs(old.center.x-center.x)<1&&std::fabs(old.center.y-center.y)<1&&std::fabs(old.center.z-center.z)<1)return true;
    if(pending.size()>=16)return false;
    pending.push_back({world,generation,center});return true;
}
void Probe(std::uintptr_t base,std::uintptr_t world,std::uint32_t generation,std::uintptr_t pawn,const Responses& reference,std::uint64_t now){
    if(!base||!world||!generation||!ue::IsLive(pawn))return;
    const auto root=mem::ReadPtr(pawn+off::AActor::RootComponent);ue::FVector before{};
    if(!ue::ReadWorldLocation(root,&before)||!std::isfinite(before.x)||!std::isfinite(before.y)||!std::isfinite(before.z))return;
    if(active_world!=world||active_generation!=generation){pending.clear();active_world=world;active_generation=generation;local_valid=false;}
    const auto epoch=requested_epoch.load();
    const bool local=!local_valid||local_epoch!=epoch||std::fabs(before.x-local_center.x)>=800||std::fabs(before.y-local_center.y)>=800||std::fabs(before.z-local_center.z)>=800;
    if(!local&&pending.empty())return;
    if(now<next_probe)return;next_probe=now+500;
    const auto cls=ue::FindClass("KismetSystemLibrary"),primitive=ue::FindClass("PrimitiveComponent"),character=ue::FindClass("Character");
    const auto system=ue::DefaultObject(cls),profile=ue::DefaultObject(ue::FindClass("CollisionProfile"));ue::FunctionCall query;
    if(!ue::IsRegistered(system)||!ue::IsRegistered(profile)||ue::ClassOf(system)!=cls||!primitive||!character||!Signature(query,cls)){SC_LOG("[coverage-query] REFUSE function/CDO/layout preflight");return;}
    mem::RawArray mapping;
    if(!mem::ReadArray(profile+off::UCollisionProfile::ObjectTypeMapping,&mapping)||mapping.num<6||mapping.num>32){SC_LOG("[coverage-query] REFUSE object mapping");return;}
    std::array<std::int32_t,32> channels{};std::array<std::uint8_t,32> types{};std::uint32_t used=0;
    if(!mem::Copy(channels.data(),mapping.data,mapping.num*sizeof(channels[0])))return;
    for(int i=0;i<mapping.num;i++){if(channels[i]<0||channels[i]>=32||(used&(1u<<channels[i])))return;used|=1u<<channels[i];types[i]=static_cast<std::uint8_t>(i);}
    auto result=std::make_shared<Sample>();result->world=world;result->generation=generation;result->at_ms=now;result->epoch=epoch;result->center=local?before:pending.front().center;result->extent={1600,1600,1600};
    alignas(16) std::array<std::uint8_t,96> params{};
    Put(params,0,world);Put(params,8,result->center);Put(params,20,result->extent);Put(params,32,mem::RawArray{reinterpret_cast<std::uintptr_t>(types.data()),mapping.num,mapping.num});Put(params,48,primitive);
    const auto start=std::chrono::steady_clock::now();query.Invoke(system,params.data());
    mem::RawArray out{};std::memcpy(&out,params.data()+72,sizeof(out));Output cleanup{out.data,base};
    if(out.num<0||out.num>out.max||out.max>16384||(out.num&&!out.data)||params[88]>1||(params[88]!=0)!=(out.num!=0)){
        SC_LOG("[coverage-query] REFUSE output array count=%d capacity=%d",out.num,out.max);return;
    }
    std::vector<std::uintptr_t> pointers(out.num);if(out.num&&!mem::Copy(pointers.data(),out.data,pointers.size()*sizeof(pointers[0])))return;
    cleanup.Free();
    for(auto component:pointers){
        if(!ue::IsLive(component)||!ue::IsA(component,primitive))return;
        const auto owner=ue::OuterOf(component),level=ue::OuterOf(owner);
        if(!ue::IsLive(owner)||mem::ReadPtr(level+off::ULevel::OwningWorld)!=world)return;
        if(ue::IsA(owner,character)){result->characters++;continue;}
        const auto id=ue::IdentityOf(owner);const auto name=ue::NameOf(owner);
        if(owned_scene_actors.Contains(owner,id.index,id.serial,(std::uint64_t(name.number)<<32)|name.comparison_index)){result->derived++;continue;}
        if(!Blocks(component,reference))continue;
        result->blocking++;if(!BodySetupOf(component))result->missing_setup++;
        result->components.push_back({component,ue::IdentityOf(component),ue::NameOf(component),owner,id,name});
    }
    ue::FVector after{};if(mem::ReadPtr(pawn+off::AActor::RootComponent)!=root||!ue::ReadWorldLocation(root,&after)||
        std::fabs(after.x-before.x)>.001f||std::fabs(after.y-before.y)>.001f||std::fabs(after.z-before.z)>.001f)return;
    if(requested_epoch.load()!=epoch)return;
    published.store(result);
    if(local){local_valid=true;local_epoch=epoch;local_center=before;}else pending.pop_front();
    SC_LOG("[coverage-query] PASS world=%u box_cm=(%.1f,%.1f,%.1f)+/-1600 types=%d returned=%d blocking=%u character=%u derived=%u missing=%u cpu_us=%lld; output freed with UE allocator; awaiting voxel publication",generation,result->center.x,result->center.y,result->center.z,mapping.num,out.num,result->blocking,result->characters,result->derived,result->missing_setup,static_cast<long long>(std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-start).count()));
}
}
