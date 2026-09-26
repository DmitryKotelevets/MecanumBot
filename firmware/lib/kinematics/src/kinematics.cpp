#include "kinematics.h"

#include <math.h>

namespace kin {

float fromWire(int8_t v) {
  if (v < -127) v = -127;
  return v / 127.0f;
}

int8_t toWire(float v) {
  float r = roundf(v * 127.0f);
  if (r > 127.0f) r = 127.0f;
  if (r < -127.0f) r = -127.0f;
  return static_cast<int8_t>(r);
}

void mix(float vx, float vy, float w, float wheels[4]) {
  wheels[FL] = vy + vx + w;
  wheels[FR] = vy - vx - w;
  wheels[RL] = vy - vx + w;
  wheels[RR] = vy + vx - w;
  float m = 1.0f;
  for (int i = 0; i < 4; i++) m = fmaxf(m, fabsf(wheels[i]));
  for (int i = 0; i < 4; i++) wheels[i] /= m;
}

void toChannels(const float wheels[4], const Params& p, float channels[4]) {
  for (int i = 0; i < 4; i++) channels[i] = 0.0f;
  const float lo = p.min_duty / 100.0f;
  const float hi = p.max_duty / 100.0f;
  for (int i = 0; i < 4; i++) {
    float v = wheels[i];
    if (fabsf(v) < kDeadZone) continue;
    v *= p.trim[i] / 100.0f;
    if (p.invert[i]) v = -v;
    const float mag = lo + fminf(fabsf(v), 1.0f) * (hi - lo);
    channels[p.map[i] & 3] = v < 0 ? -mag : mag;
  }
}

void drive(int8_t vx, int8_t vy, int8_t w, bool enable, const Params& p, float channels[4]) {
  float wheels[4] = {0, 0, 0, 0};
  if (enable) mix(fromWire(vx), fromWire(vy), fromWire(w), wheels);
  toChannels(wheels, p, channels);
}

}  // namespace kin
