/**
 * The real-process scenarios every target runs: declared streams and their
 * backpressure, bounds and quotas, exit and crash classification, kill trees,
 * owner shutdown, and bounded stdin against a real child process.
 */
#include "processes_tests_fixture.h"

#include <algorithm>
#include <cstdio>
#include <string>
#include <vector>

namespace {

using namespace kweb_processes_fixture;

constexpr uint64_t kMebibyte = 1024ull * 1024ull;

/** The declared environment of every scenario: an allowlist, never inherited. */
constexpr const char *kInheritedVariable = "KWEB_PROCESSES_INHERITED";

void ScenarioReportRoundTrip(uint64_t service, const Fixture &fixture) {
  StartRequest request;
  request.process_id = "report";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"report", "h\xc3\xa9llo-processes", "argument with spaces"};
  request.environment = {"KWEB_DECLARED=1", "KWEB_SECOND=value"};
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome outcome = Drain(process, 20000);
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_EXITED);
  KWEB_CHECK(outcome.termination.exit_code == 0);
  KWEB_CHECK(outcome.termination.signal == 0);

  const int child = ReportedChildPid(outcome);
  if (!outcome.StdoutContains("report-end\n")) {
    // A truncated transcript is printed so the failure names the missing bytes
    // instead of only the assertion that noticed them.
    std::printf("report transcript (%zu bytes):\n%.*s\n", outcome.stdout_bytes.size(),
                static_cast<int>(outcome.stdout_bytes.size()),
                reinterpret_cast<const char *>(outcome.stdout_bytes.data()));
  }
  KWEB_CHECK(child > 0);
  KWEB_CHECK(outcome.StdoutContains("cwd " + HexPath(fixture.working_directory) + "\n"));
  KWEB_CHECK(outcome.StdoutContains("argc 3\n"));
  KWEB_CHECK(outcome.StdoutContains("arg 0 " + HexText("report") + "\n"));
  KWEB_CHECK(outcome.StdoutContains("arg 1 " + HexText("h\xc3\xa9llo-processes") + "\n"));
  KWEB_CHECK(outcome.StdoutContains("arg 2 " + HexText("argument with spaces") + "\n"));
#if !defined(_WIN32)
  KWEB_CHECK(outcome.StdoutContains("ppid " + std::to_string(CurrentProcessId()) + "\n"));
