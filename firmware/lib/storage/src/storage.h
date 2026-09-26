// Config and boot counter in NVS via Preferences — DESIGN.md §4.6.
// Config is stored as its CONFIG payload bytes (versioned by its first byte).
#pragma once

#include <controller.h>
#include <protocol.h>

class Storage : public ConfigStore {
 public:
  // Stored config if present and valid, defaults otherwise.
  proto::Config loadConfig();
  bool save(const proto::Config& config) override;

  // Increments and returns the boot counter (wraps at 65535).
  uint16_t bumpBootCount();
};
