// USB CDC link: frame parsing on RX, non-blocking frame TX — PROTOCOL.md §1, §5.6.
#pragma once

#include <controller.h>
#include <protocol.h>

class UsbLink : public FrameSender {
 public:
  using Handler = proto::Parser::Handler;

  UsbLink(Handler handler, void* ctx) : handler_(handler), ctx_(ctx), parser_(onFrame, this) {}

  void begin();
  // Reads all pending bytes; delivers frames to the handler.
  void poll(uint32_t now_ms);

  // Drops the frame (never blocks) if the TX buffer lacks room.
  bool send(proto::Type type, const uint8_t* payload, size_t len) override;

  uint32_t parserErrors() const { return parser_.errors(); }
  uint32_t framesReceived() const { return rx_frames_; }
  uint32_t framesDropped() const { return tx_dropped_; }

 private:
  static void onFrame(const proto::Frame& frame, void* self);

  Handler handler_;
  void* ctx_;
  proto::Parser parser_;
  uint8_t seq_ = 0;
  uint32_t last_rx_ms_ = 0;
  uint32_t rx_frames_ = 0;
  uint32_t tx_dropped_ = 0;
};
