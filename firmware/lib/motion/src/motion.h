// Motor output shaping: acceleration limit and H-bridge mode sequencing.
// Pure C++: no Arduino dependencies, built by the native tests.
#pragma once

#include <stdint.h>

namespace motion {

// One step of the acceleration limit (DESIGN.md §4.4). Only growth in
// magnitude is limited, by 1.0 (full PWM) per slew_ms. Slowing down, stopping
// and reversing drop to the new value / zero at once; a reversal then ramps up
// from zero.
float slewStep(float current, float target, uint32_t dt_ms, uint16_t slew_ms);

enum class Mode : uint8_t { Coast, Brake, Forward, Reverse };

constexpr uint32_t kDeadTimeMs = 2;

struct BridgeCmd {
  Mode mode;
  float duty;  // 0..1, meaningful for Forward/Reverse
};

// Per-motor DRV8833 input sequencing (DESIGN.md §4.5). A change between two
// non-coast modes passes through Coast for kDeadTimeMs without blocking.
class Bridge {
 public:
  BridgeCmd update(float duty, bool brake, uint32_t now_ms);
  Mode mode() const { return mode_; }

 private:
  Mode mode_ = Mode::Coast;
  bool settling_ = false;
  uint32_t settle_start_ = 0;
};

}  // namespace motion
