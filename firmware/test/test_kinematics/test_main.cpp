// kinematics: directions, normalization, dead zone, calibration (DESIGN.md §4.4).
#include <kinematics.h>
#include <unity.h>

using namespace kin;

void setUp() {}
void tearDown() {}

constexpr float kEps = 1e-4f;

// Identity calibration with full-range duty, so channel == wheel speed.
Params identity() { return Params{{0, 1, 2, 3}, {0, 0, 0, 0}, {100, 100, 100, 100}, 0, 100}; }

void expectWheels(float vx, float vy, float w, float fl, float fr, float rl, float rr) {
  float o[4];
  mix(vx, vy, w, o);
  TEST_ASSERT_FLOAT_WITHIN(kEps, fl, o[FL]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, fr, o[FR]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, rl, o[RL]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, rr, o[RR]);
}

void expectChannels(const float c[4], float a, float b, float d, float e) {
  TEST_ASSERT_FLOAT_WITHIN(kEps, a, c[0]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, b, c[1]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, d, c[2]);
  TEST_ASSERT_FLOAT_WITHIN(kEps, e, c[3]);
}

void test_forward_back() {
  expectWheels(0, 1, 0, 1, 1, 1, 1);
  expectWheels(0, -1, 0, -1, -1, -1, -1);
}

void test_strafe_right_left() {
  expectWheels(1, 0, 0, 1, -1, -1, 1);
  expectWheels(-1, 0, 0, -1, 1, 1, -1);
}

void test_diagonals() {
  expectWheels(1, 1, 0, 1, 0, 0, 1);     // forward-right
  expectWheels(-1, 1, 0, 0, 1, 1, 0);    // forward-left
  expectWheels(1, -1, 0, 0, -1, -1, 0);  // back-right
  expectWheels(-1, -1, 0, -1, 0, 0, -1); // back-left
}

void test_rotation() {
  expectWheels(0, 0, 1, 1, -1, 1, -1);   // clockwise: left wheels forward
  expectWheels(0, 0, -1, -1, 1, -1, 1);
}

void test_partial_speed_not_scaled_up() { expectWheels(0, 0.5f, 0, 0.5f, 0.5f, 0.5f, 0.5f); }

void test_normalization() {
  // FL = 3, FR = -1, RL = 1, RR = 1 → divided by 3.
  expectWheels(1, 1, 1, 1, -1.0f / 3, 1.0f / 3, 1.0f / 3);
  float o[4];
  mix(1, 1, 1, o);
  for (float v : o) TEST_ASSERT_TRUE(v <= 1.0f + kEps && v >= -1.0f - kEps);
}

void test_wire_conversion() {
  TEST_ASSERT_FLOAT_WITHIN(kEps, 1.0f, fromWire(127));
  TEST_ASSERT_FLOAT_WITHIN(kEps, -1.0f, fromWire(-127));
  TEST_ASSERT_FLOAT_WITHIN(kEps, -1.0f, fromWire(-128));  // clamped
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.0f, fromWire(0));
  TEST_ASSERT_EQUAL(127, toWire(1.0f));
  TEST_ASSERT_EQUAL(-127, toWire(-2.0f));
  TEST_ASSERT_EQUAL(64, toWire(64 / 127.0f));
}

void test_dead_zone() {
  const float wheels[4] = {0.019f, -0.019f, 0.02f, -0.5f};
  float c[4];
  toChannels(wheels, identity(), c);
  expectChannels(c, 0, 0, 0.02f, -0.5f);
}

void test_duty_scaling() {
  Params p = identity();
  p.min_duty = 15;
  p.max_duty = 80;
  const float wheels[4] = {1.0f, -1.0f, 0.5f, 0.0f};
  float c[4];
  toChannels(wheels, p, c);
  expectChannels(c, 0.80f, -0.80f, 0.15f + 0.5f * 0.65f, 0.0f);
}

void test_small_speed_jumps_to_min_duty() {
  Params p = identity();
  p.min_duty = 15;
  const float wheels[4] = {0.03f, 0, 0, 0};
  float c[4];
  toChannels(wheels, p, c);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.15f + 0.03f * 0.85f, c[0]);
}

void test_invert() {
  Params p = identity();
  p.invert[FR] = 1;
  p.invert[RR] = 1;
  const float wheels[4] = {0.5f, 0.5f, 0.5f, -0.5f};
  float c[4];
  toChannels(wheels, p, c);
  expectChannels(c, 0.5f, -0.5f, 0.5f, 0.5f);
}

void test_map() {
  Params p = identity();
  // FL on channel 2, FR on 0, RL on 3, RR on 1.
  p.map[FL] = 2;
  p.map[FR] = 0;
  p.map[RL] = 3;
  p.map[RR] = 1;
  const float wheels[4] = {0.1f, 0.2f, 0.3f, 0.4f};
  float c[4];
  toChannels(wheels, p, c);
  expectChannels(c, 0.2f, 0.4f, 0.1f, 0.3f);
}

void test_trim() {
  Params p = identity();
  p.trim[RL] = 90;
  const float wheels[4] = {1.0f, 1.0f, 1.0f, 1.0f};
  float c[4];
  toChannels(wheels, p, c);
  expectChannels(c, 1.0f, 1.0f, 0.9f, 1.0f);
}

void test_drive_disabled_is_zero() {
  float c[4];
  drive(127, 127, 127, false, identity(), c);
  expectChannels(c, 0, 0, 0, 0);
}

void test_drive_strafe_with_calibration() {
  Params p = identity();
  p.invert[FR] = 1;
  p.map[FL] = 1;
  p.map[FR] = 0;
  float c[4];
  drive(127, 0, 0, true, p, c);
  // FL=+1 → ch1; FR=-1 inverted → +1 on ch0; RL=-1 → ch2; RR=+1 → ch3.
  expectChannels(c, 1, 1, -1, 1);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_forward_back);
  RUN_TEST(test_strafe_right_left);
  RUN_TEST(test_diagonals);
  RUN_TEST(test_rotation);
  RUN_TEST(test_partial_speed_not_scaled_up);
  RUN_TEST(test_normalization);
  RUN_TEST(test_wire_conversion);
  RUN_TEST(test_dead_zone);
  RUN_TEST(test_duty_scaling);
  RUN_TEST(test_small_speed_jumps_to_min_duty);
  RUN_TEST(test_invert);
  RUN_TEST(test_map);
  RUN_TEST(test_trim);
  RUN_TEST(test_drive_disabled_is_zero);
  RUN_TEST(test_drive_strafe_with_calibration);
  return UNITY_END();
}
