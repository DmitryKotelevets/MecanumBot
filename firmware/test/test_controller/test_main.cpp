// controller: frame handling, modes, failsafe, ACKs (PROTOCOL.md §5).
#include <controller.h>
#include <string.h>
#include <unity.h>

#include <vector>

using namespace proto;

struct Sent {
  Type type;
  std::vector<uint8_t> payload;
};

struct FakeTx : FrameSender {
  std::vector<Sent> sent;
  bool send(Type type, const uint8_t* payload, size_t len) override {
    sent.push_back({type, std::vector<uint8_t>(payload, payload + len)});
    return true;
  }
  const Sent& last() const { return sent.back(); }
};

struct FakeStore : ConfigStore {
  bool ok = true;
  int saves = 0;
  Config saved{};
  bool save(const Config& c) override {
    saves++;
    saved = c;
    return ok;
  }
};

FakeTx tx;
FakeStore store;
Controller* ctl;

const BootInfo kBoot{0, 1, 1, 7};

void setUp() {
  tx = FakeTx{};
  store = FakeStore{};
  static Controller* c = nullptr;
  delete c;
  c = new Controller(tx, store);
  ctl = c;
  ctl->begin(defaultConfig(), kBoot);
}
void tearDown() {}

template <typename T>
Frame frame(Type type, uint8_t seq, const T& msg) {
  Frame f{};
  f.type = static_cast<uint8_t>(type);
  f.seq = seq;
  f.len = static_cast<uint8_t>(encode(msg, f.payload));
  return f;
}

Frame empty(Type type, uint8_t seq) {
  Frame f{};
  f.type = static_cast<uint8_t>(type);
  f.seq = seq;
  return f;
}

Frame driveFrame(uint8_t seq, int8_t vx, int8_t vy, int8_t w, bool enable = true) {
  return frame(Type::Drive, seq, Drive{static_cast<uint8_t>(enable ? 1 : 0), vx, vy, w});
}

Ack lastAck() {
  TEST_ASSERT_TRUE(tx.last().type == Type::Ack);
  Ack a;
  TEST_ASSERT_TRUE(decode(tx.last().payload.data(), tx.last().payload.size(), a));
  return a;
}

void expectOutput(float a, float b, float c, float d) {
  float ch[4];
  ctl->output(ch);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, a, ch[0]);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, b, ch[1]);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, c, ch[2]);
  TEST_ASSERT_FLOAT_WITHIN(1e-4f, d, ch[3]);
}

void test_announce_sends_hello_ack() {
  TEST_ASSERT_EQUAL(0, tx.sent.size());  // nothing before the host connects
  ctl->announce();
  TEST_ASSERT_EQUAL(1, tx.sent.size());
  TEST_ASSERT_TRUE(tx.last().type == Type::HelloAck);
  HelloAck h;
  TEST_ASSERT_TRUE(decode(tx.last().payload.data(), tx.last().payload.size(), h));
  TEST_ASSERT_EQUAL(kProtoVer, h.proto_ver);
  TEST_ASSERT_EQUAL(1, h.fw_minor);
  TEST_ASSERT_EQUAL(1, h.reset_reason);
  TEST_ASSERT_EQUAL(7, h.reset_count);
}

void test_failsafe_active_at_boot() {
  TEST_ASSERT_TRUE(ctl->failsafeActive());
  TEST_ASSERT_BITS_HIGH(telemetry_flags::kFailsafe, ctl->telemetryFlags());
  expectOutput(0, 0, 0, 0);
}

void test_hello_answered() {
  ctl->onFrame(frame(Type::Hello, 0, Hello{kProtoVer, 0, 1}), 0);
  TEST_ASSERT_TRUE(tx.last().type == Type::HelloAck);
}

void test_ping_pong_echoes_ts() {
  ctl->onFrame(frame(Type::Ping, 3, Ping{0xDEADBEEF}), 0);
  Ping p;
  TEST_ASSERT_TRUE(tx.last().type == Type::Pong);
  TEST_ASSERT_TRUE(decode(tx.last().payload.data(), 4, p));
  TEST_ASSERT_EQUAL_HEX32(0xDEADBEEF, p.ts);
}

