#include "kweb_processes.h"
#include "processes_digest.h"
#include "processes_internal.h"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <cstdio>
#include <cstring>
#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace {

namespace processes = kwebshell::processes;

constexpr uint32_t kMonitorPeriodMillis = KWEB_PROCESSES_MAX_MONITOR_PERIOD_MILLIS;
/** After the child ends, a stream stops when no further byte arrives in this window. */
constexpr uint32_t kDrainGraceMillis = 300;
/** The queue also stops at a chunk ceiling so a trickling producer stays bounded. */
constexpr size_t kMaxQueuedChunks = 4096;
constexpr uint64_t kTerminateWaitMillis = 5000;

// ---------------------------------------------------------------------------
// String helpers and validation
// ---------------------------------------------------------------------------

bool ReadString(const kweb_processes_string &value, size_t maximum, std::string *output) {
  if (value.data == nullptr && value.size != 0) return false;
  if (value.size > maximum) return false;
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  const size_t size = std::min(value.size(), capacity - 1);
  std::memcpy(target, value.data(), size);
  target[size] = '\0';
}

bool ValidIdentifier(const std::string &value) {
  if (value.empty() || value.size() > 128) return false;
  if (!((value[0] >= 'A' && value[0] <= 'Z') || (value[0] >= 'a' && value[0] <= 'z'))) return false;
  for (char character : value) {
    const bool allowed = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
                         (character >= '0' && character <= '9') || character == '.' || character == '_' ||
                         character == '-';
    if (!allowed) return false;
  }
  return true;
}

bool ValidDigest(const std::string &value) {
  if (value.size() != 64) return false;
  for (char character : value) {
    const bool hex = (character >= '0' && character <= '9') || (character >= 'a' && character <= 'f');
    if (!hex) return false;
  }
  return true;
}

bool ValidArgument(const std::string &value) {
  if (value.size() > KWEB_PROCESSES_MAX_ARGUMENT_BYTES) return false;
  return value.find('\0') == std::string::npos;
}

bool ValidEnvironmentEntry(const std::string &entry) {
  const size_t separator = entry.find('=');
  if (separator == std::string::npos || separator == 0) return false;
  const std::string name = entry.substr(0, separator);
  if (name.size() > 64) return false;
  if (!((name[0] >= 'A' && name[0] <= 'Z') || (name[0] >= 'a' && name[0] <= 'z') || name[0] == '_')) return false;
  for (char character : name) {
    const bool allowed = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
                         (character >= '0' && character <= '9') || character == '_';
    if (!allowed) return false;
  }
  const std::string value = entry.substr(separator + 1);
  if (value.size() > KWEB_PROCESSES_MAX_ENVIRONMENT_VALUE_BYTES) return false;
  return value.find('\0') == std::string::npos;
}

uint64_t MonotonicMillis() {
  return static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
                                   std::chrono::steady_clock::now().time_since_epoch())
                                   .count());
}

// ---------------------------------------------------------------------------
// Records
// ---------------------------------------------------------------------------

struct Chunk {
  uint64_t sequence = 0;
  uint32_t stream = 0;
  std::vector<uint8_t> bytes;
};

struct ProcessRecord {
  uint64_t handle = 0;
  std::string id;
  std::unique_ptr<processes::ChildProcess> child;
  intptr_t stdin_write = -1;
  intptr_t stdout_read = -1;
  intptr_t stderr_read = -1;
  uint32_t window_bytes[2] = {0, 0};
  uint64_t quota_bytes[2] = {0, 0};
  uint64_t produced_bytes[2] = {0, 0};
  uint64_t delivered_bytes[2] = {0, 0};
  size_t queued_bytes[2] = {0, 0};
  bool stream_eof[2] = {false, false};
  bool stdin_open = true;
  bool stdin_eof = false;
  std::vector<uint8_t> stdin_queue;
  std::condition_variable stdin_space;
  std::deque<Chunk> chunks;
  uint64_t sequence = 0;
  uint64_t closure_sequence = 0;
  uint32_t state = KWEB_PROCESSES_STATE_RUNNING;
  uint32_t reason = 0;
  int32_t exit_code = -1;
  int32_t signal = 0;
  uint64_t cpu_millis = 0;
  uint64_t peak_memory_bytes = 0;
  uint64_t started_millis = 0;
  uint64_t ended_millis = 0;
  uint32_t timeout_seconds = 0;
  uint32_t cpu_seconds = 0;
  uint64_t memory_bytes = 0;
  std::thread reader_stdout;
  std::thread reader_stderr;
  std::thread stdin_writer;
  std::thread monitor;
  std::mutex mutex;
  std::condition_variable changed;

