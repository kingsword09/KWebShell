#ifndef KWEB_SCREEN_TESTS_FIXTURE_H_
#define KWEB_SCREEN_TESTS_FIXTURE_H_

#include "kweb_screen.h"

#include <chrono>
#include <cstdio>
#include <string>
#include <thread>
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

/** Owns the ABI buffers and the service handle one test needs. */
struct ScreenFixture {
  static constexpr const char *kApplicationId = "io.github.kingsword09.kwebshell.screen.fixture";
  static constexpr const char *kPackageIdentity = "kwebshell-screen-fixture";

  kweb_screen_configuration Configuration() {
    kweb_screen_configuration configuration{};
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_SCREEN_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::string(kApplicationId).size();
    configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
    configuration.package_identity.size = std::string(kPackageIdentity).size();
    return configuration;
  }

  kweb_screen_capabilities_result Capabilities(uint64_t handle) {
    kweb_screen_capabilities_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SCREEN_ABI_VERSION;
    const kweb_screen_status status = kweb_screen_capabilities(handle, &result);
    if (status != KWEB_SCREEN_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  /** Reads the topology into this fixture's display buffer. */
  kweb_screen_status Topology(uint64_t handle, kweb_screen_topology_result *result) {
    result->displays = displays_.data();
    result->capacity = static_cast<uint32_t>(displays_.size());
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SCREEN_ABI_VERSION;
    return kweb_screen_topology(handle, result);
  }

  kweb_screen_status DisplayAt(uint64_t handle, int32_t x, int32_t y, uint32_t space,
                               kweb_screen_id_result *result) {
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SCREEN_ABI_VERSION;
    return kweb_screen_display_at(handle, x, y, space, result);
  }

  kweb_screen_status Nearest(uint64_t handle, int32_t x, int32_t y, uint32_t space,
                             kweb_screen_id_result *result) {
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SCREEN_ABI_VERSION;
    return kweb_screen_nearest(handle, x, y, space, result);
  }

  kweb_screen_status Convert(uint64_t handle, const std::string &display_id, uint32_t from_space,
                             uint32_t to_space, int32_t x, int32_t y, kweb_screen_point_result *result) {
    kweb_screen_conversion_request request{};
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_SCREEN_ABI_VERSION;
    request.display_id.data = reinterpret_cast<const uint8_t *>(display_id.data());
    request.display_id.size = display_id.size();
    request.from_space = from_space;
    request.to_space = to_space;
    request.x = x;
    request.y = y;
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SCREEN_ABI_VERSION;
    return kweb_screen_convert(handle, &request, result);
  }

  /** Pops every queued event, at most one capacity window. */
  std::vector<kweb_screen_event> DrainEvents(uint64_t handle) {
    std::vector<kweb_screen_event> events;
    for (uint32_t index = 0; index < KWEB_SCREEN_EVENT_CAPACITY; ++index) {
      kweb_screen_event event{};
      event.struct_size = sizeof(event);
      event.abi_version = KWEB_SCREEN_ABI_VERSION;
      if (kweb_screen_poll_event(handle, &event) != KWEB_SCREEN_STATUS_OK) break;
      events.push_back(event);
    }
    return events;
  }

  /** Pops events until one arrives or the deadline passes. */
  bool AwaitEvent(uint64_t handle, kweb_screen_event *event, int timeout_ms = 5000) {
    const int step_ms = 25;
    for (int waited = 0; waited <= timeout_ms; waited += step_ms) {
      if (kweb_screen_poll_event(handle, event) == KWEB_SCREEN_STATUS_OK) return true;
      pump_platform_queue();
      std::this_thread::sleep_for(std::chrono::milliseconds(step_ms));
    }
    return false;
  }

  static std::string IdOf(const kweb_screen_display &display) { return std::string(display.id); }

  /** Drains pending platform work; the current target's structure test provides it. */
  static void pump_platform_queue();

 private:
  std::vector<kweb_screen_display> displays_ = std::vector<kweb_screen_display>(KWEB_SCREEN_MAX_DISPLAYS);
};

#endif
