#pragma once
#include <cstdint>
#include <array>
#include <string>
#include "render/depth_copy.h"
#include "render/overlay_status.h"

namespace sifucraft::render::depth_observer {
struct HeldDepth {
    Com<ID3D11ShaderResourceView> view;
    DepthIdentity identity;
    Scope scope;
    std::uint64_t observed_ms = 0;
    DWORD thread_id = 0;
    DepthBinding::Stage stage = DepthBinding::None;
    UINT slot = 0;
};
// Discovery retains at most 14 buffers once; production retains only the verified
// stage/slot's latest buffer. GPU work and inspection remain on H8.
struct BoundParameters {
    std::array<Com<ID3D11Buffer>, D3D11_COMMONSHADER_CONSTANT_BUFFER_API_SLOT_COUNT> buffers;
    Scope scope;
    DepthIdentity identity;
    D3D11_VIEWPORT viewport{};
    std::uint64_t observed_ms = 0;
    DWORD thread_id = 0;
    DepthBinding::Stage stage = DepthBinding::None;
    bool full_snapshot = false;
};
// One observer per DLL lifetime. Install/Disable/SetTarget/Take run on existing H8.
bool Install(ID3D11DeviceContext* context);
// D3D11 context-state swaps can rewrite the COM slots. Re-arm the same approved
// methods after Present, retaining immutable forwarders for old callers.
bool Rearm();
bool Disable();
bool Installed();
void SetTarget(const Scope& scope, const DepthIdentity& identity);
HeldDepth Take(ID3D11DeviceContext* context, const Scope& scope, const DepthIdentity& identity, std::uint64_t now_ms);
void RequestParameters(bool requested = true);
void StreamParameters(DepthBinding::Stage stage = DepthBinding::None, UINT slot = 0);
BoundParameters TakeParameters(ID3D11DeviceContext* context, const Scope& scope, const DepthIdentity& identity, std::uint64_t now_ms);
std::string TakeStats();
// Atomic gate only; no COM/locks under process teardown.
void OnProcessExit();
}
