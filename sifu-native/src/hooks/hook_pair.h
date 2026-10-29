#pragma once
namespace sifucraft::hooks {
enum class PairResult { Ready, Refused, RolledBack, RollbackFailed };
// Both preflights precede either write. A partial install must never enable ownership.
template<class First,class Second,class Undo>
PairResult InstallPair(bool both_ready,First first,Second second,Undo undo_first) {
    if(!both_ready || !first()) return PairResult::Refused;
    if(second()) return PairResult::Ready;
    return undo_first()?PairResult::RolledBack:PairResult::RollbackFailed;
}
}
