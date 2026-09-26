// failsafe: timeout, reset by command, millis() overflow (PROTOCOL.md §5.2).
#include <failsafe.h>
#include <unity.h>

void setUp() {}
void tearDown() {}

void test_active_from_boot() {
  Failsafe fs(300);
  TEST_ASSERT_TRUE(fs.active());
  TEST_ASSERT_FALSE(fs.tick(0));  // already active: no new transition
  TEST_ASSERT_FALSE(fs.tick(10000));
  TEST_ASSERT_TRUE(fs.active());
}

void test_feed_clears() {
  Failsafe fs(300);
  fs.feed(1000);
  TEST_ASSERT_FALSE(fs.active());
  TEST_ASSERT_FALSE(fs.tick(1000));
}

void test_expires_at_timeout() {
  Failsafe fs(300);
  fs.feed(1000);
  TEST_ASSERT_FALSE(fs.tick(1299));
  TEST_ASSERT_FALSE(fs.active());
  TEST_ASSERT_TRUE(fs.tick(1300));
  TEST_ASSERT_TRUE(fs.active());
  TEST_ASSERT_FALSE(fs.tick(1301));  // transition reported once
}

void test_feed_extends() {
  Failsafe fs(300);
  fs.feed(0);
  for (uint32_t t = 25; t <= 5000; t += 25) {
    TEST_ASSERT_FALSE(fs.tick(t));
    fs.feed(t);
  }
  TEST_ASSERT_FALSE(fs.active());
}

void test_recovers_after_expiry() {
  Failsafe fs(300);
  fs.feed(0);
  TEST_ASSERT_TRUE(fs.tick(400));
  fs.feed(500);
  TEST_ASSERT_FALSE(fs.active());
  TEST_ASSERT_TRUE(fs.tick(800));
}

void test_millis_overflow() {
  Failsafe fs(300);
  const uint32_t start = 0xFFFFFF00u;  // 256 ms before wrap
  fs.feed(start);
  TEST_ASSERT_FALSE(fs.tick(start + 299));  // wraps to 0x2B
  TEST_ASSERT_FALSE(fs.active());
  TEST_ASSERT_TRUE(fs.tick(start + 300));
}

void test_feed_across_overflow() {
  Failsafe fs(300);
  fs.feed(0xFFFFFFF0u);
  fs.feed(0x00000010u);
  TEST_ASSERT_FALSE(fs.tick(0x00000010u + 299));
  TEST_ASSERT_TRUE(fs.tick(0x00000010u + 300));
}

void test_set_timeout() {
  Failsafe fs(300);
  fs.setTimeout(100);
  fs.feed(0);
  TEST_ASSERT_FALSE(fs.tick(99));
  TEST_ASSERT_TRUE(fs.tick(100));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_active_from_boot);
  RUN_TEST(test_feed_clears);
  RUN_TEST(test_expires_at_timeout);
  RUN_TEST(test_feed_extends);
  RUN_TEST(test_recovers_after_expiry);
  RUN_TEST(test_millis_overflow);
  RUN_TEST(test_feed_across_overflow);
  RUN_TEST(test_set_timeout);
  return UNITY_END();
}
