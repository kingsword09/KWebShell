/**
 * The RFC 0024 portable ABI tests. Every case is an observable rule of the
 * published contract: validation before a provider sees a request, identity and
 * isolation refusals before a process exists, bounded buffers, and typed handle
 * errors. The real child behaviour lives in the target structure tests.
 */
#include "processes_tests_fixture.h"

#include <cstdio>
#include <string>
#include <vector>

namespace {

using namespace kweb_processes_fixture;

void TestAbiAndLifecycle() {
  KWEB_CHECK(kweb_processes_abi_version() == KWEB_PROCESSES_ABI_VERSION);

  Fixture fixture;
  KWEB_CHECK(!fixture.digest.empty());
  KWEB_CHECK(!fixture.working_directory.empty());

  kweb_processes_configuration wrong_size = fixture.Configuration();
  wrong_size.struct_size = sizeof(wrong_size) - 4;
  uint64_t ignored = 0;
  KWEB_CHECK(kweb_processes_open(&wrong_size, &ignored) == KWEB_PROCESSES_STATUS_ABI_MISMATCH);
  kweb_processes_configuration wrong_version = fixture.Configuration();
  wrong_version.abi_version = KWEB_PROCESSES_ABI_VERSION + 1;
  KWEB_CHECK(kweb_processes_open(&wrong_version, &ignored) == KWEB_PROCESSES_STATUS_ABI_MISMATCH);

  uint64_t service = 0;
  kweb_processes_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_processes_open(&configuration, &service) == KWEB_PROCESSES_STATUS_OK);
  KWEB_CHECK(service == 1);
  uint64_t second = 0;
  KWEB_CHECK(kweb_processes_open(&configuration, &second) == KWEB_PROCESSES_STATUS_ALREADY_OPEN);

