#pragma once

#include <cmath>
#include <limits>
#include "core/safe_memory.h"
#include "generated/sifu_build.g.h"

namespace sifucraft::combat {
// UGuardAbility::ProcessForPlayer compares guard duration * 59.999996 against this range.
// The game's logic frames are 1/60 s, independently of display/render frequency.
inline bool InParryWindow(std::uintptr_t guard_db, double age_seconds) {
    namespace off = gen::off;
    namespace bounds = gen::value::ERangeBoundTypes::Type;
    if (!guard_db || !std::isfinite(age_seconds) || age_seconds < 0 || age_seconds > 5) return false;
    const auto range = guard_db + off::UGuardDB::m_ParryWindow;
    const auto lower = range + off::FFloatRange::LowerBound;
    const auto upper = range + off::FFloatRange::UpperBound;
    const auto low_type = mem::ReadOr<std::uint8_t>(lower + off::FloatRangeBound::Type, 0xFF);
    const auto high_type = mem::ReadOr<std::uint8_t>(upper + off::FloatRangeBound::Type, 0xFF);
    const float low = mem::ReadOr<float>(lower + off::FloatRangeBound::Value, NAN);
    const float high = mem::ReadOr<float>(upper + off::FloatRangeBound::Value, NAN);
    if (low_type > bounds::Inclusive || high_type > bounds::Inclusive ||
        !std::isfinite(low) || !std::isfinite(high) || low < 0 || high < low || high > 120) return false;
    const double frames = age_seconds * 59.999996185302734;
    return (low_type == bounds::Inclusive ? frames >= low : frames > low) &&
           (high_type == bounds::Inclusive ? frames <= high : frames < high);
}

inline double GameSeconds(std::uintptr_t base) {
    const auto world = mem::ReadPtr(base + gen::rva::GWorld);
    return world ? mem::ReadOr<float>(world + gen::off::UWorld::TimeSeconds, NAN) : NAN;
}
}
