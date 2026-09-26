// TELEMETRY at 10 Hz: VM measurement, per-second counters — PROTOCOL.md §4.3.
#pragma once

#include <controller.h>
#include <protocol.h>

struct TelemetryInputs {
  uint8_t last_seq;
  uint8_t flags;  // all TELEMETRY flag bits
  int8_t pwm[4];
  uint32_t crc_err;    // parser rejects since boot
  uint32_t rx_frames;  // valid frames since boot
};

class TelemetryReporter {
 public:
  void begin();

  // Duration of one loop() pass, µs.
  void recordLoop(uint32_t us);

  // True when a TELEMETRY frame is due (every 100 ms).
  bool due(uint32_t now_ms) const;
  void send(uint32_t now_ms, FrameSender& tx, const TelemetryInputs& in);

  // Last VM measurement, mV.
  uint16_t vmMv() const { return vm_mv_; }

 private:
  void measureVm();

  uint32_t last_send_ms_ = 0;
  uint32_t window_start_ms_ = 0;
  uint32_t window_rx_start_ = 0;
  uint16_t rx_per_s_ = 0;
  uint32_t loop_max_cur_ = 0;
  uint32_t loop_max_prev_ = 0;
  uint16_t vm_mv_ = 0;
};
