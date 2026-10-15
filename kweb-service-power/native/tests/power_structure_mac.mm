#include "power_platform_test.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <IOKit/pwr_mgt/IOPMLib.h>

#include "power_tests_fixture.h"

#include <cstring>
#include <string>
#include <sys/wait.h>
#include <unistd.h>

/** The provider observes on the main queue; nothing else to pump. */
void PowerFixture::pump_platform_queue() {}

namespace {

/** The live IOPMAssertion count the operating system reports for one process. */
int LiveAssertionsForPid(pid_t pid) {
  CFDictionaryRef by_process = nullptr;
  if (IOPMCopyAssertionsByProcess(&by_process) != kIOReturnSuccess || by_process == nullptr) return -1;
  // A process with no assertions simply has no entry, which is a count of zero
  // rather than a query failure.
  int count = 0;
  const CFNumberRef key = CFNumberCreate(kCFAllocatorDefault, kCFNumberIntType, &pid);
  if (key != nullptr) {
    const void *value = CFDictionaryGetValue(by_process, key);
    if (value != nullptr && CFGetTypeID(value) == CFArrayGetTypeID()) {
      count = static_cast<int>(CFArrayGetCount(static_cast<CFArrayRef>(value)));
    }
    CFRelease(key);
  }
  CFRelease(by_process);
  return count;
}

bool WaitForAssertionCount(pid_t pid, int expected, int timeout_ms) {
  for (int waited = 0; waited <= timeout_ms; waited += 50) {
    if (LiveAssertionsForPid(pid) == expected) return true;
    PowerFixture::pump_platform_queue();
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
  }
  return false;
}

/** Acquires one lease in a child process that exits without releasing it. */
void RunCrashCleanupChild() {
  PowerFixture child_fixture;
  uint64_t child_handle = 0;
  kweb_power_configuration configuration = child_fixture.Configuration();
  if (kweb_power_open(&configuration, &child_handle) != KWEB_POWER_STATUS_OK) _exit(3);
  kweb_power_lease_request request =
      child_fixture.Request("crash", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Crash cleanup probe", 600);
  kweb_power_lease_result result{};
  result.struct_size = sizeof(result);
  result.abi_version = KWEB_POWER_ABI_VERSION;
  if (kweb_power_acquire(child_handle, &request, &result) != KWEB_POWER_STATUS_OK) _exit(4);
  std::this_thread::sleep_for(std::chrono::milliseconds(700));
  // The owner dies with the assertion live; the kernel must reclaim it.
  _exit(0);
}

}  // namespace

/** The real IOPMAssertion, NSWorkspace, and IOKit power facts. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;

  // 1. A crashed owner leaves no live assertion behind.
  const pid_t child = fork();
  if (child == 0) {
    RunCrashCleanupChild();
    _exit(5);
  }
  KWEB_CHECK(child > 0);
  if (child > 0) {
    const bool observed = WaitForAssertionCount(child, 1, 4000);
    KWEB_CHECK(observed);
    int status = 0;
    waitpid(child, &status, 0);
    KWEB_CHECK(WIFEXITED(status));
    const bool reclaimed = WaitForAssertionCount(child, 0, 6000);
    KWEB_CHECK(reclaimed);
  }

  PowerFixture fixture;
  uint64_t handle = 0;
  kweb_power_configuration configuration = fixture.Configuration();
  const kweb_power_status opened = kweb_power_open(&configuration, &handle);
  if (opened != KWEB_POWER_STATUS_OK) {
    std::printf("SKIP: the macOS power host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "power.macos.workspace-iokit") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SUSPEND_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_LOCK_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_POWER_SOURCE_STATE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_THERMAL_STATE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER) != 0);

  const kweb_power_state_result state = fixture.State(handle);
  KWEB_CHECK((state.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0);
  KWEB_CHECK((state.fact_bits & KWEB_POWER_FACT_THERMAL) != 0);

  // 2. Two live leases hold two real assertions, and releasing them returns to
  // the process baseline.
  const int baseline = LiveAssertionsForPid(getpid());
  KWEB_CHECK(baseline >= 0);
  kweb_power_lease_result result{};
  auto system_lease = fixture.Request("system", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Keep working", 600);
  auto display_lease = fixture.Request("display", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Show progress", 600);
  KWEB_CHECK(fixture.Acquire(handle, system_lease, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(fixture.Acquire(handle, display_lease, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(WaitForAssertionCount(getpid(), baseline + 2, 2000));
  kweb_power_release_result released{};
  KWEB_CHECK(fixture.Release(handle, "system", &released) == KWEB_POWER_STATUS_OK);
  // Releasing one lease never releases the other.
  KWEB_CHECK(WaitForAssertionCount(getpid(), baseline + 1, 2000));
  KWEB_CHECK(fixture.Release(handle, "display", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(WaitForAssertionCount(getpid(), baseline, 2000));

  // 3. The real NSWorkspace notifications publish ordered events. The lease
  // checks above already published their end events, so drain them first.
  fixture.DrainEvents(handle);
  NSNotificationCenter *workspace = NSWorkspace.sharedWorkspace.notificationCenter;
  kweb_power_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_POWER_ABI_VERSION;
  [workspace postNotificationName:NSWorkspaceWillSleepNotification object:nil];
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 2000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_SUSPENDED);
  [workspace postNotificationName:NSWorkspaceDidWakeNotification object:nil];
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 2000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_RESUMED);
  [workspace postNotificationName:NSWorkspaceSessionDidResignActiveNotification object:nil];
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 2000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_LOCKED);
  [workspace postNotificationName:NSWorkspaceSessionDidBecomeActiveNotification object:nil];
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 2000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_UNLOCKED);

  // 4. A thermal notification that changes nothing publishes no event.
  [NSNotificationCenter.defaultCenter postNotificationName:NSProcessInfoThermalStateDidChangeNotification
                                                    object:nil];
  kweb_power_event spurious{};
  spurious.struct_size = sizeof(spurious);
  spurious.abi_version = KWEB_POWER_ABI_VERSION;
  const bool emitted = fixture.AwaitEvent(handle, &spurious, 500);
  if (emitted) KWEB_CHECK(spurious.kind != KWEB_POWER_EVENT_THERMAL_CHANGED);

  // 5. Closing with live leases releases every assertion and reports each end.
  auto one = fixture.Request("close-one", KWEB_POWER_TYPE_SYSTEM_SLEEP, "First", 60);
  auto two = fixture.Request("close-two", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Second", 60);
  KWEB_CHECK(fixture.Acquire(handle, one, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(fixture.Acquire(handle, two, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(WaitForAssertionCount(getpid(), baseline + 2, 2000));
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(WaitForAssertionCount(getpid(), baseline, 2000));

  return g_failures - failures_before;
}

#endif
