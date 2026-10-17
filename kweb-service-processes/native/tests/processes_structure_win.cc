#include "processes_tests_fixture.h"

/**
 * The Windows structure test runs the shared real-process scenarios and adds the
 * facts only the Win32 job object can report: the job owns the child, and
 * terminating the job leaves no descendant behind.
 */
int RunSharedStructureScenarios();

int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;

  {
    kweb_processes_fixture::Fixture fixture;
    uint64_t service = 0;
    kweb_processes_configuration configuration = fixture.Configuration();
    if (kweb_processes_open(&configuration, &service) != KWEB_PROCESSES_STATUS_OK) {
      std::printf("FAIL: the Windows process host did not open.\n");
      g_failures += 1;
      return g_failures - failures_before;
    }
    const kweb_processes_capabilities_result capabilities = fixture.Capabilities(service);
    KWEB_CHECK(std::strcmp(capabilities.provider_id, "processes.windows.job-object") == 0);

    kweb_processes_fixture::StartRequest request;
    request.process_id = "job-object";
    request.executable_sha256 = fixture.digest;
    request.working_directory = fixture.working_directory;
    request.arguments = {"tree"};
    request.timeout_seconds = 30;
    uint64_t process = 0;
    KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
    const int child = kweb_processes_fixture::ReadAnnouncedChildPid(process, 10000);
    KWEB_CHECK(child > 0);
    if (child > 0) {
      // The job owns the direct child, so the process handle the provider holds is
      // still alive while the declared timeout has not expired.
      const HANDLE handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(child));
      KWEB_CHECK(handle != nullptr);
      if (handle != nullptr) CloseHandle(handle);
    }
    const kweb_processes_termination terminated = kweb_processes_fixture::Terminate(process, 0);
    KWEB_CHECK(terminated.reason == KWEB_PROCESSES_REASON_KILLED);
    KWEB_CHECK(kweb_processes_fixture::ProcessIsGone(child, 5000));
    KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
    KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
  }

  RunSharedStructureScenarios();
  return g_failures - failures_before;
}
