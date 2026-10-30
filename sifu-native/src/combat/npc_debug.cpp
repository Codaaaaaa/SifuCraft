#include "combat/npc_debug.h"
#include "sifucraft_npc_debug.h"
#include <windows.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cstdio>
#include <deque>
#include <optional>
#include "core/log.h"
#include "core/safe_memory.h"
#include "probe/combat_sample.h"
#include "ue/function_call.h"
#include "ue/reflection.h"
#include "ue/transform.h"

namespace sifucraft::npc_debug {
namespace {
namespace off=gen::off;
namespace motion=combat::motion;
bool g_startup=false;
SRWLOCK g_lock=SRWLOCK_INIT;
world_motion::Context g_published;
std::optional<Request> g_request;
std::deque<Reply> g_replies;
std::uint64_t g_next_io=0;
std::optional<Request> g_last_file;
bool g_opted=false; // guarded by g_lock
DWORD g_thread=0;
struct Object {std::uintptr_t ptr=0;ue::ObjectIdentity id{};bool live()const{return ptr&&id.valid()&&ue::IdentityOf(ptr)==id;}};
Object Remember(std::uintptr_t p){return {p,ue::IdentityOf(p)};}
struct Owned {Object spawner,npc;motion::Scope scope;bool stopping=false;std::uint64_t stop_at=0;};
std::vector<Owned> g_owned;
struct Job {Request request;motion::Scope scope;Object spawner;std::uint64_t began=0;};
std::optional<Job> g_job;
struct Cache {Request request;Reply reply;};
std::deque<Cache> g_cache;
motion::Scope g_scope;
std::uint64_t g_high=0,g_last_spawn=0;
const char* kClasses[]={"BP_AICharacter_Grunt_M_HD1_Fighter_21_C","BP_AICharacter_Grunt_M_HD1_Fighter_11_C"};
bool Allowed(std::uintptr_t p){const auto name=ue::ClassName(p);return name==kClasses[0]||name==kClasses[1];}
struct Calls {
    ue::FunctionCall teleport,begin,finish,set_class,wants,get,unspawn,destroy;
    std::uintptr_t statics=0,spawner_class=0;
    int destination=-1,rotation=-1,teleport_result=-1,class_arg=-1,get_result=-1;
    bool ready=false;
} g_calls;
bool Field(const ue::FunctionCall& f,const char* name,int size,int* at){
    *at=f.ParamOffset(name);return *at>=0&&*at+size<=f.params_size();
}
bool Resolve(){
    auto& c=g_calls;
    if(c.ready)return ue::IsRegistered(c.statics)&&ue::IsRegistered(c.spawner_class)&&ue::IsRegistered(c.teleport.function());
    const auto actor=ue::FindClass("Actor"),statics=ue::FindClass("GameplayStatics");
    c.spawner_class=ue::FindClass("AISpawner");c.statics=ue::DefaultObject(statics);
    if(!actor||!c.spawner_class||!ue::IsRegistered(c.statics))return false;
    if(!c.teleport.Resolve(actor,"K2_TeleportTo")||!c.begin.Resolve(statics,"BeginDeferredActorSpawnFromClass")||
       !c.finish.Resolve(statics,"FinishSpawningActor")||!c.set_class.Resolve(c.spawner_class,"BPF_SetSpawningClass")||
       !c.wants.Resolve(c.spawner_class,"BPF_WantsSpawn")||!c.get.Resolve(c.spawner_class,"BPF_GetSpawnedAI")||
       !c.unspawn.Resolve(c.spawner_class,"BPF_UnSpawnAIDelayed")||!c.destroy.Resolve(actor,"K2_DestroyActor"))return false;
    const auto tele=ue::ListProperties(c.teleport.function()),set=ue::ListProperties(c.set_class.function()),get=ue::ListProperties(c.get.function());
    if(tele.size()!=3||set.size()!=1||get.size()!=1||c.wants.params_size()!=0||c.unspawn.params_size()!=0||c.destroy.params_size()!=0)return false;
    auto parameter=[](const auto& properties,const char* name,const char* type,const char* detail,int size){
        return std::any_of(properties.begin(),properties.end(),[&](const auto& p){return p.name==name&&p.type==type&&p.detail==detail&&p.element_size==size&&p.array_dim==1;});
    };
    if(!parameter(tele,"DestLocation","StructProperty","Vector",12)||!parameter(tele,"DestRotation","StructProperty","Rotator",12)||
       !parameter(tele,"ReturnValue","BoolProperty","",1)||set[0].type!="ClassProperty"||set[0].element_size!=8||set[0].array_dim!=1||
       get[0].name!="ReturnValue"||get[0].type!="ObjectProperty"||get[0].element_size!=8||get[0].array_dim!=1)return false;
    c.class_arg=set[0].offset;c.get_result=get[0].offset;
    if(!Field(c.teleport,"DestLocation",12,&c.destination)||!Field(c.teleport,"DestRotation",12,&c.rotation)||!Field(c.teleport,"ReturnValue",1,&c.teleport_result))return false;
    for(auto* f:{&c.teleport,&c.begin,&c.finish,&c.set_class,&c.wants,&c.get,&c.unspawn,&c.destroy}){
        if(f->params_size()<0||f->params_size()>256||!ue::IsRegistered(f->function())||!(mem::ReadOr<std::uint32_t>(f->function()+off::UFunction::FunctionFlags,0)&0x400))return false;
    }
    for(auto spec:{std::pair{"WorldContextObject",8},std::pair{"ActorClass",8},std::pair{"SpawnTransform",48},std::pair{"CollisionHandlingOverride",1},std::pair{"ReturnValue",8}}){int at;if(!Field(c.begin,spec.first,spec.second,&at))return false;}
    for(auto spec:{std::pair{"Actor",8},std::pair{"SpawnTransform",48},std::pair{"ReturnValue",8}}){int at;if(!Field(c.finish,spec.first,spec.second,&at))return false;}
    if(c.class_arg<0||c.class_arg+8>c.set_class.params_size()||c.get_result<0||c.get_result+8>c.get.params_size())return false;
    c.ready=true;
    SC_LOG("[npc-debug] reflection PASS teleport=%d set_class=%d wants=%d get=%d unspawn=%d begin=%d finish=%d; no new hooks/field writes",c.teleport.params_size(),c.set_class.params_size(),c.wants.params_size(),c.get.params_size(),c.unspawn.params_size(),c.begin.params_size(),c.finish.params_size());
    return true;
}
struct Params {alignas(16) std::array<std::byte,256> bytes{};template<class T> void put(int at,const T& value){std::memcpy(bytes.data()+at,&value,sizeof(value));}template<class T>T get(int at)const{T value{};std::memcpy(&value,bytes.data()+at,sizeof(value));return value;}};
void InvokeEmpty(const ue::FunctionCall& f,std::uintptr_t p){Params params;f.Invoke(p,params.bytes.data());}
std::uintptr_t Spawned(Object object){if(!object.live())return 0;Params params;g_calls.get.Invoke(object.ptr,params.bytes.data());return params.get<std::uintptr_t>(g_calls.get_result);}
bool Inactive(Object object){return !object.live()||(mem::ReadOr<std::uint8_t>(object.ptr+off::ASCCharacter::m_bIsPooled,0)&&!mem::ReadOr<std::uint8_t>(object.ptr+off::ASCCharacter::m_bPooledActorActive,1));}
bool SameWorld(Object object,std::uintptr_t world){return object.live()&&mem::ReadPtr(ue::OuterOf(object.ptr)+off::ULevel::OwningWorld)==world;}
Reply Result(const Request& request,Status status,const char* why){
    Reply r;r.op=request.op;r.id=request.id;r.native=request.native;r.peer=request.peer;r.world=request.world;r.status=status;
    std::snprintf(r.reason,sizeof(r.reason),"%s",why);return r;
}
void Publish(const Reply& reply){AcquireSRWLockExclusive(&g_lock);if(g_replies.size()<32)g_replies.push_back(reply);ReleaseSRWLockExclusive(&g_lock);SC_LOG("[npc-debug] result id=%llu op=%u status=%u target=%u/%u feet=(%.2f,%.2f,%.2f) hp=%.3f reason=%s",reply.id,static_cast<unsigned>(reply.op),static_cast<unsigned>(reply.status),reply.target,reply.spawn,reply.feet[0],reply.feet[1],reply.feet[2],reply.health,reply.reason);}
void Complete(const Request& request,Reply reply){g_cache.push_back({request,reply});if(g_cache.size()>32)g_cache.pop_front();Publish(reply);}
bool Body(std::uintptr_t actor,motion::BodySample* out){
    if(!ue::IsLive(actor)||!Allowed(actor)||Inactive(Remember(actor)))return false;
    out->root=mem::ReadPtr(actor+off::ACharacter::CapsuleComponent);out->health=mem::ReadPtr(actor+off::AFightingCharacter::m_HealthComponent);
    if(!ue::IsLive(out->root)||ue::AttachParentOf(out->root)||!ue::ReadWorldLocation(out->root,&out->position))return false;
    out->radius=mem::ReadOr<float>(out->root+off::UCapsuleComponent::CapsuleRadius,NAN);out->half=mem::ReadOr<float>(out->root+off::UCapsuleComponent::CapsuleHalfHeight,NAN);
    probe::HealthSample hp;if(!probe::ReadHealthSample(out->health,&hp)||hp.health<=0||hp.down)return false;out->hp=hp.health;
    return std::isfinite(out->half)&&out->half>=out->radius&&out->half<=200&&out->radius>=10&&out->radius<=100;
}
// Feet stay exactly where MC asked: MC checked the floor, which may be a placed block this scene grid never sees.
const char* Destination(const world_motion::Context& c,const ue::FVector& feet,float radius,float half,std::uintptr_t except){
    ue::FVector player{};
    if(!ue::ReadWorldLocation(mem::ReadPtr(c.actors.pawn+off::AActor::RootComponent),&player))return "player-location";
    const float dx=feet.x-player.x,dy=feet.y-player.y,dz=feet.z-player.z;
    if(dx*dx+dy*dy+dz*dz>3000*3000)return "destination-too-far";
    for(int z=collision::CellIndex(feet.z+collision::kCellCm);z<=collision::CellIndex(feet.z+half*2-1);z++)
        for(int y=collision::CellIndex(feet.y-radius);y<=collision::CellIndex(feet.y+radius);y++)
            for(int x=collision::CellIndex(feet.x-radius);x<=collision::CellIndex(feet.x+radius);x++)if(c.actors.grid->Get(x,y,z))return "destination-blocked";
    auto overlaps=[&](std::uintptr_t actor){if(actor==except)return false;const auto root=mem::ReadPtr(actor+off::AActor::RootComponent);ue::FVector at{};if(!ue::ReadWorldLocation(root,&at))return true;
        const auto rr=mem::ReadOr<float>(root+off::UCapsuleComponent::CapsuleRadius,100),hh=mem::ReadOr<float>(root+off::UCapsuleComponent::CapsuleHalfHeight,100);
        return std::hypot(at.x-feet.x,at.y-feet.y)<rr+radius+2&&std::fabs(at.z-(feet.z+half))<hh+half;};
    if(overlaps(c.actors.pawn))return "destination-occupied-by-player";
    for(const auto& target:c.actors.targets)if(overlaps(target.actor))return "destination-occupied-by-npc";
    return nullptr;
}
Reply Position(const Request& r,std::uintptr_t actor,std::uint32_t id,std::uint32_t spawn,const char* why){
    auto reply=Result(r,Status::Applied,why);motion::BodySample body;
    if(!Body(actor,&body))return Result(r,Status::Failed,"body-not-ready");
    reply.target=id;reply.spawn=spawn;reply.feet[0]=body.position.x;reply.feet[1]=body.position.y;reply.feet[2]=body.position.z-body.half;reply.health=body.hp;return reply;
}
void Stop(Owned& owned,std::uint64_t now){
    if(owned.stopping)return;
    owned.stopping=true;owned.stop_at=now;
    if(owned.spawner.live())InvokeEmpty(g_calls.unspawn,owned.spawner.ptr);
}
void Cleanup(const world_motion::Context& c,std::uint64_t now,bool stop_all){
    for(auto it=g_owned.begin();it!=g_owned.end();){
        if(it->scope.world!=c.actors.scope.world||it->scope.generation!=c.actors.scope.generation||!SameWorld(it->spawner,c.actors.scope.world)){it=g_owned.erase(it);continue;}
        if(stop_all)Stop(*it,now);
        if(it->stopping){
            const auto actor=Spawned(it->spawner);
            if(actor&&!it->npc.ptr)it->npc=Remember(actor);
            if(!actor||Inactive(Remember(actor))) {InvokeEmpty(g_calls.destroy,it->spawner.ptr);it=g_owned.erase(it);continue;}
        }
        ++it;
    }
}
std::optional<Request> g_clear;
void StartSpawn(const world_motion::Context& c,const Request& r,std::uint64_t now){
    if(g_owned.size()>=8||now-g_last_spawn<1000){Complete(r,Result(r,Status::Refused,"spawn-limit-or-rate"));return;}
    const auto cls=ue::FindClass(kClasses[r.kind-1]);if(!ue::IsRegistered(cls)){Complete(r,Result(r,Status::Refused,"class-not-loaded"));return;}
    const ue::FVector feet{r.feet[0],r.feet[1],r.feet[2]};
    if(const auto why=Destination(c,feet,35,90,0)){Complete(r,Result(r,Status::Refused,why));return;}
    alignas(16) float transform[12]{};transform[3]=1;transform[4]=feet.x;transform[5]=feet.y;transform[6]=feet.z+90;transform[8]=transform[9]=transform[10]=1;
    Params begin;begin.put(g_calls.begin.ParamOffset("WorldContextObject"),c.actors.scope.world);begin.put(g_calls.begin.ParamOffset("ActorClass"),g_calls.spawner_class);begin.put(g_calls.begin.ParamOffset("SpawnTransform"),transform);
    g_calls.begin.Invoke(g_calls.statics,begin.bytes.data());auto spawner=Remember(begin.get<std::uintptr_t>(g_calls.begin.ParamOffset("ReturnValue")));
    if(!SameWorld(spawner,c.actors.scope.world)||ue::ClassOf(spawner.ptr)!=g_calls.spawner_class){Complete(r,Result(r,Status::Failed,"spawner-create"));return;}
    Params set;set.put(g_calls.class_arg,cls);g_calls.set_class.Invoke(spawner.ptr,set.bytes.data());
    Params finish;finish.put(g_calls.finish.ParamOffset("Actor"),spawner.ptr);finish.put(g_calls.finish.ParamOffset("SpawnTransform"),transform);g_calls.finish.Invoke(g_calls.statics,finish.bytes.data());
    if(!spawner.live()){Complete(r,Result(r,Status::Failed,"spawner-finish"));return;}
    g_owned.push_back({spawner,{},c.actors.scope,false,0});
    InvokeEmpty(g_calls.wants,spawner.ptr);g_last_spawn=now;g_job=Job{r,c.actors.scope,spawner,now};
}
}
void Configure(bool enabled){g_startup=enabled;SC_LOG("[npc-debug] startup enabled=%d; explicit scoped commands only",enabled);}
void Exchange(const world_motion::Context& context){
    if(!g_startup)return;
    const auto now=GetTickCount64();
    AcquireSRWLockExclusive(&g_lock);g_published=context;ReleaseSRWLockExclusive(&g_lock);
    if(now<g_next_io)return;
    g_next_io=now+50;
    const auto root=log::RootDirectory();const bool opted=GetFileAttributesW((root+L"\\npc-debug").c_str())!=INVALID_FILE_ATTRIBUTES;
    AcquireSRWLockExclusive(&g_lock);g_opted=opted;std::deque<Reply> replies;replies.swap(g_replies);ReleaseSRWLockExclusive(&g_lock);
    for(const auto& reply:replies){
        const auto temp=root+L"\\npc-debug.reply.tmp",path=root+L"\\npc-debug.reply";
        if(FILE* file=_wfopen(temp.c_str(),L"wb")){const bool ok=std::fwrite(&reply,1,sizeof(reply),file)==sizeof(reply);std::fclose(file);if(ok)MoveFileExW(temp.c_str(),path.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);}
    }
    if(!opted)return;
    Request request;bool read=false;
    if(FILE* file=_wfopen((root+L"\\npc-debug.request").c_str(),L"rb")){read=std::fread(&request,1,sizeof(request),file)==sizeof(request)&&std::fgetc(file)==EOF;std::fclose(file);}
    if(!read||!Valid(request)||(g_last_file&&Same(*g_last_file,request)))return;
    AcquireSRWLockExclusive(&g_lock);if(!g_request){g_request=request;g_last_file=request;}ReleaseSRWLockExclusive(&g_lock);
}
void ApplyOnGameThread(std::uintptr_t base){
    if(!g_startup)return;
    const auto now=GetTickCount64();
    if(!g_thread)g_thread=GetCurrentThreadId();
    if(g_thread!=GetCurrentThreadId())return;
    world_motion::Context c;std::optional<Request> request;bool opted;
    AcquireSRWLockExclusive(&g_lock);c=g_published;request.swap(g_request);opted=g_opted;ReleaseSRWLockExclusive(&g_lock);
    if(g_scope!=c.actors.scope){
        if(g_job){Publish(Result(g_job->request,Status::Failed,"scope-changed"));g_job.reset();}
        if(g_clear)Publish(Result(*g_clear,Status::Failed,"scope-changed"));
        g_clear.reset();g_cache.clear();g_high=0;g_scope=c.actors.scope;
    }
    const auto why=motion::CheckWorld(base,c.actors,now);
    if(!why&&Resolve())Cleanup(c,now,!opted);
    if(g_job&&!opted){Complete(g_job->request,Result(g_job->request,Status::Failed,"disabled"));g_job.reset();}
    if(g_job&&!why&&opted){
        const auto job=*g_job;auto owned=std::find_if(g_owned.begin(),g_owned.end(),[&](const auto& o){return o.spawner.ptr==job.spawner.ptr&&o.spawner.id==job.spawner.id;});
        const auto actor=Spawned(job.spawner);if(owned!=g_owned.end()&&actor)owned->npc=Remember(actor);
        auto target=std::find_if(c.actors.targets.begin(),c.actors.targets.end(),[&](const auto& t){return t.actor==actor&&actor;});
        motion::BodySample body;
        if(target!=c.actors.targets.end()&&Body(actor,&body)){
            const auto reply=Position(job.request,actor,target->id,target->spawn,"spawned");
            const float dx=reply.feet[0]-job.request.feet[0],dy=reply.feet[1]-job.request.feet[1],dz=reply.feet[2]-job.request.feet[2];
            if(dx*dx+dy*dy+dz*dz<=100*100){Complete(job.request,reply);g_job.reset();}
            else {if(owned!=g_owned.end())Stop(*owned,now);Complete(job.request,Result(job.request,Status::Failed,"spawn-position-mismatch"));g_job.reset();}
        }else if(now-job.began>8000){if(owned!=g_owned.end())Stop(*owned,now);Complete(job.request,Result(job.request,Status::Failed,"spawn-initialization-timeout"));g_job.reset();}
    }
    if(g_clear&&!why){
        Cleanup(c,now,true);if(g_owned.empty()){Complete(*g_clear,Result(*g_clear,Status::Applied,"cleared"));g_clear.reset();}
        else if(now-g_clear->issued_ms>8000){Complete(*g_clear,Result(*g_clear,Status::Failed,"cleanup-pending"));g_clear.reset();}
    }
    if(!request)return;
    const auto r=*request;
    if(!opted){Publish(Result(r,Status::Refused,"disabled"));return;}
    if(!c.enabled||r.native!=c.actors.scope.native_session||r.peer!=c.actors.scope.peer_session||r.world!=c.actors.scope.generation||r.map!=c.map||r.save!=c.save){Publish(Result(r,Status::Refused,"scope"));return;}
    for(const auto& cached:g_cache)if(cached.request.id==r.id){Publish(Same(cached.request,r)?cached.reply:Result(r,Status::Refused,"id-conflict"));return;}
    if(g_job&&g_job->request.id==r.id){if(!Same(g_job->request,r))Publish(Result(r,Status::Refused,"id-conflict"));return;}
    if(g_clear&&g_clear->id==r.id)return;
    if(!Valid(r)||!Fresh(r,now)||r.id<=g_high){Publish(Result(r,Status::Refused,"expired-or-retired-id"));return;}
    g_high=r.id;
    if(why){Complete(r,Result(r,Status::Refused,why));return;}
    if(!Resolve()){Complete(r,Result(r,Status::Refused,"reflection-unavailable"));return;}
    if(g_job||g_clear){Complete(r,Result(r,Status::Refused,"busy"));return;}
    if(r.op==Op::Clear){g_clear=r;Cleanup(c,now,true);return;}
    if(r.op==Op::Spawn){StartSpawn(c,r,now);return;}
    const auto it=std::find_if(c.actors.targets.begin(),c.actors.targets.end(),[&](const auto& t){return t.id==r.target&&t.spawn==r.spawn;});
    if(it==c.actors.targets.end()){Complete(r,Result(r,Status::Refused,"target-not-current"));return;}
    auto retained=world_motion::RetainedTargets(r.world,now);const auto key=(std::uint64_t(r.target)<<32)|r.spawn;
    if(std::find(retained.retained.begin(),retained.retained.end(),key)!=retained.retained.end()){Complete(r,Result(r,Status::Refused,"movement-authority-held"));return;}
    const auto movement=mem::ReadPtr(it->actor+off::ACharacter::CharacterMovement);const auto movement_id=ue::IdentityOf(movement);
    motion::Target target{it->actor,movement,it->id,it->spawn,it->identity.serial,movement_id.index,movement_id.serial};motion::BodySample before;
    if(const auto refused=motion::CheckBody(c.actors,target,&before,false)){auto reply=Result(r,Status::Refused,refused);reply.target=it->id;reply.spawn=it->spawn;Complete(r,reply);return;}
    const ue::FVector feet{r.feet[0],r.feet[1],r.feet[2]};
    if(const auto why=Destination(c,feet,before.radius,before.half,it->actor)){auto reply=Result(r,Status::Refused,why);reply.target=it->id;reply.spawn=it->spawn;Complete(r,reply);return;}
    ue::FRotator rotation;ue::FVector old;if(!ue::ReadWorldTransform(before.root,&old,&rotation)){Complete(r,Result(r,Status::Refused,"root-transform"));return;}
    Params params;params.put(g_calls.destination,ue::FVector{feet.x,feet.y,feet.z+before.half});params.put(g_calls.rotation,rotation);
    g_calls.teleport.Invoke(target.actor,params.bytes.data());
    auto reply=Position(r,target.actor,target.id,target.spawn,params.get<std::uint8_t>(g_calls.teleport_result)?"moved":"engine-refused");
    if(!params.get<std::uint8_t>(g_calls.teleport_result))reply.status=Status::Refused;
    if(reply.status==Status::Applied&&reply.health!=before.hp){reply.status=Status::Failed;std::snprintf(reply.reason,sizeof(reply.reason),"health-changed");}
    Complete(r,reply);
}
}
