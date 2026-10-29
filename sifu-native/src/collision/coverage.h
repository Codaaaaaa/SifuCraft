#pragma once
#include <array>
#include <vector>
#include <cstdint>
#include <algorithm>

namespace sifucraft::collision {
// Native 1/8m cell coordinates, lower inclusive / upper exclusive.
struct CellRegion {
    std::array<std::int32_t,3> min{},max{};
    bool operator==(const CellRegion&)const=default;
    bool valid()const{for(int i=0;i<3;i++)if(min[i]>=max[i]||min[i]<-8388608||max[i]>8388608)return false;return true;}
    bool contains(int x,int y,int z)const{return x>=min[0]&&x<max[0]&&y>=min[1]&&y<max[1]&&z>=min[2]&&z<max[2];}
};
struct Coverage {
    std::vector<CellRegion> known,unknown;
    bool operator==(const Coverage&)const=default;
    bool valid()const{return known.size()<=4096&&unknown.size()<=65536&&std::all_of(known.begin(),known.end(),[](const auto& r){return r.valid();})&&std::all_of(unknown.begin(),unknown.end(),[](const auto& r){return r.valid();});}
    bool Known(int x,int y,int z)const{return std::any_of(known.begin(),known.end(),[&](const auto& r){return r.contains(x,y,z);})&&!std::any_of(unknown.begin(),unknown.end(),[&](const auto& r){return r.contains(x,y,z);});}
};
}
