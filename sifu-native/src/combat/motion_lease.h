#pragma once
#include <array>
#include <cmath>
#include <cstdint>

// Pure state: world motion uses entity activation, never the player's anchor epoch.
namespace sifucraft::combat::motion {
struct Scope {
    std::uint64_t native_session = 0, peer_session = 0, world = 0;
    std::uint32_t generation = 0;
    std::int32_t world_index = -1, world_serial = 0;
    bool valid() const { return native_session && peer_session && world && generation && world_index >= 0 && world_serial > 0; }
    bool operator==(const Scope&) const = default;
};
struct Target {
    std::uint64_t actor = 0, movement = 0;
    std::uint32_t id = 0, spawn = 0;
    std::int32_t serial = 0, movement_index = -1, movement_serial = 0;
    bool valid() const { return actor && movement && id && spawn && serial > 0 && movement_index >= 0 && movement_serial > 0; }
    bool operator==(const Target&) const = default;
};
struct Step {
    float x = 0, y = 0;
    bool valid() const {
        return std::isfinite(x) && std::isfinite(y) && ((x == 0) != (y == 0)) && std::fabs(x) + std::fabs(y) <= 10;
    }
    bool operator==(const Step&) const = default;
};
enum class Part : unsigned { Movement=1, AI=2, Behavior=4 };
struct Component {
    std::uint64_t pointer=0;
    std::int32_t index=-1,serial=0;
    bool valid() const { return pointer && index>=0 && serial>0; }
    bool operator==(const Component&) const = default;
};
struct Peers {
    Component ai,behavior;
    bool valid() const { return ai.valid() && behavior.valid() && ai.pointer!=behavior.pointer; }
    bool operator==(const Peers&) const = default;
};
enum class Kind { Sweep, AIHold };
struct Request {
    Scope scope;
    Target target;
    std::uint64_t id = 0, created_ms = 0;
    std::uint32_t duration_ms = 0;
    Step step;
    Kind kind=Kind::Sweep;
    Peers peers;
    bool operator==(const Request&) const = default;
};
enum class State { NativeFree, AcquirePending, Machine, ReleasePending };
enum class Outcome { Ready, Applied, Partial, Blocked, Penetrating, Invalid, Stale, Busy, Conflict, Budget, Cancelled };
struct Result {
    std::uint64_t id = 0;
    Outcome outcome = Outcome::Invalid;
    float actual_x = 0, actual_y = 0, actual_z = 0;
    bool replay = false;
};
class Lease {
public:
    Result Begin(const Request& r, const Scope& current, std::uint64_t now) {
        if (r.scope != current || !current.valid()) return {r.id, Outcome::Stale};
        for (const auto& old : history_) if (old.request.id == r.id && r.id) {
            if (old.request != r) return {r.id, Outcome::Conflict};
            auto result = old.result; result.replay = true; return result;
        }
        if (!r.id || r.id <= highest_id_) return {r.id, Outcome::Stale};
        const bool operation = r.kind==Kind::Sweep ? r.step.valid() :
            r.kind==Kind::AIHold && r.step.x==0 && r.step.y==0 && r.peers.valid();
        if (!r.target.valid() || !operation || !r.duration_ms || r.duration_ms > 500 ||
            r.created_ms > now || now - r.created_ms > 1000) return Save(r, Outcome::Invalid);
        if (state_ != State::NativeFree) return Save(r, Outcome::Busy);
        request_ = r; deadline_ = now + r.duration_ms; restored_=0; state_ = State::AcquirePending;
        return Save(r, Outcome::Ready);
    }
    Result Complete(std::uint64_t id, Outcome outcome, float x = 0, float y = 0, float z = 0) {
        if (state_ != State::AcquirePending || request_.id != id) return {id, Outcome::Stale};
        Result result{id, outcome, x, y, z};
        for (auto& old : history_) if (old.request.id == id) old.result = result;
        state_ = outcome == Outcome::Applied ? State::Machine : State::ReleasePending;
        return result;
    }
    bool Check(std::uint64_t now, bool still_owned) {
        if ((state_ == State::AcquirePending || state_ == State::Machine) && (!still_owned || now >= deadline_)) {
            Release(); return false;
        }
        return state_ == State::Machine || state_ == State::AcquirePending;
    }
    void Release() {
        if (state_ == State::AcquirePending) Complete(request_.id, Outcome::Cancelled);
        if (state_ == State::Machine) state_ = State::ReleasePending;
    }
    void OriginalTick(const Target& target,Part part=Part::Movement,const Peers& peers={}) {
        if(state_!=State::ReleasePending || request_.target!=target) return;
        if(request_.kind==Kind::AIHold && request_.peers!=peers) return;
        restored_|=static_cast<unsigned>(part);
        if((restored_&required())==required()) state_=State::NativeFree;
    }
    // A destroyed target needs no original tick, but losing it must not forget applied ids.
    void Abandon() { Release(); state_ = State::NativeFree; }
    void Reset() { *this = {}; }
    State state() const { return state_; }
    const Request& request() const { return request_; }
    std::uint64_t deadline() const { return deadline_; }
    unsigned restored() const { return restored_; }
    unsigned required() const { return request_.kind==Kind::AIHold?7u:1u; }
private:
    struct Saved { Request request; Result result; };
    Result Save(const Request& r, Outcome outcome) {
        Result result{r.id, outcome};
        history_[next_++ % history_.size()] = {r, result}; highest_id_ = r.id; return result;
    }
    State state_ = State::NativeFree;
    Request request_;
    std::uint64_t deadline_ = 0, highest_id_ = 0;
    unsigned restored_=0;
    std::size_t next_ = 0;
    std::array<Saved, 64> history_{};
};
// Initial probe only: four explicitly addressed calls/process, one second apart.
struct ProbeBudget {
    unsigned calls = 0;
    std::uint64_t last_ms = 0;
    bool Spend(std::uint64_t now) {
        if (calls >= 4 || (calls && (now < last_ms || now - last_ms < 1000))) return false;
        ++calls; last_ms = now; return true;
    }
};
}  // namespace sifucraft::combat::motion