  bool readers_done() const {
    return stream_eof[0] && stream_eof[1];
  }
};

struct ServiceState {
  std::string application_id;
  std::unique_ptr<processes::PlatformProvider> provider;
  std::map<uint64_t, std::shared_ptr<ProcessRecord>> processes;
  uint64_t next_handle = 1;
  std::mutex mutex;
};

std::mutex g_service_mutex;
std::shared_ptr<ServiceState> g_service;
constexpr uint64_t kServiceHandle = 1;

std::shared_ptr<ServiceState> LockService() {
  std::lock_guard<std::mutex> lock(g_service_mutex);
  return g_service;
}

std::shared_ptr<ProcessRecord> FindProcess(const std::shared_ptr<ServiceState> &service, uint64_t handle) {
  std::lock_guard<std::mutex> lock(service->mutex);
  const auto found = service->processes.find(handle);
  return found == service->processes.end() ? nullptr : found->second;
}

// ---------------------------------------------------------------------------
// Worker threads
// ---------------------------------------------------------------------------

/** Finalizes one outcome exactly once; the caller holds the record lock. */
void FinalizeLocked(const std::shared_ptr<ProcessRecord> &record, int exit_code, int signal) {
  if (record->state == KWEB_PROCESSES_STATE_TERMINATED) return;
  uint32_t reason = record->reason;
  if (reason == 0) {
    if (signal > 0) {
      const uint32_t mapped = record->child->SignalReason(signal);
      reason = mapped != 0 ? mapped : KWEB_PROCESSES_REASON_CRASHED;
    } else {
      reason = KWEB_PROCESSES_REASON_EXITED;
    }
  }
  record->reason = reason;
  record->exit_code = exit_code;
  record->signal = signal;
  record->ended_millis = MonotonicMillis();
  record->closure_sequence = ++record->sequence;
  record->state = KWEB_PROCESSES_STATE_TERMINATED;
  record->changed.notify_all();
  record->stdin_space.notify_all();
}

void ReadLoop(const std::shared_ptr<ProcessRecord> &record, uint32_t stream, intptr_t handle) {
  const size_t index = stream - 1;
  std::vector<uint8_t> scratch(KWEB_PROCESSES_MAX_OUTPUT_CHUNK);
  for (;;) {
    {
      std::unique_lock<std::mutex> lock(record->mutex);
      if (record->stream_eof[index]) break;
      const bool live = record->state == KWEB_PROCESSES_STATE_RUNNING;
      if (!live) {
        // Drain what the pipe still holds, then stop when no further byte arrives
        // within the grace window so a surviving descendant cannot pin the reader.
        lock.unlock();
        const int readable = processes::WaitReadable(handle, kDrainGraceMillis);
        const int drained =
            readable == 1 ? processes::ReadSome(handle, scratch.data(), scratch.size()) : 0;
        lock.lock();
        if (drained > 0) {
          Chunk chunk;
          chunk.sequence = ++record->sequence;
          chunk.stream = stream;
          chunk.bytes.assign(scratch.begin(), scratch.begin() + drained);
          record->produced_bytes[index] += static_cast<uint64_t>(drained);
          record->queued_bytes[index] += static_cast<size_t>(drained);
          record->chunks.push_back(std::move(chunk));
          record->changed.notify_all();
          continue;
        }
        record->stream_eof[index] = true;
        record->changed.notify_all();
        break;
      }
      if (record->queued_bytes[index] >= record->window_bytes[index] ||
          record->chunks.size() >= kMaxQueuedChunks) {
        record->changed.wait_for(lock, std::chrono::milliseconds(kMonitorPeriodMillis));
        continue;
      }
      lock.unlock();
    }
    const int readable = processes::WaitReadable(handle, kMonitorPeriodMillis);
    if (readable < 0) {
      std::lock_guard<std::mutex> lock(record->mutex);
      record->stream_eof[index] = true;
      record->changed.notify_all();
      break;
    }
    if (readable == 0) {
      // A producer that stopped writing but keeps running is not an EOF yet.
      continue;
    }
    const int read = processes::ReadSome(handle, scratch.data(), scratch.size());
    std::unique_lock<std::mutex> lock(record->mutex);
    if (read <= 0) {
      record->stream_eof[index] = true;
      record->changed.notify_all();
      break;
    }
    Chunk chunk;
    chunk.sequence = ++record->sequence;
    chunk.stream = stream;
    chunk.bytes.assign(scratch.begin(), scratch.begin() + read);
    record->produced_bytes[index] += static_cast<uint64_t>(read);
    if (record->produced_bytes[index] > record->quota_bytes[index]) {
      // The declared output quota is reached: the stream ends here, the tree is
      // terminated, and no byte beyond the quota is delivered or silently kept.
      record->stream_eof[index] = true;
      if (record->reason == 0) {
        record->reason = KWEB_PROCESSES_REASON_OUTPUT_LIMIT;
        processes::ChildProcess *child = record->child.get();
        lock.unlock();
        child->TerminateTree();
        lock.lock();
      }
      record->changed.notify_all();
      break;
    }
    record->queued_bytes[index] += static_cast<size_t>(read);
    record->chunks.push_back(std::move(chunk));
    record->changed.notify_all();
  }
}

