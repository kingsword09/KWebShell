#include "kwebshell/native/event_recorder.h"
#include "kwebshell/native/host_configuration.h"
#include "kwebshell/native/shutdown_watchdog.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <mutex>
#include <string>
#include <vector>

namespace {

int failures = 0;

void Check(bool condition, const std::string &message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << std::endl;
    ++failures;
  }
}

std::string AbsoluteTestPath(const std::string &name) {
#if defined(_WIN32)
  return "C:\\kwebshell-tests\\" + name;
#else
  return "/tmp/kwebshell-tests/" + name;
#endif
}

std::string RootCacheArgument(const std::string &name = "profile") {
  return "--kweb-root-cache-path=" + AbsoluteTestPath(name);
}

void TestValidConfiguration() {
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {RootCacheArgument(),
       "--kweb-event-log-path=" + AbsoluteTestPath("events.jsonl"),
       "--kweb-url=https://example.com", "--kweb-width=1024",
       "--kweb-height=768"},
      &error);

  Check(configuration.has_value(),
        "valid configuration should parse: " + error);
  if (configuration) {
    Check(configuration->width == 1024, "width should be parsed");
    Check(configuration->height == 768, "height should be parsed");
    Check(configuration->url == "https://example.com", "URL should be parsed");
  }
}

void TestStrictConfigurationFailures() {
  const std::vector<std::vector<std::string>> invalid_arguments = {
      {"--kweb-url=https://example.com"},
      {"--kweb-root-cache-path=relative", "--kweb-url=https://example.com"},
      {RootCacheArgument(), "--kweb-width=0", "--kweb-url=https://example.com"},
      {RootCacheArgument(), "--kweb-unknown=true",
       "--kweb-url=https://example.com"},
      {RootCacheArgument()},
  };

  for (const auto &arguments : invalid_arguments) {
    std::string error;
    Check(!kwebshell::HostConfiguration::Parse(arguments, &error).has_value(),
          "invalid configuration should fail");
    Check(!error.empty(), "invalid configuration should explain failure");
  }
}

void TestSelfTestConfigurationDoesNotRequireExternalUrl() {
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {RootCacheArgument(), "--kweb-self-test"}, &error);
  Check(configuration.has_value(), "self-test configuration should parse");
  Check(configuration && configuration->self_test,
        "self-test flag should be retained");
}

void TestConfigurationPreservesUtf8Paths() {
  const std::string utf8_name = "profile-\xE6\xB5\x8B\xE8\xAF\x95";
  const std::string expected_path = AbsoluteTestPath(utf8_name);
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {"--kweb-root-cache-path=" + expected_path, "--kweb-self-test"}, &error);
  Check(configuration.has_value(),
        "UTF-8 configuration path should parse: " + error);
  if (configuration) {
    const std::u8string encoded_path =
        configuration->root_cache_path.u8string();
    const std::string actual_path(encoded_path.begin(), encoded_path.end());
    Check(actual_path == expected_path,
          "filesystem path should preserve UTF-8 command-line bytes");
  }
}

void TestEventRecorderEscapesJsonAndTracksState() {
  const auto path =
      std::filesystem::temp_directory_path() / "kweb-host-unit-events.jsonl";
  {
    kwebshell::EventRecorder recorder(path);
    Check(recorder.IsOpen(), "event recorder should open a temp file");
    recorder.Record("quoted", {{"value", "line\n\"value\""}});
    Check(recorder.MarkChildProcess("renderer") == 1,
          "first renderer launch should be counted");
    Check(recorder.MarkChildProcess("gpu-process") == 1,
          "first GPU launch should be counted");
    Check(recorder.MarkChildProcess("gpu-process") == 2,
          "GPU restarts should be counted");
    recorder.Fail("native.test.failure", {{"detail", "expected"}});
    Check(recorder.saw_renderer_process(),
          "renderer process should be tracked");
    Check(recorder.saw_gpu_process(), "GPU process should be tracked");
    Check(recorder.failed(), "failure state should be tracked");
  }

  std::string content;
  {
    std::ifstream stream(path);
    Check(stream.is_open(), "event recorder output should be readable");
    content.assign(std::istreambuf_iterator<char>(stream),
                   std::istreambuf_iterator<char>());
  }
  Check(content.find("line\\n\\\"value\\\"") != std::string::npos,
        "JSON control characters should be escaped");
  Check(content.find("native.test.failure") != std::string::npos,
        "typed failure code should be recorded");
  std::error_code remove_error;
  const bool removed = std::filesystem::remove(path, remove_error);
  Check(removed && !remove_error,
        "event recorder output should be removed after closing the reader");
}

void TestShutdownWatchdogRejectsInvalidConfiguration() {
  std::string error;
  auto watchdog = kwebshell::ShutdownWatchdog::Start(
      std::chrono::milliseconds::zero(), [] {}, error);
  Check(!watchdog, "zero shutdown watchdog timeout should be rejected");
  Check(!error.empty(),
        "invalid shutdown watchdog timeout should explain failure");

  watchdog = kwebshell::ShutdownWatchdog::Start(std::chrono::milliseconds(10),
                                                {}, error);
  Check(!watchdog, "missing shutdown watchdog handler should be rejected");
  Check(!error.empty(),
        "missing shutdown watchdog handler should explain failure");
}

void TestShutdownWatchdogCompletionSuppressesTimeout() {
  std::atomic<bool> timed_out = false;
  std::string error;
  auto watchdog = kwebshell::ShutdownWatchdog::Start(
      std::chrono::milliseconds(50), [&] { timed_out.store(true); }, error);
  Check(watchdog != nullptr, "valid shutdown watchdog should start: " + error);
  if (watchdog) {
    watchdog->Complete();
  }
  Check(!timed_out.load(), "completed shutdown watchdog should not time out");
}

void TestShutdownWatchdogTimesOutExactlyOnce() {
  std::mutex mutex;
  std::condition_variable timeout_condition;
  unsigned int timeout_count = 0;
  std::string error;
  auto watchdog = kwebshell::ShutdownWatchdog::Start(
      std::chrono::milliseconds(20),
      [&] {
        {
          std::lock_guard lock(mutex);
          ++timeout_count;
        }
        timeout_condition.notify_one();
      },
      error);
  Check(watchdog != nullptr, "valid shutdown watchdog should start: " + error);
  if (watchdog) {
    std::unique_lock lock(mutex);
    const bool observed = timeout_condition.wait_for(
        lock, std::chrono::seconds(1), [&] { return timeout_count > 0; });
    Check(observed, "shutdown watchdog should invoke its timeout handler");
    lock.unlock();
    watchdog->Complete();
  }
  Check(timeout_count == 1,
        "shutdown watchdog timeout handler should run exactly once");
}

} // namespace

int main() {
  TestValidConfiguration();
  TestStrictConfigurationFailures();
  TestSelfTestConfigurationDoesNotRequireExternalUrl();
  TestConfigurationPreservesUtf8Paths();
  TestEventRecorderEscapesJsonAndTracksState();
  TestShutdownWatchdogRejectsInvalidConfiguration();
  TestShutdownWatchdogCompletionSuppressesTimeout();
  TestShutdownWatchdogTimesOutExactlyOnce();

  if (failures != 0) {
    std::cerr << failures << " native unit assertion(s) failed." << std::endl;
    return 1;
  }
  std::cout << "All native host unit tests passed." << std::endl;
  return 0;
}
