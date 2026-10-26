#pragma once
#include <cstdint>
#include <string>
#include <dxgi.h>
#include <d3d11.h>
#include "render/overlay_status.h"
namespace sifucraft::render::depth_probe {
void Configure(std::uintptr_t image_base, bool enabled, bool capture, bool observe_bindings, bool world_render, const Scope& scope);
void UpdateScope(const Scope& scope);
void RequestWorldCapture();
void Stop();
void Tick(IDXGISwapChain* chain); // existing H8 immediate-context thread only
void DrawWorld(ID3D11Device* device, ID3D11Texture2D* backbuffer, const Scope& scope, std::uint64_t now);
std::string TakeStats();
}
