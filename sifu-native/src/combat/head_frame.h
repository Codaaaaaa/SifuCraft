#pragma once

#include <cctype>
#include <cmath>
#include <string_view>

#include "ue/transform.h"

// Pure head-pose math for head-worn items; no game memory.
namespace sifucraft::combat::head {

struct Rotation { float m[3][3]; };

// Linear part with each axis normalised: the rotation of an unsheared transform.
inline bool RotationOf(const ue::Affine& a, Rotation* out) {
    for (int j = 0; j < 3; ++j) {
        const float length = std::sqrt(a.m[0][j] * a.m[0][j] + a.m[1][j] * a.m[1][j] + a.m[2][j] * a.m[2][j]);
        if (!std::isfinite(length) || length < 1e-6f) return false;
        for (int i = 0; i < 3; ++i) out->m[i][j] = a.m[i][j] / length;
    }
    return true;
}

inline Rotation Multiply(const Rotation& a, const Rotation& b) {
    Rotation r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            for (int k = 0; k < 3; ++k) r.m[i][j] += a.m[i][k] * b.m[k][j];
    return r;
}

inline Rotation Transpose(const Rotation& a) {
    Rotation r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) r.m[i][j] = a.m[j][i];
    return r;
}

inline ue::FVector Apply(const Rotation& r, const ue::FVector& v) {
    return {r.m[0][0] * v.x + r.m[0][1] * v.y + r.m[0][2] * v.z, r.m[1][0] * v.x + r.m[1][1] * v.y + r.m[1][2] * v.z,
            r.m[2][0] * v.x + r.m[2][1] * v.y + r.m[2][2] * v.z};
}

inline bool Unit(ue::FVector* v) {
    const float length = std::sqrt(v->x * v->x + v->y * v->y + v->z * v->z);
    if (!std::isfinite(length) || length < 1e-4f) return false;
    v->x /= length; v->y /= length; v->z /= length;
    return true;
}

}  // namespace sifucraft::combat::head

namespace sifucraft::combat {

// Turns the character's `facing` (and world up) by however far the head bone has moved from its
// reference pose. `bone` / `reference` are the bone's current and reference component-space
// transforms, `component` the mesh's ComponentToWorld; scale is ignored.
inline void HeadFrame(const ue::Affine& component, const ue::Affine& bone, const ue::Affine& reference,
                      const ue::FVector& facing, ue::FVector* forward, ue::FVector* up) {
    head::Rotation c, b, r;
    if (!head::RotationOf(component, &c) || !head::RotationOf(bone, &b) || !head::RotationOf(reference, &r)) {
        *forward = facing; *up = {0, 0, 1};
        return;
    }
    // World head rotation against the world rotation the reference pose would have here.
    const head::Rotation turn = head::Multiply(head::Multiply(c, b), head::Transpose(head::Multiply(c, r)));
    *forward = head::Apply(turn, facing);
    *up = head::Apply(turn, {0, 0, 1});
}

inline bool IsHeadBoneName(std::string_view name) {
    if (name.size() < 4) return false;
    const auto tail = name.substr(name.size() - 4);
    for (int i = 0; i < 4; ++i)
        if (std::tolower(static_cast<unsigned char>(tail[i])) != "head"[i]) return false;
    return name.size() == 4 || !std::isalnum(static_cast<unsigned char>(name[name.size() - 5]));
}

}  // namespace sifucraft::combat