#endif
  // The declared allowlist is the child's whole environment: the inherited probe
  // variable is absent and both declared names are present.
  std::vector<std::string> names;
  for (const std::string &line : outcome.StdoutLines()) {
    if (line.rfind("env-name ", 0) == 0) names.push_back(line.substr(9));
  }
  KWEB_CHECK(names.size() == 2);
  bool declared = false;
  bool second = false;
  bool inherited = false;
  for (const std::string &name : names) {
    if (name == "KWEB_DECLARED") declared = true;
    if (name == "KWEB_SECOND") second = true;
    if (name == kInheritedVariable) inherited = true;
  }
  KWEB_CHECK(declared);
  KWEB_CHECK(second);
  KWEB_CHECK(!inherited);
  KWEB_CHECK(ProcessIsGone(child, 3000));
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioStreamBackpressure(uint64_t service, const Fixture &fixture) {
  const uint64_t burst = 8ull * kMebibyte;
  StartRequest request;
  request.process_id = "burst";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"stdout-burst", std::to_string(burst)};
  request.stdout_window_bytes = KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES;
  request.stderr_window_bytes = KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES;
  request.stdout_quota_bytes = 16ull * kMebibyte;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  // A slow consumer applies real backpressure: the child cannot finish faster
  // than the window and the consumer pace allow.
  const Outcome outcome = Drain(process, 120000, 2);
  KWEB_CHECK(outcome.ended);
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_EXITED);
  KWEB_CHECK(outcome.termination.exit_code == 0);
  KWEB_CHECK(outcome.stdout_bytes.size() == burst);
  KWEB_CHECK(outcome.termination.stdout_bytes == burst);
  bool ordered = true;
  for (size_t index = 0; index < outcome.stdout_bytes.size(); ++index) {
    if (outcome.stdout_bytes[index] != PatternByte(index)) ordered = false;
  }
  KWEB_CHECK(ordered);
  KWEB_CHECK(outcome.first_delivered_after_closure == 0);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioOutputQuota(uint64_t service, const Fixture &fixture) {
  const auto started = std::chrono::steady_clock::now();
  StartRequest request;
  request.process_id = "quota";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"stdout-burst", std::to_string(64ull * kMebibyte)};
  request.stdout_quota_bytes = KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES;
  request.stdout_window_bytes = 4096;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome outcome = Drain(process, 60000);
  const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - started)
                           .count();
  KWEB_CHECK(outcome.ended);
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_OUTPUT_LIMIT);
  KWEB_CHECK(elapsed < 30000);
  bool ordered = true;
  for (size_t index = 0; index < outcome.stdout_bytes.size(); ++index) {
    if (outcome.stdout_bytes[index] != PatternByte(index)) ordered = false;
  }
  KWEB_CHECK(ordered);
  KWEB_CHECK(outcome.stdout_bytes.size() >= 1);
  // The provider reads ahead of the quota by at most one chunk, and never
  // silently truncates what the child already produced.
  KWEB_CHECK(outcome.stdout_bytes.size() <= KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES +
                                                 KWEB_PROCESSES_MAX_OUTPUT_CHUNK);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioStderrQuotaAndBoundedStdin(uint64_t service, const Fixture &fixture) {
  StartRequest stderr_request;
  stderr_request.process_id = "stderr-quota";
  stderr_request.executable_sha256 = fixture.digest;
  stderr_request.working_directory = fixture.working_directory;
  stderr_request.arguments = {"stderr-burst", std::to_string(64ull * kMebibyte)};
  stderr_request.stderr_quota_bytes = KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES;
  stderr_request.stderr_window_bytes = 4096;
  uint64_t process = 0;
  KWEB_CHECK(stderr_request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome stderr_outcome = Drain(process, 60000);
  KWEB_CHECK(stderr_outcome.termination.reason == KWEB_PROCESSES_REASON_OUTPUT_LIMIT);
  KWEB_CHECK(stderr_outcome.stderr_bytes.size() >= 1);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);

  // A bounded stdin round trip: bytes written by the host arrive exactly once.
  StartRequest echo_request;
  echo_request.process_id = "echo";
  echo_request.executable_sha256 = fixture.digest;
  echo_request.working_directory = fixture.working_directory;
  echo_request.arguments = {"echo"};
  KWEB_CHECK(echo_request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  std::vector<uint8_t> payload;
  for (size_t index = 0; index < 81920; ++index) payload.push_back(PatternByte(index));
  size_t offset = 0;
  while (offset < payload.size()) {
    const size_t take = std::min<size_t>(32768, payload.size() - offset);
    uint64_t accepted = 0;
    KWEB_CHECK(kweb_processes_write_stdin(process, payload.data() + offset, take, 5000, &accepted) ==
               KWEB_PROCESSES_STATUS_OK);
    KWEB_CHECK(accepted == take);
    offset += take;
  }
  KWEB_CHECK(kweb_processes_close_stdin(process) == KWEB_PROCESSES_STATUS_OK);
  KWEB_CHECK(kweb_processes_close_stdin(process) == KWEB_PROCESSES_STATUS_STDIN_CLOSED);
  const Outcome echo_outcome = Drain(process, 30000);
  KWEB_CHECK(echo_outcome.termination.reason == KWEB_PROCESSES_REASON_EXITED);
  KWEB_CHECK(echo_outcome.stdout_bytes == payload);
  KWEB_CHECK(echo_outcome.StderrText() == "echo 81920\n");
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);

  // A child that never reads its stdin reports a typed timeout instead of
  // blocking a host thread, and its closed pipe reports a typed close.
  StartRequest stubborn;
  stubborn.process_id = "stubborn-stdin";
  stubborn.executable_sha256 = fixture.digest;
  stubborn.working_directory = fixture.working_directory;
  stubborn.arguments = {"sleep", "30"};
  stubborn.timeout_seconds = 30;
  KWEB_CHECK(stubborn.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  std::vector<uint8_t> chunk(KWEB_PROCESSES_MAX_STDIN_CHUNK, 's');
  bool timed_out = false;
  for (int attempt = 0; attempt < 8 && !timed_out; ++attempt) {
    uint64_t accepted = 0;
    const kweb_processes_status status =
        kweb_processes_write_stdin(process, chunk.data(), chunk.size(), 400, &accepted);
    if (status == KWEB_PROCESSES_STATUS_STDIN_TIMEOUT) {
      timed_out = true;
    } else {
      KWEB_CHECK(status == KWEB_PROCESSES_STATUS_OK);
      KWEB_CHECK(accepted == chunk.size());
    }
  }
  KWEB_CHECK(timed_out);
  const kweb_processes_termination killed = Terminate(process, 0);
  KWEB_CHECK(killed.reason == KWEB_PROCESSES_REASON_KILLED);
  uint64_t accepted = 0;
  KWEB_CHECK(kweb_processes_write_stdin(process, chunk.data(), chunk.size(), 100, &accepted) ==
             KWEB_PROCESSES_STATUS_STDIN_CLOSED);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioTimeout(uint64_t service, const Fixture &fixture) {
  const auto started = std::chrono::steady_clock::now();
  StartRequest request;
  request.process_id = "timeout";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"announce", "30"};
  request.timeout_seconds = 1;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const int child = ReadAnnouncedChildPid(process, 5000);
  KWEB_CHECK(child > 0);
  const Outcome outcome = Drain(process, 20000);
  const auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(
                           std::chrono::steady_clock::now() - started)
                           .count();
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_TIMEOUT);
  KWEB_CHECK(elapsed >= 900);
  KWEB_CHECK(elapsed < 15000);
  KWEB_CHECK(ProcessIsGone(child, 5000));
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioCpuLimit(uint64_t service, const Fixture &fixture) {
  StartRequest request;
  request.process_id = "cpu";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"spin"};
  request.cpu_seconds = 1;
  request.timeout_seconds = 60;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome outcome = Drain(process, 45000);
  // The recorded accounting is printed because it is the retained evidence that
  // the declared CPU quota, not a crash, ended this child.
  std::printf("cpu scenario: reason=%u cpu_millis=%llu signal=%d exit=%d duration=%llu\n",
              outcome.termination.reason,
              static_cast<unsigned long long>(outcome.termination.cpu_millis), outcome.termination.signal,
              outcome.termination.exit_code,
              static_cast<unsigned long long>(outcome.termination.duration_millis));
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_CPU_LIMIT);
#if !defined(_WIN32)
  // The operating system signals the declared CPU quota; on Windows the sampled
  // accounting is what ends the child.
  KWEB_CHECK(outcome.termination.signal == SIGXCPU);
