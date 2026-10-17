/**
 * The macOS and Linux provider. One shared implementation issues the verified
 * child with its own process group, a declared environment, a declared working
 * directory, and bounded resource guards; a monitor thread in the ABI layer
 * samples the child's own resident memory and CPU time and enforces the declared
 * limits.
 */
#if defined(__APPLE__) && !defined(_DARWIN_C_SOURCE)
#define _DARWIN_C_SOURCE 1
#endif

#include "processes_digest.h"
#include "processes_internal.h"
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <poll.h>
#include <signal.h>
#include <string>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>
#include <vector>

#if defined(__APPLE__)
#include <libproc.h>
#include <mach/mach_time.h>
#else
#include <sys/prctl.h>
#include <sys/syscall.h>
#endif

namespace {

constexpr uint64_t kGuardMemoryMarginBytes [[maybe_unused]] = 64ull * 1024ull * 1024ull;
constexpr rlim_t kGuardDescriptorLimit = 256;

/**
 * macOS reports per-process CPU time in mach absolute time units, not in
 * nanoseconds; the timebase turns both counters into real milliseconds.
 */
#if defined(__APPLE__)
uint64_t MachTicksToMillis(uint64_t ticks) {
  static const mach_timebase_info_data_t timebase = [] {
    mach_timebase_info_data_t value;
    mach_timebase_info(&value);
    return value;
  }();
  return ticks * timebase.numer / timebase.denom / 1000000ull;
}
#endif

/** One declared CPU quota: the OS signals at the quota and stops one second later. */
void SetCpuGuard(uint32_t cpu_seconds) {
  struct rlimit limit = {static_cast<rlim_t>(cpu_seconds), static_cast<rlim_t>(cpu_seconds) + 1};
  [[maybe_unused]] const int ignored = setrlimit(RLIMIT_CPU, &limit);
}

void SetGuardLimit(int resource, rlim_t value) {
  struct rlimit limit = {value, value};
  // A guard cap, not the declared limit: the declared limit is enforced by the
  // monitor thread through the child's own counters.
  [[maybe_unused]] const int ignored = setrlimit(resource, &limit);
}

/** Reads one bounded decimal field out of a small text file. */
[[maybe_unused]] bool ReadCounter(const char *path, const char *key, uint64_t *value) {
  FILE *file = std::fopen(path, "rb");
  if (file == nullptr) return false;
  char line[256];
  bool found = false;
  const size_t key_length = std::strlen(key);
  while (std::fgets(line, sizeof(line), file) != nullptr) {
    if (std::strncmp(line, key, key_length) != 0) continue;
    unsigned long long parsed = 0;
    if (std::sscanf(line + key_length, "%llu", &parsed) == 1) {
      *value = static_cast<uint64_t>(parsed);
      found = true;
    }
    break;
  }
  std::fclose(file);
  return found;
}

class PosixChild final : public kwebshell::processes::ChildProcess {
 public:
  PosixChild(pid_t pid, int stdin_write, int stdout_read, int stderr_read)
      : pid_(pid), stdin_write_(stdin_write), stdout_read_(stdout_read), stderr_read_(stderr_read) {}

  int ProcessId() const override { return static_cast<int>(pid_); }

  uint64_t ResidentBytes() override {
#if defined(__APPLE__)
    struct proc_taskinfo info;
    std::memset(&info, 0, sizeof(info));
    if (proc_pidinfo(pid_, PROC_PIDTASKINFO, 0, &info, static_cast<int>(sizeof(info))) !=
        static_cast<int>(sizeof(info))) {
      return 0;
    }
    return static_cast<uint64_t>(info.pti_resident_size);
#else
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/status", static_cast<int>(pid_));
    uint64_t kilobytes = 0;
    if (!ReadCounter(path, "VmRSS:", &kilobytes)) return 0;
    return kilobytes * 1024ull;
#endif
  }