void MonitorLoop(const std::shared_ptr<ProcessRecord> &record) {
  for (;;) {
    std::this_thread::sleep_for(std::chrono::milliseconds(kMonitorPeriodMillis));
    uint32_t reason = 0;
    {
      std::unique_lock<std::mutex> lock(record->mutex);
      if (record->state == KWEB_PROCESSES_STATE_TERMINATED) break;
      const uint64_t cpu = record->child->CpuMillis();
      if (cpu > record->cpu_millis) record->cpu_millis = cpu;
      const uint64_t resident = record->child->ResidentBytes();
      if (resident > record->peak_memory_bytes) record->peak_memory_bytes = resident;
      const uint64_t now = MonotonicMillis();
      if (record->reason != 0) {
        reason = record->reason;
      } else if (cpu >= static_cast<uint64_t>(record->cpu_seconds) * 1000u) {
        reason = KWEB_PROCESSES_REASON_CPU_LIMIT;
      } else if (resident >= record->memory_bytes) {
        reason = KWEB_PROCESSES_REASON_MEMORY_LIMIT;
      } else if (now - record->started_millis >= static_cast<uint64_t>(record->timeout_seconds) * 1000u) {
        reason = KWEB_PROCESSES_REASON_TIMEOUT;
      }
      if (reason != 0) record->reason = reason;
      lock.unlock();
    }
    processes::ChildProcess *child = record->child.get();
    if (reason != 0) child->TerminateTree();
    int exit_code = -1;
    int signal = 0;
    const int reaped = child->Reap(&exit_code, &signal);
    if (reaped == 1) {
      std::lock_guard<std::mutex> lock(record->mutex);
      FinalizeLocked(record, exit_code, signal);
      break;
    }
    if (reaped < 0) {
      std::lock_guard<std::mutex> lock(record->mutex);
      FinalizeLocked(record, -1, 0);
      break;
    }
  }
}

/** Terminates one live tree and finalizes it with the requested reason. */
void TerminateRecord(const std::shared_ptr<ProcessRecord> &record, uint32_t reason, bool already_locked) {
  if (!already_locked) {
    std::unique_lock<std::mutex> lock(record->mutex);
    if (record->state == KWEB_PROCESSES_STATE_TERMINATED) return;
    if (record->reason == 0) record->reason = reason;
    lock.unlock();
  }
  record->child->TerminateTree();
}

/** Waits up to the budget for one record's outcome; the caller holds no lock. */
bool WaitForOutcome(const std::shared_ptr<ProcessRecord> &record, uint32_t timeout_millis) {
  std::unique_lock<std::mutex> lock(record->mutex);
  return record->changed.wait_for(lock, std::chrono::milliseconds(timeout_millis), [&record] {
    return record->state == KWEB_PROCESSES_STATE_TERMINATED;
  });
}

void JoinWorkers(const std::shared_ptr<ProcessRecord> &record) {
  if (record->reader_stdout.joinable()) record->reader_stdout.join();
  if (record->reader_stderr.joinable()) record->reader_stderr.join();
  if (record->stdin_writer.joinable()) record->stdin_writer.join();
  if (record->monitor.joinable()) record->monitor.join();
}

