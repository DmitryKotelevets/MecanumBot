// Command watchdog — PROTOCOL.md §5.2. Pure C++, millis-overflow safe.
#pragma once

#include <stdint.h>

class Failsafe {
 public:
  explicit Failsafe(uint32_t timeout_ms) : timeout_(timeout_ms) {}

  void setTimeout(uint32_t timeout_ms) { timeout_ = timeout_ms; }

  // A valid DRIVE/MOTOR_RAW arrived.
  void feed(uint32_t now_ms) {
    last_ = now_ms;
    active_ = false;
  }

  // Returns true exactly once, on the tick that enters failsafe.
  // A feed() stamped later than now_ms (fresh millis() vs the loop's cached one)
  // counts as zero elapsed, not as a wrapped ~49-day gap.
  bool tick(uint32_t now_ms) {
    const int32_t elapsed = static_cast<int32_t>(now_ms - last_);
    if (active_ || elapsed < static_cast<int32_t>(timeout_)) return false;
    active_ = true;
    return true;
  }

  // Active from boot until the first feed().
  bool active() const { return active_; }

 private:
  uint32_t timeout_;
  uint32_t last_ = 0;
  bool active_ = true;
};
