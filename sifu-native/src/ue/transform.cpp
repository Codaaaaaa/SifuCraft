#include "ue/transform.h"

#include <algorithm>
#include <cmath>
#include <cstddef>

#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"

namespace sifucraft::ue {

namespace {

namespace off = gen::off;

// FTransform with vectorized storage: rotation (FQuat), translation and scale, each 16 bytes.
struct RawTransform {
    float rotation[4];
    float translation[4];
    float scale[4];
};
static_assert(sizeof(RawTransform) == gen::size::FTransform);
static_assert(offsetof(RawTransform, rotation) == off::FTransform::Rotation);
static_assert(offsetof(RawTransform, translation) == off::FTransform::Translation);
static_assert(offsetof(RawTransform, scale) == off::FTransform::Scale3D);

constexpr float kRadToDeg = 57.2957795130823208768f;
constexpr float kDegToRad = 0.0174532925199432957692f;
constexpr float kSingularityThreshold = 0.4999995f;

float NormalizeAxis(float degrees) {
    degrees = std::fmod(degrees, 360.0f);
    if (degrees > 180.0f) degrees -= 360.0f;
    if (degrees <= -180.0f) degrees += 360.0f;
    return degrees;
}

Affine FromRaw(const RawTransform& t) {
    return AffineFromQuat(t.rotation, {t.translation[0], t.translation[1], t.translation[2]},
                          {t.scale[0], t.scale[1], t.scale[2]});
}

float ColumnLength(const Affine& a, int column) {
    return std::sqrt(a.m[0][column] * a.m[0][column] + a.m[1][column] * a.m[1][column] +
                     a.m[2][column] * a.m[2][column]);
}

}  // namespace

FRotator QuatToRotator(float x, float y, float z, float w) {
    const float singularity = z * x - w * y;
    FRotator r{};
    r.yaw = std::atan2(2.0f * (w * z + x * y), 1.0f - 2.0f * (y * y + z * z)) * kRadToDeg;
    if (singularity < -kSingularityThreshold) {
        r.pitch = -90.0f;
        r.roll = NormalizeAxis(-r.yaw - 2.0f * std::atan2(x, w) * kRadToDeg);
    } else if (singularity > kSingularityThreshold) {
        r.pitch = 90.0f;
        r.roll = NormalizeAxis(r.yaw - 2.0f * std::atan2(x, w) * kRadToDeg);
    } else {
        r.pitch = std::asin(2.0f * singularity) * kRadToDeg;
        r.roll = std::atan2(-2.0f * (w * x + y * z), 1.0f - 2.0f * (x * x + y * y)) * kRadToDeg;
    }
    return r;
}

bool ReadWorldTransform(std::uintptr_t component, FVector* location, FRotator* rotation) {
    RawTransform t;
    if (!component || !mem::Read(component + off::USceneComponent::ComponentToWorld, &t)) return false;
    *location = {t.translation[0], t.translation[1], t.translation[2]};
    *rotation = QuatToRotator(t.rotation[0], t.rotation[1], t.rotation[2], t.rotation[3]);
    return true;
}

bool ReadWorldLocation(std::uintptr_t component, FVector* location) {
    return component &&
           mem::Read(component + off::USceneComponent::ComponentToWorld + off::FTransform::Translation, location);
}

std::uintptr_t AttachParentOf(std::uintptr_t component) {
    return component ? mem::ReadPtr(component + off::USceneComponent::AttachParent) : 0;
}

FVector Affine::Apply(const FVector& p) const {
    const FVector v = ApplyLinear(p);
    return {v.x + t.x, v.y + t.y, v.z + t.z};
}

FVector Affine::ApplyLinear(const FVector& v) const {
    return {m[0][0] * v.x + m[0][1] * v.y + m[0][2] * v.z, m[1][0] * v.x + m[1][1] * v.y + m[1][2] * v.z,
            m[2][0] * v.x + m[2][1] * v.y + m[2][2] * v.z};
}

Affine Affine::operator*(const Affine& other) const {
    Affine r;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) {
            r.m[i][j] = m[i][0] * other.m[0][j] + m[i][1] * other.m[1][j] + m[i][2] * other.m[2][j];
        }
    }
    r.t = Apply(other.t);
    return r;
}

float Affine::MinAxisScale() const {
    return std::min({ColumnLength(*this, 0), ColumnLength(*this, 1), ColumnLength(*this, 2)});
}

float Affine::MaxAxisScale() const {
    return std::max({ColumnLength(*this, 0), ColumnLength(*this, 1), ColumnLength(*this, 2)});
}

Affine AffineFromQuat(const float (&q)[4], const FVector& translation, const FVector& scale) {
    const float x = q[0], y = q[1], z = q[2], w = q[3];
    const float rotation[3][3] = {
        {1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y)},
        {2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x)},
        {2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)},
    };
    const float s[3] = {scale.x, scale.y, scale.z};
    Affine a;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) a.m[i][j] = rotation[i][j] * s[j];
    }
    a.t = translation;
    return a;
}

Affine AffineFromRotator(const FRotator& r, const FVector& translation) {
    // FRotator::Quaternion.
    const float half = kDegToRad * 0.5f;
    const float sp = std::sin(r.pitch * half), cp = std::cos(r.pitch * half);
    const float sy = std::sin(r.yaw * half), cy = std::cos(r.yaw * half);
    const float sr = std::sin(r.roll * half), cr = std::cos(r.roll * half);
    const float q[4] = {cr * sp * sy - sr * cp * cy, -cr * sp * cy - sr * cp * sy, cr * cp * sy - sr * sp * cy,
                        cr * cp * cy + sr * sp * sy};
    return AffineFromQuat(q, translation, {1.0f, 1.0f, 1.0f});
}

Affine AffineFromRowMatrix(const float (&m)[4][4]) {
    Affine a;
    for (int i = 0; i < 3; ++i) {
        for (int j = 0; j < 3; ++j) a.m[i][j] = m[j][i];
    }
    a.t = {m[3][0], m[3][1], m[3][2]};
    return a;
}

bool ReadComponentToWorld(std::uintptr_t component, Affine* out) {
    return component && ReadTransformAt(component + off::USceneComponent::ComponentToWorld, out);
}

bool ReadTransformAt(std::uintptr_t address, Affine* out) {
    RawTransform t;
    if (!mem::Read(address, &t)) return false;
    *out = FromRaw(t);
    return true;
}

}  // namespace sifucraft::ue
