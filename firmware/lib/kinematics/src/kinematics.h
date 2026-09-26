// Mecanum mixing and per-wheel calibration — DESIGN.md §4.4, PROTOCOL.md §2.1.
// Pure C++: no Arduino dependencies, built by the native tests.
#pragma once

#include <stdint.h>

namespace kin {

enum Wheel : uint8_t { FL = 0, FR = 1, RL = 2, RR = 3 };

constexpr float kDeadZone = 0.02f;

// Calibration subset of proto::Config.
struct Params {
  uint8_t map[4];     // map[wheel] = physical channel 0..3
  uint8_t invert[4];  // per wheel, 0/1
  uint8_t trim[4];    // per wheel, 50..100 %
  uint8_t min_duty;   // %
  uint8_t max_duty;   // %
};

// i8 wire speed → −1..1; −128 is treated as −127.
float fromWire(int8_t v);
// −1..1 → i8 wire/telemetry value, rounded and clamped to ±127.
int8_t toWire(float v);

// vx right, vy forward, w clockwise (all −1..1) → wheel speeds FL, FR, RL, RR,
// scaled down so the largest magnitude is at most 1.
void mix(float vx, float vy, float w, float wheels[4]);

// Wheel speeds → signed duty fraction (−1..1 of full PWM) per physical
// channel: dead zone, trim, invert, [min_duty, max_duty] scaling, map.
void toChannels(const float wheels[4], const Params& p, float channels[4]);

// DRIVE command → channel duties. enable == false gives all zeros.
void drive(int8_t vx, int8_t vy, int8_t w, bool enable, const Params& p, float channels[4]);

}  // namespace kin
