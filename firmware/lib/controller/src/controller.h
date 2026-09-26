// Frame dispatch, modes, failsafe and command state — PROTOCOL.md §5.
// Pure C++: hardware is reached only through the interfaces below.
#pragma once

#include <failsafe.h>
#include <protocol.h>
#include <stddef.h>
#include <stdint.h>

// Sends one frame; the implementation assigns seq. Returns false if dropped.
class FrameSender {
 public:
  virtual bool send(proto::Type type, const uint8_t* payload, size_t len) = 0;

 protected:
  ~FrameSender() = default;
};

class ConfigStore {
 public:
  virtual bool save(const proto::Config& config) = 0;

 protected:
  ~ConfigStore() = default;
};

struct BootInfo {
  uint8_t fw_major;
  uint8_t fw_minor;
  uint8_t reset_reason;
  uint16_t reset_count;
};

class Controller {
 public:
  enum class Mode : uint8_t { Drive, Raw };

  Controller(FrameSender& tx, ConfigStore& store) : tx_(tx), store_(store), failsafe_(300) {}

  void begin(const proto::Config& config, const BootInfo& boot);

  // Unsolicited HELLO_ACK; call when the host (re)connects.
  void announce() { sendHelloAck(); }

  void onFrame(const proto::Frame& frame, uint32_t now_ms);

  // Runs the failsafe timer. Returns true on the tick that enters failsafe.
  bool tick(uint32_t now_ms);

  // Target signed duty (−1..1) per physical channel, before slew.
  void output(float channels[4]) const;

  // TELEMETRY flag bits owned by the controller (failsafe, raw, enable).
  uint8_t telemetryFlags() const;

  void log(proto::LogLevel level, const char* text);

  const proto::Config& config() const { return config_; }
  Mode mode() const { return mode_; }
  bool failsafeActive() const { return failsafe_.active(); }
  uint8_t lastSeq() const { return last_seq_; }

  // Set when a new CONFIG was applied; cleared by the call.
  bool takeConfigChanged();
  bool rebootRequested() const { return reboot_; }

 private:
  void ack(const proto::Frame& frame, proto::AckStatus status);
  void sendHelloAck();
  void applyConfig(const proto::Config& config);

  FrameSender& tx_;
  ConfigStore& store_;
  Failsafe failsafe_;
  proto::Config config_{};
  BootInfo boot_{};
  Mode mode_ = Mode::Drive;
  proto::Drive drive_{};
  proto::MotorRaw raw_{};
  uint8_t last_seq_ = 0;
  bool config_changed_ = false;
  bool reboot_ = false;
};