/**
 * Delivers queued stdin payloads to the child and closes the pipe exactly once.
 * A host call never blocks on the kernel pipe: it only waits for queue space, so
 * a child that stops reading reports a typed timeout instead of stalling a host
 * thread. This is the only thread that blocks on a stdin write.
 */
void StdinLoop(const std::shared_ptr<ProcessRecord> &record) {
  constexpr size_t kPieceBytes = 4096;
  for (;;) {
    std::unique_lock<std::mutex> lock(record->mutex);
    if (record->stdin_queue.empty() && !record->stdin_eof &&
        record->state == KWEB_PROCESSES_STATE_RUNNING) {
      record->stdin_space.wait_for(lock, std::chrono::milliseconds(kMonitorPeriodMillis));
      continue;
    }
    if (record->stdin_queue.empty()) {
      if (record->stdin_eof || record->state != KWEB_PROCESSES_STATE_RUNNING) {
        if (record->stdin_open) {
          processes::ClosePipeHandle(record->stdin_write);
          record->stdin_write = -1;
          record->stdin_open = false;
        }
        record->stdin_space.notify_all();
        break;
      }
      continue;
    }
    const size_t take = std::min(kPieceBytes, record->stdin_queue.size());
    const std::vector<uint8_t> piece(record->stdin_queue.begin(), record->stdin_queue.begin() + take);
    const intptr_t handle = record->stdin_write;
    lock.unlock();
    const int written = processes::WriteBlocking(handle, piece.data(), piece.size());
    lock.lock();
    if (written <= 0) {
      // The child closed its stdin: the queue is dropped and the next host call
      // reports the closed pipe instead of a silent truncation.
      record->stdin_queue.clear();
      if (record->stdin_open) {
        processes::ClosePipeHandle(record->stdin_write);
        record->stdin_write = -1;
        record->stdin_open = false;
      }
      record->stdin_space.notify_all();
      continue;
    }
    record->stdin_queue.erase(record->stdin_queue.begin(), record->stdin_queue.begin() + written);
    record->stdin_space.notify_all();
  }
}

void FillTermination(const std::shared_ptr<ProcessRecord> &record, kweb_processes_termination *termination) {
  termination->struct_size = sizeof(kweb_processes_termination);
  termination->abi_version = KWEB_PROCESSES_ABI_VERSION;
  termination->reason = record->reason;
  termination->exit_code = record->exit_code;
  termination->signal = record->signal;
  termination->sequence = record->closure_sequence;
  termination->stdout_bytes = record->delivered_bytes[0];
  termination->stderr_bytes = record->delivered_bytes[1];
  termination->cpu_millis = record->cpu_millis;
  termination->peak_memory_bytes = record->peak_memory_bytes;
  termination->duration_millis = (record->ended_millis > record->started_millis)
                                     ? record->ended_millis - record->started_millis
                                     : 0;
}

kweb_processes_status LaunchStatusFor(const std::string &detail) {
  if (detail == "missing") return KWEB_PROCESSES_STATUS_EXECUTABLE_MISSING;
  if (detail == "unverified") return KWEB_PROCESSES_STATUS_EXECUTABLE_UNVERIFIED;
  if (detail == "working-directory") return KWEB_PROCESSES_STATUS_WORKING_DIRECTORY_INVALID;
  if (detail == "unsupported") return KWEB_PROCESSES_STATUS_PLATFORM_UNAVAILABLE;
  return KWEB_PROCESSES_STATUS_LAUNCH_FAILED;
}

}  // namespace

