#ifndef KWEB_PROCESSES_TESTS_FIXTURE_H_
#define KWEB_PROCESSES_TESTS_FIXTURE_H_

#include "kweb_processes.h"
#include "processes_digest.h"

#include <cctype>
#include <chrono>
#include <cstdio>
#include <fstream>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

#ifndef KWEB_PROCESSES_HELPER_PATH
#error "KWEB_PROCESSES_HELPER_PATH must name the declared helper executable."
#endif

#if defined(_WIN32)
#include <windows.h>
#else
#include <errno.h>
#include <signal.h>
#include <sys/types.h>
#include <unistd.h>
#endif

/** One shared assertion counter so every translation unit reports one total. */
inline int g_failures = 0;

#define KWEB_CHECK(condition)                                                                       \
  do {                                                                                             \
    if (!(condition)) {                                                                            \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition);                               \
      g_failures += 1;                                                                             \
    }                                                                                              \
  } while (false)

namespace kweb_processes_fixture {

inline void SleepMillis(int millis) { std::this_thread::sleep_for(std::chrono::milliseconds(millis)); }

/** The id of the process running the test, as the child reports its parent. */
inline int CurrentProcessId() {
#if defined(_WIN32)
  return static_cast<int>(GetCurrentProcessId());
#else
  return static_cast<int>(getpid());
#endif
}

/** The declared lowercase hex form of one UTF-8 value. */
inline std::string HexText(const std::string &value) {
  return kwebshell::processes::ToHex(reinterpret_cast<const uint8_t *>(value.data()), value.size());
}

/** The declared lowercase hex form of one path, as the helper reports its cwd. */
inline std::string HexPath(const std::string &value) { return HexText(value); }

/**
 * Resolves one created directory to the canonical form the child reports, so a
 * temporary path that is a symlink or a short name still compares exactly.
 */
inline std::string NormalizedDirectory(const std::string &path) {
#if defined(_WIN32)
  wchar_t wide[4096];
  const int wide_size =
      MultiByteToWideChar(CP_UTF8, 0, path.c_str(), static_cast<int>(path.size()), wide, 4096);
  wchar_t resolved[4096];
  const DWORD size = GetLongPathNameW(wide, resolved, 4096);
  if (wide_size <= 0 || size == 0) return path;
  char converted[8192];
  const int written =
      WideCharToMultiByte(CP_UTF8, 0, resolved, static_cast<int>(size), converted, sizeof(converted),
                          nullptr, nullptr);
  return written > 0 ? std::string(converted, static_cast<size_t>(written)) : path;
#else
  char resolved[4096];
  return realpath(path.c_str(), resolved) != nullptr ? std::string(resolved) : path;
#endif
}

/** The declared byte pattern of the helper's `stdout-burst` and `stderr-burst`. */
inline uint8_t PatternByte(size_t index) { return static_cast<uint8_t>((index * 31u + 7u) & 0xffu); }

inline std::string ReadFileDigest(const std::string &path) {
  std::ifstream stream(path, std::ios::binary);
  if (!stream.is_open()) return std::string();
  kwebshell::processes::Sha256 hash;
  std::vector<char> buffer(65536);
  for (;;) {
    stream.read(buffer.data(), static_cast<std::streamsize>(buffer.size()));
    const std::streamsize read_bytes = stream.gcount();
    if (read_bytes > 0) {
      hash.Update(reinterpret_cast<const uint8_t *>(buffer.data()), static_cast<size_t>(read_bytes));
    }
    if (read_bytes < static_cast<std::streamsize>(buffer.size())) break;
  }
  uint8_t digest[32];
  hash.Finish(digest);
  return kwebshell::processes::ToHex(digest, sizeof(digest));
}

/** Whether the operating system still knows this process id. */
inline bool ProcessIsAlive(int pid) {
#if defined(_WIN32)
  const HANDLE handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, static_cast<DWORD>(pid));
  if (handle == nullptr) return false;
  const DWORD waited = WaitForSingleObject(handle, 0);
  CloseHandle(handle);
  return waited == WAIT_TIMEOUT;
#else
  if (pid <= 0) return false;
  return kill(static_cast<pid_t>(pid), 0) == 0 || errno != ESRCH;
#endif
}

inline bool ProcessIsGone(int pid, int timeout_millis) {
  for (int waited = 0; waited <= timeout_millis; waited += 50) {
    if (!ProcessIsAlive(pid)) return true;
    SleepMillis(50);
  }
  return !ProcessIsAlive(pid);
}

