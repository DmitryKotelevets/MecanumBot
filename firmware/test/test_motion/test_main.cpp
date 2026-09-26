// motion: acceleration limit and H-bridge sequencing (DESIGN.md §4.4–4.5).
#include <motion.h>
#include <unity.h>

using namespace motion;

void setUp() {}
void tearDown() {}

constexpr float kEps = 1e-5f;

void test_slew_ramps_up() {
  // 250 ms for full scale: 25 ms per step adds 0.1.
  float v = 0;
  v = slewStep(v, 1.0f, 25, 250);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.1f, v);
  for (int i = 0; i < 20; i++) v = slewStep(v, 1.0f, 25, 250);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 1.0f, v);  // capped at target
}

void test_slew_reverse_direction_ramps_negative() {
  float v = slewStep(0, -0.5f, 50, 250);
  TEST_ASSERT_FLOAT_WITHIN(kEps, -0.2f, v);
}

void test_slew_decelerates_instantly() {
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.3f, slewStep(0.9f, 0.3f, 1, 250));
  TEST_ASSERT_FLOAT_WITHIN(kEps, -0.3f, slewStep(-0.9f, -0.3f, 1, 250));
}

void test_slew_stop_is_instant() { TEST_ASSERT_EQUAL_FLOAT(0.0f, slewStep(1.0f, 0.0f, 0, 250)); }

void test_slew_reversal_restarts_from_zero() {
  TEST_ASSERT_FLOAT_WITHIN(kEps, -0.1f, slewStep(0.8f, -0.8f, 25, 250));
}

void test_slew_zero_disables_limit() { TEST_ASSERT_EQUAL_FLOAT(1.0f, slewStep(0, 1.0f, 0, 0)); }

void test_slew_zero_dt_holds() { TEST_ASSERT_FLOAT_WITHIN(kEps, 0.4f, slewStep(0.4f, 1.0f, 0, 250)); }

void test_bridge_starts_coast_and_enters_forward() {
  Bridge b;
  TEST_ASSERT_TRUE(b.mode() == Mode::Coast);
  BridgeCmd c = b.update(0.5f, true, 0);
  TEST_ASSERT_TRUE(c.mode == Mode::Forward);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.5f, c.duty);
}

void test_bridge_zero_follows_brake_flag() {
  Bridge b;
  TEST_ASSERT_TRUE(b.update(0, true, 0).mode == Mode::Brake);
  Bridge c;
  TEST_ASSERT_TRUE(c.update(0, false, 0).mode == Mode::Coast);
}

void test_bridge_dead_time_on_reversal() {
  Bridge b;
  b.update(0.5f, true, 100);
  BridgeCmd c = b.update(-0.5f, true, 101);
  TEST_ASSERT_TRUE(c.mode == Mode::Coast);
  TEST_ASSERT_EQUAL_FLOAT(0.0f, c.duty);
  TEST_ASSERT_TRUE(b.update(-0.5f, true, 102).mode == Mode::Coast);  // 1 ms elapsed
  c = b.update(-0.5f, true, 103);                                    // 2 ms elapsed
  TEST_ASSERT_TRUE(c.mode == Mode::Reverse);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.5f, c.duty);
}

void test_bridge_dead_time_forward_to_brake() {
  Bridge b;
  b.update(1.0f, true, 0);
  TEST_ASSERT_TRUE(b.update(0, true, 10).mode == Mode::Coast);
  TEST_ASSERT_TRUE(b.update(0, true, 12).mode == Mode::Brake);
}

void test_bridge_to_coast_is_immediate() {
  Bridge b;
  b.update(1.0f, false, 0);
  TEST_ASSERT_TRUE(b.update(0, false, 0).mode == Mode::Coast);
  TEST_ASSERT_TRUE(b.update(0.2f, false, 0).mode == Mode::Forward);  // from coast: immediate
}

void test_bridge_same_mode_updates_duty() {
  Bridge b;
  b.update(0.2f, true, 0);
  BridgeCmd c = b.update(0.7f, true, 1);
  TEST_ASSERT_TRUE(c.mode == Mode::Forward);
  TEST_ASSERT_FLOAT_WITHIN(kEps, 0.7f, c.duty);
}

void test_bridge_dead_time_across_millis_wrap() {
  Bridge b;
  b.update(0.5f, true, 0xFFFFFFFFu);
  TEST_ASSERT_TRUE(b.update(-0.5f, true, 0xFFFFFFFFu).mode == Mode::Coast);
  TEST_ASSERT_TRUE(b.update(-0.5f, true, 0).mode == Mode::Coast);
  TEST_ASSERT_TRUE(b.update(-0.5f, true, 1).mode == Mode::Reverse);
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_slew_ramps_up);
  RUN_TEST(test_slew_reverse_direction_ramps_negative);
  RUN_TEST(test_slew_decelerates_instantly);
  RUN_TEST(test_slew_stop_is_instant);
  RUN_TEST(test_slew_reversal_restarts_from_zero);
  RUN_TEST(test_slew_zero_disables_limit);
  RUN_TEST(test_slew_zero_dt_holds);
  RUN_TEST(test_bridge_starts_coast_and_enters_forward);
  RUN_TEST(test_bridge_zero_follows_brake_flag);
  RUN_TEST(test_bridge_dead_time_on_reversal);
  RUN_TEST(test_bridge_dead_time_forward_to_brake);
  RUN_TEST(test_bridge_to_coast_is_immediate);
  RUN_TEST(test_bridge_same_mode_updates_duty);
  RUN_TEST(test_bridge_dead_time_across_millis_wrap);
  return UNITY_END();
}
