#include "controller.h"

#include <kinematics.h>
#include <string.h>

using namespace proto;

void Controller::begin(const Config& config, const BootInfo& boot) {
  boot_ = boot;
  applyConfig(config);
  config_changed_ = false;
}

void Controller::onFrame(const Frame& f, uint32_t now_ms) {
  switch (static_cast<Type>(f.type)) {
    case Type::Hello:
      sendHelloAck();
      break;

    case Type::Drive: {
      Drive d;
      if (!decode(f.payload, f.len, d)) break;
      drive_ = d;
      mode_ = Mode::Drive;
      last_seq_ = f.seq;
      failsafe_.feed(now_ms);
      break;
    }

    case Type::MotorRaw: {
      MotorRaw m;
      if (!decode(f.payload, f.len, m)) break;
      raw_ = m;
      mode_ = Mode::Raw;
      last_seq_ = f.seq;
      failsafe_.feed(now_ms);
      break;
    }

    case Type::Ping: {
      uint8_t buf[4];
      memcpy(buf, f.payload, 4);  // PONG echoes the PING payload
      tx_.send(Type::Pong, buf, 4);
      break;
    }

    case Type::Config: {
      Config c;
      if (!decode(f.payload, f.len, c) || !validate(c)) {
        ack(f, AckStatus::Err);
        break;
      }
      if (!store_.save(c)) {
        log(LogLevel::Error, "config: NVS write failed");
        ack(f, AckStatus::Err);
        break;
      }
      applyConfig(c);
      ack(f, AckStatus::Ok);
      break;
    }

    case Type::Stop:
      drive_ = Drive{};
      mode_ = Mode::Drive;
      ack(f, AckStatus::Ok);
      break;

    case Type::GetConfig: {
      uint8_t buf[kConfigSize];
      tx_.send(Type::ConfigData, buf, encode(config_, buf));
      break;
    }

    case Type::OtaBegin:
    case Type::OtaData:
    case Type::OtaEnd:
    case Type::OtaAbort:
    case Type::WifiOtaEnter:
    case Type::WifiOtaExit:
      // TODO(DESIGN §10 stage 7): ota_usb and wifi_ota.
      log(LogLevel::Warn, "ota: not implemented");
      ack(f, AckStatus::Err);
      break;

    case Type::Reboot:
      reboot_ = true;
      break;

    default:
      break;  // ESP32 -> phone types: not for us
  }
}

bool Controller::tick(uint32_t now_ms) { return failsafe_.tick(now_ms); }

void Controller::output(float channels[4]) const {
  for (int i = 0; i < 4; i++) channels[i] = 0.0f;
  if (failsafe_.active()) return;
  if (mode_ == Mode::Raw) {
    // Physical channels, bypassing kinematics and calibration; max_duty still caps.
    const float cap = config_.max_duty / 100.0f;
    for (int i = 0; i < 4; i++) channels[i] = kin::fromWire(raw_.m[i]) * cap;
    return;
  }
  const kin::Params p{
      {config_.map[0], config_.map[1], config_.map[2], config_.map[3]},
      {config_.invert[0], config_.invert[1], config_.invert[2], config_.invert[3]},
      {config_.trim[0], config_.trim[1], config_.trim[2], config_.trim[3]},
      config_.min_duty,
      config_.max_duty,
  };
  kin::drive(drive_.vx, drive_.vy, drive_.w, drive_.enable(), p, channels);
}

uint8_t Controller::telemetryFlags() const {
  uint8_t flags = 0;
  if (failsafe_.active()) flags |= telemetry_flags::kFailsafe;
  if (mode_ == Mode::Raw) flags |= telemetry_flags::kRawMode;
  if (mode_ == Mode::Drive && drive_.enable() && !failsafe_.active()) flags |= telemetry_flags::kEnable;
  return flags;
}

void Controller::log(LogLevel level, const char* text) {
  uint8_t buf[1 + kMaxLogText];
  size_t n = strlen(text);
  if (n > kMaxLogText) n = kMaxLogText;
  tx_.send(Type::Log, buf, encode(Log{static_cast<uint8_t>(level), text, static_cast<uint8_t>(n)}, buf));
}

bool Controller::takeConfigChanged() {
  const bool changed = config_changed_;
  config_changed_ = false;
  return changed;
}

void Controller::ack(const Frame& f, AckStatus status) {
  uint8_t buf[3];
  tx_.send(Type::Ack, buf, encode(Ack{f.type, f.seq, static_cast<uint8_t>(status)}, buf));
}

void Controller::sendHelloAck() {
  uint8_t buf[6];
  const HelloAck m{kProtoVer, boot_.fw_major, boot_.fw_minor, boot_.reset_reason, boot_.reset_count};
  tx_.send(Type::HelloAck, buf, encode(m, buf));
}

void Controller::applyConfig(const Config& config) {
  config_ = config;
  failsafe_.setTimeout(config.failsafe_ms);
  config_changed_ = true;
}
