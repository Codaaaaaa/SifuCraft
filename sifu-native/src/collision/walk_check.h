#pragma once

#include <string>
#include <vector>

#include "collision/voxels.h"

// Measures the voxel grid against the game itself: while Sifu walks the pawn (its own physics,
// on its own collision), the voxel floor under the feet should be where the feet are, and no
// solid cell should lie inside the pawn's capsule (shrunk by a margin). A mismatch is a floor at
// the wrong height or a wall that is not there in Sifu. The samples are also recorded, so that a
// changed voxelization can be scored offline against the same walk (tools/collision_replay.cpp).
namespace sifucraft::collision {

// The pawn walking on the ground under Sifu's control (Unreal cm).
struct WalkSample {
    FVector feet{};
    float capsule_radius = 0.0f;
    float capsule_half_height = 0.0f;
};

class WalkCheck {
public:
    // Scores one sample. A sample where the previous one was (the pawn standing still) is skipped,
    // so that idling does not outweigh walking; returns whether it was scored.
    bool Sample(const VoxelGrid& grid, const WalkSample& sample);
    int samples() const { return samples_; }
    // The samples scored since the last reset.
    const std::vector<WalkSample>& scored() const { return scored_; }
    // A summary line, then up to a few places where the capsule overlapped solid cells.
    std::vector<std::string> Report(const std::string& map) const;
    // Forgets the measurements (not the last spot: standing still stays skipped).
    void Reset();
    // Logs the report and resets (no-op without samples).
    void Flush(const std::string& map);

private:
    static constexpr int kBuckets = 6;

    int samples_ = 0;
    int no_floor_ = 0;
    int inside_solid_ = 0;
    double error_sum_ = 0.0, error_sum_sq_ = 0.0;
    float error_min_ = 0.0f, error_max_ = 0.0f;
    int buckets_[kBuckets] = {};
    std::vector<std::string> examples_;
    FVector last_example_{};
    std::vector<WalkSample> scored_;
    bool have_last_ = false;
    FVector last_{};
};

// Walk samples as CSV lines "feet_x,feet_y,feet_z,capsule_radius,capsule_half_height": appended,
// with the header line when the file is new.
bool AppendWalkSamples(const std::wstring& path, const std::vector<WalkSample>& samples);
// Appends the file's samples to `samples`.
bool ReadWalkSamples(const std::wstring& path, std::vector<WalkSample>* samples);

}  // namespace sifucraft::collision
