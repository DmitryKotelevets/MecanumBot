#include "messages.h"

#include <string.h>

namespace proto {

namespace {

// Little-endian cursor helpers.
struct Writer {
  uint8_t* p;
  size_t n = 0;
  void u8(uint8_t v) { p[n++] = v; }
  void i8(int8_t v) { p[n++] = static_cast<uint8_t>(v); }
  void u16(uint16_t v) {
    u8(v & 0xFF);
    u8(v >> 8);
  }
  void u32(uint32_t v) {
    u16(v & 0xFFFF);
    u16(v >> 16);
  }
  void bytes(const void* src, size_t len) {
    if (len) memcpy(p + n, src, len);
    n += len;
  }
};

struct Reader {
  const uint8_t* p;
  size_t len;
  size_t n = 0;
  bool has(size_t k) const { return len - n >= k; }
  uint8_t u8() { return p[n++]; }
  int8_t i8() { return static_cast<int8_t>(p[n++]); }
  uint16_t u16() {
    uint16_t v = static_cast<uint16_t>(p[n] | (p[n + 1] << 8));
    n += 2;
    return v;
  }
  uint32_t u32() {
    uint32_t lo = u16();
    return lo | (static_cast<uint32_t>(u16()) << 16);
  }
  void bytes(void* dst, size_t k) {
    memcpy(dst, p + n, k);
    n += k;
  }
};

}  // namespace

Config defaultConfig() {
  Config c{};
  c.version = 1;
  for (uint8_t i = 0; i < 4; i++) {
    c.map[i] = i;
    c.invert[i] = 0;
    c.trim[i] = 100;
  }
  c.max_duty = 100;
  c.slew_ms = 250;
  c.brake = 1;
  c.min_duty = 15;
  c.failsafe_ms = 300;
  c.pwm_hz = 20000;
  return c;
}

bool validate(const Config& c) {
  if (c.version != 1) return false;
  uint8_t seen = 0;
  for (int i = 0; i < 4; i++) {
    if (c.map[i] > 3) return false;
    seen |= 1 << c.map[i];
    if (c.invert[i] > 1) return false;
    if (c.trim[i] < 50 || c.trim[i] > 100) return false;
  }
  if (seen != 0x0F) return false;
  if (c.max_duty < 1 || c.max_duty > 100) return false;
  if (c.min_duty >= c.max_duty) return false;
  if (c.slew_ms > 2000) return false;
  if (c.brake > 1) return false;
  if (c.failsafe_ms < 100 || c.failsafe_ms > 1000) return false;
  if (c.pwm_hz < 1000 || c.pwm_hz > 30000) return false;
  return true;
}

// --- encode ------------------------------------------------------------------

size_t encode(const Hello& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.proto_ver);
  w.u8(m.app_major);
  w.u8(m.app_minor);
  return w.n;
}

size_t encode(const Drive& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.flags);
  w.i8(m.vx);
  w.i8(m.vy);
  w.i8(m.w);
  return w.n;
}

size_t encode(const MotorRaw& m, uint8_t* out) {
  Writer w{out};
  for (int8_t v : m.m) w.i8(v);
  return w.n;
}

size_t encode(const Ping& m, uint8_t* out) {
  Writer w{out};
  w.u32(m.ts);
  return w.n;
}

size_t encode(const Config& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.version);
  w.bytes(m.map, 4);
  w.bytes(m.invert, 4);
  w.u8(m.max_duty);
  w.u16(m.slew_ms);
  w.bytes(m.trim, 4);
  w.u8(m.brake);
  w.u8(m.min_duty);
  w.u16(m.failsafe_ms);
  w.u16(m.pwm_hz);
  return w.n;
}

size_t encode(const OtaBegin& m, uint8_t* out) {
  Writer w{out};
  w.u32(m.size);
  w.bytes(m.sha256, 32);
  return w.n;
}

size_t encode(const OtaData& m, uint8_t* out) {
  if (m.len < 1 || m.len > kMaxOtaChunk) return 0;
  Writer w{out};
  w.u32(m.offset);
  w.bytes(m.data, m.len);
  return w.n;
}

size_t encode(const WifiOtaEnter& m, uint8_t* out) {
  const size_t ns = strnlen(m.ssid, sizeof m.ssid);
  const size_t np = strnlen(m.pass, sizeof m.pass);
  if (ns > kMaxSsid || np > kMaxPass) return 0;
  Writer w{out};
  w.u8(static_cast<uint8_t>(ns));
  w.bytes(m.ssid, ns);
  w.u8(static_cast<uint8_t>(np));
  w.bytes(m.pass, np);
  return w.n;
}

size_t encode(const HelloAck& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.proto_ver);
  w.u8(m.fw_major);
  w.u8(m.fw_minor);
  w.u8(m.reset_reason);
  w.u16(m.reset_count);
  return w.n;
}

