#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <utility>
#include <vector>

#include "collision/shapes.h"
#include "collision/voxels.h"
#include "collision/coverage.h"
#include "collision/region_cache.h"

// Turns extracted shapes into the voxel grid off the runtime thread and publishes each grid as a
// file Minecraft loads (SifuCraft\collision\<map>-<generation>.scvx; the last two are kept). One
// build at a time; a newer request replaces a pending one.
namespace sifucraft::collision {

struct BuildRequest {
    ShapeSet shapes;
    std::string map;       // e.g. "Wuguan_Main"
    FVector pawn_feet{};   // for the floor check
    std::uint32_t generation = 0;  // the runtime's world generation the shapes came from
    std::string file_tag;
    bool diagnostics = true;
    std::optional<Coverage> coverage;
    std::optional<CellRegion> clip;
    bool cache_regions=false;
    std::uint64_t coverage_epoch=0;
};

// The latest grid written for Minecraft.
struct PublishedGrid {
    std::uint64_t coverage_epoch=0;
    std::uint32_t generation = 0;        // unique per grid, also across Sifu sessions; 0 = none yet
    std::uint32_t world_generation = 0;
    std::uint32_t block_count = 0;
    std::uint64_t cell_count = 0;
    std::string map;
    std::wstring path;
};

// Worker-owned cache of the last successfully published content. Compare every voxel,
// so transform jitter cannot churn files/MC caches and real door changes are never lost.
struct PublishedContent {
    std::shared_ptr<const VoxelGrid> grid;
    std::uint32_t world = 0;
    std::string map, tag;
    std::optional<Coverage> coverage;
    std::uint64_t coverage_epoch=0;
    bool Matches(const BuildRequest& request, const VoxelGrid& candidate) const {
        return grid && world == request.generation && map == request.map && tag == request.file_tag && coverage==request.coverage && coverage_epoch==request.coverage_epoch && *grid == candidate;
    }
    void Confirm(const BuildRequest& request, std::shared_ptr<const VoxelGrid> accepted) {
        grid = std::move(accepted); world = request.generation; map = request.map; tag = request.file_tag;coverage=request.coverage;coverage_epoch=request.coverage_epoch;
    }
};

class Builder {
public:
    Builder();

    void Submit(BuildRequest request);
    // The grid of the latest finished build of world `generation`, or null.
    std::shared_ptr<const VoxelGrid> Latest(std::uint32_t generation) const;
    PublishedGrid Published() const;

private:
    void Run();
    // Writes the grid file (worker thread, outside the lock).
    std::optional<PublishedGrid> Publish(const BuildRequest& request, const VoxelGrid& grid);

    mutable std::mutex mutex_;
    std::optional<BuildRequest> pending_;
    bool running_ = false;
    std::shared_ptr<const VoxelGrid> latest_;
    std::uint32_t latest_generation_ = 0;
    PublishedGrid published_;

    // Worker thread only.
    std::uint32_t next_generation_;
    std::vector<std::wstring> written_;
    PublishedContent content_;
    RegionCache regions_;
};

}  // namespace sifucraft::collision
