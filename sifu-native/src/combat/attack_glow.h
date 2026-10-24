#pragma once
#include <algorithm>
#include <cmath>
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"

namespace sifucraft::combat {
// Read the lifetime of the actual limb/weapon highlight curve, without invoking UE curve code.
inline float GlowDuration(std::uintptr_t curve) {
    namespace off = gen::off;
    mem::RawArray keys;
    if (!curve || !mem::ReadArray(curve + off::UCurveFloat::FloatCurve + off::FRichCurve::Keys, &keys) ||
        keys.num < 1 || keys.num > 128) return NAN;
    float previous = 0;
    for (int i = 0; i < keys.num; ++i) {
        float time = NAN;
        if (!mem::Read(keys.data + i * gen::size::FRichCurveKey + off::FRichCurveKey::Time, &time) ||
            !std::isfinite(time) || time < previous || time > 10) return NAN;
        previous = time;
    }
    return previous > 0 ? previous : NAN;
}

inline bool GlowOverlapsHit(std::uintptr_t request, float fps, float start, float duration) {
    namespace off = gen::off;
    namespace bounds = gen::value::ERangeBoundTypes::Type;
    if (!std::isfinite(fps) || fps < 1 || fps > 240 || !std::isfinite(start) || start < -0.05f ||
        !std::isfinite(duration) || duration <= 0 || duration > 10) return false;
    const auto range = request + off::FHitRequest::m_HitBox + off::FHitBox::m_FrameRange + off::FFrameRange::m_Anim;
    const auto lower = range + off::FFloatRange::LowerBound, upper = range + off::FFloatRange::UpperBound;
    const auto lt = mem::ReadOr<std::uint8_t>(lower + off::FloatRangeBound::Type, 0xFF);
    const auto ut = mem::ReadOr<std::uint8_t>(upper + off::FloatRangeBound::Type, 0xFF);
    const float from = mem::ReadOr<float>(lower + off::FloatRangeBound::Value, NAN);
    const float to = mem::ReadOr<float>(upper + off::FloatRangeBound::Value, NAN);
    if (lt > bounds::Inclusive || ut > bounds::Inclusive || !std::isfinite(from) || !std::isfinite(to) ||
        from < 0 || to < from) return false;
    start = std::max(0.0f, start);
    return (ut == bounds::Inclusive ? start <= to / fps : start < to / fps) &&
        (lt == bounds::Inclusive ? start + duration >= from / fps : start + duration > from / fps);
}

// The resolver validates the notify's UObject identity, exact glow class and curve property.
template <typename ResolveCurve>
bool AttackHasGlow(std::uintptr_t request, std::uintptr_t attack, std::uintptr_t animation, ResolveCurve resolve) {
    namespace off = gen::off;
    mem::RawArray events;
    if (!animation || !mem::ReadArray(animation + off::UAnimSequenceBase::Notifies, &events) || events.num > 256) return false;
    const float fps = mem::ReadOr<float>(attack + off::FComboAttack::m_fAnimFrameRate, NAN);
    for (int i = 0; i < events.num; ++i) {
        const auto event = events.data + i * gen::size::FAnimNotifyEvent;
        if (mem::ReadOr<std::uint8_t>(event + off::FAnimLinkableElement::LinkMethod, 0xFF) != gen::value::EAnimLinkMethod::Type::Absolute) continue;
        const auto curve = resolve(mem::ReadPtr(event + off::FAnimNotifyEvent::Notify));
        const float start = mem::ReadOr<float>(event + off::FAnimLinkableElement::LinkValue, NAN) +
            mem::ReadOr<float>(event + off::FAnimNotifyEvent::TriggerTimeOffset, NAN);
        if (curve && GlowOverlapsHit(request, fps, start, GlowDuration(curve))) return true;
    }
    return false;
}
}
