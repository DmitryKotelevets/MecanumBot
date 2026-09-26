// protocol: CRC, header table, frame codec and parser against protocol/vectors.json.
#include <protocol.h>
#include <string.h>
#include <unity.h>

#include "../vectors.h"

using namespace proto;

void setUp() {}
void tearDown() {}

// --- helpers -----------------------------------------------------------------

template <size_t N, typename T>
void fillArray(JsonArrayConst a, T (&dst)[N]) {
  TEST_ASSERT_EQUAL(N, a.size());
  for (size_t i = 0; i < N; i++) dst[i] = a[i].as<T>();
}

void fillHex(const char* hex, uint8_t* dst, size_t n) {
  auto v = unhex(hex);
  TEST_ASSERT_EQUAL(n, v.size());
  memcpy(dst, v.data(), n);
}

Config configFrom(JsonObjectConst f) {
  Config c{};
  c.version = f["version"];
  fillArray(f["map"], c.map);
  fillArray(f["invert"], c.invert);
  c.max_duty = f["max_duty"];
  c.slew_ms = f["slew_ms"];
  fillArray(f["trim"], c.trim);
  c.brake = f["brake"];
  c.min_duty = f["min_duty"];
  c.failsafe_ms = f["failsafe_ms"];
  c.pwm_hz = f["pwm_hz"];
  return c;
}

// Encodes `msg`, checks it against the expected payload, then decodes the
// expected payload and checks that it re-encodes to the same bytes.
template <typename T>
void roundTrip(const T& msg, const std::vector<uint8_t>& expected, const char* name) {
  uint8_t buf[kMaxPayload];
  size_t n = encode(msg, buf);
  TEST_ASSERT_EQUAL_STRING_MESSAGE(tohex(expected.data(), expected.size()).c_str(),
                                   tohex(buf, n).c_str(), name);
  T decoded{};
  TEST_ASSERT_TRUE_MESSAGE(decode(expected.data(), expected.size(), decoded), name);
  n = encode(decoded, buf);
  TEST_ASSERT_EQUAL_STRING_MESSAGE(tohex(expected.data(), expected.size()).c_str(),
                                   tohex(buf, n).c_str(), name);
}

// Payload of a vector, checked per type. Returns false for zero-length types.
void checkPayload(JsonObjectConst v, const std::vector<uint8_t>& payload) {
  const char* name = v["name"];
  const std::string t = v["type_name"].as<const char*>();
  JsonObjectConst f = v["fields"];

  if (t == "HELLO") {
    roundTrip(Hello{f["proto_ver"], f["app_major"], f["app_minor"]}, payload, name);
  } else if (t == "DRIVE") {
    roundTrip(Drive{f["flags"], f["vx"], f["vy"], f["w"]}, payload, name);
  } else if (t == "MOTOR_RAW") {
    MotorRaw m{};
    fillArray(f["m"], m.m);
    roundTrip(m, payload, name);
  } else if (t == "PING" || t == "PONG") {
    roundTrip(Ping{f["ts"]}, payload, name);
  } else if (t == "CONFIG" || t == "CONFIG_DATA") {
    roundTrip(configFrom(f), payload, name);
  } else if (t == "OTA_BEGIN") {
    OtaBegin m{};
    m.size = f["size"];
    fillHex(f["sha256"], m.sha256, 32);
    roundTrip(m, payload, name);
  } else if (t == "OTA_DATA") {
    auto data = unhex(f["data"]);
    roundTrip(OtaData{f["offset"], data.data(), static_cast<uint8_t>(data.size())}, payload, name);
  } else if (t == "WIFI_OTA_ENTER") {
    WifiOtaEnter m{};
    strncpy(m.ssid, f["ssid"], kMaxSsid);
    strncpy(m.pass, f["pass"], kMaxPass);
    roundTrip(m, payload, name);
  } else if (t == "HELLO_ACK") {
    roundTrip(HelloAck{f["proto_ver"], f["fw_major"], f["fw_minor"], f["reset_reason"], f["reset_count"]},
              payload, name);
  } else if (t == "TELEMETRY") {
    Telemetry m{};
    m.last_seq = f["last_seq"];
    m.flags = f["flags"];
    fillArray(f["pwm"], m.pwm);
    m.vm_mv = f["vm_mv"];
    m.crc_err = f["crc_err"];
    m.rx_frames = f["rx_frames"];
    m.uptime_s = f["uptime_s"];
    m.fw_major = f["fw_major"];
    m.fw_minor = f["fw_minor"];
    m.loop_max_us = f["loop_max_us"];
    roundTrip(m, payload, name);
  } else if (t == "ACK") {
    roundTrip(Ack{f["req_type"], f["req_seq"], f["status"]}, payload, name);
  } else if (t == "LOG") {
    const char* text = f["text"];
    roundTrip(Log{f["level"], text, static_cast<uint8_t>(strlen(text))}, payload, name);
  } else if (t == "WIFI_STATUS") {
    WifiStatus m{};
    m.state = f["state"];
    fillArray(f["ip"], m.ip);
    roundTrip(m, payload, name);
  } else {
    // STOP, GET_CONFIG, OTA_END, OTA_ABORT, WIFI_OTA_EXIT, REBOOT
    TEST_ASSERT_EQUAL_MESSAGE(0, payload.size(), name);
  }
}