/** The process group of one child, or -1 where the target has no such concept. */
inline int ProcessGroup(int pid) {
#if defined(_WIN32)
  (void)pid;
  return -1;
#else
  return static_cast<int>(getpgid(static_cast<pid_t>(pid)));
#endif
}

inline std::string TemporaryDirectory() {
#if defined(_WIN32)
  wchar_t base[4096];
  const DWORD size = GetTempPathW(4096, base);
  if (size == 0) return std::string();
  std::wstring path(base);
  path += L"kwebshell-processes-fixture";
  CreateDirectoryW(path.c_str(), nullptr);
  char converted[8192];
  const int written = WideCharToMultiByte(CP_UTF8, 0, path.c_str(), static_cast<int>(path.size()), converted,
                                          sizeof(converted), nullptr, nullptr);
  return written > 0 ? std::string(converted, static_cast<size_t>(written)) : std::string();
#else
  char template_path[] = "/tmp/kwebshell-processes-fixture-XXXXXX";
  char *created = mkdtemp(template_path);
  return created != nullptr ? std::string(created) : std::string();
#endif
}

/** The shared fixture: the declared helper, its digest, and a real directory. */
struct Fixture {
  const char *helper = KWEB_PROCESSES_HELPER_PATH;
  std::string digest = ReadFileDigest(KWEB_PROCESSES_HELPER_PATH);
  std::string working_directory = NormalizedDirectory(TemporaryDirectory());

  kweb_processes_configuration Configuration() const {
    static const char *kApplicationId = "io.github.kingsword09.kwebshell.processes.fixture";
    kweb_processes_configuration configuration;
    std::memset(&configuration, 0, sizeof(configuration));
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_PROCESSES_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::strlen(kApplicationId);
    return configuration;
  }