void test_drive_forward_full() {
  ctl->onFrame(driveFrame(5, 0, 127, 0), 1000);
  TEST_ASSERT_FALSE(ctl->failsafeActive());
  TEST_ASSERT_EQUAL(5, ctl->lastSeq());
  TEST_ASSERT_BITS_HIGH(telemetry_flags::kEnable, ctl->telemetryFlags());
  expectOutput(1, 1, 1, 1);  // default max_duty 100
}

void test_drive_disabled_is_heartbeat() {
  ctl->onFrame(driveFrame(1, 0, 127, 0, false), 1000);
  TEST_ASSERT_FALSE(ctl->failsafeActive());
  TEST_ASSERT_BITS_LOW(telemetry_flags::kEnable, ctl->telemetryFlags());
  expectOutput(0, 0, 0, 0);
}

void test_failsafe_after_timeout() {
  ctl->onFrame(driveFrame(1, 0, 127, 0), 1000);
  TEST_ASSERT_FALSE(ctl->tick(1299));
  TEST_ASSERT_TRUE(ctl->tick(1300));
  TEST_ASSERT_TRUE(ctl->failsafeActive());
  TEST_ASSERT_BITS_LOW(telemetry_flags::kEnable, ctl->telemetryFlags());
  expectOutput(0, 0, 0, 0);
  ctl->onFrame(driveFrame(2, 0, 127, 0), 1400);  // recovers on next DRIVE
  TEST_ASSERT_FALSE(ctl->failsafeActive());
  expectOutput(1, 1, 1, 1);
}

void test_other_frames_do_not_feed_failsafe() {
  ctl->onFrame(driveFrame(1, 0, 64, 0), 0);
  ctl->onFrame(frame(Type::Ping, 2, Ping{1}), 250);
  ctl->onFrame(empty(Type::GetConfig, 3), 250);
  TEST_ASSERT_TRUE(ctl->tick(300));
}

void test_motor_raw_bypasses_calibration() {
  Config c = defaultConfig();
  c.map[0] = 1;
  c.map[1] = 0;
  c.invert[2] = 1;
  c.max_duty = 50;
  ctl->onFrame(frame(Type::Config, 1, c), 0);
  ctl->onFrame(frame(Type::MotorRaw, 2, MotorRaw{{127, -127, 127, 0}}), 0);
  TEST_ASSERT_TRUE(ctl->mode() == Controller::Mode::Raw);
  TEST_ASSERT_BITS_HIGH(telemetry_flags::kRawMode, ctl->telemetryFlags());
  TEST_ASSERT_FALSE(ctl->failsafeActive());
  expectOutput(0.5f, -0.5f, 0.5f, 0);  // physical channels, capped by max_duty only
}

void test_drive_leaves_raw_mode() {
  ctl->onFrame(frame(Type::MotorRaw, 1, MotorRaw{{127, 0, 0, 0}}), 0);
  ctl->onFrame(driveFrame(2, 0, 0, 0), 10);
  TEST_ASSERT_TRUE(ctl->mode() == Controller::Mode::Drive);
  TEST_ASSERT_BITS_LOW(telemetry_flags::kRawMode, ctl->telemetryFlags());
}

void test_stop_zeroes_and_acks() {
  ctl->onFrame(frame(Type::MotorRaw, 1, MotorRaw{{127, 127, 127, 127}}), 0);
  ctl->onFrame(empty(Type::Stop, 9), 10);
  Ack a = lastAck();
  TEST_ASSERT_EQUAL(0x05, a.req_type);
  TEST_ASSERT_EQUAL(9, a.req_seq);
  TEST_ASSERT_EQUAL(0, a.status);
  TEST_ASSERT_TRUE(ctl->mode() == Controller::Mode::Drive);
  expectOutput(0, 0, 0, 0);
  ctl->onFrame(driveFrame(10, 0, 127, 0), 20);  // next DRIVE resumes
  expectOutput(1, 1, 1, 1);
}

void test_drive_minus_128_clamped() {
  ctl->onFrame(driveFrame(1, 0, -128, 0), 0);
  expectOutput(-1, -1, -1, -1);
}

