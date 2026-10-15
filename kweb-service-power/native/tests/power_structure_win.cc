#include "power_platform_test.h"

#if defined(_WIN32)

#include "power_tests_fixture.h"

#include <cstring>
#include <string>

/** The Win32 message loop runs on the provider thread; the test thread is idle. */
void PowerFixture::pump_platform_queue() {}

/** The real Win32 power-request, power-status, and capability facts. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  PowerFixture fixture;
  uint64_t handle = 0;
  kweb_power_configuration configuration = fixture.Configuration();
  const kweb_power_status opened = kweb_power_open(&configuration, &handle);
  if (opened != KWEB_POWER_STATUS_OK) {
    std::printf("SKIP: the Win32 power host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "power.windows.power-request") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SUSPEND_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_LOCK_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_POWER_SOURCE_STATE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER) != 0);
  // Windows publishes no thermal level through the declared facility.
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_THERMAL_STATE) == 0);

  const kweb_power_state_result state = fixture.State(handle);
  KWEB_CHECK((state.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0);

  kweb_power_lease_result result{};
  auto system_lease = fixture.Request("system", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Keep working", 600);
  KWEB_CHECK(fixture.Acquire(handle, system_lease, &result) == KWEB_POWER_STATUS_OK);
  auto display_lease = fixture.Request("display", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Show progress", 600);
  KWEB_CHECK(fixture.Acquire(handle, display_lease, &result) == KWEB_POWER_STATUS_OK);
  kweb_power_lease_list_result list{};
  KWEB_CHECK(fixture.Leases(handle, &list) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(list.count == 2);
  kweb_power_release_result released{};
  KWEB_CHECK(fixture.Release(handle, "system", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(released.released == 1u);
  KWEB_CHECK(fixture.Release(handle, "display", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(released.released == 1u);
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
  return g_failures - failures_before;
}

#endif
