#include "collision/builder.h"

#include <windows.h>

#include <algorithm>
#include <climits>
#include <cmath>
#include <ctime>
#include <thread>
#include <utility>

#include "collision/export.h"
#include "collision/shape_file.h"
#include "collision/voxels.h"
#include "core/log.h"

namespace sifucraft::collision {

namespace {

constexpr std::size_t kGridFilesKept = 2;

std::wstring Widen(const std::string& text) { return std::wstring(text.begin(), text.end()); }

void LogExtent(const VoxelGrid& grid) {
    int lo[3] = {INT_MAX, INT_MAX, INT_MAX}, hi[3] = {INT_MIN, INT_MIN, INT_MIN};
    for (const auto& [key, block] : grid.blocks()) {
        int b[3];
        VoxelGrid::Unkey(key, &b[0], &b[1], &b[2]);
        for (int k = 0; k < 3; ++k) {
            lo[k] = std::min(lo[k], b[k]);
            hi[k] = std::max(hi[k], b[k]);
        }
    }
    if (lo[0] <= hi[0]) {
        SC_LOG("[collision] grid extent (blocks): x %d..%d  y %d..%d  z %d..%d", lo[0], hi[0], lo[1], hi[1], lo[2],
               hi[2]);
    }
}

std::shared_ptr<const VoxelGrid> Build(const BuildRequest& request) {
    const ShapeSet& s = request.shapes;
    auto built = std::make_shared<VoxelGrid>();
    VoxelGrid& grid = *built;
    VoxelStats stats;
    Voxelize(s, &grid, &stats);
    SC_LOG("[collision] %s: voxelized %d convex (%d degenerate) %d capsules %d triangles (%d slivers) in %.0f + %.0f + "
           "%.0f ms -> %llu cells in %zu blocks (%d oversized shapes left out) | %d steep faces made walls "
           "(walkable floor Z %.3f, %d components with a slope override)",
           request.map.c_str(), stats.convexes, stats.degenerate, stats.capsules, stats.triangles, stats.slivers,
           stats.ms_convex, stats.ms_capsule, stats.ms_triangle, static_cast<unsigned long long>(stats.cells),
           stats.blocks, stats.oversized, stats.steep_faces, s.pawn_walkable_z, s.slope_overrides);
    LogExtent(grid);

    const float floor = FloorUnder(grid, request.pawn_feet.x, request.pawn_feet.y, request.pawn_feet.z);
    if (std::isnan(floor)) {
        SC_LOG("[collision] floor check: NO solid cell under the pawn's feet (%.0f, %.0f, %.1f)", request.pawn_feet.x,
               request.pawn_feet.y, request.pawn_feet.z);
    } else {
        SC_LOG("[collision] floor check: voxel floor %.1f cm vs pawn feet %.1f cm -> error %+.1f cm", floor,
               request.pawn_feet.z, floor - request.pawn_feet.z);
    }

    // The source shapes, for replaying the voxelization offline and for comparing against the
    // voxels in a 3D viewer.
    const std::wstring shapes = log::RootDirectory() + L"\\collision";
    CreateDirectoryW(shapes.c_str(), nullptr);
    if (!WriteShapeFile(shapes + L"\\" + Widen(request.map) + L".shapes", s, request.map)) {
        SC_LOG("[collision] could not write collision\\%s.shapes", request.map.c_str());
    }
    const std::wstring debug = log::RootDirectory() + L"\\debug";
    CreateDirectoryW(debug.c_str(), nullptr);
    if (!WriteShapesObj(debug + L"\\collision_" + Widen(request.map) + L"_shapes.obj", s)) {
        SC_LOG("[collision] could not write debug\\collision_%s_shapes.obj", request.map.c_str());
    }
    return built;
}

}  // namespace

// Seeded from the clock so that a restarted Sifu never reuses a generation Minecraft may still
// report as loaded from the previous session.
Builder::Builder() : next_generation_(static_cast<std::uint32_t>(std::time(nullptr)) * 16u | 1u) {}

void Builder::Submit(BuildRequest request) {
    std::lock_guard lock(mutex_);
    pending_ = std::move(request);
    if (running_) return;
    running_ = true;
    std::thread([this] { Run(); }).detach();
}

std::optional<PublishedGrid> Builder::Publish(const BuildRequest& request, const VoxelGrid& grid) {
    PublishedGrid out;
    out.generation = next_generation_++;
    if (out.generation == 0) out.generation = next_generation_++;
    out.world_generation = request.generation;
    out.block_count = static_cast<std::uint32_t>(grid.blocks().size());
    out.cell_count = grid.CellCount();
    out.map = request.map;

    const std::wstring directory = log::RootDirectory() + L"\\collision";
    CreateDirectoryW(directory.c_str(), nullptr);
    out.path = directory + L"\\" + Widen(request.map) + L"-" + std::to_wstring(out.generation) + L".scvx";
    // Written aside and renamed into place, so Minecraft never sees a partial file.
    const std::wstring partial = out.path + L".partial";
    if (!WriteVoxelFile(partial, grid, request.map, request.pawn_feet) ||
        !MoveFileExW(partial.c_str(), out.path.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        SC_LOG("[collision] could not write the grid file for %s (error %lu)", request.map.c_str(), GetLastError());
        DeleteFileW(partial.c_str());
        return std::nullopt;
    }
    written_.push_back(out.path);
    while (written_.size() > kGridFilesKept) {
        DeleteFileW(written_.front().c_str());  // Minecraft may still be reading it: removal waits
        written_.erase(written_.begin());
    }
    SC_LOG("[collision] published grid %u: %s, %u blocks, %llu cells -> collision\\%ls", out.generation,
           out.map.c_str(), out.block_count, static_cast<unsigned long long>(out.cell_count),
           out.path.substr(directory.size() + 1).c_str());
    return out;
}

void Builder::Run() {
    for (;;) {
        BuildRequest request;
        {
            std::lock_guard lock(mutex_);
            if (!pending_) {
                running_ = false;
                return;
            }
            request = std::move(*pending_);
            pending_.reset();
        }
        std::shared_ptr<const VoxelGrid> grid = Build(request);
        std::optional<PublishedGrid> published = Publish(request, *grid);
        std::lock_guard lock(mutex_);
        latest_ = std::move(grid);
        latest_generation_ = request.generation;
        if (published) published_ = std::move(*published);
    }
}

std::shared_ptr<const VoxelGrid> Builder::Latest(std::uint32_t generation) const {
    std::lock_guard lock(mutex_);
    return latest_generation_ == generation ? latest_ : nullptr;
}

PublishedGrid Builder::Published() const {
    std::lock_guard lock(mutex_);
    return published_;
}

}  // namespace sifucraft::collision