struct Collected {
  std::vector<Frame> frames;
};

void collect(const Frame& f, void* ctx) { static_cast<Collected*>(ctx)->frames.push_back(f); }

// --- tests -------------------------------------------------------------------

void test_proto_ver() { TEST_ASSERT_EQUAL(vectors()["proto_ver"].as<int>(), kProtoVer); }

void test_crc_vectors() {
  for (JsonObjectConst v : vectors()["crc8"]["vectors"].as<JsonArrayConst>()) {
    auto data = unhex(v["data_hex"]);
    TEST_ASSERT_EQUAL_HEX8_MESSAGE(v["crc"].as<uint8_t>(), crc8(data.data(), data.size()),
                                   v["name"].as<const char*>());
  }
}

void test_crc_incremental() {
  const uint8_t s[] = "123456789";
  TEST_ASSERT_EQUAL_HEX8(0xF4, crc8(s + 4, 5, crc8(s, 4)));
}

void test_type_table() {
  bool listed[256] = {};
  for (JsonObjectConst t : vectors()["types"].as<JsonArrayConst>()) {
    const uint8_t type = t["type"];
    const int lo = t["min_len"], hi = t["max_len"];
    listed[type] = true;
    TEST_ASSERT_TRUE(typeKnown(type));
    TEST_ASSERT_TRUE(lenValid(type, lo));
    TEST_ASSERT_TRUE(lenValid(type, hi));
    if (lo > 0) TEST_ASSERT_FALSE(lenValid(type, lo - 1));
    if (hi < 255) TEST_ASSERT_FALSE(lenValid(type, hi + 1));
  }
  for (int t = 0; t < 256; t++) {
    if (!listed[t]) TEST_ASSERT_FALSE_MESSAGE(typeKnown(t), "type not in vectors.json is known");
  }
}

void test_frame_vectors() {
  for (JsonObjectConst v : vectors()["frames"].as<JsonArrayConst>()) {
    const char* name = v["name"];
    const uint8_t type = v["type"], seq = v["seq"];
    auto payload = unhex(v["payload_hex"]);
    auto frame = unhex(v["frame_hex"]);

    checkPayload(v, payload);

    uint8_t out[kMaxFrame];
    size_t n = encodeFrame(type, seq, payload.data(), payload.size(), out, sizeof out);
    TEST_ASSERT_EQUAL_STRING_MESSAGE(v["frame_hex"].as<const char*>(), tohex(out, n).c_str(), name);

    Collected c;
    Parser p(collect, &c);
    p.feed(frame.data(), frame.size());
    TEST_ASSERT_EQUAL_MESSAGE(1, c.frames.size(), name);
    TEST_ASSERT_EQUAL_MESSAGE(0, p.errors(), name);
    TEST_ASSERT_EQUAL_MESSAGE(type, c.frames[0].type, name);
    TEST_ASSERT_EQUAL_MESSAGE(seq, c.frames[0].seq, name);
    TEST_ASSERT_EQUAL_STRING_MESSAGE(v["payload_hex"].as<const char*>(),
                                     tohex(c.frames[0].payload, c.frames[0].len).c_str(), name);
  }
}

void test_stream_vectors() {
  for (JsonObjectConst v : vectors()["streams"].as<JsonArrayConst>()) {
    const char* name = v["name"];
    Collected c;
    Parser p(collect, &c);
    for (JsonVariantConst chunk : v["chunks"].as<JsonArrayConst>()) {
      auto bytes = unhex(chunk.as<const char*>());
      p.feed(bytes.data(), bytes.size());
    }
    JsonArrayConst expected = v["expected_frames"];
    TEST_ASSERT_EQUAL_MESSAGE(expected.size(), c.frames.size(), name);
    TEST_ASSERT_EQUAL_MESSAGE(v["expected_crc_err"].as<uint32_t>(), p.errors(), name);
    for (size_t i = 0; i < expected.size(); i++) {
      TEST_ASSERT_EQUAL_MESSAGE(expected[i]["type"].as<uint8_t>(), c.frames[i].type, name);
      TEST_ASSERT_EQUAL_MESSAGE(expected[i]["seq"].as<uint8_t>(), c.frames[i].seq, name);
      TEST_ASSERT_EQUAL_STRING_MESSAGE(expected[i]["payload_hex"].as<const char*>(),
                                       tohex(c.frames[i].payload, c.frames[i].len).c_str(), name);
    }
  }
}

