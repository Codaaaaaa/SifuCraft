#pragma once

#include <cstdint>

namespace sifucraft::guard {

struct GameModule {
    std::uintptr_t base = 0;
    std::uint32_t time_date_stamp = 0;
    std::uint32_t size_of_image = 0;
};

// True when running inside Sifu-Win64-Shipping.exe; fills `out` from its PE headers.
bool ReadGameModule(GameModule* out);

// True only if the executable is exactly the build the generated offset table describes.
bool Verify(const GameModule& game);

}  // namespace sifucraft::guard