  const kweb_processes_capabilities_result capabilities = fixture.Capabilities(service);
  KWEB_CHECK(capabilities.max_live_processes == KWEB_PROCESSES_MAX_LIVE);
  KWEB_CHECK(capabilities.min_timeout_seconds == KWEB_PROCESSES_MIN_TIMEOUT_SECONDS);
  KWEB_CHECK(capabilities.max_timeout_seconds == KWEB_PROCESSES_MAX_TIMEOUT_SECONDS);
  KWEB_CHECK(capabilities.min_memory_bytes == KWEB_PROCESSES_MIN_MEMORY_BYTES);
  KWEB_CHECK(capabilities.max_memory_bytes == KWEB_PROCESSES_MAX_MEMORY_BYTES);
  KWEB_CHECK(capabilities.min_output_quota_bytes == KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES);
  KWEB_CHECK(capabilities.max_output_quota_bytes == KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES);
  KWEB_CHECK(capabilities.max_stdin_chunk_bytes == KWEB_PROCESSES_MAX_STDIN_CHUNK);
  KWEB_CHECK(capabilities.max_arguments == KWEB_PROCESSES_MAX_ARGUMENTS);
  KWEB_CHECK(capabilities.max_environment == KWEB_PROCESSES_MAX_ENVIRONMENT);
  KWEB_CHECK(capabilities.memory_measure == KWEB_PROCESSES_MEMORY_RESIDENT);
  // The packaged provider declares exactly the isolation profiles it implements.
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_PROCESS_TREE) != 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS) != 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_SEATBELT) == 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_SECCOMP) == 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_NAMESPACES) == 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_SYSTEMD_SCOPE) == 0);
  KWEB_CHECK((capabilities.isolation_bits & KWEB_PROCESSES_ISOLATION_RESTRICTED_TOKEN) == 0);
  KWEB_CHECK(capabilities.provider_id[0] != '\0');
  KWEB_CHECK(capabilities.live_processes == 0);

  // The capability result requires the caller's own struct size and version.
  kweb_processes_capabilities_result stale;
  std::memset(&stale, 0, sizeof(stale));
  stale.struct_size = sizeof(stale);
  stale.abi_version = KWEB_PROCESSES_ABI_VERSION + 1;
  KWEB_CHECK(kweb_processes_capabilities(service, &stale) == KWEB_PROCESSES_STATUS_ABI_MISMATCH);
  KWEB_CHECK(kweb_processes_capabilities(1, nullptr) == KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  // Every per-process call reports an unknown handle as its own typed outcome.
  uint64_t accepted = 0;
  const uint8_t byte = 'x';
  KWEB_CHECK(kweb_processes_write_stdin(4096, &byte, 1, 100, &accepted) ==
             KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  KWEB_CHECK(kweb_processes_close_stdin(4096) == KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  KWEB_CHECK(kweb_processes_release(4096) == KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  uint8_t buffer[16];
  kweb_processes_output_chunk chunk;
  std::memset(&chunk, 0, sizeof(chunk));
  chunk.struct_size = sizeof(chunk);
  chunk.abi_version = KWEB_PROCESSES_ABI_VERSION;
  KWEB_CHECK(kweb_processes_read_output(4096, buffer, sizeof(buffer), 0, &chunk) ==
             KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  kweb_processes_status_result status_result;
  std::memset(&status_result, 0, sizeof(status_result));
  status_result.struct_size = sizeof(status_result);
  status_result.abi_version = KWEB_PROCESSES_ABI_VERSION;
  KWEB_CHECK(kweb_processes_state(4096, &status_result) == KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  kweb_processes_termination termination;
  std::memset(&termination, 0, sizeof(termination));
  termination.struct_size = sizeof(termination);
  termination.abi_version = KWEB_PROCESSES_ABI_VERSION;
  KWEB_CHECK(kweb_processes_wait(4096, 0, &termination) == KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  KWEB_CHECK(kweb_processes_terminate(4096, 0, &termination) == KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN);
  KWEB_CHECK(kweb_processes_terminate(1, 0, nullptr) == KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  // A closed service reports its own terminal outcome.
  KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
  KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OWNER_CLOSED);
  StartRequest after_close;
  after_close.process_id = "after-close";
  after_close.executable_sha256 = fixture.digest;
  uint64_t handle = 0;
  KWEB_CHECK(after_close.Start(service, &handle, nullptr, nullptr) == KWEB_PROCESSES_STATUS_OWNER_CLOSED);
  kweb_processes_capabilities_result after_close_capabilities;
  std::memset(&after_close_capabilities, 0, sizeof(after_close_capabilities));
  after_close_capabilities.struct_size = sizeof(after_close_capabilities);
  after_close_capabilities.abi_version = KWEB_PROCESSES_ABI_VERSION;
  KWEB_CHECK(kweb_processes_capabilities(service, &after_close_capabilities) ==
             KWEB_PROCESSES_STATUS_OWNER_CLOSED);
}

void TestRequestValidation() {
  Fixture fixture;
  uint64_t service = 0;
  kweb_processes_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_processes_open(&configuration, &service) == KWEB_PROCESSES_STATUS_OK);
  const uint32_t baseline = fixture.LiveProcesses(service);

  const auto expect = [&](StartRequest &request, kweb_processes_status expected) {
    uint64_t handle = 0;
    const kweb_processes_status status = request.Start(service, &handle, nullptr, nullptr);
    if (status != expected) std::printf("unexpected status %u for %s\n", status, request.process_id.c_str());
    KWEB_CHECK(status == expected);
    KWEB_CHECK(handle == 0);
    // A refused request never leaves a live process behind.
    KWEB_CHECK(fixture.LiveProcesses(service) == baseline);
  };

  StartRequest invalid_id;
  invalid_id.process_id = "invalid id with spaces";
  invalid_id.executable_sha256 = fixture.digest;
  invalid_id.working_directory = fixture.working_directory;
  expect(invalid_id, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest bad_digest;
  bad_digest.process_id = "bad-digest";
  bad_digest.executable_sha256 = "not-a-digest";
  bad_digest.working_directory = fixture.working_directory;
  expect(bad_digest, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest uppercase_digest;
  uppercase_digest.process_id = "upper-digest";
  uppercase_digest.executable_sha256 = fixture.digest;
  for (char &character : uppercase_digest.executable_sha256) {
    if (character >= 'a' && character <= 'f') character = static_cast<char>(character - 'a' + 'A');
  }
  uppercase_digest.working_directory = fixture.working_directory;
  expect(uppercase_digest, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest missing_isolation;
  missing_isolation.process_id = "no-tree";
  missing_isolation.executable_sha256 = fixture.digest;
  missing_isolation.working_directory = fixture.working_directory;
  missing_isolation.isolation_bits = KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS;
  expect(missing_isolation, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest unknown_isolation;
  unknown_isolation.process_id = "unknown-isolation";
  unknown_isolation.executable_sha256 = fixture.digest;
  unknown_isolation.working_directory = fixture.working_directory;
  // A bit outside the published vocabulary is a malformed request; the whole
  // published vocabulary without declared support is a typed refusal.
  unknown_isolation.isolation_bits = KWEB_PROCESSES_ISOLATION_ALL | (1u << 20);
  expect(unknown_isolation, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);
  unknown_isolation.isolation_bits = KWEB_PROCESSES_ISOLATION_ALL;
  expect(unknown_isolation, KWEB_PROCESSES_STATUS_ISOLATION_UNSUPPORTED);

  // An isolation profile the packaged provider does not implement fails before
  // launch: the request is refused and no process exists.
  for (uint32_t profile : {KWEB_PROCESSES_ISOLATION_SEATBELT, KWEB_PROCESSES_ISOLATION_SECCOMP,
                           KWEB_PROCESSES_ISOLATION_NAMESPACES, KWEB_PROCESSES_ISOLATION_SYSTEMD_SCOPE,
                           KWEB_PROCESSES_ISOLATION_RESTRICTED_TOKEN}) {
    StartRequest request;
    request.process_id = "isolation-refused";
    request.executable_sha256 = fixture.digest;
    request.working_directory = fixture.working_directory;
    request.isolation_bits = KWEB_PROCESSES_ISOLATION_PROCESS_TREE | profile;
    expect(request, KWEB_PROCESSES_STATUS_ISOLATION_UNSUPPORTED);
  }

  StartRequest unknown_executable;
  unknown_executable.process_id = "unknown-executable";
  unknown_executable.executable_path = fixture.working_directory + "/does-not-exist";
  unknown_executable.executable_sha256 = fixture.digest;
  unknown_executable.working_directory = fixture.working_directory;
  expect(unknown_executable, KWEB_PROCESSES_STATUS_EXECUTABLE_MISSING);

  StartRequest relative_path;
  relative_path.process_id = "relative-path";
  relative_path.executable_path = "processes-helper";
  relative_path.executable_sha256 = fixture.digest;
  relative_path.working_directory = fixture.working_directory;
  expect(relative_path, KWEB_PROCESSES_STATUS_EXECUTABLE_MISSING);

  StartRequest directory_as_executable;
  directory_as_executable.process_id = "directory-executable";
  directory_as_executable.executable_path = fixture.working_directory;
  directory_as_executable.executable_sha256 = fixture.digest;
  directory_as_executable.working_directory = fixture.working_directory;
  expect(directory_as_executable, KWEB_PROCESSES_STATUS_EXECUTABLE_UNVERIFIED);

  StartRequest missing_directory;
  missing_directory.process_id = "missing-directory";
  missing_directory.executable_sha256 = fixture.digest;
  missing_directory.working_directory = fixture.working_directory + "/missing";
  expect(missing_directory, KWEB_PROCESSES_STATUS_WORKING_DIRECTORY_INVALID);

  StartRequest wrong_digest;
  wrong_digest.process_id = "wrong-digest";
  wrong_digest.executable_sha256 = std::string(64, '0');
  wrong_digest.working_directory = fixture.working_directory;
  expect(wrong_digest, KWEB_PROCESSES_STATUS_EXECUTABLE_UNVERIFIED);

  StartRequest too_many_arguments;
  too_many_arguments.process_id = "too-many-arguments";
  too_many_arguments.executable_sha256 = fixture.digest;
  too_many_arguments.working_directory = fixture.working_directory;
  for (size_t index = 0; index <= KWEB_PROCESSES_MAX_ARGUMENTS; ++index) {
    too_many_arguments.arguments.push_back("a" + std::to_string(index));
  }
  expect(too_many_arguments, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest long_argument;
  long_argument.process_id = "long-argument";
  long_argument.executable_sha256 = fixture.digest;
  long_argument.working_directory = fixture.working_directory;
  long_argument.arguments.push_back(std::string(KWEB_PROCESSES_MAX_ARGUMENT_BYTES + 1, 'a'));
  expect(long_argument, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest bad_environment;
  bad_environment.process_id = "bad-environment";
  bad_environment.executable_sha256 = fixture.digest;
  bad_environment.working_directory = fixture.working_directory;
  bad_environment.environment.push_back("not-an-assignment");
  expect(bad_environment, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest empty_environment_name;
  empty_environment_name.process_id = "empty-environment-name";
  empty_environment_name.executable_sha256 = fixture.digest;
  empty_environment_name.working_directory = fixture.working_directory;
  empty_environment_name.environment.push_back("=value");
  expect(empty_environment_name, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest too_many_environment;
  too_many_environment.process_id = "too-many-environment";
  too_many_environment.executable_sha256 = fixture.digest;
  too_many_environment.working_directory = fixture.working_directory;
  for (size_t index = 0; index <= KWEB_PROCESSES_MAX_ENVIRONMENT; ++index) {
    too_many_environment.environment.push_back("KWEB_" + std::to_string(index) + "=1");
  }
  expect(too_many_environment, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest bad_timeout;
  bad_timeout.process_id = "bad-timeout";
  bad_timeout.executable_sha256 = fixture.digest;
  bad_timeout.working_directory = fixture.working_directory;
  bad_timeout.timeout_seconds = 0;
  expect(bad_timeout, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);
  bad_timeout.timeout_seconds = KWEB_PROCESSES_MAX_TIMEOUT_SECONDS + 1;
  expect(bad_timeout, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest bad_cpu;
  bad_cpu.process_id = "bad-cpu";
  bad_cpu.executable_sha256 = fixture.digest;
  bad_cpu.working_directory = fixture.working_directory;
  bad_cpu.cpu_seconds = 0;
  expect(bad_cpu, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest small_window;
  small_window.process_id = "small-window";
  small_window.executable_sha256 = fixture.digest;
  small_window.working_directory = fixture.working_directory;
  small_window.stdout_window_bytes = KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES - 1;
  expect(small_window, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);
  small_window.stdout_window_bytes = 16384;
  small_window.stderr_window_bytes = KWEB_PROCESSES_MAX_OUTPUT_WINDOW_BYTES + 1;
  expect(small_window, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest small_memory;
  small_memory.process_id = "small-memory";
  small_memory.executable_sha256 = fixture.digest;
  small_memory.working_directory = fixture.working_directory;
  small_memory.memory_bytes = KWEB_PROCESSES_MIN_MEMORY_BYTES - 1;
  expect(small_memory, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);
  small_memory.memory_bytes = KWEB_PROCESSES_MAX_MEMORY_BYTES + 1;
  expect(small_memory, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  StartRequest small_quota;
  small_quota.process_id = "small-quota";
  small_quota.executable_sha256 = fixture.digest;
  small_quota.working_directory = fixture.working_directory;
  small_quota.stdout_quota_bytes = KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES - 1;
  expect(small_quota, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);
  small_quota.stdout_quota_bytes = 1024ull * 1024ull;
  small_quota.stderr_quota_bytes = KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES + 1;
  expect(small_quota, KWEB_PROCESSES_STATUS_INVALID_ARGUMENT);

  // Nothing above created a process, so the live count is still the baseline.
  KWEB_CHECK(fixture.LiveProcesses(service) == baseline);
  KWEB_CHECK(kweb_processes_close(service) == KWEB_PROCESSES_STATUS_OK);
}

}  // namespace

int main() {
  const int failures_before = g_failures;
  TestAbiAndLifecycle();
  TestRequestValidation();
  RunPlatformNativeStructureTest();
  const int failures = g_failures - failures_before;
  if (failures == 0) {
    std::printf("PASS: the RFC 0024 process ABI, validation, and provider structure tests passed.\n");
  } else {
    std::printf("FAIL: %d process service assertions failed.\n", failures);
  }
  return failures == 0 ? 0 : 1;
}
