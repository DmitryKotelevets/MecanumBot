#include "usb_link.h"

#include <Arduino.h>

#include "config.h"

void UsbLink::begin() {
  // Never block loop() on a slow or absent host.
  Serial.setTxTimeoutMs(0);
  Serial.setTxBufferSize(cfg::kUsbTxBuffer);
  Serial.setRxBufferSize(cfg::kUsbRxBuffer);
  Serial.begin(115200);
}

void UsbLink::poll(uint32_t now_ms) {
  uint8_t buf[64];
  int n;
  while ((n = Serial.available()) > 0) {
    const size_t k = Serial.read(buf, n < static_cast<int>(sizeof buf) ? n : sizeof buf);
    if (k == 0) break;
    parser_.feed(buf, k);
    last_rx_ms_ = now_ms;
  }
  if (parser_.pending() && now_ms - last_rx_ms_ >= cfg::kParserIdleMs) parser_.expireIdle();
}

void UsbLink::onFrame(const proto::Frame& frame, void* self) {
  UsbLink* self_link = static_cast<UsbLink*>(self);
  self_link->rx_frames_++;
  self_link->handler_(frame, self_link->ctx_);
}

bool UsbLink::send(proto::Type type, const uint8_t* payload, size_t len) {
  uint8_t frame[proto::kMaxFrame];
  const size_t n = proto::encodeFrame(static_cast<uint8_t>(type), seq_, payload,
                                      static_cast<uint8_t>(len), frame, sizeof frame);
  if (n == 0) return false;
  // LOG is lowest priority: keep room for ACK/TELEMETRY.
  const size_t need = n + (type == proto::Type::Log ? cfg::kLogReserve : 0);
  if (!Serial || static_cast<size_t>(Serial.availableForWrite()) < need) {
    tx_dropped_++;
    return false;
  }
  if (Serial.write(frame, n) != n) {
    tx_dropped_++;
    return false;
  }
  seq_++;
  return true;
}
