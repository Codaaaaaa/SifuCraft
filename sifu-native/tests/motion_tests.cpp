#include <cstdio>
#include <limits>
#include "combat/motion_lease.h"
#include "combat/sweep_params.h"
using namespace sifucraft::combat::motion;
namespace {
int failures = 0;
void Check(bool condition, const char* message) {
    std::printf("%s %s\n", condition ? "PASS" : "FAIL", message); failures += !condition;
}
Request Sample(std::uint64_t id = 1) {
    return {{11,22,0x1000,3,7,9}, {0x2000,0x3000,42,2,8,43,10}, id, 1000, 100, {10,0}};
}
}
int main() {
    auto r = Sample(); Lease l;
    Check(l.Begin(r,r.scope,1000).outcome == Outcome::Ready && l.state() == State::AcquirePending, "reserve one addressed identity");
    Check(l.Begin(r,r.scope,1001).replay, "reservation replay cannot invoke twice");
    Check(l.Complete(1,Outcome::Applied,7,0,0).actual_x == 7 && l.state() == State::Machine, "store measured delta and bounded ownership");
    Check(l.Begin(r,r.scope,1050).replay && l.Begin(r,r.scope,1050).actual_x == 7, "duplicate returns measured result");
    auto forged = r; forged.step = {-10,0};
    Check(l.Begin(forged,r.scope,1050).outcome == Outcome::Conflict, "same id cannot change payload");
    Check(l.Check(1099,true) && !l.Check(1100,true) && l.state() == State::ReleasePending, "exact deadline releases without fresh input");
    auto next = Sample(2); next.created_ms = 1100;
    Check(l.Begin(next,r.scope,1100).outcome == Outcome::Busy, "no reacquire before original tick");
    auto reused = r.target; ++reused.serial; l.OriginalTick(reused);
    Check(l.state() == State::ReleasePending, "reused actor cannot acknowledge old original tick");
    l.OriginalTick(r.target);
    Check(l.state() == State::NativeFree && l.Begin(next,r.scope,1101).outcome == Outcome::Busy, "rejected id stays rejected after release");
    next.id = 3;
    Check(l.Begin(next,r.scope,1101).outcome == Outcome::Ready && !l.Check(1102,false), "pause or command cancels pending application");
    Check(l.Begin(next,r.scope,1103).outcome == Outcome::Cancelled, "cancelled request never applies after resume");
    l.Reset(); auto changed = r.scope; ++changed.generation;
    Check(l.Begin(r,changed,1000).outcome == Outcome::Stale, "world mismatch rejects before mutation");
    for (int which=0; which<6; ++which) {
        Lease bad; auto request=r;
        if(which==0) request.step={11,0};
        if(which==1) request.step={1,1};
        if(which==2) request.step={0,0};
        if(which==3) request.step={std::numeric_limits<float>::quiet_NaN(),0};
        if(which==4) request.duration_ms=501;
        if(which==5) request.target.spawn=0;
        Check(bad.Begin(request,r.scope,1000).outcome==Outcome::Invalid, "reject unsafe step, duration or identity");
    }
    Check(l.Begin(r,r.scope,2001).outcome==Outcome::Invalid, "expired source cannot reserve");
    l.Reset(); Check(l.Begin(r,r.scope,999).outcome==Outcome::Invalid, "future timestamp rejected");
    l.Reset(); bool fresh=true;
    for(std::uint64_t id=1;id<=80;++id) {
        auto request=Sample(id); fresh &= l.Begin(request,r.scope,1000).outcome==Outcome::Ready;
        l.Complete(id,Outcome::Partial,3,0,0); l.OriginalTick(request.target);
    }
    Check(fresh && l.Begin(r,r.scope,1000).outcome==Outcome::Stale,"evicted ids never apply again");
    l.Reset(); l.Begin(r,r.scope,1000); l.Complete(1,Outcome::Applied); l.Abandon();
    Check(l.state()==State::NativeFree && l.Begin(r,r.scope,1001).replay,"identity loss clears ownership but preserves deduplication");
    ProbeBudget budget;
    Check(budget.Spend(1000) && !budget.Spend(1999) && budget.Spend(2000) && budget.Spend(3000) && budget.Spend(4000) && !budget.Spend(9000), "probe cooldown and lifetime four-call cap");
    SweepParams layout{172,{0,12,24,28,168,169}};
    std::array<std::uint8_t,172> bytes; bytes.fill(255);
    const sifucraft::ue::FVector location{13,-24,90};
    const sifucraft::ue::FRotator rotation{4,-127,2};
    Check(layout.valid() && layout.Encode(bytes,location,rotation) && bytes[24]==1 && bytes[168]==0 && bytes[169]==0,
          "sweep encoding enables sweep and zeroes teleport, hit and return storage");
    Check(std::memcmp(bytes.data(),&location,12)==0 && std::memcmp(bytes.data()+12,&rotation,12)==0,
          "sweep preserves all rotation axes and exact requested location");
    auto broken=layout; broken.offset[3]=24;
    Check(!broken.valid() && !broken.Encode(bytes,location,rotation),"overlapping reflected parameters cannot invoke");
    broken=layout; broken.size=169;
    Check(!broken.valid(),"return value must fit the full reflected allocation");
    Check(!layout.Encode(std::span<std::uint8_t>(bytes).first(170),location,rotation),"short parameter buffer rejected");
    return failures ? 1 : 0;
}