void test_encode_frame_rejects_invalid() {
  uint8_t out[kMaxFrame];
  const uint8_t payload[5] = {};
  TEST_ASSERT_EQUAL(0, encodeFrame(0x01, 0, payload, 5, out, sizeof out));  // DRIVE len 5
  TEST_ASSERT_EQUAL(0, encodeFrame(0x55, 0, payload, 0, out, sizeof out));  // unknown type
  TEST_ASSERT_EQUAL(0, encodeFrame(0x01, 0, payload, 4, out, 8));           // no room
  TEST_ASSERT_EQUAL(9, encodeFrame(0x01, 0, payload, 4, out, 9));
}

void test_idle_expiry_recovers_partial_frame() {
  // A stray 0xAA + DRIVE header waits for 4 payload bytes that never come.
  Collected c;
  Parser p(collect, &c);
  const uint8_t partial[] = {0xAA, 0x01, 0x04, 0x00};
  p.feed(partial, sizeof partial);
  TEST_ASSERT_TRUE(p.pending());
  p.expireIdle();
  TEST_ASSERT_FALSE(p.pending());
  TEST_ASSERT_EQUAL(1, p.errors());
  TEST_ASSERT_EQUAL(0, c.frames.size());
}

void test_decode_rejects_bad_lengths() {
  const uint8_t buf[40] = {};
  Drive d;
  TEST_ASSERT_FALSE(decode(buf, 3, d));
  Config cfg;
  TEST_ASSERT_FALSE(decode(buf, 21, cfg));
  OtaData od;
  TEST_ASSERT_FALSE(decode(buf, 4, od));
  WifiOtaEnter we;
  const uint8_t ssid_overrun[] = {5, 'a', 'b'};
  TEST_ASSERT_FALSE(decode(ssid_overrun, sizeof ssid_overrun, we));
  const uint8_t pass_trailing[] = {1, 'a', 1, 'b', 'x'};
  TEST_ASSERT_FALSE(decode(pass_trailing, sizeof pass_trailing, we));
  const uint8_t ssid_too_long[] = {33};
  TEST_ASSERT_FALSE(decode(ssid_too_long, sizeof ssid_too_long, we));
}

void test_drive_flag_accessors() {
  Drive d{static_cast<uint8_t>(drive_flags::kEnable | (2 << drive_flags::kSourceShift)), 0, 0, 0};
  TEST_ASSERT_TRUE(d.enable());
  TEST_ASSERT_EQUAL(static_cast<uint8_t>(Source::Remote), d.source());
  d.flags = 1 << drive_flags::kSourceShift;
  TEST_ASSERT_FALSE(d.enable());
  TEST_ASSERT_EQUAL(static_cast<uint8_t>(Source::LocalPad), d.source());
}

void test_config_validation() {
  const Config def = defaultConfig();
  TEST_ASSERT_TRUE(validate(def));

  // The defaults match the config_defaults vector.
  for (JsonObjectConst v : vectors()["frames"].as<JsonArrayConst>()) {
    if (std::string(v["name"].as<const char*>()) != "config_defaults") continue;
    uint8_t buf[kConfigSize];
    encode(def, buf);
    TEST_ASSERT_EQUAL_STRING(v["payload_hex"].as<const char*>(), tohex(buf, kConfigSize).c_str());
  }

  Config c = def;
  c.version = 2;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.map[3] = 0;  // not a permutation
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.map[0] = 4;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.invert[1] = 2;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.trim[2] = 49;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.max_duty = 101;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.min_duty = c.max_duty;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.slew_ms = 2001;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.brake = 2;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.failsafe_ms = 99;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.pwm_hz = 30001;
  TEST_ASSERT_FALSE(validate(c));
  c = def;
  c.map[0] = 3;
  c.map[3] = 0;
  c.trim[0] = 50;
  c.slew_ms = 0;
  TEST_ASSERT_TRUE(validate(c));
}

int main() {
  UNITY_BEGIN();
  RUN_TEST(test_proto_ver);
  RUN_TEST(test_crc_vectors);
  RUN_TEST(test_crc_incremental);
  RUN_TEST(test_type_table);
  RUN_TEST(test_frame_vectors);
  RUN_TEST(test_stream_vectors);
  RUN_TEST(test_encode_frame_rejects_invalid);
  RUN_TEST(test_idle_expiry_recovers_partial_frame);
  RUN_TEST(test_decode_rejects_bad_lengths);
  RUN_TEST(test_drive_flag_accessors);
  RUN_TEST(test_config_validation);
  return UNITY_END();
}
