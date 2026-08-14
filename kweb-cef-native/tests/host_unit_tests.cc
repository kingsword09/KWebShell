#include "kwebshell/native/event_recorder.h"
#include "kwebshell/native/host_configuration.h"
#include "kwebshell/native/shutdown_watchdog.h"
#include "mv3_core_test_fixture.h"

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
  return "--kweb-root-cache-path=" + AbsoluteTestPath(name + "-root");
}

std::string ProfileArgument(const std::string &name = "profile") {
  return "--kweb-profile-path=" + AbsoluteTestPath(name + "-root/primary");
}

void TestValidConfiguration() {
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {RootCacheArgument(), ProfileArgument(),
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
    Check(kwebshell::IsDirectProfileChild(configuration->root_cache_path,
                                          configuration->profile_path),
          "profile path should be retained as a direct root child");
  }
}

void TestStrictConfigurationFailures() {
  const std::vector<std::vector<std::string>> invalid_arguments = {
      {"--kweb-url=https://example.com"},
      {RootCacheArgument(), "--kweb-url=https://example.com"},
      {"--kweb-root-cache-path=relative", ProfileArgument(),
       "--kweb-url=https://example.com"},
      {RootCacheArgument(), "--kweb-profile-path=relative",
       "--kweb-url=https://example.com"},
      {RootCacheArgument(),
       "--kweb-profile-path=" + AbsoluteTestPath("outside-profile"),
       "--kweb-url=https://example.com"},
      {RootCacheArgument(),
       "--kweb-profile-path=" + AbsoluteTestPath("profile-root/Default"),
       "--kweb-url=https://example.com"},
      {RootCacheArgument(),
       "--kweb-profile-path=" + AbsoluteTestPath("profile-root/dEfAuLt"),
       "--kweb-url=https://example.com"},
      {RootCacheArgument(), ProfileArgument(), "--kweb-width=0",
       "--kweb-url=https://example.com"},
      {RootCacheArgument(), ProfileArgument(), "--kweb-unknown=true",
       "--kweb-url=https://example.com"},
      {RootCacheArgument(), ProfileArgument()},
      {RootCacheArgument(), ProfileArgument(), "--kweb-self-test",
       "--kweb-profile-self-test=read", "--kweb-profile-test-value=value"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-profile-self-test=unknown", "--kweb-profile-test-value=value"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-profile-self-test=write"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-profile-test-value=value", "--kweb-url=https://example.com"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-mv3-core-self-test=unknown",
       "--kweb-mv3-extension-path=" + AbsoluteTestPath("extension")},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-mv3-core-self-test=initial"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-mv3-core-self-test=initial",
       "--kweb-mv3-extension-path=relative"},
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-mv3-extension-path=" + AbsoluteTestPath("extension"),
       "--kweb-url=https://example.com"},
      {RootCacheArgument(), ProfileArgument(), "--kweb-self-test",
       "--kweb-mv3-core-self-test=initial",
       "--kweb-mv3-extension-path=" + AbsoluteTestPath("extension")},
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
      {RootCacheArgument(), ProfileArgument(), "--kweb-self-test"}, &error);
  Check(configuration.has_value(), "self-test configuration should parse");
  Check(configuration && configuration->self_test,
        "self-test flag should be retained");
}

void TestConfigurationPreservesUtf8Paths() {
  const std::string utf8_name = "profile-\xE6\xB5\x8B\xE8\xAF\x95";
  const std::string expected_path = AbsoluteTestPath(utf8_name);
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {"--kweb-root-cache-path=" + expected_path,
       "--kweb-profile-path=" + expected_path + "/primary", "--kweb-self-test"},
      &error);
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