#endif
  KWEB_CHECK(outcome.termination.cpu_millis >= 500);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioMemoryLimit(uint64_t service, const Fixture &fixture) {
  StartRequest request;
  request.process_id = "memory";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"hold", "256"};
  request.memory_bytes = 64ull * kMebibyte;
  request.timeout_seconds = 60;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome outcome = Drain(process, 45000);
  // The recorded accounting is printed because it is the retained evidence that
  // the declared memory cap, not a crash, ended this child.
  std::printf("memory scenario: reason=%u peak_bytes=%llu signal=%d exit=%d duration=%llu out=%s\n",
              outcome.termination.reason,
              static_cast<unsigned long long>(outcome.termination.peak_memory_bytes),
              outcome.termination.signal, outcome.termination.exit_code,
              static_cast<unsigned long long>(outcome.termination.duration_millis),
              outcome.StdoutText().c_str());
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_MEMORY_LIMIT);
  KWEB_CHECK(outcome.termination.peak_memory_bytes >= 64ull * kMebibyte);
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioKillTree(uint64_t service, const Fixture &fixture) {
  StartRequest request;
  request.process_id = "tree";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"tree"};
  request.timeout_seconds = 60;
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const int child = ReadAnnouncedChildPid(process, 10000);
  KWEB_CHECK(child > 0);
  if (child <= 0) {
    // A child that never announced its pid has no tree to check; the outcome is
    // still asserted so the missing announcement is visible in the transcript.
    const kweb_processes_termination missing = Terminate(process, 0);
    KWEB_CHECK(missing.reason == KWEB_PROCESSES_REASON_KILLED);
    KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
    return;
  }
#if !defined(_WIN32)
  // The whole tree shares one process group, and it is not the test's group: that
  // is what makes a single kill reach every descendant. Windows owns the tree in a
  // job object instead, which the target structure test asserts directly.
  KWEB_CHECK(ProcessGroup(child) != ProcessGroup(CurrentProcessId()));