size_t encode(const Telemetry& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.last_seq);
  w.u8(m.flags);
  for (int8_t v : m.pwm) w.i8(v);
  w.u16(m.vm_mv);
  w.u16(m.crc_err);
  w.u16(m.rx_frames);
  w.u32(m.uptime_s);
  w.u8(m.fw_major);
  w.u8(m.fw_minor);
  w.u16(m.loop_max_us);
  return w.n;
}

size_t encode(const Ack& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.req_type);
  w.u8(m.req_seq);
  w.u8(m.status);
  return w.n;
}

size_t encode(const Log& m, uint8_t* out) {
  if (m.len > kMaxLogText) return 0;
  Writer w{out};
  w.u8(m.level);
  w.bytes(m.text, m.len);
  return w.n;
}

size_t encode(const WifiStatus& m, uint8_t* out) {
  Writer w{out};
  w.u8(m.state);
  w.bytes(m.ip, 4);
  return w.n;
}

// --- decode ------------------------------------------------------------------

bool decode(const uint8_t* p, size_t len, Hello& out) {
  if (len != 3) return false;
  Reader r{p, len};
  out.proto_ver = r.u8();
  out.app_major = r.u8();
  out.app_minor = r.u8();
  return true;
}

bool decode(const uint8_t* p, size_t len, Drive& out) {
  if (len != 4) return false;
  Reader r{p, len};
  out.flags = r.u8();
  out.vx = r.i8();
  out.vy = r.i8();
  out.w = r.i8();
  return true;
}

bool decode(const uint8_t* p, size_t len, MotorRaw& out) {
  if (len != 4) return false;
  Reader r{p, len};
  for (int8_t& v : out.m) v = r.i8();
  return true;
}

bool decode(const uint8_t* p, size_t len, Ping& out) {
  if (len != 4) return false;
  Reader r{p, len};
  out.ts = r.u32();
  return true;
}

bool decode(const uint8_t* p, size_t len, Config& out) {
  if (len != kConfigSize) return false;
  Reader r{p, len};
  out.version = r.u8();
  r.bytes(out.map, 4);
  r.bytes(out.invert, 4);
  out.max_duty = r.u8();
  out.slew_ms = r.u16();
  r.bytes(out.trim, 4);
  out.brake = r.u8();
  out.min_duty = r.u8();
  out.failsafe_ms = r.u16();
  out.pwm_hz = r.u16();
  return true;
}

bool decode(const uint8_t* p, size_t len, OtaBegin& out) {
  if (len != 36) return false;
  Reader r{p, len};
  out.size = r.u32();
  r.bytes(out.sha256, 32);
  return true;
}

bool decode(const uint8_t* p, size_t len, OtaData& out) {
  if (len < 5 || len > 4 + kMaxOtaChunk) return false;
  Reader r{p, len};
  out.offset = r.u32();
  out.data = p + r.n;
  out.len = static_cast<uint8_t>(len - r.n);
  return true;
}

bool decode(const uint8_t* p, size_t len, WifiOtaEnter& out) {
  Reader r{p, len};
  if (!r.has(1)) return false;
  const uint8_t ns = r.u8();
  if (ns > kMaxSsid || !r.has(ns + 1u)) return false;
  r.bytes(out.ssid, ns);
  out.ssid[ns] = '\0';
  const uint8_t np = r.u8();
  if (np > kMaxPass || r.len - r.n != np) return false;
  r.bytes(out.pass, np);
  out.pass[np] = '\0';
  return true;
}

bool decode(const uint8_t* p, size_t len, HelloAck& out) {
  if (len != 6) return false;
  Reader r{p, len};
  out.proto_ver = r.u8();
  out.fw_major = r.u8();
  out.fw_minor = r.u8();
  out.reset_reason = r.u8();
  out.reset_count = r.u16();
  return true;
}

bool decode(const uint8_t* p, size_t len, Telemetry& out) {
  if (len != 20) return false;
  Reader r{p, len};
  out.last_seq = r.u8();
  out.flags = r.u8();
  for (int8_t& v : out.pwm) v = r.i8();
  out.vm_mv = r.u16();
  out.crc_err = r.u16();
  out.rx_frames = r.u16();
  out.uptime_s = r.u32();
  out.fw_major = r.u8();
  out.fw_minor = r.u8();
  out.loop_max_us = r.u16();
  return true;
}

bool decode(const uint8_t* p, size_t len, Ack& out) {
  if (len != 3) return false;
  Reader r{p, len};
  out.req_type = r.u8();
  out.req_seq = r.u8();
  out.status = r.u8();
  return true;
}

bool decode(const uint8_t* p, size_t len, Log& out) {
  if (len < 1 || len > 1 + kMaxLogText) return false;
  out.level = p[0];
  out.text = reinterpret_cast<const char*>(p + 1);
  out.len = static_cast<uint8_t>(len - 1);
  return true;
}

bool decode(const uint8_t* p, size_t len, WifiStatus& out) {
  if (len != 5) return false;
  Reader r{p, len};
  out.state = r.u8();
  r.bytes(out.ip, 4);
  return true;
}

}  // namespace proto
