/**
 * The Windows provider. One verified child runs inside a job object that owns
 * kill-tree semantics and the declared memory cap; the provider owns the child's
 * pipes and the monitor thread in the ABI layer samples the child's own working
 * set and CPU time and enforces the declared limits.
 */
#include "processes_digest.h"
#include "processes_internal.h"

#include <windows.h>
#include <psapi.h>

#include <algorithm>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {

/** The marker the ABI reports as the abnormal-termination code on Windows. */
constexpr int32_t kCrashMarker = 1;

/** The declared resident cap plus this margin is the job's committed-memory guard. */
constexpr uint64_t kGuardMemoryMarginBytes = 64ull * 1024ull * 1024ull;

std::wstring Widen(const std::string &value) {
  if (value.empty()) return std::wstring();
  const int size = MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), nullptr, 0);
  std::wstring widened(static_cast<size_t>(size), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, value.c_str(), static_cast<int>(value.size()), widened.data(), size);
  return widened;
}

/** A command line built from declared arguments only, never a shell string. */
std::wstring BuildCommandLine(const std::wstring &executable, const std::vector<std::string> &arguments) {
  std::wstring line = L"\"" + executable + L"\"";
  for (const std::string &argument : arguments) {
    line.append(L" \"");
    const std::wstring wide = Widen(argument);
    for (wchar_t character : wide) {
      // CommandLineToArgvW-compatible escaping: a quote is escaped and a
      // backslash is doubled, so no declared argument can end the quoting.
      if (character == L'"' || character == L'\\') line.push_back(L'\\');
      line.push_back(character);
    }
    line.append(L"\"");
  }
  return line;
}

std::wstring BuildEnvironmentBlock(const std::vector<std::string> &environment) {
  std::wstring block;
  for (const std::string &entry : environment) {
    block.append(Widen(entry));
    block.push_back(L'\0');
  }
  block.push_back(L'\0');
  return block;
}

bool CloseWin32(void *handle) {
  return handle != nullptr && handle != INVALID_HANDLE_VALUE && CloseHandle(static_cast<HANDLE>(handle)) != 0;
}

bool VerifyDigest(const std::string &path, const std::string &expected, std::string *failure_detail) {
  const HANDLE file = CreateFileW(Widen(path).c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING,
                                  FILE_ATTRIBUTE_NORMAL, nullptr);
  if (file == INVALID_HANDLE_VALUE) {
    *failure_detail = GetLastError() == ERROR_FILE_NOT_FOUND ? "missing" : "unverified";
    return false;
  }
  if (GetFileType(file) != FILE_TYPE_DISK) {
    CloseHandle(file);
    *failure_detail = "unverified";
    return false;
  }
  kwebshell::processes::Sha256 hash;
  std::vector<uint8_t> buffer(65536);
  for (;;) {
    DWORD read = 0;
    if (ReadFile(file, buffer.data(), static_cast<DWORD>(buffer.size()), &read, nullptr) == 0) {
      CloseHandle(file);
      *failure_detail = "unverified";
      return false;
    }
    if (read == 0) break;
    hash.Update(buffer.data(), read);
  }
  CloseHandle(file);
  uint8_t digest[32];
  hash.Finish(digest);
  if (kwebshell::processes::ToHex(digest, sizeof(digest)) != expected) {
    *failure_detail = "unverified";
    return false;
  }
  return true;
}

class WindowsChild final : public kwebshell::processes::ChildProcess {
 public:
  WindowsChild(HANDLE process, HANDLE job) : process_(process), job_(job), pid_(GetProcessId(process)) {}

  ~WindowsChild() override {
    if (job_ != nullptr) CloseHandle(job_);
    if (process_ != nullptr) CloseHandle(process_);
  }

  int ProcessId() const override { return static_cast<int>(pid_); }

  uint64_t ResidentBytes() override {
    PROCESS_MEMORY_COUNTERS counters;
    std::memset(&counters, 0, sizeof(counters));
    if (K32GetProcessMemoryInfo(process_, &counters, sizeof(counters)) == 0) return 0;
    return static_cast<uint64_t>(counters.WorkingSetSize);
  }

  uint64_t CpuMillis() override {
    FILETIME creation;
    FILETIME exit;
    FILETIME kernel;
    FILETIME user;
    if (GetProcessTimes(process_, &creation, &exit, &kernel, &user) == 0) return 0;
    const auto to_millis = [](const FILETIME &value) {
      ULARGE_INTEGER ticks;
      ticks.LowPart = value.dwLowDateTime;
      ticks.HighPart = value.dwHighDateTime;
      return ticks.QuadPart / 10000ull;
    };
    return to_millis(kernel) + to_millis(user);
  }

