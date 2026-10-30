#include "combat/enemy_heads.h"

#include <algorithm>
#include <cmath>

#include "combat/head_frame.h"
#include "core/log.h"
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"
#include "ue/names.h"
#include "ue/objects.h"

namespace sifucraft::combat {
namespace {
namespace off = gen::off;
constexpr int kMaxBones = 1024;
constexpr std::uint32_t kArrayBytes = 16;  // TArray: data, num, max

bool ReadBone(const mem::RawArray& transforms, int bone, ue::Affine* out) {
    return bone >= 0 && bone < transforms.num &&
        ue::ReadTransformAt(transforms.data + static_cast<std::uintptr_t>(bone) * gen::size::FTransform, out);
}
}  // namespace

const EnemyHeads::Skeleton* EnemyHeads::Find(std::uintptr_t skeletal_mesh) {
    const auto identity = ue::IdentityOf(skeletal_mesh);
    if (!identity.valid()) return nullptr;
    auto found = skeletons_.find(skeletal_mesh);
    if (found != skeletons_.end() && found->second.identity == identity) return &found->second;
    Skeleton skeleton;
    skeleton.identity = identity;
    const auto ref = skeletal_mesh + off::USkeletalMesh::RefSkeleton;
    mem::RawArray info, pose;
    if (mem::ReadArray(ref + off::FReferenceSkeleton::FinalRefBoneInfo, &info) &&
        mem::ReadArray(ref + off::FReferenceSkeleton::FinalRefBonePose, &pose) && info.num == pose.num) {
        const int count = std::min(info.num, kMaxBones);
        const auto parent_of = [&](int i) {
            return mem::ReadOr<std::int32_t>(info.data + static_cast<std::uintptr_t>(i) * gen::size::FMeshBoneInfo +
                                             off::FMeshBoneInfo::ParentIndex, -1);
        };
        for (int i = 0; i < count && skeleton.bone < 0; ++i) {
            ue::FName name{};
            if (mem::Read(info.data + static_cast<std::uintptr_t>(i) * gen::size::FMeshBoneInfo + off::FMeshBoneInfo::Name, &name) &&
                IsHeadBoneName(ue::NameToString(name))) skeleton.bone = i;
        }
        // Reference pose is parent-relative: compose it up to the root.
        ue::Affine local;
        if (skeleton.bone >= 0 && ReadBone(pose, skeleton.bone, &skeleton.reference)) {
            int parent = parent_of(skeleton.bone);
            for (int depth = 0; parent >= 0 && parent < count && depth < kMaxBones; ++depth) {
                if (!ReadBone(pose, parent, &local)) { skeleton.bone = -1; break; }
                skeleton.reference = local * skeleton.reference;
                parent = parent_of(parent);
            }
        } else {
            skeleton.bone = -1;
        }
    }
    SC_LOG("[enemy-heads] %s head bone %d of %s", ue::ObjectName(skeletal_mesh).c_str(), skeleton.bone,
           skeleton.bone >= 0 ? "found" : "not found");
    return &(skeletons_[skeletal_mesh] = skeleton);
}

void EnemyHeads::Update(const std::vector<Enemies::Target>& targets, const std::vector<protocol::EnemyEntry>& entries) {
    entries_.clear();
    for (const auto& target : targets) {
        const protocol::EnemyEntry* entry = nullptr;
        for (const auto& e : entries)
            if (e.id == target.id && e.spawn == target.spawn) entry = &e;
        if (!entry || ue::IdentityOf(target.actor) != target.identity) continue;
        ++samples_;
        const auto mesh = mem::ReadPtr(target.actor + off::ACharacter::Mesh);
        const auto asset = ue::IsLive(mesh) ? mem::ReadPtr(mesh + off::USkinnedMeshComponent::SkeletalMesh) : 0;
        const Skeleton* skeleton = ue::IsLive(asset) ? Find(asset) : nullptr;
        const int read = mem::ReadOr<std::int32_t>(mesh + off::USkinnedMeshComponent::CurrentReadComponentTransforms, -1);
        mem::RawArray pose;
        ue::Affine component, bone;
        if (!skeleton || skeleton->bone < 0 || (read != 0 && read != 1) ||
            !mem::ReadArray(mesh + off::USkinnedMeshComponent::ComponentSpaceTransformsArray + read * kArrayBytes, &pose) ||
            !ReadBone(pose, skeleton->bone, &bone) || !ue::ReadComponentToWorld(mesh, &component)) {
            ++missing_;
            continue;
        }
        const float yaw = entry->yaw * 3.14159265f / 180.0f;
        ue::FVector forward, up;
        HeadFrame(component, bone, skeleton->reference, {std::cos(yaw), std::sin(yaw), 0}, &forward, &up);
        const auto at = component.Apply(bone.t);
        if (!std::isfinite(at.x) || !std::isfinite(at.y) || !std::isfinite(at.z) || !head::Unit(&forward) || !head::Unit(&up)) {
            ++missing_;
            continue;
        }
        entries_.push_back({target.id, target.spawn, {at.x, at.y, at.z}, {forward.x, forward.y, forward.z},
                            {up.x, up.y, up.z}, 0});
    }
}

std::string EnemyHeads::TakeStats() {
    const std::string text = FormatString("heads=%zu samples=%llu missing=%llu skeletons=%zu", entries_.size(),
        static_cast<unsigned long long>(samples_), static_cast<unsigned long long>(missing_), skeletons_.size());
    samples_ = missing_ = 0;
    return text;
}

}  // namespace sifucraft::combat
