// Pins and constants — DESIGN.md §2.3, hardware/wiring.md. The only place for them.
#pragma once

#include <stdint.h>

#ifndef FW_MAJOR
#define FW_MAJOR 0
#endif
#ifndef FW_MINOR
#define FW_MINOR 1
#endif

namespace cfg {

// DRV8833 inputs, physical channels M1..M4 = {IN1, IN2}.
constexpr uint8_t kMotorPins[4][2] = {
    {18, 19},  // M1, DRV8833 #A channel A
    {20, 21},  // M2, DRV8833 #A channel B
    {22, 23},  // M3, DRV8833 #B channel A
    {10, 11},  // M4, DRV8833 #B channel B
};
constexpr uint8_t kPinFaultA = 2;  // nFAULT, active low
constexpr uint8_t kPinFaultB = 3;
constexpr uint8_t kPinSleep = 6;  // nSLEEP of both drivers, HIGH = run
constexpr uint8_t kPinVm = 1;     // VM divider, ADC1
constexpr uint8_t kPinLed = 8;    // on-board WS2812 on DevKitC-1

constexpr uint8_t kPwmResolutionBits = 10;
constexpr float kVmDividerRatio = 2.0f;  // 100k / 100k
constexpr uint16_t kVmWarnMv = 3200;

constexpr uint32_t kTelemetryPeriodMs = 100;
constexpr uint32_t kFaultPollMs = 50;
constexpr uint32_t kParserIdleMs = 50;
constexpr uint32_t kWdtTimeoutMs = 2000;
constexpr uint32_t kRebootDelayMs = 50;  // let the last frames leave

constexpr size_t kUsbTxBuffer = 1024;
constexpr size_t kUsbRxBuffer = 1024;
constexpr size_t kLogReserve = 64;  // LOG only if this much TX space stays free

}  // namespace cfg
