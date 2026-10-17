#ifndef KWEB_PROCESSES_INTERNAL_H_
#define KWEB_PROCESSES_INTERNAL_H_

#include "kweb_processes.h"

#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace kwebshell::processes {

/** The facts the JVM host resolves before the provider may start anything. */
struct LaunchRequest {
  std::string process_id;
  std::string executable_path;
  std::string executable_sha256;
  std::string working_directory;
  std::vector<std::string> arguments;
  std::vector<std::string> environment;
  uint32_t requested_isolation_bits = 0;
  uint32_t timeout_seconds = 0;
  uint32_t cpu_seconds = 0;
  uint32_t stdout_window_bytes = 0;
  uint32_t stderr_window_bytes = 0;
  uint64_t memory_bytes = 0;
  uint64_t stdout_quota_bytes = 0;
  uint64_t stderr_quota_bytes = 0;
};

/** The live child a platform provider owns until its tree is reaped. */
class ChildProcess {
 public:
  virtual ~ChildProcess() = default;
  /** The child process id; used only inside the provider. */
  virtual int ProcessId() const = 0;
  /** Reads one bounded chunk of the child's resident memory, or 0 when unknown. */
  virtual uint64_t ResidentBytes() = 0;
  /** Reads the child's consumed CPU time in milliseconds. */
  virtual uint64_t CpuMillis() = 0;
  /**
 * Terminates the whole process tree: every descendant the child created is
 * signalled, not only the direct child.
 */
  virtual void TerminateTree() = 0;
  /**
   * Reaps the direct child without blocking; returns 1 when it was reaped (and
   * fills `exit_code` or `signal`), 0 while it still runs, -1 on failure.
   */
  virtual int Reap(int *exit_code, int *signal) = 0;
  /** Closes the provider's descriptors; the child must already be reaped. */
  virtual void CloseDescriptors() = 0;
  /**
   * Maps one observed termination code to a declared limit reason, or 0 when it
   * is a crash. POSIX maps SIGXCPU to the CPU limit; Windows reports 0.
   */
  virtual uint32_t SignalReason(int signal) const = 0;
};

struct OpenOptions {
  std::string executable_path;
  std::string executable_sha256;
  std::string working_directory;
  std::vector<std::string> arguments;
  std::vector<std::string> environment;
  uint64_t memory_bytes = 0;
  uint32_t cpu_seconds = 0;
};

/**
 * One platform provider. The ABI layer owns validation, identity verification,
 * the bounded output windows, limit monitoring, and terminal-reason precedence;
 * the provider owns the target-specific process, pipe, and kill mechanics.
 */
class PlatformProvider {
 public:
  virtual ~PlatformProvider() = default;
  /** The stable provider identity published in the capability report. */
  virtual const char *ProviderId() const = 0;
  /** The isolation profiles this packaged provider implements. */
  virtual uint32_t DeclaredIsolation() const = 0;
  /** The memory measure this provider enforces against. */
  virtual uint32_t MemoryMeasure() const { return KWEB_PROCESSES_MEMORY_RESIDENT; }
  /**
   * Starts one child with declared stdin/stdout/stderr pipes.
   *
   * `stdin_write` is the provider-owned write end, `stdout_read` and
   * `stderr_read` are the provider-owned read ends; they are valid only when the
   * call returns a child. POSIX stores file descriptors; Windows stores HANDLE
   * values.
   */
  virtual std::unique_ptr<ChildProcess> Launch(const OpenOptions &options, intptr_t *stdin_write,
                                               intptr_t *stdout_read, intptr_t *stderr_read,
                                               std::string *failure_detail) = 0;
};

/** Returns the packaged provider of the current target. */
std::unique_ptr<PlatformProvider> CreatePlatformProvider();

/**
 * Waits up to the budget for one provider pipe handle to become readable.
 * Returns 1 when readable, 0 on timeout, -1 when the handle is closed or failed.
 */
int WaitReadable(intptr_t handle, uint32_t timeout_millis);

/** Reads up to `size` bytes; returns the byte count, 0 at end of stream, or -1. */
int ReadSome(intptr_t handle, uint8_t *buffer, size_t size);

/**
 * Writes one piece that the calling provider thread already owns. It may block
 * until the child accepts the bytes, which is why only the stdin writer thread
 * ever calls it; returns the accepted byte count or -1 when the pipe is closed.
 */
int WriteBlocking(intptr_t handle, const uint8_t *bytes, size_t size);

/** Closes one provider pipe handle exactly once. */
void ClosePipeHandle(intptr_t handle);

}  // namespace kwebshell::processes

#endif
