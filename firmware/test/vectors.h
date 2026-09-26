// Loads protocol/vectors.json (the shared source of truth) for native tests.
#pragma once

#include <ArduinoJson.h>

#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#ifndef VECTORS_PATH
#define VECTORS_PATH "../protocol/vectors.json"
#endif

inline JsonDocument& vectors() {
  static JsonDocument doc;
  static bool loaded = false;
  if (!loaded) {
    std::ifstream f(VECTORS_PATH);
    std::stringstream ss;
    ss << f.rdbuf();
    DeserializationError err = deserializeJson(doc, ss.str());
    if (err) {
      fprintf(stderr, "cannot load %s: %s\n", VECTORS_PATH, err.c_str());
      abort();
    }
    loaded = true;
  }
  return doc;
}

inline std::vector<uint8_t> unhex(const char* s) {
  std::vector<uint8_t> out;
  for (size_t i = 0; s[i] && s[i + 1]; i += 2) {
    out.push_back(static_cast<uint8_t>(std::stoi(std::string(s + i, 2), nullptr, 16)));
  }
  return out;
}

inline std::string tohex(const uint8_t* p, size_t n) {
  static const char* d = "0123456789abcdef";
  std::string s;
  for (size_t i = 0; i < n; i++) {
    s += d[p[i] >> 4];
    s += d[p[i] & 15];
  }
  return s;
}
