// DRV8833 outputs via LEDC, nFAULT and nSLEEP — DESIGN.md §4.5.
// One LEDC channel per motor (C6 has 6): PWM goes on IN1 (forward) or IN2
// (reverse), the other input is held LOW.
#pragma once

#include <motion.h>
#include <protocol.h>

class Motors {
 public:
  void begin(const proto::Config& config);
  // New pwm_hz / slew_ms / brake from CONFIG.
  void configure(const proto::Config& config);

  // target: signed duty −1..1 per physical channel. Applies slew and bridge
  // sequencing; call every loop().
  void update(const float target[4], uint32_t now_ms);

  // nSLEEP LOW (OTA modes) / HIGH.
  void setSleep(bool sleep);

  void pollFaults(uint32_t now_ms);
  bool faultA() const { return fault_a_; }
  bool faultB() const { return fault_b_; }

  // Actual output per physical channel, −127..127 (for TELEMETRY).
  int8_t pwm(int channel) const;

 private:
  void apply(int ch, const motion::BridgeCmd& cmd);
  void detach(int ch);

  motion::Bridge bridge_[4];
  motion::Mode applied_[4] = {motion::Mode::Coast, motion::Mode::Coast, motion::Mode::Coast,
                              motion::Mode::Coast};
  int8_t attached_[4] = {-1, -1, -1, -1};  // index of the pin carrying PWM, or −1
  float current_[4] = {0, 0, 0, 0};        // after slew
  uint32_t duty_[4] = {0, 0, 0, 0};
  uint32_t last_ms_ = 0;
  uint32_t last_fault_ms_ = 0;
  uint16_t pwm_hz_ = 20000;
  uint16_t slew_ms_ = 250;
  bool brake_ = true;
  bool fault_a_ = false;
  bool fault_b_ = false;
};