  void TerminateTree() override {
    // The job object owns every descendant, so terminating the job is the
    // kill-tree operation and never leaves an orphan behind.
    if (job_ != nullptr) {
      TerminateJobObject(job_, 1);
    } else {
      TerminateProcess(process_, 1);
    }
  }

  int Reap(int *exit_code, int *signal) override {
    const DWORD waited = WaitForSingleObject(process_, 0);
    if (waited == WAIT_TIMEOUT) return 0;
    if (waited != WAIT_OBJECT_0) return -1;
    DWORD code = 0;
    if (GetExitCodeProcess(process_, &code) == 0) return -1;
    // A CRT abort and an NTSTATUS failure are both abnormal terminations; the
    // provider reports its platform marker instead of a POSIX signal number.
    if (code == 3 || (code & 0xc0000000u) == 0xc0000000u) {
      *exit_code = -1;
      *signal = kCrashMarker;
    } else {
      *exit_code = static_cast<int32_t>(code);
      *signal = 0;
    }
    return 1;
  }

  void CloseDescriptors() override {}

  uint32_t SignalReason(int) const override { return 0; }

 private:
  HANDLE process_ = nullptr;
  HANDLE job_ = nullptr;
  DWORD pid_ = 0;
};

class WindowsProvider final : public kwebshell::processes::PlatformProvider {
 public:
  const char *ProviderId() const override { return "processes.windows.job-object"; }

  uint32_t DeclaredIsolation() const override {
    return KWEB_PROCESSES_ISOLATION_PROCESS_TREE | KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS;
  }

  std::unique_ptr<kwebshell::processes::ChildProcess> Launch(
      const kwebshell::processes::OpenOptions &options, intptr_t *stdin_write, intptr_t *stdout_read,
      intptr_t *stderr_read, std::string *failure_detail) override {
    const std::wstring executable = Widen(options.executable_path);
    if (executable.size() < 3 || executable[1] != L':') {
      *failure_detail = "missing";
      return nullptr;
    }
    const DWORD directory_attributes = GetFileAttributesW(Widen(options.working_directory).c_str());
    if (directory_attributes == INVALID_FILE_ATTRIBUTES ||
        (directory_attributes & FILE_ATTRIBUTE_DIRECTORY) == 0) {
      *failure_detail = "working-directory";
      return nullptr;
    }
    if (!VerifyDigest(options.executable_path, options.executable_sha256, failure_detail)) return nullptr;

    SECURITY_ATTRIBUTES attributes;
    attributes.nLength = sizeof(attributes);
    attributes.bInheritHandle = TRUE;
    attributes.lpSecurityDescriptor = nullptr;
    HANDLE input_read = nullptr;
    HANDLE input_write = nullptr;
    HANDLE output_read = nullptr;
    HANDLE output_write = nullptr;
    HANDLE error_read = nullptr;
    HANDLE error_write = nullptr;
    if (CreatePipe(&input_read, &input_write, &attributes, 0) == 0 ||
        CreatePipe(&output_read, &output_write, &attributes, 0) == 0 ||
        CreatePipe(&error_read, &error_write, &attributes, 0) == 0) {
      ClosePipes(input_read, input_write, output_read, output_write, error_read, error_write);
      *failure_detail = "native";
      return nullptr;
    }
    // The provider's own ends are never inherited by the child.
    SetHandleInformation(input_write, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(output_read, HANDLE_FLAG_INHERIT, 0);
    SetHandleInformation(error_read, HANDLE_FLAG_INHERIT, 0);

    HANDLE job = CreateJobObjectW(nullptr, nullptr);
    if (job == nullptr) {
      ClosePipes(input_read, input_write, output_read, output_write, error_read, error_write);
      *failure_detail = "native";
      return nullptr;
    }
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits;
    std::memset(&limits, 0, sizeof(limits));
    // The job owns kill-tree semantics and a committed-memory guard above the
    // declared resident cap; the declared cap itself is enforced by the provider
    // monitor, so a legitimate worker's virtual reservations are never clipped.
    limits.BasicLimitInformation.LimitFlags =
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE | JOB_OBJECT_LIMIT_PROCESS_MEMORY;
    limits.ProcessMemoryLimit = static_cast<SIZE_T>(options.memory_bytes + kGuardMemoryMarginBytes);
    if (SetInformationJobObject(job, JobObjectExtendedLimitInformation, &limits, sizeof(limits)) == 0) {
      CloseHandle(job);
      ClosePipes(input_read, input_write, output_read, output_write, error_read, error_write);
      *failure_detail = "unsupported";
      return nullptr;
    }

    std::wstring command_line = BuildCommandLine(executable, options.arguments);
    std::wstring environment = BuildEnvironmentBlock(options.environment);
    const std::wstring working_directory = Widen(options.working_directory);
    STARTUPINFOEXW startup;
    std::memset(&startup, 0, sizeof(startup));
    startup.StartupInfo.cb = sizeof(startup);
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = input_read;
    startup.StartupInfo.hStdOutput = output_write;
    startup.StartupInfo.hStdError = error_write;

    SIZE_T attribute_size = 0;
    InitializeProcThreadAttributeList(nullptr, 1, 0, &attribute_size);
    std::vector<uint8_t> attribute_storage(attribute_size);
    startup.lpAttributeList = reinterpret_cast<LPPROC_THREAD_ATTRIBUTE_LIST>(attribute_storage.data());
    if (InitializeProcThreadAttributeList(startup.lpAttributeList, 1, 0, &attribute_size) == 0) {
      CloseHandle(job);
      ClosePipes(input_read, input_write, output_read, output_write, error_read, error_write);
      *failure_detail = "native";
      return nullptr;
    }
    // Only the three declared standard handles are inherited by the child.
    HANDLE inherited[3] = {input_read, output_write, error_write};
    const BOOL attribute_ready = UpdateProcThreadAttribute(
        startup.lpAttributeList, 0, PROC_THREAD_ATTRIBUTE_HANDLE_LIST, inherited, sizeof(inherited), nullptr,
        nullptr);

    PROCESS_INFORMATION process_info;
    std::memset(&process_info, 0, sizeof(process_info));
    const BOOL created =
        attribute_ready != 0 &&
        CreateProcessW(executable.c_str(), command_line.data(), nullptr, nullptr, TRUE,
                       EXTENDED_STARTUPINFO_PRESENT | CREATE_UNICODE_ENVIRONMENT, environment.data(),
                       working_directory.c_str(), &startup.StartupInfo, &process_info) != 0;
    DeleteProcThreadAttributeList(startup.lpAttributeList);
    CloseWin32(input_read);
    CloseWin32(output_write);
    CloseWin32(error_write);
    if (created == 0) {
      CloseHandle(job);
      ClosePipes(nullptr, input_write, output_read, nullptr, error_read, nullptr);
      *failure_detail = "native";
      return nullptr;
    }
    CloseHandle(process_info.hThread);
    if (AssignProcessToJobObject(job, process_info.hProcess) == 0) {
      TerminateProcess(process_info.hProcess, 1);
      CloseHandle(process_info.hProcess);
      CloseHandle(job);
      ClosePipes(nullptr, input_write, output_read, nullptr, error_read, nullptr);
      *failure_detail = "unsupported";
      return nullptr;
    }
    *stdin_write = reinterpret_cast<intptr_t>(input_write);
    *stdout_read = reinterpret_cast<intptr_t>(output_read);
    *stderr_read = reinterpret_cast<intptr_t>(error_read);
    return std::make_unique<WindowsChild>(process_info.hProcess, job);
  }

 private:
  static void ClosePipes(HANDLE input_read, HANDLE input_write, HANDLE output_read, HANDLE output_write,
                         HANDLE error_read, HANDLE error_write) {
    const HANDLE handles[6] = {input_read, input_write, output_read, output_write, error_read, error_write};
    for (HANDLE handle : handles) CloseWin32(handle);
  }
};

}  // namespace