void test_config_valid_saved_and_applied() {
  Config c = defaultConfig();
  c.failsafe_ms = 100;
  c.max_duty = 80;
  c.min_duty = 0;
  ctl->onFrame(frame(Type::Config, 4, c), 0);
  Ack a = lastAck();
  TEST_ASSERT_EQUAL(0x04, a.req_type);
  TEST_ASSERT_EQUAL(4, a.req_seq);
  TEST_ASSERT_EQUAL(0, a.status);
  TEST_ASSERT_EQUAL(1, store.saves);
  TEST_ASSERT_EQUAL(100, store.saved.failsafe_ms);
  TEST_ASSERT_TRUE(ctl->takeConfigChanged());
  TEST_ASSERT_FALSE(ctl->takeConfigChanged());
  ctl->onFrame(driveFrame(5, 0, 127, 0), 1000);
  expectOutput(0.8f, 0.8f, 0.8f, 0.8f);
  TEST_ASSERT_TRUE(ctl->tick(1100));  // new failsafe timeout in effect
}

void test_config_invalid_rejected() {
  Config c = defaultConfig();
  c.map[1] = 0;
  ctl->onFrame(frame(Type::Config, 4, c), 0);
  TEST_ASSERT_EQUAL(1, lastAck().status);
  TEST_ASSERT_EQUAL(0, store.saves);
  TEST_ASSERT_FALSE(ctl->takeConfigChanged());
  TEST_ASSERT_EQUAL(1, ctl->config().map[1]);  // unchanged
}

void test_config_store_failure_not_applied() {
  store.ok = false;
  Config c = defaultConfig();
  c.max_duty = 50;
  ctl->onFrame(frame(Type::Config, 4, c), 0);
  TEST_ASSERT_EQUAL(1, lastAck().status);
  TEST_ASSERT_EQUAL(100, ctl->config().max_duty);
}

void test_get_config_returns_current() {
  ctl->onFrame(empty(Type::GetConfig, 1), 0);
  TEST_ASSERT_TRUE(tx.last().type == Type::ConfigData);
  Config c;
  TEST_ASSERT_TRUE(decode(tx.last().payload.data(), tx.last().payload.size(), c));
  TEST_ASSERT_EQUAL(250, c.slew_ms);
  TEST_ASSERT_EQUAL(20000, c.pwm_hz);
}

void test_ota_not_implemented_yet() {
  ctl->onFrame(empty(Type::OtaEnd, 3), 0);
  Ack a = lastAck();
  TEST_ASSERT_EQUAL(0x12, a.req_type);
  TEST_ASSERT_EQUAL(1, a.status);
}

void test_reboot_requested() {
  TEST_ASSERT_FALSE(ctl->rebootRequested());
  ctl->onFrame(empty(Type::Reboot, 1), 0);
  TEST_ASSERT_TRUE(ctl->rebootRequested());
}

void test_esp_to_phone_types_ignored() {
  const size_t before = tx.sent.size();
  ctl->onFrame(frame(Type::Log, 1, Log{2, "x", 1}), 0);
  TEST_ASSERT_EQUAL(before, tx.sent.size());
  TEST_ASSERT_TRUE(ctl->failsafeActive());
}

void test_log_truncates() {
  char big[300];
  memset(big, 'a', sizeof big - 1);
  big[sizeof big - 1] = '\0';
  ctl->log(LogLevel::Info, big);
  TEST_ASSERT_TRUE(tx.last().type == Type::Log);
  TEST_ASSERT_EQUAL(200, tx.last().payload.size());
  TEST_ASSERT_TRUE(lenValid(0x83, tx.last().payload.size()));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_announce_sends_hello_ack);
  RUN_TEST(test_failsafe_active_at_boot);
  RUN_TEST(test_hello_answered);
  RUN_TEST(test_ping_pong_echoes_ts);
  RUN_TEST(test_drive_forward_full);
  RUN_TEST(test_drive_disabled_is_heartbeat);
  RUN_TEST(test_failsafe_after_timeout);
  RUN_TEST(test_other_frames_do_not_feed_failsafe);
  RUN_TEST(test_motor_raw_bypasses_calibration);
  RUN_TEST(test_drive_leaves_raw_mode);
  RUN_TEST(test_stop_zeroes_and_acks);
  RUN_TEST(test_drive_minus_128_clamped);
  RUN_TEST(test_config_valid_saved_and_applied);
  RUN_TEST(test_config_invalid_rejected);
  RUN_TEST(test_config_store_failure_not_applied);
  RUN_TEST(test_get_config_returns_current);
  RUN_TEST(test_ota_not_implemented_yet);
  RUN_TEST(test_reboot_requested);
  RUN_TEST(test_esp_to_phone_types_ignored);
  RUN_TEST(test_log_truncates);
  return UNITY_END();
}
