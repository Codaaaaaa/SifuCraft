#pragma once
#include "sifucraft_protocol.h"

namespace sifucraft::input {
// A temporary script/menu yield is not a manual F8 disable. While blocked, F8 requests
// Minecraft for the next safe handover, including the frame before the router has yielded.
class OwnershipIntent {
public:
    void RequestSwitch(protocol::InputOwner current, bool temporarily_blocked) {
        enabled_ = temporarily_blocked || current != protocol::InputOwner::Minecraft;
    }
    bool enabled() const { return enabled_; }
    protocol::InputOwner Desired(bool temporarily_blocked) const {
        return enabled_ && !temporarily_blocked ? protocol::InputOwner::Minecraft : protocol::InputOwner::Sifu;
    }
private:
    bool enabled_ = true;
};
}
