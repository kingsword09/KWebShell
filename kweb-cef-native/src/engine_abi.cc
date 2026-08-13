#include "kwebshell/native/engine_abi.h"

#include <atomic>
#include <cstdint>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <new>
#include <thread>
#include <utility>

#if defined(_WIN32)
#include <windows.h>
#endif

#include "engine_configuration.h"
#include "browser_session.h"
#include "engine_internal.h"
#include "engine_platform.h"
#include "include/cef_app.h"
#include "include/cef_browser_process_handler.h"

namespace kwebshell {
namespace {

void AssignCefPath(cef_string_t *output, const std::filesystem::path &path) {
  CefString cef_path(output);
#if defined(_WIN32)
  cef_path = path.wstring();
#else
  cef_path = path.string();
#endif
}

class Engine;

class EngineApplication final : public CefApp, public CefBrowserProcessHandler {
public:
  explicit EngineApplication(Engine *engine) : engine_(engine) {}

  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override {
    return this;
  }

  void OnBeforeCommandLineProcessing(
      const CefString &process_type,
      CefRefPtr<CefCommandLine> command_line) override;
  void OnContextInitialized() override;
  void OnScheduleMessagePumpWork(int64_t delay_ms) override;
  bool OnAlreadyRunningAppRelaunch(CefRefPtr<CefCommandLine> command_line,
                                   const CefString &current_directory) override;

private:
  Engine *const engine_;

  IMPLEMENT_REFCOUNTING(EngineApplication);
  DISALLOW_COPY_AND_ASSIGN(EngineApplication);
};

class Engine final {
public:
  Engine(kweb_engine_handle handle, kweb_engine_event_callback callback,
         void *user_data, ValidatedEngineConfiguration configuration)
      : handle_(handle), callback_(callback), user_data_(user_data),
        configuration_(std::move(configuration)),
        application_(new EngineApplication(this)) {}

  Engine(const Engine &) = delete;
  Engine &operator=(const Engine &) = delete;

  bool Initialize() {
    if (!EnginePlatformRuntimeMatches(configuration_.cef_runtime_path)) {
      return false;
    }

    CefSettings settings;
    settings.no_sandbox = true;
    settings.windowless_rendering_enabled = false;
    settings.command_line_args_disabled = true;
    settings.remote_debugging_port = configuration_.remote_debugging_port;
    settings.log_severity = LOGSEVERITY_INFO;
#if !defined(_WIN32)
    settings.disable_signal_handlers = true;
#endif
#if defined(__APPLE__)
    settings.multi_threaded_message_loop = false;
    settings.external_message_pump = true;
#else
    settings.multi_threaded_message_loop = true;
    settings.external_message_pump = false;
#endif
    AssignCefPath(&settings.browser_subprocess_path,
                  configuration_.browser_subprocess_path);
    AssignCefPath(&settings.resources_dir_path, configuration_.resources_path);
    AssignCefPath(&settings.locales_dir_path, configuration_.locales_path);
    AssignCefPath(&settings.root_cache_path, configuration_.root_cache_path);
    AssignCefPath(&settings.log_file, configuration_.log_path);
    CefString(&settings.locale) = "en-US";
#if defined(__APPLE__)
    AssignCefPath(&settings.framework_dir_path,
                  configuration_.framework_dir_path);
    AssignCefPath(&settings.main_bundle_path, configuration_.main_bundle_path);
#endif

#if defined(_WIN32)
    const CefMainArgs main_args(::GetModuleHandleW(nullptr));
#elif defined(__APPLE__)
    char program_name[] = "kwebshell";
    char *arguments[] = {program_name};
    const CefMainArgs main_args(1, arguments);
#else
    const CefMainArgs main_args(0, nullptr);
#endif
    if (!InitializeCefOnPlatform(main_args, settings, application_)) {
      application_ = nullptr;
      CleanupPlatformAfterCefInitializeFailure();
      return false;
    }
    initialized_.store(true, std::memory_order_release);
    return true;
  }