extern "C" uint32_t KWEB_PROCESSES_CALL kweb_processes_abi_version(void) {
  return KWEB_PROCESSES_ABI_VERSION;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_open(
    const kweb_processes_configuration *configuration, uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (configuration->struct_size != sizeof(kweb_processes_configuration)) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  if (configuration->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  std::string application_id;
  if (!ReadString(configuration->application_id, 256, &application_id) || application_id.empty()) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_service_mutex);
  if (g_service != nullptr) return KWEB_PROCESSES_STATUS_ALREADY_OPEN;
#if !defined(_WIN32)
  // A child that closes its stdin must report a typed failure instead of raising
  // SIGPIPE on the host. The JVM host already ignores SIGPIPE; a native host gets
  // the same declared disposition here rather than a process-killing signal.
  std::signal(SIGPIPE, SIG_IGN);
#endif
  auto state = std::make_shared<ServiceState>();
  state->application_id = application_id;
  state->provider = processes::CreatePlatformProvider();
  if (state->provider == nullptr) return KWEB_PROCESSES_STATUS_PLATFORM_UNAVAILABLE;
  g_service = state;
  *handle = kServiceHandle;
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_capabilities(
    uint64_t handle, kweb_processes_capabilities_result *result) {
  if (handle != kServiceHandle || result == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (result->struct_size != sizeof(kweb_processes_capabilities_result)) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  if (result->abi_version != KWEB_PROCESSES_ABI_VERSION) return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(kweb_processes_capabilities_result);
  result->abi_version = KWEB_PROCESSES_ABI_VERSION;
  CopyBounded(result->provider_id, sizeof(result->provider_id), service->provider->ProviderId());
  result->isolation_bits = service->provider->DeclaredIsolation();
  result->memory_measure = service->provider->MemoryMeasure();
  result->max_live_processes = KWEB_PROCESSES_MAX_LIVE;
  result->min_timeout_seconds = KWEB_PROCESSES_MIN_TIMEOUT_SECONDS;
  result->max_timeout_seconds = KWEB_PROCESSES_MAX_TIMEOUT_SECONDS;
  result->min_cpu_seconds = KWEB_PROCESSES_MIN_CPU_SECONDS;
  result->max_cpu_seconds = KWEB_PROCESSES_MAX_CPU_SECONDS;
  result->min_output_window_bytes = KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES;
  result->max_output_window_bytes = KWEB_PROCESSES_MAX_OUTPUT_WINDOW_BYTES;
  result->max_stdin_chunk_bytes = KWEB_PROCESSES_MAX_STDIN_CHUNK;
  result->max_arguments = KWEB_PROCESSES_MAX_ARGUMENTS;
  result->max_environment = KWEB_PROCESSES_MAX_ENVIRONMENT;
  result->min_memory_bytes = KWEB_PROCESSES_MIN_MEMORY_BYTES;
  result->max_memory_bytes = KWEB_PROCESSES_MAX_MEMORY_BYTES;
  result->min_output_quota_bytes = KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES;
  result->max_output_quota_bytes = KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES;
  std::lock_guard<std::mutex> lock(service->mutex);
  result->live_processes = static_cast<uint32_t>(service->processes.size());
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_start(
    uint64_t handle, const kweb_processes_start_request *request, kweb_processes_start_result *result) {
  if (handle != kServiceHandle || request == nullptr || result == nullptr) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->struct_size != sizeof(kweb_processes_start_request) ||
      result->struct_size != sizeof(kweb_processes_start_result)) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  if (request->abi_version != KWEB_PROCESSES_ABI_VERSION || result->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;

  processes::LaunchRequest launch;
  if (!ReadString(request->process_id, 128, &launch.process_id) || !ValidIdentifier(launch.process_id)) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (!ReadString(request->executable_path, 4096, &launch.executable_path) || launch.executable_path.empty()) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (!ReadString(request->executable_sha256, 64, &launch.executable_sha256) ||
      !ValidDigest(launch.executable_sha256)) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (!ReadString(request->working_directory, 4096, &launch.working_directory) ||
      launch.working_directory.empty()) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->arguments.count > KWEB_PROCESSES_MAX_ARGUMENTS ||
      request->environment.count > KWEB_PROCESSES_MAX_ENVIRONMENT) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  for (size_t index = 0; index < request->arguments.count; ++index) {
    std::string argument;
    if (!ReadString(request->arguments.items[index], KWEB_PROCESSES_MAX_ARGUMENT_BYTES, &argument) ||
        !ValidArgument(argument)) {
      return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
    }
    launch.arguments.push_back(std::move(argument));
  }
  for (size_t index = 0; index < request->environment.count; ++index) {
    std::string entry;
    if (!ReadString(request->environment.items[index], KWEB_PROCESSES_MAX_ENVIRONMENT_VALUE_BYTES + 65, &entry) ||
        !ValidEnvironmentEntry(entry)) {
      return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
    }
    launch.environment.push_back(std::move(entry));
  }
  if ((request->requested_isolation_bits & ~KWEB_PROCESSES_ISOLATION_ALL) != 0 ||
      (request->requested_isolation_bits & KWEB_PROCESSES_ISOLATION_PROCESS_TREE) == 0) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if ((request->requested_isolation_bits & ~service->provider->DeclaredIsolation()) != 0) {
    return KWEB_PROCESSES_STATUS_ISOLATION_UNSUPPORTED;
  }
  if (request->timeout_seconds < KWEB_PROCESSES_MIN_TIMEOUT_SECONDS ||
      request->timeout_seconds > KWEB_PROCESSES_MAX_TIMEOUT_SECONDS) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->cpu_seconds < KWEB_PROCESSES_MIN_CPU_SECONDS ||
      request->cpu_seconds > KWEB_PROCESSES_MAX_CPU_SECONDS) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->stdout_window_bytes < KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES ||
      request->stdout_window_bytes > KWEB_PROCESSES_MAX_OUTPUT_WINDOW_BYTES ||
      request->stderr_window_bytes < KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES ||
      request->stderr_window_bytes > KWEB_PROCESSES_MAX_OUTPUT_WINDOW_BYTES) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->memory_bytes < KWEB_PROCESSES_MIN_MEMORY_BYTES ||
      request->memory_bytes > KWEB_PROCESSES_MAX_MEMORY_BYTES) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  if (request->stdout_quota_bytes < KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES ||
      request->stdout_quota_bytes > KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES ||
      request->stderr_quota_bytes < KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES ||
      request->stderr_quota_bytes > KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES) {
    return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  }
  launch.requested_isolation_bits = request->requested_isolation_bits;
  launch.timeout_seconds = request->timeout_seconds;
  launch.cpu_seconds = request->cpu_seconds;
  launch.memory_bytes = request->memory_bytes;
  launch.stdout_window_bytes = request->stdout_window_bytes;
  launch.stderr_window_bytes = request->stderr_window_bytes;
  launch.stdout_quota_bytes = request->stdout_quota_bytes;
  launch.stderr_quota_bytes = request->stderr_quota_bytes;

  {
    std::lock_guard<std::mutex> lock(service->mutex);
    if (service->processes.size() >= KWEB_PROCESSES_MAX_LIVE) {
      return KWEB_PROCESSES_STATUS_LIMIT_EXCEEDED;
    }
    for (const auto &entry : service->processes) {
      if (entry.second->id == launch.process_id) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
    }
  }

  processes::OpenOptions options;
  options.executable_path = launch.executable_path;
  options.executable_sha256 = launch.executable_sha256;
  options.working_directory = launch.working_directory;
  options.arguments = launch.arguments;
  options.environment = launch.environment;
  options.memory_bytes = launch.memory_bytes;
  options.cpu_seconds = launch.cpu_seconds;
  intptr_t stdin_write = -1;
  intptr_t stdout_read = -1;
  intptr_t stderr_read = -1;
  std::string failure_detail;
  std::unique_ptr<processes::ChildProcess> child =
      service->provider->Launch(options, &stdin_write, &stdout_read, &stderr_read, &failure_detail);
  if (child == nullptr) {
    result->process_handle = 0;
    return LaunchStatusFor(failure_detail);
  }

  auto record = std::make_shared<ProcessRecord>();
  record->id = launch.process_id;
  record->child = std::move(child);
  record->stdin_write = stdin_write;
  record->stdout_read = stdout_read;
  record->stderr_read = stderr_read;
  record->window_bytes[0] = launch.stdout_window_bytes;
  record->window_bytes[1] = launch.stderr_window_bytes;
  record->quota_bytes[0] = launch.stdout_quota_bytes;
  record->quota_bytes[1] = launch.stderr_quota_bytes;
  record->timeout_seconds = launch.timeout_seconds;
  record->cpu_seconds = launch.cpu_seconds;
  record->memory_bytes = launch.memory_bytes;
  record->started_millis = MonotonicMillis();
  {
    std::lock_guard<std::mutex> lock(service->mutex);
    record->handle = service->next_handle++;
    service->processes.emplace(record->handle, record);
  }
  const std::shared_ptr<ProcessRecord> shared = record;
  record->reader_stdout = std::thread([shared] { ReadLoop(shared, KWEB_PROCESSES_STREAM_STDOUT, shared->stdout_read); });
  record->reader_stderr = std::thread([shared] { ReadLoop(shared, KWEB_PROCESSES_STREAM_STDERR, shared->stderr_read); });
  record->stdin_writer = std::thread([shared] { StdinLoop(shared); });
  record->monitor = std::thread([shared] { MonitorLoop(shared); });

  result->isolation_bits = request->requested_isolation_bits;
  result->process_handle = record->handle;
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_write_stdin(
    uint64_t process, const uint8_t *bytes, size_t size, uint32_t timeout_millis, uint64_t *accepted) {
  if (bytes == nullptr || size == 0 || accepted == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (size > KWEB_PROCESSES_MAX_STDIN_CHUNK) return KWEB_PROCESSES_STATUS_BUFFER_TOO_SMALL;
  *accepted = 0;
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  std::unique_lock<std::mutex> lock(record->mutex);
  if (!record->stdin_open || record->state != KWEB_PROCESSES_STATE_RUNNING) {
    return KWEB_PROCESSES_STATUS_STDIN_CLOSED;
  }
  const auto space = [&record, size] {
    return !record->stdin_open || record->state != KWEB_PROCESSES_STATE_RUNNING ||
           record->stdin_queue.size() + size <= KWEB_PROCESSES_MAX_STDIN_CHUNK;
  };
  if (!space() &&
      !record->stdin_space.wait_for(lock, std::chrono::milliseconds(timeout_millis), space)) {
    return KWEB_PROCESSES_STATUS_STDIN_TIMEOUT;
  }
  if (!record->stdin_open || record->state != KWEB_PROCESSES_STATE_RUNNING) {
    return KWEB_PROCESSES_STATUS_STDIN_CLOSED;
  }
  record->stdin_queue.insert(record->stdin_queue.end(), bytes, bytes + size);
  *accepted = static_cast<uint64_t>(size);
  record->stdin_space.notify_all();
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_close_stdin(uint64_t process) {
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  std::lock_guard<std::mutex> lock(record->mutex);
  if (!record->stdin_open || record->stdin_eof) return KWEB_PROCESSES_STATUS_STDIN_CLOSED;
  // The queued bytes are still delivered; the writer closes the pipe afterwards.
  record->stdin_eof = true;
  record->stdin_space.notify_all();
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_read_output(
    uint64_t process, uint8_t *buffer, size_t capacity, uint32_t timeout_millis,
    kweb_processes_output_chunk *chunk) {
  if (buffer == nullptr || chunk == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (chunk->struct_size != sizeof(kweb_processes_output_chunk) ||
      chunk->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  if (capacity > KWEB_PROCESSES_MAX_OUTPUT_CHUNK) capacity = KWEB_PROCESSES_MAX_OUTPUT_CHUNK;
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  std::unique_lock<std::mutex> lock(record->mutex);
  const auto ready = [&record] { return !record->chunks.empty() || record->readers_done(); };
  if (!ready()) {
    record->changed.wait_for(lock, std::chrono::milliseconds(timeout_millis), ready);
  }
  if (record->chunks.empty()) {
    lock.unlock();
    return record->readers_done() ? KWEB_PROCESSES_STATUS_OUTPUT_ENDED : KWEB_PROCESSES_STATUS_NO_OUTPUT;
  }
  Chunk &front = record->chunks.front();
  if (front.bytes.size() > capacity) {
    chunk->size = 0;
    return KWEB_PROCESSES_STATUS_BUFFER_TOO_SMALL;
  }
  chunk->stream = front.stream;
  chunk->sequence = front.sequence;
  chunk->size = static_cast<uint32_t>(front.bytes.size());
  chunk->total_bytes = record->delivered_bytes[front.stream - 1] + front.bytes.size();
  std::memcpy(buffer, front.bytes.data(), front.bytes.size());
  record->queued_bytes[front.stream - 1] -= front.bytes.size();
  record->delivered_bytes[front.stream - 1] += front.bytes.size();
  record->chunks.pop_front();
  record->changed.notify_all();
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_state(
    uint64_t process, kweb_processes_status_result *result) {
  if (result == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (result->struct_size != sizeof(kweb_processes_status_result) ||
      result->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  const kweb_processes_status_result template_value = *result;
  std::lock_guard<std::mutex> lock(record->mutex);
  std::memset(result, 0, sizeof(*result));
  result->struct_size = template_value.struct_size;
  result->abi_version = template_value.abi_version;
  result->state = record->state;
  result->sequence = record->sequence;
  result->stdout_bytes = record->delivered_bytes[0];
  result->stderr_bytes = record->delivered_bytes[1];
  result->pending_output_bytes = static_cast<uint64_t>(record->queued_bytes[0] + record->queued_bytes[1]);
  const bool terminated = record->state == KWEB_PROCESSES_STATE_TERMINATED;
  result->cpu_millis = terminated && record->cpu_millis == 0 ? 0 : record->cpu_millis;
  result->peak_memory_bytes = record->peak_memory_bytes;
  result->duration_millis = terminated
                                ? ((record->ended_millis > record->started_millis)
                                       ? record->ended_millis - record->started_millis
                                       : 0)
                                : (MonotonicMillis() - record->started_millis);
  result->termination.struct_size = sizeof(kweb_processes_termination);
  result->termination.abi_version = KWEB_PROCESSES_ABI_VERSION;
  if (terminated) FillTermination(record, &result->termination);
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_wait(
    uint64_t process, uint32_t timeout_millis, kweb_processes_termination *termination) {
  if (termination == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (termination->struct_size != sizeof(kweb_processes_termination) ||
      termination->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  if (!WaitForOutcome(record, timeout_millis)) return KWEB_PROCESSES_STATUS_STILL_RUNNING;
  const kweb_processes_termination template_value = *termination;
  std::lock_guard<std::mutex> lock(record->mutex);
  std::memset(termination, 0, sizeof(*termination));
  termination->struct_size = template_value.struct_size;
  termination->abi_version = template_value.abi_version;
  FillTermination(record, termination);
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_terminate(
    uint64_t process, uint32_t owner_closed, kweb_processes_termination *termination) {
  if (termination == nullptr) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  if (termination->struct_size != sizeof(kweb_processes_termination) ||
      termination->abi_version != KWEB_PROCESSES_ABI_VERSION) {
    return KWEB_PROCESSES_STATUS_ABI_MISMATCH;
  }
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  TerminateRecord(record, owner_closed != 0 ? KWEB_PROCESSES_REASON_OWNER_CLOSED : KWEB_PROCESSES_REASON_KILLED,
                  false);
  if (!WaitForOutcome(record, static_cast<uint32_t>(kTerminateWaitMillis))) {
    return KWEB_PROCESSES_STATUS_NATIVE_FAILED;
  }
  const kweb_processes_termination template_value = *termination;
  std::lock_guard<std::mutex> lock(record->mutex);
  std::memset(termination, 0, sizeof(*termination));
  termination->struct_size = template_value.struct_size;
  termination->abi_version = template_value.abi_version;
  FillTermination(record, termination);
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_release(uint64_t process) {
  const std::shared_ptr<ServiceState> service = LockService();
  if (service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
  const std::shared_ptr<ProcessRecord> record = FindProcess(service, process);
  if (record == nullptr) return KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN;
  // Releasing a live process is owner shutdown: its whole tree is terminated so
  // a released handle can never leave a child behind.
  TerminateRecord(record, KWEB_PROCESSES_REASON_OWNER_CLOSED, false);
  if (!WaitForOutcome(record, static_cast<uint32_t>(kTerminateWaitMillis))) {
    return KWEB_PROCESSES_STATUS_NATIVE_FAILED;
  }
  {
    std::lock_guard<std::mutex> lock(service->mutex);
    service->processes.erase(process);
  }
  JoinWorkers(record);
  record->child->CloseDescriptors();
  return KWEB_PROCESSES_STATUS_OK;
}

extern "C" kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_close(uint64_t handle) {
  if (handle != kServiceHandle) return KWEB_PROCESSES_STATUS_INVALID_ARGUMENT;
  std::shared_ptr<ServiceState> service;
  {
    std::lock_guard<std::mutex> lock(g_service_mutex);
    if (g_service == nullptr) return KWEB_PROCESSES_STATUS_OWNER_CLOSED;
    service = g_service;
    g_service.reset();
  }
  std::vector<std::shared_ptr<ProcessRecord>> records;
  {
    std::lock_guard<std::mutex> lock(service->mutex);
    for (const auto &entry : service->processes) records.push_back(entry.second);
    service->processes.clear();
  }
  for (const std::shared_ptr<ProcessRecord> &record : records) {
    TerminateRecord(record, KWEB_PROCESSES_REASON_OWNER_CLOSED, false);
    if (!WaitForOutcome(record, static_cast<uint32_t>(kTerminateWaitMillis))) {
      return KWEB_PROCESSES_STATUS_NATIVE_FAILED;
    }
    JoinWorkers(record);
    record->child->CloseDescriptors();
  }
  return KWEB_PROCESSES_STATUS_OK;
}
