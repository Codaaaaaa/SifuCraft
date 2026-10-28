#pragma once
#include <deque>
#include <memory>
#include <string>
#include "collision/coverage.h"
#include "collision/voxels.h"

namespace sifucraft::collision {
// Voxel data only. No UObject pointer survives into this worker-owned cache.
class RegionCache {
    struct Entry {CellRegion region;std::shared_ptr<const VoxelGrid> grid;std::vector<CellRegion> holes;};
    std::uint32_t world_=0;std::uint64_t epoch_=0;std::string map_;
    std::deque<Entry> entries_;
    static void RemoveCovered(std::vector<CellRegion>& holes,const CellRegion& fresh){
        std::vector<CellRegion> remaining;
        for(auto old:holes){
            CellRegion cut;for(int i=0;i<3;i++){cut.min[i]=std::max(old.min[i],fresh.min[i]);cut.max[i]=std::min(old.max[i],fresh.max[i]);}
            if(!cut.valid()){remaining.push_back(old);continue;}
            for(int i=0;i<3;i++){
                if(old.min[i]<cut.min[i]){auto slab=old;slab.max[i]=cut.min[i];remaining.push_back(slab);old.min[i]=cut.min[i];}
                if(old.max[i]>cut.max[i]){auto slab=old;slab.min[i]=cut.max[i];remaining.push_back(slab);old.max[i]=cut.max[i];}
            }
        }
        holes=std::move(remaining);
    }
public:
    struct Result {std::shared_ptr<VoxelGrid> grid;Coverage coverage;};
    Result Put(std::uint32_t world,std::string map,std::uint64_t epoch,const CellRegion& region,std::shared_ptr<const VoxelGrid> grid,const Coverage& coverage){
        if(world_!=world||map_!=map||epoch_!=epoch){entries_.clear();world_=world;map_=std::move(map);epoch_=epoch;}
        for(auto at=entries_.begin();at!=entries_.end();)if(at->region==region)at=entries_.erase(at);else ++at;
        entries_.push_back({region,std::move(grid),coverage.unknown});while(entries_.size()>64)entries_.pop_front();
        Result result{std::make_shared<VoxelGrid>(),{}};
        for(const auto& entry:entries_){
            result.grid->ReplaceRegion(*entry.grid,entry.region);result.coverage.known.push_back(entry.region);
            RemoveCovered(result.coverage.unknown,entry.region);
            result.coverage.unknown.insert(result.coverage.unknown.end(),entry.holes.begin(),entry.holes.end());
        }
        return result;
    }
};
}