  kweb_status Shutdown(std::function<void(kweb_status)> completion) {
    if (!initialized_.exchange(false, std::memory_order_acq_rel)) {
      return KWEB_STATUS_CEF_INITIALIZE_FAILED;
    }
    shutdown_completion_ = std::move(completion);
    const kweb_status shutdown_status =
        ShutdownCefOnPlatform(&Engine::PlatformShutdownCompleted, this);
    if (shutdown_status != KWEB_STATUS_OK) {
      shutdown_completion_ = {};
      return shutdown_status;
    }
    return platform_failure_.load(std::memory_order_acquire)
               ? KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED
               : KWEB_STATUS_OK;
  }

  static void PlatformShutdownCompleted(void *context, kweb_status status) {
    static_cast<Engine *>(context)->CompleteShutdown(status);
  }

  void CompleteShutdown(kweb_status status) {
    Emit(KWEB_ENGINE_EVENT_CLOSED);
    application_ = nullptr;
    auto completion = std::move(shutdown_completion_);
    completion(status);
  }

  void ContextInitialized() {
    bool expected = false;
    if (opened_emitted_.compare_exchange_strong(expected, true,
                                                std::memory_order_acq_rel)) {
      Emit(KWEB_ENGINE_EVENT_OPENED);
    }
  }

  void ScheduleMessagePumpWork(int64_t delay_ms) {
    if (!ScheduleCefMessagePumpWorkOnPlatform(delay_ms)) {
      platform_failure_.store(true, std::memory_order_release);
    }
  }

  const std::filesystem::path &root_cache_path() const {
    return configuration_.root_cache_path;
  }

private:
  void Emit(kweb_engine_event_type type) {
    std::lock_guard lock(callback_mutex_);
    const kweb_engine_event event = {sizeof(kweb_engine_event),
                                     KWEB_ABI_VERSION,
                                     type,
                                     0,
                                     handle_,
                                     ++sequence_};
    callback_(user_data_, &event);
  }

  const kweb_engine_handle handle_;
  const kweb_engine_event_callback callback_;
  void *const user_data_;
  const ValidatedEngineConfiguration configuration_;
  CefRefPtr<EngineApplication> application_;
  std::mutex callback_mutex_;
  uint64_t sequence_ = 0;
  std::atomic<bool> initialized_ = false;
  std::atomic<bool> opened_emitted_ = false;
  std::atomic<bool> platform_failure_ = false;
  std::function<void(kweb_status)> shutdown_completion_;
};

void EngineApplication::OnContextInitialized() {
  engine_->ContextInitialized();
}

void EngineApplication::OnBeforeCommandLineProcessing(
    const CefString &process_type, CefRefPtr<CefCommandLine> command_line) {
  ConfigureEngineCommandLineOnPlatform(process_type, command_line);
}

void EngineApplication::OnScheduleMessagePumpWork(int64_t delay_ms) {
  engine_->ScheduleMessagePumpWork(delay_ms);
}

bool EngineApplication::OnAlreadyRunningAppRelaunch(
    CefRefPtr<CefCommandLine> command_line,
    const CefString &current_directory) {
  (void)command_line;
  (void)current_directory;
  return true;
}

enum class RegistryState {
  kNeverStarted,
  kStarting,
  kRunning,
  kClosing,
  kTerminal,
};

class EngineRegistry final {
public:
  kweb_status PlatformStartup(const char *cef_runtime_path_utf8,
                              size_t cef_runtime_path_size) {
    {
      std::lock_guard lock(mutex_);
      if (state_ == RegistryState::kTerminal) {
        return KWEB_STATUS_ENGINE_RESTART_FORBIDDEN;
      }
      if (state_ != RegistryState::kNeverStarted) {
        return KWEB_STATUS_ENGINE_ALREADY_EXISTS;
      }
    }
    return EnginePlatformStartup(cef_runtime_path_utf8, cef_runtime_path_size);
  }

