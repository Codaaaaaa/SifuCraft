#include "combat/ai_tick_gate.h"
#include <atomic>
#include "combat/motion_probe.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "hooks/hook_pair.h"
#include "hooks/vtable_hook.h"

namespace sifucraft::combat::ai_gate {
namespace {
using Tick=void(*)(void*,float,int,void*);
hooks::VtableHook g_ai,g_tree;
// Retained across uninstall/reinstall, including any in-flight forwarding callback.
std::atomic<Tick> g_ai_original{nullptr},g_tree_original{nullptr};
void AiTick(void* component,float dt,int type,void* tick) {
    const auto address=reinterpret_cast<std::uintptr_t>(component);
    if(motion::SkipDecision(address,motion::Part::AI))return;
    g_ai_original.load(std::memory_order_acquire)(component,dt,type,tick);
    motion::AfterOriginalDecision(address,motion::Part::AI);
}
void TreeTick(void* component,float dt,int type,void* tick) {
    const auto address=reinterpret_cast<std::uintptr_t>(component);
    if(motion::SkipDecision(address,motion::Part::Behavior))return;
    g_tree_original.load(std::memory_order_acquire)(component,dt,type,tick);
    motion::AfterOriginalDecision(address,motion::Part::Behavior);
}
bool Match(std::uintptr_t base,std::uint64_t component,const gen::hook::Target& target,const hooks::VtableHook& hook,Tick replacement) {
    const auto table=mem::ReadPtr(component);
    return table==base+target.vftable && mem::ReadPtr(table+target.slot)==
        (hook.installed()?reinterpret_cast<std::uintptr_t>(replacement):base+target.original);
}
}
bool Installed(){return g_ai.installed()&&g_tree.installed();}
bool Matches(std::uintptr_t base,const motion::Peers& peers) {
    return peers.valid() && Match(base,peers.ai.pointer,gen::hook::AIFightingTick,g_ai,&AiTick) &&
        Match(base,peers.behavior.pointer,gen::hook::BehaviorTreeTick,g_tree,&TreeTick);
}
bool Install(std::uintptr_t base,const motion::Peers& peers) {
    if(Installed())return Matches(base,peers);
    if(g_ai.installed()||g_tree.installed())return false;
    const auto a=hooks::Resolve(base,gen::hook::AIFightingTick,peers.ai.pointer);
    const auto b=hooks::Resolve(base,gen::hook::BehaviorTreeTick,peers.behavior.pointer);
    if(!peers.valid()||!a.ready()||!b.ready())return false;
    g_ai_original.store(reinterpret_cast<Tick>(a.slot_value),std::memory_order_release);
    g_tree_original.store(reinterpret_cast<Tick>(b.slot_value),std::memory_order_release);
    const auto result=hooks::InstallPair(true,
        [&]{return g_ai.Install(base,gen::hook::AIFightingTick,peers.ai.pointer,reinterpret_cast<void*>(&AiTick));},
        [&]{return g_tree.Install(base,gen::hook::BehaviorTreeTick,peers.behavior.pointer,reinterpret_cast<void*>(&TreeTick));},
        [&]{return g_ai.Uninstall();});
    SC_LOG("[npc-ai] hook pair result=%d (only forwarding until explicit hold)",static_cast<int>(result));
    return result==hooks::PairResult::Ready;
}
bool Uninstall() {
    const bool tree=g_tree.Uninstall();
    const bool ai=g_ai.Uninstall();
    return tree&&ai;
}
}
