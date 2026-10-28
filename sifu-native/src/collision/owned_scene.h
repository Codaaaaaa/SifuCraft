#pragma once
#include <cstdint>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>

namespace sifucraft::collision {
// Exclusion only: this registry never authorizes an engine call. Reads happen on
// Runtime while the game thread creates/removes our derived collision actors.
class OwnedSceneActors {
    struct Identity {std::int32_t index,serial;std::uint64_t name;};
    std::shared_mutex mutex_;
    std::unordered_map<std::uintptr_t,Identity> actors_;
public:
    void Add(std::uintptr_t actor,std::int32_t index,std::int32_t serial,std::uint64_t name){
        if(!actor||index<0||serial<0||!name)return;
        std::unique_lock lock(mutex_);actors_[actor]={index,serial,name};
    }
    void Remove(std::uintptr_t actor){std::unique_lock lock(mutex_);actors_.erase(actor);}
    bool Contains(std::uintptr_t actor,std::int32_t index,std::int32_t serial,std::uint64_t name){
        std::shared_lock lock(mutex_);auto at=actors_.find(actor);
        if(at==actors_.end()||at->second.index!=index||at->second.name!=name||serial<0)return false;
        const auto old=at->second.serial;if(old)return old==serial;
        if(!serial)return true;
        lock.unlock();std::unique_lock write(mutex_);at=actors_.find(actor);
        if(at==actors_.end()||at->second.index!=index||at->second.name!=name)return false;
        if(!at->second.serial)at->second.serial=serial;
        return at->second.serial==serial;
    }
};
inline OwnedSceneActors owned_scene_actors;
}
