/**
 * The declared helper executable of the RFC 0024 native tests and the JVM
 * integration fixture. Every mode is deterministic and reports its own facts so
 * a provider test can assert real bytes instead of trusting a summary.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(_WIN32)
#include <windows.h>
#include <wchar.h>
#else
#include <signal.h>
#include <unistd.h>
#endif

#include <string>
#include <vector>

#if !defined(_WIN32)
extern char **environ;
#endif

namespace {

std::string Hex(const unsigned char *bytes, size_t size) {
  static const char *digits = "0123456789abcdef";
  std::string output;
  output.reserve(size * 2);
  for (size_t index = 0; index < size; ++index) {
    output.push_back(digits[bytes[index] >> 4]);
    output.push_back(digits[bytes[index] & 0x0fu]);
  }
  return output;
}

std::string Hex(const std::string &value) {
  return Hex(reinterpret_cast<const unsigned char *>(value.data()), value.size());
}

unsigned char PatternByte(size_t index) { return static_cast<unsigned char>((index * 31u + 7u) & 0xffu); }

int ExitCode(const char *argument) { return static_cast<int>(strtol(argument, nullptr, 10)); }

long LongValue(const char *argument) { return strtol(argument, nullptr, 10); }

std::string CurrentDirectory() {
#if defined(_WIN32)
  wchar_t buffer[4096];
  const DWORD size = GetCurrentDirectoryW(4096, buffer);
  if (size == 0) return std::string();
  char converted[8192];
  const int written = WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size), converted,
                                          sizeof(converted), nullptr, nullptr);
  return written > 0 ? std::string(converted, static_cast<size_t>(written)) : std::string();
#else
  char buffer[4096];
  return getcwd(buffer, sizeof(buffer)) != nullptr ? std::string(buffer) : std::string();
#endif
}

std::vector<std::string> Environment() {
  std::vector<std::string> entries;
#if defined(_WIN32)
  LPWCH block = GetEnvironmentStringsW();
  if (block == nullptr) return entries;
  for (const wchar_t *entry = block; *entry != L'\0'; entry += wcslen(entry) + 1) {
    char converted[8192];
    const int written = WideCharToMultiByte(CP_UTF8, 0, entry, static_cast<int>(wcslen(entry)), converted,
                                            sizeof(converted), nullptr, nullptr);
    if (written > 0) entries.emplace_back(converted, static_cast<size_t>(written));
  }
  FreeEnvironmentStringsW(block);
#else
  for (char **entry = environ; entry != nullptr && *entry != nullptr; ++entry) entries.emplace_back(*entry);
#endif
  return entries;
}

int ProcessId() {
#if defined(_WIN32)
  return static_cast<int>(GetCurrentProcessId());
#else
  return static_cast<int>(getpid());
#endif
}

int ParentProcessId() {
#if defined(_WIN32)
  return 0;
#else
  return static_cast<int>(getppid());
#endif
}

void SleepSeconds(long seconds) {
#if defined(_WIN32)
  Sleep(static_cast<DWORD>(seconds * 1000));
#else
  sleep(static_cast<unsigned int>(seconds));
#endif
}

[[maybe_unused]] std::string ExecutablePath(const char *argv0) {
#if defined(_WIN32)
  wchar_t buffer[4096];
  const DWORD size = GetModuleFileNameW(nullptr, buffer, 4096);
  if (size == 0) return std::string(argv0);
  char converted[8192];
  const int written = WideCharToMultiByte(CP_UTF8, 0, buffer, static_cast<int>(size), converted,
                                          sizeof(converted), nullptr, nullptr);
  return written > 0 ? std::string(converted, static_cast<size_t>(written)) : std::string(argv0);
#else
  return std::string(argv0);
#endif
}

void Report() {
  printf("pid %d\n", ProcessId());
  printf("ppid %d\n", ParentProcessId());
  printf("cwd %s\n", Hex(CurrentDirectory()).c_str());
  std::vector<std::string> entries = Environment();
  // Names only, sorted, so a test can prove the allowlist without printing a
  // value that could carry a host secret. Per-drive directory variables whose
  // name starts with '=' are skipped because no declared entry can express one.
  std::vector<std::string> names;
  for (const std::string &entry : entries) {
    const size_t separator = entry.find('=');
    if (separator == 0 || separator == std::string::npos) continue;
    names.push_back(entry.substr(0, separator));
  }
  for (size_t index = 0; index + 1 < names.size(); ++index) {
    for (size_t other = index + 1; other < names.size(); ++other) {
      if (names[other] < names[index]) {
        const std::string swap = names[index];
        names[index] = names[other];
        names[other] = swap;
      }
    }
  }
  for (const std::string &name : names) printf("env-name %s\n", name.c_str());
}

int Burst(size_t bytes, FILE *stream) {
  std::vector<unsigned char> block(65536);
  size_t remaining = bytes;
  size_t offset = 0;
  while (remaining > 0) {
    const size_t take = remaining < block.size() ? remaining : block.size();
    for (size_t index = 0; index < take; ++index) block[index] = PatternByte(offset + index);
    if (fwrite(block.data(), 1, take, stream) != take) return 1;
    fflush(stream);
    remaining -= take;
    offset += take;
  }
  return 0;
}

int Hold(long mebibytes) {
  const size_t chunk = 16u * 1024u * 1024u;
  size_t total = 0;
  unsigned long long state = 0x2545f4914f6cdd1dull;
  const size_t target = static_cast<size_t>(mebibytes) * 1024u * 1024u;
  while (total < target) {
    unsigned char *block = static_cast<unsigned char *>(malloc(chunk));
    if (block == nullptr) break;
    for (size_t index = 0; index < chunk; index += 8) {
      state ^= state << 13;
      state ^= state >> 7;
      state ^= state << 17;
      memcpy(block + index, &state, 8);
    }
    // Touch every page again so the resident set is real, not an optimized store.
    volatile unsigned long long touch = 0;
    for (size_t index = 0; index < chunk; index += 4096) touch ^= block[index];
    if (touch == 0x5a5a5a5aull) printf("unreachable\n");
    total += chunk;
  }
  printf("held %zu\n", total / (1024u * 1024u));
  fflush(stdout);
  SleepSeconds(30);
  return 0;
}

int Tree(const char *argv0) {
#if defined(_WIN32)
  STARTUPINFOW startup;
  PROCESS_INFORMATION process_info;
  memset(&startup, 0, sizeof(startup));
  startup.cb = sizeof(startup);
  memset(&process_info, 0, sizeof(process_info));
  const std::string path = ExecutablePath(argv0);
  std::wstring wide(path.begin(), path.end());
  const int size = MultiByteToWideChar(CP_UTF8, 0, path.c_str(), static_cast<int>(path.size()), nullptr, 0);
  if (size > 0) {
    wide.assign(static_cast<size_t>(size), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, path.c_str(), static_cast<int>(path.size()), wide.data(), size);
  }
  std::wstring command = L"\"" + wide + L"\" sleep 30";
  if (CreateProcessW(wide.c_str(), command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup,
                     &process_info) == 0) {
    return 4;
  }
  printf("child %lu\n", static_cast<unsigned long>(process_info.dwProcessId));
  CloseHandle(process_info.hThread);
  CloseHandle(process_info.hProcess);
#else
  const pid_t child = fork();
  if (child == 0) {
    char *const args[] = {const_cast<char *>(argv0), const_cast<char *>("sleep"), const_cast<char *>("30"),
                          nullptr};
    execv(argv0, args);
    _exit(9);
  }
  printf("child %d\n", static_cast<int>(child));
#endif
  fflush(stdout);
  SleepSeconds(30);
  return 0;
}

}  // namespace

int main(int argc, char **argv) {
  if (argc < 2) {
    fprintf(stderr, "usage: processes_helper <mode> [payload]\n");
    return 2;
  }
  const std::string mode = argv[1];
  if (mode == "report") {
    Report();
    // Every declared argument after the executable path is reported as its
    // exact hex form so a test can prove byte equality, including Unicode.
    printf("argc %d\n", argc - 1);
    for (int index = 1; index < argc; ++index) {
      printf("arg %d %s\n", index - 1, Hex(std::string(argv[index])).c_str());
    }
    return 0;
  }
  if (mode == "announce") {
    printf("pid %d\n", ProcessId());
    fflush(stdout);
    SleepSeconds(LongValue(argv[2]));
    return 0;
  }
  if (mode == "echo") {
    std::vector<unsigned char> buffer(4096);
    size_t total = 0;
    for (;;) {
      const size_t read_bytes = fread(buffer.data(), 1, buffer.size(), stdin);
      if (read_bytes > 0) {
        if (fwrite(buffer.data(), 1, read_bytes, stdout) != read_bytes) return 1;
        fflush(stdout);
        total += read_bytes;
        continue;
      }
      if (feof(stdin) != 0) break;
    }
    fprintf(stderr, "echo %zu\n", total);
    return 0;
  }
  if (mode == "stdout-burst" || mode == "stderr-burst") {
    return Burst(static_cast<size_t>(LongValue(argv[2])), mode == "stdout-burst" ? stdout : stderr);
  }
  if (mode == "hold") return Hold(LongValue(argv[2]));
  if (mode == "spin") {
    volatile unsigned long long value = 0;
    for (;;) value = value + 1;
  }
  if (mode == "sleep") {
    SleepSeconds(LongValue(argv[2]));
    return 0;
  }
  if (mode == "tree") return Tree(argv[0]);
  if (mode == "exit") return ExitCode(argv[2]);
  if (mode == "crash") {
    abort();
  }
  fprintf(stderr, "unknown mode\n");
  return 3;
}
