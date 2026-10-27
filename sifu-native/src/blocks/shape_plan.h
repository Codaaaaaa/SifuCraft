#pragma once

#include <array>
#include <cstdint>
#include <cstring>
#include <map>
#include <vector>
#include "sifucraft_shapes.h"

namespace sifucraft::blocks {
using ShapeKey = std::array<std::int64_t,4>;
struct ShapeWanted { ShapeKey key{}; shape_data::Box box{}; };
struct ShapeUpdate { ShapeKey from{}; ShapeWanted wanted{}; };
struct ShapeExisting { shape_data::Box box{}; bool owned = false; };
struct ShapePlan {
    std::vector<ShapeUpdate> updates;
    std::vector<ShapeWanted> spawns;
    std::vector<ShapeKey> destroys;
};
// Only obsolete, still-owned boxes from the same section can supply a new key.
// A component belonging to any desired key is reserved, even if unchanged.
inline ShapePlan PlanShapeWork(const std::map<ShapeKey,shape_data::Box>& wanted,
                              const std::map<ShapeKey,ShapeExisting>& existing) {
    ShapePlan plan;
    using Section = std::array<std::int64_t,3>;
    const auto section=[](const ShapeKey& key){return Section{key[0],key[1],key[2]};};
    std::map<Section,std::vector<ShapeKey>> donors;
    for(const auto& [key,state]:existing)if(!wanted.count(key)) {
        if(state.owned)donors[section(key)].push_back(key);
        else plan.destroys.push_back(key);
    }
    for(const auto& [key,box]:wanted) {
        const auto old=existing.find(key);
        if(old!=existing.end()&&old->second.owned) {
            if(std::memcmp(&old->second.box,&box,sizeof(box)))plan.updates.push_back({key,{key,box}});
        } else if(old!=existing.end()) {
            plan.spawns.push_back({key,box}); // invalid identities use bounded replacement
        } else {
            auto& available=donors[section(key)];
            if(available.empty())plan.spawns.push_back({key,box});
            else {plan.updates.push_back({available.back(),{key,box}});available.pop_back();}
        }
    }
    for(const auto& [key,available]:donors)plan.destroys.insert(plan.destroys.end(),available.begin(),available.end());
    return plan;
}
}
