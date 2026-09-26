// MecanumBot firmware — DESIGN.md §4.3. One loop, no RTOS tasks, no delay().
#include <Arduino.h>
#include <controller.h>
#include <esp_system.h>
#include <esp_task_wdt.h>
#include <usb_link.h>
#include <motors.h>
#include <storage.h>
#include <telemetry.h>

#include "config.h"

namespace {

void onFrame(const proto::Frame& frame, void* ctx);

UsbLink usb(onFrame, nullptr);
Storage storage;
Controller controller(usb, storage);
Motors motors;
TelemetryReporter telemetry;

bool vm_low = false;
bool host_connected = false;
bool wdt_ok = false;
uint32_t reboot_at = 0;
bool reboot_pending = false;
int8_t led_state = -1;

void onFrame(const proto::Frame& frame, void*) { controller.onFrame(frame, millis()); }

void setupWatchdog() {
  const esp_task_wdt_config_t wdt = {
      .timeout_ms = cfg::kWdtTimeoutMs,
      .idle_core_mask = 0,  // loop() never yields, so the idle task is not watched
      .trigger_panic = true,
  };
  wdt_ok = esp_task_wdt_reconfigure(&wdt) == ESP_OK;
  enableLoopWDT();
}

// HELLO_ACK at boot is only deliverable once the host reads the port, so it
// is sent on every connect edge (PROTOCOL.md §5.6).
void checkHost() {
  const bool connected = static_cast<bool>(Serial);
  if (connected && !host_connected) {
    controller.announce();
    if (!wdt_ok) controller.log(proto::LogLevel::Error, "task WDT reconfigure failed");
  }
  host_connected = connected;
}

void checkVm() {
  const bool low = telemetry.vmMv() < cfg::kVmWarnMv;
  if (low && !vm_low) controller.log(proto::LogLevel::Warn, "VM low: motors overloaded or port off");
  vm_low = low;
}

// Green: driving enabled; blue: connected, idle; red: failsafe.
void updateLed() {
  const int8_t state = controller.failsafeActive() ? 0 : (controller.telemetryFlags() & proto::telemetry_flags::kEnable) ? 1 : 2;
  if (state == led_state) return;
  led_state = state;
  if (state == 0) rgbLedWrite(cfg::kPinLed, 16, 0, 0);
  if (state == 1) rgbLedWrite(cfg::kPinLed, 0, 16, 0);
  if (state == 2) rgbLedWrite(cfg::kPinLed, 0, 0, 16);
}

}  // namespace

void setup() {
  const proto::Config config = storage.loadConfig();
  motors.begin(config);  // outputs LOW before anything else
  usb.begin();
  telemetry.begin();
  const BootInfo boot{FW_MAJOR, FW_MINOR, static_cast<uint8_t>(esp_reset_reason()), storage.bumpBootCount()};
  controller.begin(config, boot);
  setupWatchdog();
}

void loop() {
  const uint32_t start_us = micros();
  const uint32_t now = millis();

  usb.poll(now);
  checkHost();
  controller.tick(now);
  if (controller.takeConfigChanged()) motors.configure(controller.config());

  float target[4];
  controller.output(target);
  motors.update(target, now);
  motors.pollFaults(now);

  if (telemetry.due(now)) {
    TelemetryInputs in{};
    in.last_seq = controller.lastSeq();
    in.flags = controller.telemetryFlags();
    if (motors.faultA()) in.flags |= proto::telemetry_flags::kFaultA;
    if (motors.faultB()) in.flags |= proto::telemetry_flags::kFaultB;
    for (int i = 0; i < 4; i++) in.pwm[i] = motors.pwm(i);
    in.crc_err = usb.parserErrors();
    in.rx_frames = usb.framesReceived();
    telemetry.send(now, usb, in);
    checkVm();
  }
  updateLed();

  if (controller.rebootRequested() && !reboot_pending) {
    reboot_pending = true;
    reboot_at = now + cfg::kRebootDelayMs;
  }
  if (reboot_pending && static_cast<int32_t>(now - reboot_at) >= 0) {
    const float zero[4] = {0, 0, 0, 0};
    motors.update(zero, now);
    ESP.restart();
  }

  telemetry.recordLoop(micros() - start_us);
}