void TestProfileSelfTestConfigurationIsStrict() {
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {RootCacheArgument(), ProfileArgument(), "--kweb-profile-self-test=write",
       "--kweb-profile-test-value=profile-token_01"},
      &error);
  Check(configuration.has_value(),
        "profile self-test configuration should parse: " + error);
  if (configuration) {
    Check(configuration->IsProfileSelfTest(),
          "profile self-test mode should be retained");
    Check(configuration->IsAnySelfTest(),
          "profile self-test should suppress the external URL requirement");
    Check(configuration->profile_self_test_mode ==
              kwebshell::ProfileSelfTestMode::kWrite,
          "profile write mode should be explicit");
    Check(configuration->profile_test_value == "profile-token_01",
          "profile test value should be retained exactly");
  }

  Check(kwebshell::IsDirectProfileChild(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("root/a"))),
        "direct profile child should be accepted");
  Check(!kwebshell::IsDirectProfileChild(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("root"))),
        "root itself must not be accepted as a profile");
  Check(!kwebshell::IsDirectProfileChild(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("other/profile"))),
        "sibling directory must not be accepted as a profile");
  Check(!kwebshell::IsDirectProfileChild(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("root/profiles/a"))),
        "nested profile must not be accepted by the Chrome bootstrap");
  Check(!kwebshell::IsSupportedPersistentProfilePath(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("root/Default"))),
        "Chromium's Default profile name must be reserved");
  Check(!kwebshell::IsSupportedPersistentProfilePath(
            std::filesystem::path(AbsoluteTestPath("root")),
            std::filesystem::path(AbsoluteTestPath("root/dEfAuLt"))),
        "the Default profile reservation must be case-insensitive");
}

void TestMv3CoreSelfTestConfigurationIsStrict() {
  const std::string extension_path = AbsoluteTestPath("mv3-extension");
  std::string error;
  const auto configuration = kwebshell::HostConfiguration::Parse(
      {RootCacheArgument(), ProfileArgument(),
       "--kweb-mv3-core-self-test=restart",
       "--kweb-mv3-extension-path=" + extension_path},
      &error);
  Check(configuration.has_value(),
        "MV3 core self-test configuration should parse: " + error);
  if (configuration) {
    Check(configuration->IsMv3CoreSelfTest(),
          "MV3 core self-test mode should be retained");
    Check(configuration->IsAnySelfTest(),
          "MV3 core self-test should suppress the external URL requirement");
    Check(configuration->mv3_core_self_test_mode ==
              kwebshell::Mv3CoreSelfTestMode::kRestart,
          "MV3 restart mode should be explicit");
    Check(configuration->mv3_extension_path ==
              std::filesystem::path(extension_path),
          "MV3 extension path should be retained exactly");
  }
}

void WriteMv3CoreFixtureFiles(const std::filesystem::path &root) {
  std::filesystem::create_directories(root);
  for (const char *name : {"manifest.json", "worker.js", "content.js"}) {
    std::ofstream stream(root / name);
    stream << "fixture";
  }
}

void TestMv3CoreFixtureValidationIsStrict() {
  const std::filesystem::path test_root =
      std::filesystem::temp_directory_path() /
      "kweb-mv3-core-fixture-validation";
  std::error_code filesystem_error;
  std::filesystem::remove_all(test_root, filesystem_error);
  Check(!filesystem_error, "stale MV3 fixture test root should be removable");

  const std::filesystem::path valid_fixture = test_root / "valid";
  WriteMv3CoreFixtureFiles(valid_fixture);
  std::string error;
  const auto validated =
      kwebshell::ValidateMv3CoreTestFixture(valid_fixture, error);
  Check(validated.has_value(),
        "complete MV3 core fixture should validate: " + error);
  Check(validated && *validated == std::filesystem::canonical(valid_fixture),
        "MV3 core fixture should return its canonical path");

  std::filesystem::remove(valid_fixture / "content.js", filesystem_error);
  Check(!filesystem_error, "MV3 fixture content script should be removable");
  Check(!kwebshell::ValidateMv3CoreTestFixture(valid_fixture, error),
        "MV3 core fixture missing content.js should fail");
  Check(error.find("content.js") != std::string::npos,
        "missing MV3 fixture file should be identified");

  const std::filesystem::path comma_fixture = test_root / "invalid,fixture";
  WriteMv3CoreFixtureFiles(comma_fixture);
  Check(!kwebshell::ValidateMv3CoreTestFixture(comma_fixture, error),
        "MV3 fixture path containing a switch separator should fail");
  Check(error.find("cannot contain ','") != std::string::npos,
        "MV3 fixture separator failure should be actionable");

  Check(!kwebshell::ValidateMv3CoreTestFixture(test_root / "absent", error),
        "missing MV3 fixture directory should fail");
  Check(!error.empty(), "missing MV3 fixture directory should explain failure");

  std::filesystem::remove_all(test_root, filesystem_error);
  Check(!filesystem_error, "MV3 fixture test root should be removed");
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
  TestProfileSelfTestConfigurationIsStrict();
  TestMv3CoreSelfTestConfigurationIsStrict();
  TestMv3CoreFixtureValidationIsStrict();
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