  kweb_processes_capabilities_result Capabilities(uint64_t service) const {
    kweb_processes_capabilities_result result;
    std::memset(&result, 0, sizeof(result));
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_PROCESSES_ABI_VERSION;
    const kweb_processes_status status = kweb_processes_capabilities(service, &result);
    if (status != KWEB_PROCESSES_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  uint32_t LiveProcesses(uint64_t service) const { return Capabilities(service).live_processes; }
};

/** A start request that owns the storage of every declared value. */
struct StartRequest {
  std::string process_id;
  std::string executable_path = KWEB_PROCESSES_HELPER_PATH;
  std::string executable_sha256;
  std::string working_directory;
  std::vector<std::string> arguments;
  std::vector<std::string> environment;
  uint32_t isolation_bits =
      KWEB_PROCESSES_ISOLATION_PROCESS_TREE | KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS;
  uint32_t timeout_seconds = 30;
  uint32_t cpu_seconds = 10;
  uint32_t stdout_window_bytes = 16384;
  uint32_t stderr_window_bytes = 16384;
  uint64_t memory_bytes = 512ull * 1024ull * 1024ull;
  uint64_t stdout_quota_bytes = 16ull * 1024ull * 1024ull;
  uint64_t stderr_quota_bytes = 1024ull * 1024ull;

  /** Starts one process and returns its handle, or reports the typed failure. */
  kweb_processes_status Start(uint64_t service, uint64_t *handle, kweb_processes_start_result *result_out,
                              std::string *failure_out) {
    std::vector<kweb_processes_string> argument_views;
    std::vector<kweb_processes_string> environment_views;
    argument_views.reserve(arguments.size());
    environment_views.reserve(environment.size());
    for (const std::string &argument : arguments) {
      argument_views.push_back(kweb_processes_string{
          reinterpret_cast<const uint8_t *>(argument.data()), argument.size()});
    }
    for (const std::string &entry : environment) {
      environment_views.push_back(
          kweb_processes_string{reinterpret_cast<const uint8_t *>(entry.data()), entry.size()});
    }
    kweb_processes_start_request request;
    std::memset(&request, 0, sizeof(request));
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_PROCESSES_ABI_VERSION;
    request.process_id = {reinterpret_cast<const uint8_t *>(process_id.data()), process_id.size()};
    request.executable_path = {reinterpret_cast<const uint8_t *>(executable_path.data()),
                               executable_path.size()};
    request.executable_sha256 = {reinterpret_cast<const uint8_t *>(executable_sha256.data()),
                                 executable_sha256.size()};
    request.working_directory = {reinterpret_cast<const uint8_t *>(working_directory.data()),
                                 working_directory.size()};
    request.arguments = {argument_views.empty() ? nullptr : argument_views.data(), argument_views.size()};
    request.environment = {environment_views.empty() ? nullptr : environment_views.data(),
                           environment_views.size()};
    request.requested_isolation_bits = isolation_bits;
    request.timeout_seconds = timeout_seconds;
    request.cpu_seconds = cpu_seconds;
    request.stdout_window_bytes = stdout_window_bytes;
    request.stderr_window_bytes = stderr_window_bytes;
    request.memory_bytes = memory_bytes;
    request.stdout_quota_bytes = stdout_quota_bytes;
    request.stderr_quota_bytes = stderr_quota_bytes;
    kweb_processes_start_result result;
    std::memset(&result, 0, sizeof(result));
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_PROCESSES_ABI_VERSION;
    const kweb_processes_status status = kweb_processes_start(service, &request, &result);
    if (status == KWEB_PROCESSES_STATUS_OK) {
      *handle = result.process_handle;
      if (result_out != nullptr) *result_out = result;
    } else if (failure_out != nullptr) {
      failure_out->assign(reinterpret_cast<const char *>(request.process_id.data),
                          request.process_id.size);
    }
    return status;
  }
};

/** Drains one process's ordered output and reports its terminal result. */
struct Outcome {
  std::vector<uint8_t> stdout_bytes;
  std::vector<uint8_t> stderr_bytes;
  std::vector<uint64_t> sequences;
  uint64_t first_delivered_after_closure = 0;
  kweb_processes_termination termination{};
  bool ended = false;

  bool StdoutContains(const std::string &needle) const {
    return std::string(stdout_bytes.begin(), stdout_bytes.end()).find(needle) != std::string::npos;
  }

  std::string StdoutText() const { return std::string(stdout_bytes.begin(), stdout_bytes.end()); }

  std::string StderrText() const { return std::string(stderr_bytes.begin(), stderr_bytes.end()); }

  std::vector<std::string> StdoutLines() const {
    std::vector<std::string> lines;
    std::string current;
    for (uint8_t byte : stdout_bytes) {
      if (byte == '\n') {
        lines.push_back(current);
        current.clear();
      } else {
        current.push_back(static_cast<char>(byte));
      }
    }
    if (!current.empty()) lines.push_back(current);
    return lines;
  }
};

/** Consumes ordered output with an optional per-chunk delay for backpressure. */
inline Outcome Drain(uint64_t process, int timeout_millis, int delay_per_chunk_millis = 0) {
  Outcome outcome;
  std::vector<uint8_t> buffer(KWEB_PROCESSES_MAX_OUTPUT_CHUNK);
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_millis);
  uint64_t last_sequence = 0;
  for (;;) {
    kweb_processes_output_chunk chunk;
    std::memset(&chunk, 0, sizeof(chunk));
    chunk.struct_size = sizeof(chunk);
    chunk.abi_version = KWEB_PROCESSES_ABI_VERSION;
    const kweb_processes_status status = kweb_processes_read_output(
        process, buffer.data(), buffer.size(), 200, &chunk);
    if (status == KWEB_PROCESSES_STATUS_OK) {
      KWEB_CHECK(chunk.sequence > last_sequence);
      last_sequence = chunk.sequence;
      outcome.sequences.push_back(chunk.sequence);
      std::vector<uint8_t> &target =
          chunk.stream == KWEB_PROCESSES_STREAM_STDOUT ? outcome.stdout_bytes : outcome.stderr_bytes;
      target.insert(target.end(), buffer.begin(), buffer.begin() + chunk.size);
      KWEB_CHECK(chunk.total_bytes == target.size());
      if (delay_per_chunk_millis > 0) SleepMillis(delay_per_chunk_millis);
      continue;
    }
    if (status == KWEB_PROCESSES_STATUS_OUTPUT_ENDED) {
      outcome.ended = true;
      break;
    }
    KWEB_CHECK(status == KWEB_PROCESSES_STATUS_NO_OUTPUT);
    if (std::chrono::steady_clock::now() > deadline) {
      std::printf("FAIL: output drain exceeded its budget\n");
      g_failures += 1;
      break;
    }
  }
  kweb_processes_termination termination;
  std::memset(&termination, 0, sizeof(termination));
  termination.struct_size = sizeof(termination);
  termination.abi_version = KWEB_PROCESSES_ABI_VERSION;
  const kweb_processes_status waited = kweb_processes_wait(process, 5000, &termination);
  KWEB_CHECK(waited == KWEB_PROCESSES_STATUS_OK);
  if (waited == KWEB_PROCESSES_STATUS_OK) outcome.termination = termination;
  for (uint64_t sequence : outcome.sequences) {
    if (sequence > outcome.termination.sequence) outcome.first_delivered_after_closure = sequence;
  }
  return outcome;
}

/** The child's own process id, reported by the helper's first stdout line. */
inline int ReportedChildPid(const Outcome &outcome) {
  for (const std::string &line : outcome.StdoutLines()) {
    if (line.rfind("pid ", 0) == 0) return std::atoi(line.c_str() + 4);
    if (line.rfind("child ", 0) == 0) return std::atoi(line.c_str() + 6);
  }
  return 0;
}

/**
 * Reads ordered output until the helper has announced its process id, and
 * consumes those bytes. Used by the scenarios that keep a child alive.
 */
inline int ReadAnnouncedChildPid(uint64_t process, int timeout_millis) {
  std::vector<uint8_t> buffer(KWEB_PROCESSES_MAX_OUTPUT_CHUNK);
  std::string text;
  const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_millis);
  while (std::chrono::steady_clock::now() <= deadline) {
    kweb_processes_output_chunk chunk;
    std::memset(&chunk, 0, sizeof(chunk));
    chunk.struct_size = sizeof(chunk);
    chunk.abi_version = KWEB_PROCESSES_ABI_VERSION;
    const kweb_processes_status status =
        kweb_processes_read_output(process, buffer.data(), buffer.size(), 200, &chunk);
    if (status == KWEB_PROCESSES_STATUS_OK && chunk.stream == KWEB_PROCESSES_STREAM_STDOUT) {
      text.append(reinterpret_cast<const char *>(buffer.data()), chunk.size);
      const size_t pid_at = text.find("pid ");
      if (pid_at != std::string::npos && text.find('\n', pid_at) != std::string::npos) {
        return std::atoi(text.c_str() + pid_at + 4);
      }
      const size_t child_at = text.find("child ");
      if (child_at != std::string::npos && text.find('\n', child_at) != std::string::npos) {
        return std::atoi(text.c_str() + child_at + 6);
      }
      continue;
    }
    if (status == KWEB_PROCESSES_STATUS_OUTPUT_ENDED) break;
    SleepMillis(20);
  }
  return 0;
}

