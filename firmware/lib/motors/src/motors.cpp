#include "motors.h"

#include <Arduino.h>
#include <kinematics.h>
#include <math.h>

#include "config.h"

using motion::BridgeCmd;
using motion::Mode;

namespace {
constexpr uint32_t kMaxDuty = (1u << cfg::kPwmResolutionBits) - 1;
}

void Motors::begin(const proto::Config& config) {
  for (const auto& pins : cfg::kMotorPins) {
    for (uint8_t pin : pins) {
      pinMode(pin, OUTPUT);
      digitalWrite(pin, LOW);
    }
  }
  pinMode(cfg::kPinFaultA, INPUT_PULLUP);
  pinMode(cfg::kPinFaultB, INPUT_PULLUP);
  pinMode(cfg::kPinSleep, OUTPUT);
  configure(config);
  last_ms_ = millis();
  setSleep(false);
}

void Motors::configure(const proto::Config& config) {
  slew_ms_ = config.slew_ms;
  brake_ = config.brake;
  if (config.pwm_hz != pwm_hz_) {
    pwm_hz_ = config.pwm_hz;
    for (int ch = 0; ch < 4; ch++) {
      if (attached_[ch] >= 0) {
        ledcChangeFrequency(cfg::kMotorPins[ch][attached_[ch]], pwm_hz_, cfg::kPwmResolutionBits);
      }
    }
  }
}

void Motors::update(const float target[4], uint32_t now_ms) {
  const uint32_t dt = now_ms - last_ms_;
  last_ms_ = now_ms;
  for (int ch = 0; ch < 4; ch++) {
    current_[ch] = motion::slewStep(current_[ch], target[ch], dt, slew_ms_);
    apply(ch, bridge_[ch].update(current_[ch], brake_, now_ms));
  }
}

void Motors::setSleep(bool sleep) { digitalWrite(cfg::kPinSleep, sleep ? LOW : HIGH); }

void Motors::pollFaults(uint32_t now_ms) {
  if (now_ms - last_fault_ms_ < cfg::kFaultPollMs) return;
  last_fault_ms_ = now_ms;
  // Reported only: the DRV8833 protects itself (DESIGN.md §4.5).
  fault_a_ = digitalRead(cfg::kPinFaultA) == LOW;
  fault_b_ = digitalRead(cfg::kPinFaultB) == LOW;
}

int8_t Motors::pwm(int ch) const {
  const float v = static_cast<float>(duty_[ch]) / kMaxDuty;
  if (applied_[ch] == Mode::Forward) return kin::toWire(v);
  if (applied_[ch] == Mode::Reverse) return kin::toWire(-v);
  return 0;
}

void Motors::apply(int ch, const BridgeCmd& cmd) {
  const uint8_t in1 = cfg::kMotorPins[ch][0];
  const uint8_t in2 = cfg::kMotorPins[ch][1];
  const uint32_t duty = static_cast<uint32_t>(lroundf(fminf(cmd.duty, 1.0f) * kMaxDuty));

  if (cmd.mode != applied_[ch]) {
    detach(ch);
    switch (cmd.mode) {
      case Mode::Coast:
        digitalWrite(in1, LOW);
        digitalWrite(in2, LOW);
        break;
      case Mode::Brake:
        digitalWrite(in1, HIGH);
        digitalWrite(in2, HIGH);
        break;
      case Mode::Forward:
      case Mode::Reverse: {
        const int8_t pwm_idx = cmd.mode == Mode::Forward ? 0 : 1;
        digitalWrite(pwm_idx == 0 ? in2 : in1, LOW);
        const uint8_t pin = pwm_idx == 0 ? in1 : in2;
        if (!ledcAttach(pin, pwm_hz_, cfg::kPwmResolutionBits)) {
          // No LEDC channel: coast and retry on the next update.
          digitalWrite(pin, LOW);
          applied_[ch] = Mode::Coast;
          duty_[ch] = 0;
          return;
        }
        attached_[ch] = pwm_idx;
        ledcWrite(pin, duty);
        break;
      }
    }
    applied_[ch] = cmd.mode;
    duty_[ch] = attached_[ch] >= 0 ? duty : 0;
    return;
  }

  if (attached_[ch] >= 0 && duty != duty_[ch]) {
    ledcWrite(cfg::kMotorPins[ch][attached_[ch]], duty);
    duty_[ch] = duty;
  }
}

void Motors::detach(int ch) {
  if (attached_[ch] < 0) return;
  const uint8_t pin = cfg::kMotorPins[ch][attached_[ch]];
  ledcDetach(pin);
  pinMode(pin, OUTPUT);
  digitalWrite(pin, LOW);
  attached_[ch] = -1;
}
