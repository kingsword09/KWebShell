#include "power_internal.h"
#include "power_platform_test.h"
#include "power_tests_fixture.h"

#include <cstring>
#include <vector>

namespace {

/** Opens the process-wide service, or reports that the session has no facility. */
bool OpenOrSkip(PowerFixture &fixture, uint64_t *handle, const char *label) {
  kweb_power_configuration configuration = fixture.Configuration();
  const kweb_power_status opened = kweb_power_open(&configuration, handle);
  if (opened != KWEB_POWER_STATUS_OK) {
    std::printf("SKIP: the %s power session declares no facility (status %u).\n", label, opened);
    return false;
  }
  return true;
}

/** True when the declared session can assert the given blocker type. */
bool SupportsBlockers(const kweb_power_capabilities_result &capabilities) {
  return (capabilities.capability_bits &
          (KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER | KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER)) ==
         (KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER | KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER);
}

void HeaderAndArgumentValidation() {
  PowerFixture fixture;
  uint64_t handle = 0;
  KWEB_CHECK(kweb_power_open(nullptr, &handle) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  kweb_power_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_power_open(&configuration, nullptr) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.struct_size = sizeof(configuration) - 1u;
  KWEB_CHECK(kweb_power_open(&configuration, &handle) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.abi_version = KWEB_POWER_ABI_VERSION + 1u;
  KWEB_CHECK(kweb_power_open(&configuration, &handle) == KWEB_POWER_STATUS_ABI_MISMATCH);
  configuration = fixture.Configuration();
  configuration.application_id = kweb_power_string{nullptr, 0};
  KWEB_CHECK(kweb_power_open(&configuration, &handle) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
}

void LeaseLifecycleAndBounds() {
  PowerFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(!std::string(capabilities.provider_id).empty());
  KWEB_CHECK(capabilities.max_leases == KWEB_POWER_MAX_LEASES);
  KWEB_CHECK(capabilities.max_lease_seconds == 14400);
  KWEB_CHECK(capabilities.min_lease_seconds == 1);

  kweb_power_lease_result result{};
  // Rejected requests never touch the provider.
  auto bad_id = fixture.Request("1bad", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Bad identifier", 60);
  KWEB_CHECK(fixture.Acquire(handle, bad_id, &result) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  auto blank = fixture.Request("blank", KWEB_POWER_TYPE_SYSTEM_SLEEP, "   ", 60);
  KWEB_CHECK(fixture.Acquire(handle, blank, &result) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  auto zero = fixture.Request("zero", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Zero duration", 0);
  KWEB_CHECK(fixture.Acquire(handle, zero, &result) == KWEB_POWER_STATUS_INVALID_ARGUMENT);
  auto too_long = fixture.Request("long", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Too long", 14401);
  KWEB_CHECK(fixture.Acquire(handle, too_long, &result) == KWEB_POWER_STATUS_DURATION_EXCEEDED);
  auto bad_type = fixture.Request("type", 99u, "Unknown type", 60);
  KWEB_CHECK(fixture.Acquire(handle, bad_type, &result) == KWEB_POWER_STATUS_INVALID_ARGUMENT);

  if (!SupportsBlockers(capabilities)) {
    std::printf("SKIP: this session declares no sleep blocker.\n");
    KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
    return;
  }

  // A live lease reports its declared window, and a duplicate identifier fails.
  auto render = fixture.Request("render", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Render a report", 600);
  KWEB_CHECK(fixture.Acquire(handle, render, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(std::strcmp(result.lease_id, "render") == 0);
  KWEB_CHECK(result.granted_seconds == 600);
  KWEB_CHECK(result.remaining_seconds == 600);
  auto duplicate = fixture.Request("render", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Duplicate", 60);
  KWEB_CHECK(fixture.Acquire(handle, duplicate, &result) == KWEB_POWER_STATUS_ALREADY_EXISTS);

  // A second lease of the other type composes without touching the first.
  auto display = fixture.Request("display", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Present slides", 120);
  KWEB_CHECK(fixture.Acquire(handle, display, &result) == KWEB_POWER_STATUS_OK);
  kweb_power_lease_list_result list{};
  KWEB_CHECK(fixture.Leases(handle, &list) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(list.count == 2);

  // Renewal extends the window and counts.
  KWEB_CHECK(fixture.Renew(handle, "render", &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(result.renew_count == 1);
  KWEB_CHECK(result.remaining_seconds == 600);

  // Releasing one lease leaves the other live, and an unknown release is idempotent.
  kweb_power_release_result released{};
  KWEB_CHECK(fixture.Release(handle, "render", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(released.released == 1u);
  KWEB_CHECK(fixture.Release(handle, "render", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(released.released == 0u);
  KWEB_CHECK(fixture.Leases(handle, &list) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(list.count == 1);
  KWEB_CHECK(std::strcmp(list.leases[0].lease_id, "display") == 0);

  // Renewal of a released lease is typed.
  KWEB_CHECK(fixture.Renew(handle, "render", &result) == KWEB_POWER_STATUS_LEASE_UNKNOWN);

  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OWNER_CLOSED);
}

void LeasesExpireExactlyOnce() {
  PowerFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  if (!SupportsBlockers(capabilities)) {
    std::printf("SKIP: this session declares no sleep blocker.\n");
    KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
    return;
  }
  kweb_power_lease_result result{};
  auto short_lease = fixture.Request("short", KWEB_POWER_TYPE_SYSTEM_SLEEP, "One second", 1);
  KWEB_CHECK(fixture.Acquire(handle, short_lease, &result) == KWEB_POWER_STATUS_OK);

  // The deadline expires without an explicit release and publishes one event.
  kweb_power_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_POWER_ABI_VERSION;
  bool expired = false;
  for (int waited = 0; waited <= 4000 && !expired; waited += 50) {
    kweb_power_event candidate{};
    candidate.struct_size = sizeof(candidate);
    candidate.abi_version = KWEB_POWER_ABI_VERSION;
    while (kweb_power_poll_event(handle, &candidate) == KWEB_POWER_STATUS_OK) {
      if (candidate.kind == KWEB_POWER_EVENT_LEASE_ENDED &&
          std::strcmp(candidate.lease_id, "short") == 0) {
        event = candidate;
        expired = true;
        break;
      }
    }
    if (!expired) std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  KWEB_CHECK(expired);
  KWEB_CHECK(event.reason == KWEB_POWER_END_EXPIRED);
  kweb_power_lease_list_result list{};
  KWEB_CHECK(fixture.Leases(handle, &list) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(list.count == 0);

  // The expired identifier is gone, so a fresh acquire of the same id succeeds.
  auto again = fixture.Request("short", KWEB_POWER_TYPE_SYSTEM_SLEEP, "One second again", 1);
  KWEB_CHECK(fixture.Acquire(handle, again, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
}

void StateKeepsUnpublishedFactsAbsent() {
  PowerFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_power_state_result state = fixture.State(handle);
  KWEB_CHECK((state.fact_bits & ~(KWEB_POWER_FACT_ON_BATTERY | KWEB_POWER_FACT_BATTERY_PERCENT |
                                  KWEB_POWER_FACT_THERMAL | KWEB_POWER_FACT_LOCKED)) == 0);
  if ((state.fact_bits & KWEB_POWER_FACT_BATTERY_PERCENT) != 0) {
    KWEB_CHECK(state.battery_percent <= 100);
  }
  if ((state.fact_bits & KWEB_POWER_FACT_THERMAL) != 0) {
    KWEB_CHECK(state.thermal >= KWEB_POWER_THERMAL_NOMINAL && state.thermal <= KWEB_POWER_THERMAL_CRITICAL);
  }
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
}

void CloseReleasesEveryLease() {
  PowerFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  if (!SupportsBlockers(capabilities)) {
    std::printf("SKIP: this session declares no sleep blocker.\n");
    KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
    return;
  }
  kweb_power_lease_result result{};
  auto one = fixture.Request("one", KWEB_POWER_TYPE_SYSTEM_SLEEP, "First lease", 60);
  auto two = fixture.Request("two", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Second lease", 60);
  KWEB_CHECK(fixture.Acquire(handle, one, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(fixture.Acquire(handle, two, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
  kweb_power_lease_list_result list{};
  KWEB_CHECK(fixture.Leases(handle, &list) == KWEB_POWER_STATUS_OWNER_CLOSED);
}

}  // namespace

int main() {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  HeaderAndArgumentValidation();
  LeaseLifecycleAndBounds();
  LeasesExpireExactlyOnce();
  StateKeepsUnpublishedFactsAbsent();
  CloseReleasesEveryLease();
  std::printf("kweb power portable checks: %d failures.\n", g_failures);
  const int platform_failures = RunPlatformNativeStructureTest();
  if (platform_failures > 0) {
    std::printf("kweb power provider checks: %d failures.\n", platform_failures);
  }
  if (g_failures == 0) {
    std::printf("kweb power native checks passed.\n");
    return 0;
  }
  std::printf("%d native checks failed.\n", g_failures);
  return 1;
}
