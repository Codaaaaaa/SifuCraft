#include "collision/walk_check.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <utility>

#include "core/log.h"

namespace sifucraft::collision {

namespace {

// The capsule is checked from just above the highest step Sifu climbs (45 cm) to a little below
// its top, shrunk sideways by a margin for the cells' size and the voxels' rounding.
constexpr float kAboveStepCm = 50.0f;
constexpr float kBelowTopCm = 15.0f;
constexpr float kSideMarginCm = 10.0f;
constexpr float kStillCm = 1.0f;
constexpr std::size_t kMaxExamples = 8;
constexpr float kExampleSpacingCm = 100.0f;
constexpr char kCsvHeader[] = "feet_x,feet_y,feet_z,capsule_radius,capsule_half_height\n";

struct FileCloser {
    void operator()(std::FILE* f) const { std::fclose(f); }
};
using File = std::unique_ptr<std::FILE, FileCloser>;

// Floor error buckets (voxel floor - feet, cm): < -10, -10..-5, -5..0, 0..5, 5..10, >= 10.
int Bucket(float error) {
    if (error < -10.0f) return 0;
    if (error < -5.0f) return 1;
    if (error < 0.0f) return 2;
    if (error < 5.0f) return 3;
    if (error < 10.0f) return 4;
    return 5;
}

int SolidCellsInCapsule(const VoxelGrid& grid, const FVector& feet, float radius, float half_height) {
    const float r = radius - kSideMarginCm;
    if (r <= 0.0f) return 0;
    int solid = 0;
    for (int z = CellIndex(feet.z + kAboveStepCm); z <= CellIndex(feet.z + 2.0f * half_height - kBelowTopCm); ++z) {
        for (int y = CellIndex(feet.y - r); y <= CellIndex(feet.y + r); ++y) {
            for (int x = CellIndex(feet.x - r); x <= CellIndex(feet.x + r); ++x) {
                const float dx = (x + 0.5f) * kCellCm - feet.x, dy = (y + 0.5f) * kCellCm - feet.y;
                if (dx * dx + dy * dy <= r * r && grid.Get(x, y, z)) ++solid;
            }
        }
    }
    return solid;
}

}  // namespace

bool WalkCheck::Sample(const VoxelGrid& grid, const WalkSample& sample) {
    const FVector& feet = sample.feet;
    if (have_last_) {
        const float dx = feet.x - last_.x, dy = feet.y - last_.y, dz = feet.z - last_.z;
        if (dx * dx + dy * dy + dz * dz < kStillCm * kStillCm) return false;
    }
    have_last_ = true;
    last_ = feet;
    scored_.push_back(sample);
    ++samples_;
    const float floor = FloorUnder(grid, feet.x, feet.y, feet.z);
    if (std::isnan(floor)) {
        ++no_floor_;
    } else {
        const float error = floor - feet.z;
        if (samples_ - no_floor_ == 1) error_min_ = error_max_ = error;
        error_min_ = std::min(error_min_, error);
        error_max_ = std::max(error_max_, error);
        error_sum_ += error;
        error_sum_sq_ += static_cast<double>(error) * error;
        ++buckets_[Bucket(error)];
    }
    const int solid = SolidCellsInCapsule(grid, feet, sample.capsule_radius, sample.capsule_half_height);
    if (solid == 0) return true;
    ++inside_solid_;
    const float dx = feet.x - last_example_.x, dy = feet.y - last_example_.y, dz = feet.z - last_example_.z;
    if (examples_.size() < kMaxExamples &&
        (examples_.empty() || dx * dx + dy * dy + dz * dz > kExampleSpacingCm * kExampleSpacingCm)) {
        examples_.push_back(FormatString("(%.0f, %.0f, %.0f) %d cells", feet.x, feet.y, feet.z, solid));
        last_example_ = feet;
    }
    return true;
}

std::vector<std::string> WalkCheck::Report(const std::string& map) const {
    const int measured = samples_ - no_floor_;
    const double mean = measured ? error_sum_ / measured : 0.0;
    const double sd = measured ? std::sqrt(std::max(0.0, error_sum_sq_ / measured - mean * mean)) : 0.0;
    std::vector<std::string> lines;
    lines.push_back(FormatString(
        "%s: %d walking samples | floor error (voxel - feet) mean %+.1f cm, sd %.1f, min %+.1f, max %+.1f "
        "| <-10:%d -10..-5:%d -5..0:%d 0..5:%d 5..10:%d >=10:%d | no floor: %d | capsule overlapping solid: %d",
        map.c_str(), samples_, mean, sd, error_min_, error_max_, buckets_[0], buckets_[1], buckets_[2], buckets_[3],
        buckets_[4], buckets_[5], no_floor_, inside_solid_));
    for (const std::string& example : examples_) lines.push_back("  inside solid at " + example);
    return lines;
}

void WalkCheck::Reset() {
    WalkCheck fresh;
    fresh.have_last_ = have_last_;
    fresh.last_ = last_;
    *this = std::move(fresh);
}

void WalkCheck::Flush(const std::string& map) {
    if (samples_ == 0) return;
    for (const std::string& line : Report(map)) SC_LOG("[collision-check] %s", line.c_str());
    Reset();
}

bool AppendWalkSamples(const std::wstring& path, const std::vector<WalkSample>& samples) {
    const File f(_wfopen(path.c_str(), L"ab"));
    if (!f) return false;
    std::fseek(f.get(), 0, SEEK_END);
    if (std::ftell(f.get()) == 0) std::fputs(kCsvHeader, f.get());
    for (const WalkSample& s : samples) {
        std::fprintf(f.get(), "%.2f,%.2f,%.2f,%.2f,%.2f\n", s.feet.x, s.feet.y, s.feet.z, s.capsule_radius,
                     s.capsule_half_height);
    }
    return std::ferror(f.get()) == 0;
}

bool ReadWalkSamples(const std::wstring& path, std::vector<WalkSample>* samples) {
    const File f(_wfopen(path.c_str(), L"rb"));
    if (!f) return false;
    char line[256];
    while (std::fgets(line, sizeof(line), f.get())) {
        WalkSample s;
        if (std::sscanf(line, "%f,%f,%f,%f,%f", &s.feet.x, &s.feet.y, &s.feet.z, &s.capsule_radius,
                        &s.capsule_half_height) == 5) {
            samples->push_back(s);
        }
    }
    return true;
}

}  // namespace sifucraft::collision
