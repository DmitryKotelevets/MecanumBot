#include "telemetry.h"

#include <Arduino.h>

#include "config.h"

namespace {
uint16_t sat16(uint32_t v) { return v > 0xFFFF ? 0xFFFF : static_cast<uint16_t>(v); }
}  // namespace

void TelemetryReporter::begin() {
  analogSetPinAttenuation(cfg::kPinVm, ADC_11db);
  window_start_ms_ = millis();
  measureVm();
}

void TelemetryReporter::recordLoop(uint32_t us) {
  if (us > loop_max_cur_) loop_max_cur_ = us;
}

bool TelemetryReporter::due(uint32_t now_ms) const {
  return now_ms - last_send_ms_ >= cfg::kTelemetryPeriodMs;
}

void TelemetryReporter::send(uint32_t now_ms, FrameSender& tx, const TelemetryInputs& in) {
  last_send_ms_ = now_ms;
  if (now_ms - window_start_ms_ >= 1000) {
    rx_per_s_ = sat16(in.rx_frames - window_rx_start_);
    window_rx_start_ = in.rx_frames;
    loop_max_prev_ = loop_max_cur_;
    loop_max_cur_ = 0;
    window_start_ms_ = now_ms;
  }
  measureVm();

  proto::Telemetry t{};
  t.last_seq = in.last_seq;
  t.flags = in.flags;
  for (int i = 0; i < 4; i++) t.pwm[i] = in.pwm[i];
  t.vm_mv = vm_mv_;
  t.crc_err = sat16(in.crc_err);
  t.rx_frames = rx_per_s_;
  t.uptime_s = now_ms / 1000;
  t.fw_major = FW_MAJOR;
  t.fw_minor = FW_MINOR;
  t.loop_max_us = sat16(loop_max_prev_ > loop_max_cur_ ? loop_max_prev_ : loop_max_cur_);

  uint8_t buf[20];
  tx.send(proto::Type::Telemetry, buf, proto::encode(t, buf));
}

void TelemetryReporter::measureVm() {
  uint32_t sum = 0;
  for (int i = 0; i < 4; i++) sum += analogReadMilliVolts(cfg::kPinVm);
  vm_mv_ = sat16(static_cast<uint32_t>(sum / 4 * cfg::kVmDividerRatio));
}