#endif
  const kweb_processes_termination terminated = Terminate(process, 0);
  KWEB_CHECK(terminated.reason == KWEB_PROCESSES_REASON_KILLED);
  KWEB_CHECK(ProcessIsGone(child, 5000));
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioCrash(uint64_t service, const Fixture &fixture) {
  StartRequest request;
  request.process_id = "crash";
  request.executable_sha256 = fixture.digest;
  request.working_directory = fixture.working_directory;
  request.arguments = {"crash"};
  uint64_t process = 0;
  KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
  const Outcome outcome = Drain(process, 30000);
  KWEB_CHECK(outcome.termination.reason == KWEB_PROCESSES_REASON_CRASHED);
  KWEB_CHECK(outcome.termination.signal > 0);
#if !defined(_WIN32)
  KWEB_CHECK(outcome.termination.signal == SIGABRT);
#endif
  KWEB_CHECK(kweb_processes_release(process) == KWEB_PROCESSES_STATUS_OK);
}

void ScenarioOwnerShutdownAndLiveLimit(uint64_t service, const Fixture &fixture) {
  const uint32_t baseline = fixture.LiveProcesses(service);
  std::vector<int> children;
  std::vector<uint64_t> handles;
  for (uint32_t index = 0; index < KWEB_PROCESSES_MAX_LIVE; ++index) {
    StartRequest request;
    request.process_id = "owner-" + std::to_string(index);
    request.executable_sha256 = fixture.digest;
    request.working_directory = fixture.working_directory;
    request.arguments = {"announce", "30"};
    request.timeout_seconds = 60;
    uint64_t process = 0;
    KWEB_CHECK(request.Start(service, &process, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OK);
    const int child = ReadAnnouncedChildPid(process, 10000);
    KWEB_CHECK(child > 0);
    children.push_back(child);
    handles.push_back(process);
  }
  KWEB_CHECK(fixture.LiveProcesses(service) == baseline + KWEB_PROCESSES_MAX_LIVE);

  StartRequest overflow;
  overflow.process_id = "owner-overflow";
  overflow.executable_sha256 = fixture.digest;
  overflow.working_directory = fixture.working_directory;
  overflow.arguments = {"announce", "30"};
  uint64_t refused = 0;
  KWEB_CHECK(overflow.Start(service, &refused, nullptr, nullptr) == KWEB_PROCESSES_STATUS_LIMIT_EXCEEDED);
  KWEB_CHECK(refused == 0);

  // Releasing one live handle terminates exactly its own tree.
  KWEB_CHECK(kweb_processes_release(handles.back()) == KWEB_PROCESSES_STATUS_OK);
  handles.pop_back();
  const int released_child = children.back();
  children.pop_back();
  KWEB_CHECK(ProcessIsGone(released_child, 5000));
  for (int child : children) {
    if (child > 0) KWEB_CHECK(ProcessIsAlive(child));
  }
  KWEB_CHECK(fixture.LiveProcesses(service) == baseline + handles.size());

  // Closing the service terminates every remaining tree and releases its records.
  KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
  for (int child : children) {
    if (child > 0) KWEB_CHECK(ProcessIsGone(child, 5000));
  }
  KWEB_CHECK(ProcessIsGone(released_child, 5000));
}

}  // namespace

int RunSharedStructureScenarios() {
  const int failures_before = g_failures;
#if !defined(_WIN32)
  setenv(kInheritedVariable, "inherited-value", 1);
#else
  SetEnvironmentVariableW(L"KWEB_PROCESSES_INHERITED", L"inherited-value");
#endif

  Fixture fixture;
  if (fixture.digest.empty() || fixture.working_directory.empty()) {
    std::printf("FAIL: the fixture could not read the helper or create its directory.\n");
    g_failures += 1;
    return g_failures - failures_before;
  }
  uint64_t service = 0;
  kweb_processes_configuration configuration = fixture.Configuration();
  const kweb_processes_status opened = kweb_processes_open(&configuration, &service);
  if (opened != KWEB_PROCESSES_STATUS_OK) {
    std::printf("FAIL: the process service did not open (status %u).\n", opened);
    g_failures += 1;
    return g_failures - failures_before;
  }

  ScenarioReportRoundTrip(service, fixture);
  ScenarioStreamBackpressure(service, fixture);
  ScenarioOutputQuota(service, fixture);
  ScenarioStderrQuotaAndBoundedStdin(service, fixture);
  ScenarioTimeout(service, fixture);
  ScenarioCpuLimit(service, fixture);
  ScenarioMemoryLimit(service, fixture);
  ScenarioKillTree(service, fixture);
  ScenarioCrash(service, fixture);
  ScenarioOwnerShutdownAndLiveLimit(service, fixture);
  return g_failures - failures_before;
}
