#pragma once

#include <cstdint>

#include "ue/types.h"

// Scene component transforms. RelativeLocation / RelativeRotation are relative to the attach
// parent, so they are world space only while a component is not attached to anything (a cinematic
// attaches the player's capsule to its own anchor); ComponentToWorld always is.
namespace sifucraft::ue {

// FQuat::Rotator: (x, y, z, w) -> pitch / yaw / roll in degrees, each in (-180, 180].
FRotator QuatToRotator(float x, float y, float z, float w);

bool ReadWorldTransform(std::uintptr_t component, FVector* location, FRotator* rotation);
bool ReadWorldLocation(std::uintptr_t component, FVector* location);

// The scene component `component` is attached to; 0 for an unattached one.
std::uintptr_t AttachParentOf(std::uintptr_t component);

// p' = linear * p + translation (column vectors). Covers UE's FTransform (rotation, translation,
// non-uniform scale), FRotator + offset and FMatrix.
struct Affine {
    float m[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    FVector t{};

    FVector Apply(const FVector& p) const;
    FVector ApplyLinear(const FVector& v) const;
    // (*this)(other(p)): `other` first.
    Affine operator*(const Affine& other) const;
    // Smallest / largest factor the linear part stretches an axis by (column lengths).
    float MinAxisScale() const;
    float MaxAxisScale() const;
};

// FTransform: rotate(scale * p) + translation.
Affine AffineFromQuat(const float (&quat)[4], const FVector& translation, const FVector& scale);
Affine AffineFromRotator(const FRotator& rotation, const FVector& translation);
// FMatrix, which UE applies to row vectors (p' = p * M).
Affine AffineFromRowMatrix(const float (&m)[4][4]);

// ComponentToWorld including its scale.
bool ReadComponentToWorld(std::uintptr_t component, Affine* out);
// An FTransform stored at `address` (e.g. FKConvexElem::Transform).
bool ReadTransformAt(std::uintptr_t address, Affine* out);

}  // namespace sifucraft::ue