/** Decodes one lowercase hex string into the bytes it names. */
inline std::string Unhex(const std::string &hex) {
  std::string decoded;
  decoded.reserve(hex.size() / 2);
  for (size_t index = 0; index + 1 < hex.size(); index += 2) {
    const auto nibble = [](char character) -> int {
      if (character >= '0' && character <= '9') return character - '0';
      if (character >= 'a' && character <= 'f') return character - 'a' + 10;
      return 0;
    };
    decoded.push_back(static_cast<char>((nibble(hex[index]) << 4) | nibble(hex[index + 1])));
  }
  return decoded;
}

/** Case-insensitive comparison for the declared working directory. */
inline bool SameDirectory(const std::string &reported, const std::string &declared) {
#if defined(_WIN32)
  if (reported.size() != declared.size()) return false;
  for (size_t index = 0; index < reported.size(); ++index) {
    const char left = reported[index] == '/' ? '\\' : reported[index];
    const char right = declared[index] == '/' ? '\\' : declared[index];
    if (std::tolower(static_cast<unsigned char>(left)) != std::tolower(static_cast<unsigned char>(right))) {
      return false;
    }
  }
  return true;
#else
  return reported == declared;
#endif
}

/** Whether the report transcript names exactly the declared working directory. */
inline bool ReportedCwdMatches(const Outcome &outcome, const std::string &declared) {
  for (const std::string &line : outcome.StdoutLines()) {
    if (line.rfind("cwd ", 0) != 0) continue;
    return SameDirectory(Unhex(line.substr(4)), declared);
  }
  return false;
}

/** Terminates one live tree and reports its single terminal result. */
inline kweb_processes_termination Terminate(uint64_t process, uint32_t owner_closed) {
  kweb_processes_termination termination;
  std::memset(&termination, 0, sizeof(termination));
  termination.struct_size = sizeof(termination);
  termination.abi_version = KWEB_PROCESSES_ABI_VERSION;
  const kweb_processes_status status = kweb_processes_terminate(process, owner_closed, &termination);
  KWEB_CHECK(status == KWEB_PROCESSES_STATUS_OK);
  return termination;
}

}  // namespace kweb_processes_fixture

/** Runs the real provider structure test of the current target. */
int RunPlatformNativeStructureTest();

#endif