  uint64_t CpuMillis() override {
#if defined(__APPLE__)
    struct proc_taskinfo info;
    std::memset(&info, 0, sizeof(info));
    if (proc_pidinfo(pid_, PROC_PIDTASKINFO, 0, &info, static_cast<int>(sizeof(info))) !=
        static_cast<int>(sizeof(info))) {
      return 0;
    }
    return MachTicksToMillis(info.pti_total_user + info.pti_total_system);
#else
    char path[64];
    std::snprintf(path, sizeof(path), "/proc/%d/stat", static_cast<int>(pid_));
    FILE *file = std::fopen(path, "rb");
    if (file == nullptr) return 0;
    char content[1024];
    const size_t size = std::fread(content, 1, sizeof(content) - 1, file);
    std::fclose(file);
    content[size] = '\0';
    // Field 2 is the command in parentheses and may contain spaces, so the
    // counters are read after its closing parenthesis.
    const char *cursor = std::strrchr(content, ')');
    if (cursor == nullptr) return 0;
    unsigned long long utime = 0;
    unsigned long long stime = 0;
    const int parsed = std::sscanf(cursor + 2,
                                   "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &utime, &stime);
    if (parsed != 2) return 0;
    const long ticks = sysconf(_SC_CLK_TCK);
    if (ticks <= 0) return 0;
    return (static_cast<uint64_t>(utime) + static_cast<uint64_t>(stime)) * 1000ull /
           static_cast<uint64_t>(ticks);
#endif
  }

  void TerminateTree() override {
    // The child leads its own process group, so the group kill reaches every
    // descendant the child created as well.
    if (kill(-pid_, SIGKILL) != 0) {
      [[maybe_unused]] const int ignored = kill(pid_, SIGKILL);
    }
  }

  int Reap(int *exit_code, int *signal) override {
    int status = 0;
    const pid_t reaped = waitpid(pid_, &status, WNOHANG);
    if (reaped == 0) return 0;
    if (reaped < 0) return -1;
    if (WIFSIGNALED(status)) {
      *exit_code = -1;
      *signal = WTERMSIG(status);
    } else if (WIFEXITED(status)) {
      *exit_code = WEXITSTATUS(status);
      *signal = 0;
    } else {
      *exit_code = -1;
      *signal = 0;
    }
    return 1;
  }

  void CloseDescriptors() override {
    if (stdin_write_ >= 0) {
      close(stdin_write_);
      stdin_write_ = -1;
    }
    if (stdout_read_ >= 0) {
      close(stdout_read_);
      stdout_read_ = -1;
    }
    if (stderr_read_ >= 0) {
      close(stderr_read_);
      stderr_read_ = -1;
    }
  }

  uint32_t SignalReason(int signal) const override {
    return signal == SIGXCPU ? KWEB_PROCESSES_REASON_CPU_LIMIT : 0;
  }

 private:
  pid_t pid_ = -1;
  int stdin_write_ = -1;
  int stdout_read_ = -1;
  int stderr_read_ = -1;
};

class PosixProvider final : public kwebshell::processes::PlatformProvider {
 public:
  const char *ProviderId() const override {
#if defined(__APPLE__)
    return "processes.macos.posix-exec";
#else
    return "processes.linux.posix-exec";
#endif
  }

  uint32_t DeclaredIsolation() const override {
    return KWEB_PROCESSES_ISOLATION_PROCESS_TREE | KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS;
  }

