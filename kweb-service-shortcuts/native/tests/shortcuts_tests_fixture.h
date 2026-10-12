#ifndef KWEB_SHORTCUTS_TESTS_FIXTURE_H_
#define KWEB_SHORTCUTS_TESTS_FIXTURE_H_

#include "kweb_shortcuts.h"

#include <chrono>
#include <deque>
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
struct ShortcutsFixture {
  static constexpr const char *kApplicationId = "io.github.kingsword09.kwebshell.shortcuts.fixture";
  static constexpr const char *kPackageIdentity = "kwebshell-shortcuts-fixture";

  kweb_shortcuts_configuration Configuration() {
    kweb_shortcuts_configuration configuration{};
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::string(kApplicationId).size();
    configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
    configuration.package_identity.size = std::string(kPackageIdentity).size();
    return configuration;
  }

  kweb_shortcuts_capabilities_result Capabilities(uint64_t handle) {
    kweb_shortcuts_capabilities_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    const kweb_shortcuts_status status = kweb_shortcuts_capabilities(handle, &result);
    if (status != KWEB_SHORTCUTS_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  kweb_shortcuts_registration_request Request(const std::string &id, const std::string &command,
                                              uint32_t modifier_bits, uint32_t key_kind,
                                              uint32_t key_code) {
    ids_.push_back(id);
    commands_.push_back(command);
    kweb_shortcuts_registration_request request{};
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    request.registration_id.data = reinterpret_cast<const uint8_t *>(ids_.back().data());
    request.registration_id.size = ids_.back().size();
    request.command_id.data = reinterpret_cast<const uint8_t *>(commands_.back().data());
    request.command_id.size = commands_.back().size();
    request.modifier_bits = modifier_bits;
    request.key_kind = key_kind;
    request.key_code = key_code;
    return request;
  }

  kweb_shortcuts_status Register(uint64_t handle, const kweb_shortcuts_registration_request &request,
                                 kweb_shortcuts_registration_result *result) {
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    return kweb_shortcuts_register(handle, &request, result);
  }

  kweb_shortcuts_status Unregister(uint64_t handle, const std::string &id,
                                   kweb_shortcuts_unregistration_result *result) {
    kweb_shortcuts_query query{};
    query.struct_size = sizeof(query);
    query.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    query.value.data = reinterpret_cast<const uint8_t *>(id.data());
    query.value.size = id.size();
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    return kweb_shortcuts_unregister(handle, &query, result);
  }

  kweb_shortcuts_status Dispatch(uint64_t handle, const std::string &command,
                                 kweb_shortcuts_registration_result *result) {
    kweb_shortcuts_query query{};
    query.struct_size = sizeof(query);
    query.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    query.value.data = reinterpret_cast<const uint8_t *>(command.data());
    query.value.size = command.size();
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    return kweb_shortcuts_dispatch(handle, &query, result);
  }

  /** Pops every queued event, at most one capacity window. */
  std::vector<kweb_shortcuts_event> DrainEvents(uint64_t handle) {
    std::vector<kweb_shortcuts_event> events;
    for (uint32_t index = 0; index < KWEB_SHORTCUTS_EVENT_CAPACITY; ++index) {
      kweb_shortcuts_event event{};
      event.struct_size = sizeof(event);
      event.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
      if (kweb_shortcuts_poll_event(handle, &event) != KWEB_SHORTCUTS_STATUS_OK) break;
      events.push_back(event);
    }
    return events;
  }

  /** Pops events until one arrives or the deadline passes. */
  bool AwaitEvent(uint64_t handle, kweb_shortcuts_event *event, int timeout_ms = 2000) {
    const int step_ms = 10;
    for (int waited = 0; waited <= timeout_ms; waited += step_ms) {
      if (kweb_shortcuts_poll_event(handle, event) == KWEB_SHORTCUTS_STATUS_OK) return true;
      pump_platform_queue();
      std::this_thread::sleep_for(std::chrono::milliseconds(step_ms));
    }
    return false;
  }

  /** Drains pending platform work; the current target's structure test provides it. */
  static void pump_platform_queue();

 private:
  // A deque keeps the addresses of earlier requests stable across inserts.
  std::deque<std::string> ids_;
  std::deque<std::string> commands_;
};

#endif
