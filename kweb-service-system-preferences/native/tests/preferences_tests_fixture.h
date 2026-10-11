#ifndef KWEB_PREFERENCES_TESTS_FIXTURE_H_
#define KWEB_PREFERENCES_TESTS_FIXTURE_H_

#include "kweb_system_preferences.h"

#include <chrono>
#include <cstdio>
#include <thread>
#include <string>
#include <vector>

/** One shared assertion counter so every translation unit reports one total. */
inline int g_failures = 0;

#define KWEB_CHECK(condition)                                                                    \
  do {                                                                                           \
    if (!(condition)) {                                                                          \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition);                            \
      g_failures += 1;                                                                           \
    }                                                                                            \
  } while (false)

/** Owns the configuration strings and the ABI structs one test needs. */
struct PreferencesFixture {
  static constexpr const char *kApplicationId = "io.github.kingsword09.kwebshell.preferences.fixture";
  static constexpr const char *kPackageIdentity = "kwebshell-preferences-fixture";

  kweb_preferences_configuration Configuration(uint32_t sensitive_key_bits) {
    kweb_preferences_configuration configuration{};
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::string(kApplicationId).size();
    configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
    configuration.package_identity.size = std::string(kPackageIdentity).size();
    configuration.sensitive_key_bits = sensitive_key_bits;
    return configuration;
  }

  kweb_preferences_snapshot_result Snapshot(uint64_t handle) {
    kweb_preferences_snapshot_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    const kweb_preferences_status status = kweb_preferences_snapshot(handle, &result);
    if (status != KWEB_PREFERENCES_STATUS_OK) std::printf("snapshot status %u\n", status);
    return result;
  }

  kweb_preferences_capabilities_result Capabilities(uint64_t handle) {
    kweb_preferences_capabilities_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    const kweb_preferences_status status = kweb_preferences_capabilities(handle, &result);
    if (status != KWEB_PREFERENCES_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  /** Pops every queued event, at most one capacity window. */
  std::vector<kweb_preferences_event> DrainEvents(uint64_t handle) {
    std::vector<kweb_preferences_event> events;
    for (uint32_t index = 0; index < KWEB_PREFERENCES_EVENT_CAPACITY; ++index) {
      kweb_preferences_event event{};
      event.struct_size = sizeof(event);
      event.abi_version = KWEB_PREFERENCES_ABI_VERSION;
      if (kweb_preferences_poll_event(handle, &event) != KWEB_PREFERENCES_STATUS_OK) break;
      events.push_back(event);
    }
    return events;
  }

  /** Pops events until one arrives or the deadline passes. */
  bool AwaitEvent(uint64_t handle, kweb_preferences_event *event, int timeout_ms = 5000) {
    const int step_ms = 20;
    for (int waited = 0; waited <= timeout_ms; waited += step_ms) {
      if (kweb_preferences_poll_event(handle, event) == KWEB_PREFERENCES_STATUS_OK) return true;
      pump_platform_queue();
      sleep_milliseconds(step_ms);
    }
    return false;
  }

  static void sleep_milliseconds(int milliseconds) {
    std::this_thread::sleep_for(std::chrono::milliseconds(milliseconds));
  }

  /** Drains pending platform work; the current target's structure test provides it. */
  static void pump_platform_queue();
};

#endif
