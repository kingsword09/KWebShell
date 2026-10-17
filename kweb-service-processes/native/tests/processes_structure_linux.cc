#include "processes_tests_fixture.h"

/**
 * The Linux structure test runs the shared real-process scenarios and adds the
 * facts only /proc can report: the sampled resident set of a live child and the
 * process group a child leads.
 */
int RunSharedStructureScenarios();

namespace {

bool ReadProcField(int pid, const char *key, uint64_t *value) {
  char path[64];
  std::snprintf(path, sizeof(path), "/proc/%d/status", pid);
  std::ifstream stream(path);
  if (!stream.is_open()) return false;
  std::string line;
  bool found = false;
  while (std::getline(stream, line)) {
    if (line.rfind(key, 0) != 0) continue;
    unsigned long long parsed = 0;
    if (std::sscanf(line.c_str() + std::strlen(key), "%llu", &parsed) == 1) {
      *value = parsed;
      found = true;
    }
    break;
  }
  return found;
}

}  // namespace

int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;

  {
    kweb_processes_fixture::Fixture fixture;
    uint64_t service = 0;
    kweb_processes_configuration configuration = fixture.Configuration();
    if (kweb_processes_open(&configuration, &service) != KWEB_PROCESSES_STATUS_OK) {
      std::printf("FAIL: the Linux process host did not open.\n");
      g_failures += 1;
      return g_failures - failures_before;
    }
    const kweb_processes_capabilities_result capabilities = fixture.Capabilities(service);
    KWEB_CHECK(std::strcmp(capabilities.provider_id, "processes.linux.posix-exec") == 0);

    kweb_processes_fixture::StartRequest request;
    request.process_id = "procfs";
    request.executable_sha256 = fixture.digest;
    request.working_directory = fixture.working_directory;
    request.arguments = {"announce", "30"};
    request.timeout_seconds = 30;
    uint64_t process = 0;
    KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
    const int child = kweb_processes_fixture::ReadAnnouncedChildPid(process, 5000);
    KWEB_CHECK(child > 0);
    if (child > 0) {
      uint64_t resident_kilobytes = 0;
      KWEB_CHECK(ReadProcField(child, "VmRSS:", &resident_kilobytes));
      KWEB_CHECK(resident_kilobytes > 0);
      // The child leads the process group that one kill reaches.
      KWEB_CHECK(kweb_processes_fixture::ProcessGroup(child) == child);
      char path[64];
      std::snprintf(path, sizeof(path), "/proc/%d/stat", child);
      std::ifstream stat(path);
      KWEB_CHECK(stat.is_open());
    }
    const kweb_processes_termination terminated = kweb_processes_fixture::Terminate(process, 1);
    KWEB_CHECK(terminated.reason == KWEB_PROCESSES_REASON_OWNER_CLOSED);
    KWEB_CHECK(kweb_processes_fixture::ProcessIsGone(child, 5000));
    KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
    KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
  }

  RunSharedStructureScenarios();
  return g_failures - failures_before;
}
