#include "host_main.h"

#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <iostream>
#include <memory>
#include <string>
#include <utility>

#include "browser_app.h"
#include "include/cef_command_line.h"
#include "kwebshell/native/event_recorder.h"
#include "kwebshell/native/host_configuration.h"
#include "kwebshell/native/shutdown_watchdog.h"
#include "mv3_core_test_fixture.h"

namespace kwebshell {
namespace {

constexpr auto kCefShutdownTimeout = std::chrono::seconds(30);

void PrintStartupError(const std::string &code, const std::string &message) {
  std::cerr << "{\"event\":\"startup_error\",\"code\":\"" << code
            << "\",\"message\":\"" << message << "\"}" << std::endl;
}

std::string PathForLog(const std::filesystem::path &path) {
#if defined(OS_WIN)
  return CefString(path.wstring()).ToString();
#else
  return path.string();
#endif
}

void AssignCefPath(cef_string_t *output, const std::filesystem::path &path) {
  CefString cef_path(output);
#if defined(OS_WIN)
  cef_path = path.wstring();
#else
  cef_path = path.string();
#endif
}

void ShutdownCef(const std::shared_ptr<EventRecorder> &recorder,
                 int exit_code) {
  recorder->Record("cef_shutdown_started");
  std::string watchdog_error;
  auto watchdog = ShutdownWatchdog::Start(
      kCefShutdownTimeout,
      [recorder] {
        recorder->Fail("native.cef.shutdown-timeout",
                       {{"timeout_ms",
                         std::to_string(kCefShutdownTimeout.count() * 1000)}});
        recorder->Record(
            "cef_shutdown_forced_exit",
            {{"exit_code", std::to_string(static_cast<int>(
                               HostExitCode::kBrowserRuntimeError))}});
        std::_Exit(static_cast<int>(HostExitCode::kBrowserRuntimeError));
      },
      watchdog_error);
  if (!watchdog) {
    recorder->Fail("native.cef.shutdown-watchdog-start-failed",
                   {{"message", watchdog_error}});
    recorder->Record("cef_shutdown_forced_exit",
                     {{"exit_code", std::to_string(static_cast<int>(
                                        HostExitCode::kBrowserRuntimeError))}});
    std::_Exit(static_cast<int>(HostExitCode::kBrowserRuntimeError));
  }

  const auto shutdown_started_at = std::chrono::steady_clock::now();
  CefShutdown();
  const auto shutdown_duration =
      std::chrono::duration_cast<std::chrono::milliseconds>(
          std::chrono::steady_clock::now() - shutdown_started_at);
  watchdog->Complete();
  recorder->Record("cef_shutdown",
                   {{"duration_ms", std::to_string(shutdown_duration.count())},
                    {"exit_code", std::to_string(exit_code)}});
}

} // namespace

int RunBrowserProcess(const CefMainArgs &main_args,
                      const std::vector<std::string> &arguments,
                      void *sandbox_info,
                      PlatformInitializer platform_initializer) {
  std::string configuration_error;
  auto parsed = HostConfiguration::Parse(arguments, &configuration_error);
  if (!parsed) {
    PrintStartupError("native.configuration.invalid", configuration_error);
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  HostConfiguration configuration = std::move(*parsed);

  auto recorder = std::make_shared<EventRecorder>(configuration.event_log_path);
  if (!recorder->IsOpen()) {
    PrintStartupError("native.event-log.open-failed", recorder->open_error());
    return static_cast<int>(HostExitCode::kEventLogError);
  }
  recorder->Record("browser_process_start");

  std::error_code directory_error;
  std::filesystem::create_directories(configuration.root_cache_path,
                                      directory_error);
  if (directory_error) {
    recorder->Fail("native.cache.create-failed",
                   {{"path", PathForLog(configuration.root_cache_path)},
                    {"message", directory_error.message()}});
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  configuration.root_cache_path = std::filesystem::canonical(
      configuration.root_cache_path, directory_error);
  if (directory_error) {
    recorder->Fail("native.cache.canonicalize-failed",
                   {{"path", PathForLog(configuration.root_cache_path)},
                    {"message", directory_error.message()}});
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  std::filesystem::create_directories(configuration.profile_path,
                                      directory_error);
  if (directory_error) {
    recorder->Fail("native.profile.create-failed",
                   {{"path", PathForLog(configuration.profile_path)},
                    {"message", directory_error.message()}});
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  configuration.profile_path =
      std::filesystem::canonical(configuration.profile_path, directory_error);
  if (directory_error) {
    recorder->Fail("native.profile.canonicalize-failed",
                   {{"path", PathForLog(configuration.profile_path)},
                    {"message", directory_error.message()}});
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  if (!IsSupportedPersistentProfilePath(configuration.root_cache_path,
                                        configuration.profile_path)) {
    recorder->Fail(
        "native.profile.path-invalid",
        {{"root_cache_path", PathForLog(configuration.root_cache_path)},
         {"profile_path", PathForLog(configuration.profile_path)}});
    return static_cast<int>(HostExitCode::kConfigurationError);
  }
  if (configuration.IsMv3CoreSelfTest()) {
    const std::filesystem::path requested_extension_path =
        configuration.mv3_extension_path;
    std::string fixture_error;
    const auto validated_extension_path = ValidateMv3CoreTestFixture(
        requested_extension_path, fixture_error);
    if (!validated_extension_path) {
      recorder->Fail(
          "native.mv3.test-extension-path-invalid",
          {{"path", PathForLog(requested_extension_path)},
           {"message", fixture_error}});
      return static_cast<int>(HostExitCode::kConfigurationError);
    }
    configuration.mv3_extension_path = *validated_extension_path;
  }

  CefSettings settings;
  settings.no_sandbox = sandbox_info == nullptr;
  settings.windowless_rendering_enabled = false;
  settings.multi_threaded_message_loop = false;
  settings.external_message_pump = false;
  settings.remote_debugging_port = 0;
  AssignCefPath(&settings.root_cache_path, configuration.root_cache_path);
  AssignCefPath(&settings.log_file,
                configuration.root_cache_path / "kweb-cef.log");
  settings.log_severity = LOGSEVERITY_INFO;

  CefRefPtr<BrowserApp> app(new BrowserApp(configuration, recorder));
  if (!CefInitialize(main_args, settings, app.get(), sandbox_info)) {
    const int cef_exit_code = CefGetExitCode();
    recorder->Fail("native.cef.initialize-failed",
                   {{"cef_exit_code", std::to_string(cef_exit_code)}});
    return cef_exit_code > 0
               ? cef_exit_code
               : static_cast<int>(HostExitCode::kCefInitializationError);
  }

  if (platform_initializer) {
    std::string platform_error;
    if (!platform_initializer(&platform_error)) {
      const int exit_code = static_cast<int>(HostExitCode::kConfigurationError);
      recorder->Fail("native.platform.initialize-failed",
                     {{"message", platform_error}});
      app->PrepareForShutdown();
      app = nullptr;
      ShutdownCef(recorder, exit_code);
      return exit_code;
    }
  }

  CefRunMessageLoop();
  const int exit_code = app->exit_code();
  app->PrepareForShutdown();
  app = nullptr;
  ShutdownCef(recorder, exit_code);
  return recorder->failed()
             ? static_cast<int>(HostExitCode::kBrowserRuntimeError)
             : exit_code;
}

} // namespace kwebshell
