// Payload layouts for every frame type — protocol/PROTOCOL.md §4.
// encode() returns the payload length (0 = does not fit the spec); decode()
// returns false if the payload is malformed. Decoded values are raw wire values:
// clamping (e.g. i8 −128 → −127) is the command handler's job.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace proto {

enum class Source : uint8_t { Test = 0, LocalPad = 1, Remote = 2 };

namespace drive_flags {
constexpr uint8_t kEnable = 0x01;
constexpr uint8_t kSourceShift = 1;
constexpr uint8_t kSourceMask = 0x06;
}  // namespace drive_flags

namespace telemetry_flags {
constexpr uint8_t kFailsafe = 0x01;
constexpr uint8_t kFaultA = 0x02;
constexpr uint8_t kFaultB = 0x04;
constexpr uint8_t kRawMode = 0x08;
constexpr uint8_t kOtaMode = 0x10;
constexpr uint8_t kWifiMode = 0x20;
constexpr uint8_t kEnable = 0x40;
}  // namespace telemetry_flags

enum class AckStatus : uint8_t { Ok = 0, Err = 1, Busy = 2 };
enum class LogLevel : uint8_t { Error = 0, Warn = 1, Info = 2, Debug = 3 };
enum class WifiState : uint8_t { Off = 0, Connecting = 1, Connected = 2, Error = 3 };

constexpr size_t kMaxSsid = 32;
constexpr size_t kMaxPass = 63;
constexpr size_t kMaxOtaChunk = 192;
constexpr size_t kMaxLogText = 199;

struct Hello {
  uint8_t proto_ver, app_major, app_minor;
};

struct Drive {
  uint8_t flags;
  int8_t vx, vy, w;
  bool enable() const { return flags & drive_flags::kEnable; }
  uint8_t source() const { return (flags & drive_flags::kSourceMask) >> drive_flags::kSourceShift; }
};

struct MotorRaw {
  int8_t m[4];  // physical channels M1..M4
};

struct Ping {
  uint32_t ts;
};
using Pong = Ping;

struct Config {
  uint8_t version;
  uint8_t map[4];     // map[wheel] = physical channel; wheels FL, FR, RL, RR
  uint8_t invert[4];  // per wheel
  uint8_t max_duty;   // %
  uint16_t slew_ms;
  uint8_t trim[4];  // per wheel, %
  uint8_t brake;
  uint8_t min_duty;  // %
  uint16_t failsafe_ms;
  uint16_t pwm_hz;
};
constexpr size_t kConfigSize = 22;

Config defaultConfig();
// Range checks from PROTOCOL.md §4.4.
bool validate(const Config& c);

struct OtaBegin {
  uint32_t size;
  uint8_t sha256[32];
};

struct OtaData {
  uint32_t offset;
  const uint8_t* data;  // points into the decoded payload
  uint8_t len;
};

struct WifiOtaEnter {
  char ssid[kMaxSsid + 1];  // NUL-terminated after decode
  char pass[kMaxPass + 1];
};

struct HelloAck {
  uint8_t proto_ver, fw_major, fw_minor, reset_reason;
  uint16_t reset_count;
};

struct Telemetry {
  uint8_t last_seq;
  uint8_t flags;
  int8_t pwm[4];
  uint16_t vm_mv;
  uint16_t crc_err;
  uint16_t rx_frames;
  uint32_t uptime_s;
  uint8_t fw_major, fw_minor;
  uint16_t loop_max_us;
};

struct Ack {
  uint8_t req_type, req_seq, status;
};

struct Log {
  uint8_t level;
  const char* text;  // UTF-8, not NUL-terminated
  uint8_t len;
};

struct WifiStatus {
  uint8_t state;
  uint8_t ip[4];
};

size_t encode(const Hello& m, uint8_t* out);
size_t encode(const Drive& m, uint8_t* out);
size_t encode(const MotorRaw& m, uint8_t* out);
size_t encode(const Ping& m, uint8_t* out);
size_t encode(const Config& m, uint8_t* out);
size_t encode(const OtaBegin& m, uint8_t* out);
size_t encode(const OtaData& m, uint8_t* out);
size_t encode(const WifiOtaEnter& m, uint8_t* out);
size_t encode(const HelloAck& m, uint8_t* out);
size_t encode(const Telemetry& m, uint8_t* out);
size_t encode(const Ack& m, uint8_t* out);
size_t encode(const Log& m, uint8_t* out);
size_t encode(const WifiStatus& m, uint8_t* out);

bool decode(const uint8_t* p, size_t len, Hello& out);
bool decode(const uint8_t* p, size_t len, Drive& out);
bool decode(const uint8_t* p, size_t len, MotorRaw& out);
bool decode(const uint8_t* p, size_t len, Ping& out);
bool decode(const uint8_t* p, size_t len, Config& out);
bool decode(const uint8_t* p, size_t len, OtaBegin& out);
bool decode(const uint8_t* p, size_t len, OtaData& out);
bool decode(const uint8_t* p, size_t len, WifiOtaEnter& out);
bool decode(const uint8_t* p, size_t len, HelloAck& out);
bool decode(const uint8_t* p, size_t len, Telemetry& out);
bool decode(const uint8_t* p, size_t len, Ack& out);
bool decode(const uint8_t* p, size_t len, Log& out);
bool decode(const uint8_t* p, size_t len, WifiStatus& out);

}  // namespace proto