  kweb_status Create(const kweb_engine_config *config,
                     kweb_engine_handle *engine_out) {
    if (config == nullptr || engine_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    *engine_out = KWEB_INVALID_ENGINE_HANDLE;
    if (config->struct_size < sizeof(kweb_engine_config) ||
        config->abi_version != KWEB_ABI_VERSION) {
      return KWEB_STATUS_ABI_MISMATCH;
    }
    if (config->reserved != 0) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    if (config->callback == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }

    {
      std::lock_guard lock(mutex_);
      if (state_ == RegistryState::kTerminal) {
        return KWEB_STATUS_ENGINE_RESTART_FORBIDDEN;
      }
      if (state_ != RegistryState::kNeverStarted) {
        return state_ == RegistryState::kClosing
                   ? KWEB_STATUS_ENGINE_CLOSING
                   : KWEB_STATUS_ENGINE_ALREADY_EXISTS;
      }
      state_ = RegistryState::kStarting;
    }

    ValidatedEngineConfiguration validated;
    const kweb_status validation_status =
        ValidateEngineConfiguration(*config, &validated);
    if (validation_status != KWEB_STATUS_OK) {
      ResetBeforeInitialization();
      return validation_status;
    }
    if (!EnginePlatformRuntimeMatches(validated.cef_runtime_path)) {
      ResetBeforeInitialization();
      return KWEB_STATUS_CEF_RUNTIME_MISMATCH;
    }
    const kweb_status remote_debugging_status =
        ValidateRemoteDebuggingPortAvailability(
            validated.remote_debugging_port);
    if (remote_debugging_status != KWEB_STATUS_OK) {
      ResetBeforeInitialization();
      return remote_debugging_status;
    }

    const kweb_engine_handle handle = next_handle_++;
    std::shared_ptr<Engine> engine;
    try {
      engine = std::make_shared<Engine>(
          handle, config->callback, config->user_data, std::move(validated));
    } catch (...) {
      ResetBeforeInitialization();
      throw;
    }
    {
      std::lock_guard lock(mutex_);
      engine_ = engine;
      initialization_thread_ = std::this_thread::get_id();
    }
    bool initialized = false;
    try {
      initialized = engine->Initialize();
    } catch (...) {
      std::lock_guard lock(mutex_);
      engine_.reset();
      state_ = RegistryState::kTerminal;
      throw;
    }
    if (!initialized) {
      std::lock_guard lock(mutex_);
      engine_.reset();
      state_ = RegistryState::kTerminal;
      return KWEB_STATUS_CEF_INITIALIZE_FAILED;
    }

    {
      std::lock_guard lock(mutex_);
      state_ = RegistryState::kRunning;
    }
    *engine_out = handle;
    return KWEB_STATUS_OK;
  }

  kweb_status Close(kweb_engine_handle handle) {
    std::shared_ptr<Engine> engine;
    {
      std::lock_guard lock(mutex_);
      if (handle == KWEB_INVALID_ENGINE_HANDLE || !engine_ ||
          handle != current_handle()) {
        return KWEB_STATUS_INVALID_HANDLE;
      }
      if (std::this_thread::get_id() != initialization_thread_) {
        return KWEB_STATUS_WRONG_THREAD;
      }
      if (state_ == RegistryState::kClosing) {
        return KWEB_STATUS_ENGINE_CLOSING;
      }
      if (state_ != RegistryState::kRunning) {
        return KWEB_STATUS_INVALID_HANDLE;
      }
      if (LiveBrowserSessionCount() != 0) {
        return KWEB_STATUS_ENGINE_HAS_LIVE_BROWSERS;
      }
      state_ = RegistryState::kClosing;
      engine = engine_;
    }

    return engine->Shutdown(
        [this, handle, keep_alive = engine](kweb_status status) {
          (void)keep_alive;
          CompleteClose(handle, status);
        });
  }

  uint64_t LiveCount() const {
    std::lock_guard lock(mutex_);
    return engine_ && state_ != RegistryState::kClosing ? 1U : 0U;
  }

  kweb_status ValidateForBrowser(
      kweb_engine_handle handle,
      std::filesystem::path *root_cache_out) const {
    if (root_cache_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    std::lock_guard lock(mutex_);
    if (handle == KWEB_INVALID_ENGINE_HANDLE || !engine_ ||
        handle != current_handle()) {
      return KWEB_STATUS_INVALID_HANDLE;
    }
    if (state_ == RegistryState::kClosing) {
      return KWEB_STATUS_ENGINE_CLOSING;
    }
    if (state_ != RegistryState::kRunning) {
      return KWEB_STATUS_INVALID_HANDLE;
    }
    *root_cache_out = engine_->root_cache_path();
    return KWEB_STATUS_OK;
  }

private:
  void CompleteClose(kweb_engine_handle handle, kweb_status status) {
    (void)status;
    std::lock_guard lock(mutex_);
    if (engine_ && handle == current_handle() &&
        state_ == RegistryState::kClosing) {
      engine_.reset();
      state_ = RegistryState::kTerminal;
    }
  }

  void ResetBeforeInitialization() {
    std::lock_guard lock(mutex_);
    state_ = RegistryState::kNeverStarted;
  }

  kweb_engine_handle current_handle() const { return next_handle_ - 1; }

  mutable std::mutex mutex_;
  std::shared_ptr<Engine> engine_;
  std::thread::id initialization_thread_;
  kweb_engine_handle next_handle_ = 1;
  RegistryState state_ = RegistryState::kNeverStarted;
};

EngineRegistry &Registry() {
  static EngineRegistry registry;
  return registry;
}

template <typename Operation> kweb_status GuardStatus(Operation operation) {
  try {
    return operation();
  } catch (const std::bad_alloc &) {
    return KWEB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return KWEB_STATUS_INTERNAL_ERROR;
  }
}

} // namespace

kweb_status ValidateEngineForBrowser(
    kweb_engine_handle engine, std::filesystem::path *root_cache_out) {
  return Registry().ValidateForBrowser(engine, root_cache_out);
}
} // namespace kwebshell

extern "C" {

uint32_t KWEB_ABI_CALL kweb_engine_abi_version(void) {
  return KWEB_ABI_VERSION;
}

const char *KWEB_ABI_CALL kweb_status_name(kweb_status status) {
  switch (status) {
  case KWEB_STATUS_OK:
    return "ok";
  case KWEB_STATUS_INVALID_ARGUMENT:
    return "invalid-argument";
  case KWEB_STATUS_ABI_MISMATCH:
    return "abi-mismatch";
  case KWEB_STATUS_ALLOCATION_FAILED:
    return "allocation-failed";
  case KWEB_STATUS_THREAD_START_FAILED:
    return "thread-start-failed";
  case KWEB_STATUS_HANDLE_EXHAUSTED:
    return "handle-exhausted";
  case KWEB_STATUS_INVALID_HANDLE:
    return "invalid-handle";
  case KWEB_STATUS_SESSION_CLOSING:
    return "session-closing";
  case KWEB_STATUS_INVALID_TEXT_ENCODING:
    return "invalid-text-encoding";
  case KWEB_STATUS_TEXT_TOO_LARGE:
    return "text-too-large";
  case KWEB_STATUS_INVALID_DIMENSIONS:
    return "invalid-dimensions";
  case KWEB_STATUS_REENTRANT_CLOSE:
    return "reentrant-close";
  case KWEB_STATUS_CALLBACK_FAILED:
    return "callback-failed";
  case KWEB_STATUS_INTERNAL_ERROR:
    return "internal-error";
  case KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED:
    return "engine-library-load-failed";
  case KWEB_STATUS_ENGINE_SYMBOL_MISSING:
    return "engine-symbol-missing";
  case KWEB_STATUS_CEF_RUNTIME_LOAD_FAILED:
    return "cef-runtime-load-failed";
  case KWEB_STATUS_CEF_RUNTIME_MISMATCH:
    return "cef-runtime-mismatch";
  case KWEB_STATUS_PATH_REQUIRED:
    return "path-required";
  case KWEB_STATUS_PATH_NOT_ABSOLUTE:
    return "path-not-absolute";
  case KWEB_STATUS_PATH_NOT_FOUND:
    return "path-not-found";
  case KWEB_STATUS_PATH_TYPE_INVALID:
    return "path-type-invalid";
  case KWEB_STATUS_PATH_MISMATCH:
    return "path-mismatch";
  case KWEB_STATUS_PATH_NOT_WRITABLE:
    return "path-not-writable";
  case KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED:
    return "platform-initialization-failed";
  case KWEB_STATUS_ENGINE_ALREADY_EXISTS:
    return "engine-already-exists";
  case KWEB_STATUS_ENGINE_RESTART_FORBIDDEN:
    return "engine-restart-forbidden";
  case KWEB_STATUS_WRONG_THREAD:
    return "wrong-thread";
  case KWEB_STATUS_CEF_INITIALIZE_FAILED:
    return "cef-initialize-failed";
  case KWEB_STATUS_ENGINE_CLOSING:
    return "engine-closing";
  case KWEB_STATUS_ENGINE_HAS_LIVE_BROWSERS:
    return "engine-has-live-browsers";
  case KWEB_STATUS_PROFILE_PATH_INVALID:
    return "profile-path-invalid";
  case KWEB_STATUS_PARENT_SURFACE_INVALID:
    return "parent-surface-invalid";
  case KWEB_STATUS_BROWSER_CREATE_FAILED:
    return "browser-create-failed";
  case KWEB_STATUS_BROWSER_NOT_READY:
    return "browser-not-ready";
  case KWEB_STATUS_BROWSER_CLOSING:
    return "browser-closing";
  case KWEB_STATUS_CEF_UI_TASK_FAILED:
    return "cef-ui-task-failed";
  case KWEB_STATUS_NAVIGATION_INVALID:
    return "navigation-invalid";
  case KWEB_STATUS_REMOTE_DEBUGGING_PORT_INVALID:
    return "remote-debugging-port-invalid";
  case KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE:
    return "remote-debugging-port-unavailable";
  case KWEB_STATUS_DEVTOOLS_ALREADY_OPEN:
    return "devtools-already-open";
  case KWEB_STATUS_DEVTOOLS_NOT_OPEN:
    return "devtools-not-open";
  case KWEB_STATUS_DEVTOOLS_OPEN_FAILED:
    return "devtools-open-failed";
  case KWEB_STATUS_DEVTOOLS_CLOSING:
    return "devtools-closing";
  default:
    return "unknown-status";
  }
}

kweb_status KWEB_ABI_CALL kweb_engine_platform_startup(
    const char *cef_runtime_path_utf8, size_t cef_runtime_path_size) {
  return kwebshell::GuardStatus([&] {
    return kwebshell::Registry().PlatformStartup(cef_runtime_path_utf8,
                                                 cef_runtime_path_size);
  });
}

kweb_status KWEB_ABI_CALL kweb_engine_create(const kweb_engine_config *config,
                                             kweb_engine_handle *engine_out) {
  return kwebshell::GuardStatus(
      [&] { return kwebshell::Registry().Create(config, engine_out); });
}

kweb_status KWEB_ABI_CALL kweb_engine_close(kweb_engine_handle engine) {
  return kwebshell::GuardStatus(
      [&] { return kwebshell::Registry().Close(engine); });
}

uint64_t KWEB_ABI_CALL kweb_live_engine_count(void) {
  try {
    return kwebshell::Registry().LiveCount();
  } catch (...) {
    return std::numeric_limits<uint64_t>::max();
  }
}

kweb_status KWEB_ABI_CALL kweb_browser_create(
    const kweb_browser_config *config, kweb_browser_handle *browser_out) {
  return kwebshell::CreateBrowserSession(config, browser_out);
}

kweb_status KWEB_ABI_CALL kweb_browser_navigate(kweb_browser_handle browser,
                                                const char *url_utf8,
                                                size_t url_size) {
  return kwebshell::NavigateBrowserSession(browser, url_utf8, url_size);
}

kweb_status KWEB_ABI_CALL kweb_browser_resize(kweb_browser_handle browser,
                                              int32_t width, int32_t height) {
  return kwebshell::ResizeBrowserSession(browser, width, height);
}

kweb_status KWEB_ABI_CALL kweb_browser_close(kweb_browser_handle browser) {
  return kwebshell::CloseBrowserSession(browser);
}

kweb_status KWEB_ABI_CALL
kweb_browser_open_devtools(kweb_browser_handle browser) {
  return kwebshell::OpenDevToolsSession(browser);
}

kweb_status KWEB_ABI_CALL
kweb_browser_close_devtools(kweb_browser_handle browser) {
  return kwebshell::CloseDevToolsSession(browser);
}

uint64_t KWEB_ABI_CALL kweb_live_browser_count(void) {
  return kwebshell::LiveBrowserSessionCount();
}

} // extern "C"
