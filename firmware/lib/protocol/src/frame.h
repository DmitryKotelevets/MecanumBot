// USB frame format, CRC-8 and the receive parser — protocol/PROTOCOL.md §3.
// Pure C++: no Arduino dependencies, built by the native tests.
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace proto {

constexpr uint8_t kProtoVer = 1;
constexpr uint8_t kSync = 0xAA;
constexpr size_t kMaxPayload = 200;
constexpr size_t kOverhead = 5;  // sync, type, len, seq, crc
constexpr size_t kMaxFrame = kOverhead + kMaxPayload;

enum class Type : uint8_t {
  // phone -> ESP32
  Hello = 0x00,
  Drive = 0x01,
  MotorRaw = 0x02,
  Ping = 0x03,
  Config = 0x04,
  Stop = 0x05,
  GetConfig = 0x06,
  OtaBegin = 0x10,
  OtaData = 0x11,
  OtaEnd = 0x12,
  OtaAbort = 0x13,
  WifiOtaEnter = 0x20,
  WifiOtaExit = 0x21,
  Reboot = 0x7F,
  // ESP32 -> phone
  HelloAck = 0x80,
  Telemetry = 0x81,
  Ack = 0x82,
  Log = 0x83,
  Pong = 0x84,
  ConfigData = 0x85,
  WifiStatus = 0x86,
};

struct Frame {
  uint8_t type;
  uint8_t len;
  uint8_t seq;
  uint8_t payload[kMaxPayload];
};

// CRC-8/SMBUS: poly 0x07, init 0x00, no reflect, no xorout.
uint8_t crc8(const uint8_t* data, size_t n, uint8_t crc = 0x00);

// Header table shared by both directions (PROTOCOL.md §3.1, §4).
bool typeKnown(uint8_t type);
bool lenValid(uint8_t type, uint8_t len);

// Writes a complete frame to out. Returns its size, or 0 if the type/len is
// invalid or cap is too small.
size_t encodeFrame(uint8_t type, uint8_t seq, const uint8_t* payload, uint8_t len,
                   uint8_t* out, size_t cap);

// Stream parser with rescan-on-reject (PROTOCOL.md §3.1). Feeding one byte may
// deliver zero or several frames to the handler.
class Parser {
 public:
  // `frame` is reused for the next frame: copy it if needed past the call.
  using Handler = void (*)(const Frame& frame, void* ctx);

  Parser(Handler handler, void* ctx) : handler_(handler), ctx_(ctx) {}

  void feed(uint8_t byte);
  void feed(const uint8_t* data, size_t n);

  // Call after 50 ms without input: rejects a pending partial frame.
  void expireIdle();

  bool pending() const { return n_ > 0; }
  uint32_t errors() const { return errors_; }

 private:
  void process();
  void reject();
  void drop(size_t count);

  Handler handler_;
  void* ctx_;
  uint8_t buf_[kMaxFrame];
  size_t n_ = 0;
  uint32_t errors_ = 0;
  Frame frame_;
};

}  // namespace proto
