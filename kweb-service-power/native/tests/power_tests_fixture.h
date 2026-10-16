#ifndef KWEB_POWER_TESTS_FIXTURE_H_
#define KWEB_POWER_TESTS_FIXTURE_H_

#include "kweb_power.h"

#include <chrono>
#include <cstdio>
#include <deque>
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
struct PowerFixture {
  static constexpr const char *kApplicationId = "io.github.kingsword09.kwebshell.power.fixture";
  static constexpr const char *kPackageIdentity = "kwebshell-power-fixture";

  kweb_power_configuration Configuration() {
    kweb_power_configuration configuration{};
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_POWER_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::string(kApplicationId).size();
    configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
    configuration.package_identity.size = std::string(kPackageIdentity).size();
    return configuration;
  }

  kweb_power_capabilities_result Capabilities(uint64_t handle) {
    kweb_power_capabilities_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_POWER_ABI_VERSION;
    const kweb_power_status status = kweb_power_capabilities(handle, &result);
    if (status != KWEB_POWER_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  kweb_power_state_result State(uint64_t handle) {
    kweb_power_state_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_POWER_ABI_VERSION;
    const kweb_power_status status = kweb_power_state(handle, &result);
    if (status != KWEB_POWER_STATUS_OK) std::printf("state status %u\n", status);
    return result;
  }

  /** Builds one lease request; the fixture owns the string storage. */
  kweb_power_lease_request Request(const std::string &id, uint32_t type, const std::string &reason,
                                  uint32_t seconds) {
    ids_.push_back(id);
    reasons_.push_back(reason);
    kweb_power_lease_request request{};
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_POWER_ABI_VERSION;
    request.lease_id.data = reinterpret_cast<const uint8_t *>(ids_.back().data());
    request.lease_id.size = ids_.back().size();
    request.reason.data = reinterpret_cast<const uint8_t *>(reasons_.back().data());
    request.reason.size = reasons_.back().size();
    request.type = type;
    request.max_duration_seconds = seconds;
    return request;
  }

  kweb_power_lease_query Query(const std::string &id) {
    ids_.push_back(id);
    kweb_power_lease_query query{};
    query.struct_size = sizeof(query);
    query.abi_version = KWEB_POWER_ABI_VERSION;
    query.lease_id.data = reinterpret_cast<const uint8_t *>(ids_.back().data());
    query.lease_id.size = ids_.back().size();
    return query;
  }

  kweb_power_status Acquire(uint64_t handle, const kweb_power_lease_request &request,
                            kweb_power_lease_result *result) {
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_POWER_ABI_VERSION;
    return kweb_power_acquire(handle, &request, result);
  }

  kweb_power_status Renew(uint64_t handle, const std::string &id, kweb_power_lease_result *result) {
    kweb_power_lease_query query = Query(id);
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_POWER_ABI_VERSION;
    return kweb_power_renew(handle, &query, result);
  }

  kweb_power_status Release(uint64_t handle, const std::string &id, kweb_power_release_result *result) {
    kweb_power_lease_query query = Query(id);
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_POWER_ABI_VERSION;
    return kweb_power_release(handle, &query, result);
  }

  /** Lists the live leases into this fixture's buffer. */
  kweb_power_status Leases(uint64_t handle, kweb_power_lease_list_result *result) {
    result->leases = leases_.data();
    result->capacity = static_cast<uint32_t>(leases_.size());
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_POWER_ABI_VERSION;
    return kweb_power_leases(handle, result);
  }

  /** Pops every queued event, at most one capacity window. */
  std::vector<kweb_power_event> DrainEvents(uint64_t handle) {
    std::vector<kweb_power_event> events;
    for (uint32_t index = 0; index < KWEB_POWER_EVENT_CAPACITY; ++index) {
      kweb_power_event event{};
      event.struct_size = sizeof(event);
      event.abi_version = KWEB_POWER_ABI_VERSION;
      if (kweb_power_poll_event(handle, &event) != KWEB_POWER_STATUS_OK) break;
      events.push_back(event);
    }
    return events;
  }

  /** Pops events until one arrives or the deadline passes. */
  bool AwaitEvent(uint64_t handle, kweb_power_event *event, int timeout_ms = 3000) {
    const int step_ms = 10;
    for (int waited = 0; waited <= timeout_ms; waited += step_ms) {
      if (kweb_power_poll_event(handle, event) == KWEB_POWER_STATUS_OK) return true;
      pump_platform_queue();
      std::this_thread::sleep_for(std::chrono::milliseconds(step_ms));
    }
    return false;
  }

  /** Drains pending platform work; the current target's structure test provides it. */
  static void pump_platform_queue();

 private:
  // Deques keep the addresses of earlier requests stable across inserts.
  std::deque<std::string> ids_;
  std::deque<std::string> reasons_;
  std::vector<kweb_power_lease> leases_ = std::vector<kweb_power_lease>(KWEB_POWER_MAX_LEASES);
};

#endif