  std::unique_ptr<kwebshell::processes::ChildProcess> Launch(
      const kwebshell::processes::OpenOptions &options, intptr_t *stdin_write, intptr_t *stdout_read,
      intptr_t *stderr_read, std::string *failure_detail) override {
    if (options.executable_path.empty() || options.executable_path.front() != '/') {
      *failure_detail = "missing";
      return nullptr;
    }
    struct stat directory;
    if (stat(options.working_directory.c_str(), &directory) != 0 || !S_ISDIR(directory.st_mode)) {
      *failure_detail = "working-directory";
      return nullptr;
    }
    const int executable = open(options.executable_path.c_str(), O_RDONLY);
    if (executable < 0) {
      *failure_detail = "missing";
      return nullptr;
    }
    struct stat executable_stat;
    if (fstat(executable, &executable_stat) != 0 || !S_ISREG(executable_stat.st_mode)) {
      close(executable);
      *failure_detail = "unverified";
      return nullptr;
    }
    kwebshell::processes::Sha256 hash;
    uint8_t buffer[65536];
    for (;;) {
      const ssize_t read_bytes = read(executable, buffer, sizeof(buffer));
      if (read_bytes < 0) {
        close(executable);
        *failure_detail = "unverified";
        return nullptr;
      }
      if (read_bytes == 0) break;
      hash.Update(buffer, static_cast<size_t>(read_bytes));
    }
    uint8_t digest[32];
    hash.Finish(digest);
    const std::string actual = kwebshell::processes::ToHex(digest, sizeof(digest));
    if (actual != options.executable_sha256) {
      close(executable);
      *failure_detail = "unverified";
      return nullptr;
    }

    int input_pipe[2] = {-1, -1};
    int output_pipe[2] = {-1, -1};
    int error_pipe[2] = {-1, -1};
    if (pipe(input_pipe) != 0 || pipe(output_pipe) != 0 || pipe(error_pipe) != 0) {
      CloseAll(input_pipe, output_pipe, error_pipe);
      close(executable);
      *failure_detail = "native";
      return nullptr;
    }

    // Both argv and envp are prepared before the fork so the child only runs
    // async-signal-safe calls up to the exec.
    std::vector<char *> argv;
    argv.reserve(options.arguments.size() + 2);
    argv.push_back(const_cast<char *>(options.executable_path.c_str()));
    for (const std::string &argument : options.arguments) argv.push_back(const_cast<char *>(argument.c_str()));
    argv.push_back(nullptr);
    std::vector<char *> envp;
    envp.reserve(options.environment.size() + 1);
    for (const std::string &entry : options.environment) envp.push_back(const_cast<char *>(entry.c_str()));
    envp.push_back(nullptr);

    const pid_t pid = fork();
    if (pid == 0) {
      // Child: process group, declared stdio, declared limits, declared
      // environment, declared working directory, then the verified executable.
      [[maybe_unused]] const int ignored_group = setpgid(0, 0);
      if (dup2(input_pipe[0], STDIN_FILENO) < 0 || dup2(output_pipe[1], STDOUT_FILENO) < 0 ||
          dup2(error_pipe[1], STDERR_FILENO) < 0) {
        _exit(126);
      }
      close(input_pipe[0]);
      close(input_pipe[1]);
      close(output_pipe[0]);
      close(output_pipe[1]);
      close(error_pipe[0]);
      close(error_pipe[1]);
      close(executable);
      SetCpuGuard(options.cpu_seconds);
      SetGuardLimit(RLIMIT_NOFILE, kGuardDescriptorLimit);
#if defined(__linux__)
      // No address-space rlimit is installed: a declared *resident* cap must not
      // clip a legitimate worker's virtual reservations (a JVM reserves far more
      // address space than its working set). The declared cap is enforced by the
      // provider monitor on the child's own resident counter, exactly as on macOS.
      [[maybe_unused]] const long ignored_death = syscall(SYS_prctl, PR_SET_PDEATHSIG, SIGKILL);
#endif
      if (chdir(options.working_directory.c_str()) != 0) _exit(125);
      execve(options.executable_path.c_str(), argv.data(), envp.data());
      _exit(127);
    }
    close(executable);
    if (pid < 0) {
      CloseAll(input_pipe, output_pipe, error_pipe);
      *failure_detail = "native";
      return nullptr;
    }
    [[maybe_unused]] const int ignored_group = setpgid(pid, pid);
    close(input_pipe[0]);
    close(output_pipe[1]);
    close(error_pipe[1]);
    SetCloseOnExec(input_pipe[1]);
    SetCloseOnExec(output_pipe[0]);
    SetCloseOnExec(error_pipe[0]);
    *stdin_write = input_pipe[1];
    *stdout_read = output_pipe[0];
    *stderr_read = error_pipe[0];
    return std::make_unique<PosixChild>(pid, input_pipe[1], output_pipe[0], error_pipe[0]);
  }

 private:
  static void CloseAll(const int input_pipe[2], const int output_pipe[2], const int error_pipe[2]) {
    for (int index = 0; index < 2; ++index) {
      if (input_pipe[index] >= 0) close(input_pipe[index]);
      if (output_pipe[index] >= 0) close(output_pipe[index]);
      if (error_pipe[index] >= 0) close(error_pipe[index]);
    }
  }

  static void SetCloseOnExec(int descriptor) {
    const int flags = fcntl(descriptor, F_GETFD);
    if (flags >= 0) [[maybe_unused]] const int ignored = fcntl(descriptor, F_SETFD, flags | FD_CLOEXEC);
  }
};

}  // namespace

namespace kwebshell::processes {

int WaitReadable(intptr_t handle, uint32_t timeout_millis) {
  struct pollfd entry;
  entry.fd = static_cast<int>(handle);
  entry.events = POLLIN;
  entry.revents = 0;
  const int ready = poll(&entry, 1, static_cast<int>(timeout_millis));
  if (ready < 0) return -1;
  if (ready == 0) return 0;
  if ((entry.revents & (POLLERR | POLLNVAL)) != 0) return -1;
  return 1;
}

int ReadSome(intptr_t handle, uint8_t *buffer, size_t size) {
  for (;;) {
    const ssize_t read_bytes = read(static_cast<int>(handle), buffer, size);
    if (read_bytes >= 0) return static_cast<int>(read_bytes);
    if (errno != EINTR && errno != EAGAIN) return -1;
  }
}

int WriteBlocking(intptr_t handle, const uint8_t *bytes, size_t size) {
  for (;;) {
    const ssize_t written = write(static_cast<int>(handle), bytes, size);
    if (written >= 0) return static_cast<int>(written);
    if (errno == EINTR) continue;
    return -1;
  }
}

void ClosePipeHandle(intptr_t handle) {
  if (handle >= 0) close(static_cast<int>(handle));
}

std::unique_ptr<PlatformProvider> CreatePlatformProvider() { return std::make_unique<PosixProvider>(); }

}  // namespace kwebshell::processes
