#include "motion.h"

#include <math.h>

namespace motion {

float slewStep(float current, float target, uint32_t dt_ms, uint16_t slew_ms) {
  if (target == 0.0f) return 0.0f;
  if (current != 0.0f && (current > 0.0f) != (target > 0.0f)) current = 0.0f;
  if (fabsf(target) <= fabsf(current) || slew_ms == 0) return target;
  const float step = static_cast<float>(dt_ms) / slew_ms;
  const float mag = fminf(fabsf(target), fabsf(current) + step);
  return target > 0.0f ? mag : -mag;
}

BridgeCmd Bridge::update(float duty, bool brake, uint32_t now_ms) {
  const Mode want = duty > 0.0f ? Mode::Forward : duty < 0.0f ? Mode::Reverse
                                : brake ? Mode::Brake : Mode::Coast;
  const float mag = fabsf(duty);
  if (want == mode_) {
    settling_ = false;
    return {mode_, mag};
  }
  if (mode_ != Mode::Coast && want != Mode::Coast) {
    mode_ = Mode::Coast;
    settling_ = true;
    settle_start_ = now_ms;
    return {Mode::Coast, 0.0f};
  }
  if (settling_ && static_cast<uint32_t>(now_ms - settle_start_) < kDeadTimeMs) {
    return {Mode::Coast, 0.0f};
  }
  settling_ = false;
  mode_ = want;
  return {mode_, mag};
}

}  // namespace motion