namespace kwebshell::processes {

int WaitReadable(intptr_t handle, uint32_t timeout_millis) {
  const HANDLE pipe = reinterpret_cast<HANDLE>(handle);
  const ULONGLONG deadline = GetTickCount64() + timeout_millis;
  for (;;) {
    DWORD available = 0;
    if (PeekNamedPipe(pipe, nullptr, 0, nullptr, &available, nullptr) == 0) return -1;
    if (available > 0) return 1;
    if (GetTickCount64() >= deadline) return 0;
    Sleep(2);
  }
}

int ReadSome(intptr_t handle, uint8_t *buffer, size_t size) {
  DWORD read = 0;
  if (ReadFile(reinterpret_cast<HANDLE>(handle), buffer, static_cast<DWORD>(size), &read, nullptr) == 0) {
    return GetLastError() == ERROR_BROKEN_PIPE ? 0 : -1;
  }
  return static_cast<int>(read);
}

int WriteBlocking(intptr_t handle, const uint8_t *bytes, size_t size) {
  DWORD written = 0;
  if (WriteFile(reinterpret_cast<HANDLE>(handle), bytes, static_cast<DWORD>(size), &written, nullptr) == 0) {
    return -1;
  }
  return static_cast<int>(written);
}

void ClosePipeHandle(intptr_t handle) { CloseWin32(reinterpret_cast<HANDLE>(handle)); }

std::unique_ptr<PlatformProvider> CreatePlatformProvider() { return std::make_unique<WindowsProvider>(); }

}  // namespace kwebshell::processes
