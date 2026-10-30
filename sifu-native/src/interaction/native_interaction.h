#pragma once
#include <cstdint>
#include <vector>
#include <string>
#include "sifucraft_protocol.h"
#include "ue/objects.h"

namespace sifucraft::interaction {
struct Context {
    std::uintptr_t world = 0, pawn = 0, controller = 0;
    ue::ObjectIdentity pawn_id, controller_id;
    std::uint64_t session = 0, updated_ms = 0;
    std::uint32_t generation = 0, epoch = 0;
    protocol::Vec3 eye{};
    bool active = false;
    bool pose_owned = false;
};
struct Reply { std::uint64_t session; protocol::InteractResultPayload payload; };
void UpdateContext(const Context& context);
void Submit(const protocol::Event& event);
void BeforeTick(std::uintptr_t base, int stage);
void AfterTick(std::uintptr_t base, int stage);
protocol::InteractionSnapshot Snapshot();
void DrainReplies(std::vector<Reply>* replies);
std::string TakeStats();
// H1 only: keeps existing MC pose/camera for small corrections from its own door use.
bool PreserveDoorView(std::uintptr_t pawn, float correction_cm);
// Runtime thread: a door use is still animating, so a sequence-anchor attach is not a cutscene.
bool DoorAnimationActive();
}
