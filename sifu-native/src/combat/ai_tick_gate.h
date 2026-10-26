#pragma once
#include "combat/motion_lease.h"
namespace sifucraft::combat::ai_gate {
// H1 only; installs the two approved slots as a pair. No actor/AI members are written.
bool Install(std::uintptr_t base,const motion::Peers& peers);
bool Matches(std::uintptr_t base,const motion::Peers& peers);
bool Installed();
bool Uninstall();
}
