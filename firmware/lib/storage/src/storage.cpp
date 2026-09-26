#include "storage.h"

#include <Preferences.h>

namespace {
constexpr const char* kNamespace = "mbot";
constexpr const char* kConfigKey = "cfg";
constexpr const char* kBootKey = "boots";
}  // namespace

proto::Config Storage::loadConfig() {
  Preferences prefs;
  proto::Config config = proto::defaultConfig();
  if (!prefs.begin(kNamespace, true)) return config;
  uint8_t buf[proto::kConfigSize];
  proto::Config stored;
  if (prefs.getBytesLength(kConfigKey) == sizeof buf && prefs.getBytes(kConfigKey, buf, sizeof buf) == sizeof buf &&
      proto::decode(buf, sizeof buf, stored) && proto::validate(stored)) {
    config = stored;
  }
  prefs.end();
  return config;
}

bool Storage::save(const proto::Config& config) {
  Preferences prefs;
  if (!prefs.begin(kNamespace, false)) return false;
  uint8_t buf[proto::kConfigSize];
  const size_t n = proto::encode(config, buf);
  const bool ok = prefs.putBytes(kConfigKey, buf, n) == n;
  prefs.end();
  return ok;
}

uint16_t Storage::bumpBootCount() {
  Preferences prefs;
  if (!prefs.begin(kNamespace, false)) return 0;
  const uint16_t count = static_cast<uint16_t>(prefs.getUShort(kBootKey, 0) + 1);
  prefs.putUShort(kBootKey, count);
  prefs.end();
  return count;
}
