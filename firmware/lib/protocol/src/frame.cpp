#include "frame.h"

#include <string.h>

namespace proto {

namespace {

struct LenRange {
  uint8_t type;
  uint8_t min;
  uint8_t max;
};

constexpr LenRange kTypes[] = {
    {0x00, 3, 3},   {0x01, 4, 4},   {0x02, 4, 4},   {0x03, 4, 4},  {0x04, 22, 22},
    {0x05, 0, 0},   {0x06, 0, 0},   {0x10, 36, 36}, {0x11, 5, 196}, {0x12, 0, 0},
    {0x13, 0, 0},   {0x20, 2, 97},  {0x21, 0, 0},   {0x7F, 0, 0},  {0x80, 6, 6},
    {0x81, 20, 20}, {0x82, 3, 3},   {0x83, 1, 200}, {0x84, 4, 4},  {0x85, 22, 22},
    {0x86, 5, 5},
};

const LenRange* findType(uint8_t type) {
  for (const auto& t : kTypes) {
    if (t.type == type) return &t;
  }
  return nullptr;
}

}  // namespace

uint8_t crc8(const uint8_t* data, size_t n, uint8_t crc) {
  for (size_t i = 0; i < n; i++) {
    crc ^= data[i];
    for (int b = 0; b < 8; b++) {
      crc = (crc & 0x80) ? static_cast<uint8_t>((crc << 1) ^ 0x07) : static_cast<uint8_t>(crc << 1);
    }
  }
  return crc;
}

bool typeKnown(uint8_t type) { return findType(type) != nullptr; }

bool lenValid(uint8_t type, uint8_t len) {
  const LenRange* t = findType(type);
  return t && len <= kMaxPayload && len >= t->min && len <= t->max;
}

size_t encodeFrame(uint8_t type, uint8_t seq, const uint8_t* payload, uint8_t len,
                   uint8_t* out, size_t cap) {
  const size_t total = kOverhead + len;
  if (!lenValid(type, len) || cap < total) return 0;
  out[0] = kSync;
  out[1] = type;
  out[2] = len;
  out[3] = seq;
  if (len) memcpy(out + 4, payload, len);
  out[4 + len] = crc8(out + 1, 3 + len);
  return total;
}

void Parser::feed(uint8_t byte) {
  // Invariant after process(): the buffer holds less than one full frame.
  buf_[n_++] = byte;
  process();
}

void Parser::feed(const uint8_t* data, size_t n) {
  for (size_t i = 0; i < n; i++) feed(data[i]);
}

void Parser::expireIdle() {
  if (n_ == 0) return;
  reject();
  process();
}

void Parser::process() {
  for (;;) {
    size_t i = 0;
    while (i < n_ && buf_[i] != kSync) i++;
    drop(i);
    if (n_ < 2) return;
    if (!typeKnown(buf_[1])) {
      reject();
      continue;
    }
    if (n_ < 3) return;
    const uint8_t len = buf_[2];
    if (!lenValid(buf_[1], len)) {
      reject();
      continue;
    }
    const size_t total = kOverhead + len;
    if (n_ < total) return;
    if (crc8(buf_ + 1, 3 + len) != buf_[4 + len]) {
      reject();
      continue;
    }
    frame_.type = buf_[1];
    frame_.len = len;
    frame_.seq = buf_[3];
    memcpy(frame_.payload, buf_ + 4, len);
    drop(total);
    handler_(frame_, ctx_);
  }
}

void Parser::reject() {
  // Drop only the sync byte: a real frame may start inside the rejected one.
  if (errors_ != UINT32_MAX) errors_++;
  drop(1);
}

void Parser::drop(size_t count) {
  if (count >= n_) {
    n_ = 0;
    return;
  }
  memmove(buf_, buf_ + count, n_ - count);
  n_ -= count;
}

}  // namespace proto
