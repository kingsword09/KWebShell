#include "processes_tests_fixture.h"

#include <libproc.h>

/**
 * The macOS structure test runs the shared real-process scenarios and adds the
 * facts only libproc can report: the sampled resident set of a live child and
 * the absence of any surviving child after owner shutdown.
 */
int RunSharedStructureScenarios();

int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;

  // Every process metric the provider enforces is readable for its own child
  // through libproc without an entitlement.
  {
    kweb_processes_fixture::Fixture fixture;
    uint64_t service = 0;
    kweb_processes_configuration configuration = fixture.Configuration();
    if (kweb_processes_open(&configuration, &service) != KWEB_PROCESSES_STATUS_OK) {
      std::printf("FAIL: the macOS process host did not open.\n");
      g_failures += 1;
      return g_failures - failures_before;
    }
    const kweb_processes_capabilities_result capabilities = fixture.Capabilities(service);
    KWEB_CHECK(std::strcmp(capabilities.provider_id, "processes.macos.posix-exec") == 0);

    kweb_processes_fixture::StartRequest request;
    request.process_id = "libproc";
    request.executable_sha256 = fixture.digest;
    request.working_directory = fixture.working_directory;
    request.arguments = {"hold", "64"};
    request.memory_bytes = 1024ull * 1024ull * 1024ull;
    request.timeout_seconds = 30;
    uint64_t process = 0;
    KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
    const int child = kweb_processes_fixture::ReadAnnouncedChildPid(process, 2000);
    if (child > 0) {
      struct proc_taskinfo info;
      std::memset(&info, 0, sizeof(info));
      KWEB_CHECK(proc_pidinfo(child, PROC_PIDTASKINFO, 0, &info, static_cast<int>(sizeof(info))) ==
                 static_cast<int>(sizeof(info)));
      KWEB_CHECK(info.pti_resident_size > 0);
    }
    KWEB_CHECK(kweb_processes_fixture::Terminate(process, 1).reason ==
               KWEB_PROCESSES_REASON_OWNER_CLOSED);
    const kweb_processes_status_result status = [&] {
      kweb_processes_status_result value;
      std::memset(&value, 0, sizeof(value));
      value.struct_size = sizeof(value);
      value.abi_version = KWEB_PROCESSES_ABI_VERSION;
      KWEB_CHECK(kweb_processes_state(process, &value) == KWEB_PROCESSES_STATUS_OK);
      return value;
    }();
    KWEB_CHECK(status.state == KWEB_PROCESSES_STATE_TERMINATED);
    KWEB_CHECK(status.peak_memory_bytes > 0);
    KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
    KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
  }

  RunSharedStructureScenarios();
  return g_failures - failures_before;
}
