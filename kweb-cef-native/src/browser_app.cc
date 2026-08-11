#include "browser_app.h"

#include <filesystem>
#include <utility>

#include "browser_client.h"
#include "host_main.h"
#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/cef_command_line.h"
#include "include/cef_cookie.h"
#include "include/cef_request_context_handler.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "profile_test_page.h"
#include "self_test_page.h"

namespace kwebshell {
namespace {

constexpr int64_t kProfileCookieFlushTimeoutMs = 30000;

void AssignCefPath(cef_string_t *output, const std::filesystem::path &path) {
  CefString cef_path(output);
#if defined(OS_WIN)
  cef_path = path.wstring();
#else
  cef_path = path.string();
#endif
}

class ProfileRequestContextHandler final : public CefRequestContextHandler {
public:
  explicit ProfileRequestContextHandler(CefRefPtr<BrowserApp> app)
      : app_(app) {}

  void OnRequestContextInitialized(
      CefRefPtr<CefRequestContext> request_context) override {
    CEF_REQUIRE_UI_THREAD();
    CefRefPtr<BrowserApp> app = app_;
    app_ = nullptr;
    app->OnProfileRequestContextInitialized(request_context);
  }

private:
  CefRefPtr<BrowserApp> app_;

  IMPLEMENT_REFCOUNTING(ProfileRequestContextHandler);
};

class ProfileCookieFlushCallback final : public CefCompletionCallback {
public:
  explicit ProfileCookieFlushCallback(CefRefPtr<BrowserApp> app) : app_(app) {}

  void OnComplete() override {
    CEF_REQUIRE_UI_THREAD();
    CefRefPtr<BrowserApp> app = app_;
    app_ = nullptr;
    app->OnProfileCookieFlushCompleted();
  }

private:
  CefRefPtr<BrowserApp> app_;

  IMPLEMENT_REFCOUNTING(ProfileCookieFlushCallback);
};

} // namespace

BrowserApp::BrowserApp(HostConfiguration configuration,
                       std::shared_ptr<EventRecorder> recorder)
    : configuration_(std::move(configuration)), recorder_(std::move(recorder)) {
}

BrowserApp::~BrowserApp() = default;

CefRefPtr<CefBrowserProcessHandler> BrowserApp::GetBrowserProcessHandler() {
  return this;
}

void BrowserApp::OnContextInitialized() {
  CEF_REQUIRE_UI_THREAD();
  recorder_->Record("cef_context_initialized");
  CreateProfileRequestContext();
}

void BrowserApp::CreateProfileRequestContext() {
  CEF_REQUIRE_UI_THREAD();
  CefRequestContextSettings settings;
  AssignCefPath(&settings.cache_path, configuration_.profile_path);
  settings.persist_session_cookies = true;
  recorder_->Record("profile_open_requested",
                    {{"persistent", "true"},
                     {"mode", ProfileSelfTestModeName(
                                  configuration_.profile_self_test_mode)}});
  request_context_ = CefRequestContext::CreateContext(
      settings, new ProfileRequestContextHandler(this));
  if (!request_context_) {
    OnFatalBrowserError("native.profile.context-create-failed", {});
  }
}

void BrowserApp::OnProfileRequestContextInitialized(
    CefRefPtr<CefRequestContext> request_context) {
  CEF_REQUIRE_UI_THREAD();
  if (!request_context || request_context->IsGlobal()) {
    OnFatalBrowserError("native.profile.context-invalid",
                        {{"global", request_context ? "true" : "unknown"}});
    return;
  }
  request_context_ = request_context;
  const std::string actual_cache_path = request_context->GetCachePath();
  CefString expected_cache_path;
#if defined(OS_WIN)
  expected_cache_path = configuration_.profile_path.wstring();
#else
  expected_cache_path = configuration_.profile_path.string();
#endif
  const bool cache_path_matches =
      actual_cache_path == expected_cache_path.ToString();
  recorder_->Record(
      "profile_opened",
      {{"persistent", "true"},
       {"global", "false"},
       {"cache_path_match", cache_path_matches ? "true" : "false"}});
  if (!cache_path_matches) {
    OnFatalBrowserError("native.profile.cache-path-mismatch",
                        {{"expected", expected_cache_path.ToString()},
                         {"actual", actual_cache_path}});
    return;
  }
  if (configuration_.IsProfileSelfTest() &&
      !request_context_->RegisterSchemeHandlerFactory(
          "https", "kwebshell.test",
          CreateProfileSelfTestSchemeHandlerFactory(
              configuration_.profile_self_test_mode,
              configuration_.profile_test_value, recorder_))) {
    OnFatalBrowserError("native.profile.test-scheme-registration-failed", {});
    return;
  }
  CreateBrowserForProfile();
}

void BrowserApp::CreateBrowserForProfile() {
  CEF_REQUIRE_UI_THREAD();
  native_window_ = CreateNativeWindow(this, recorder_);
  client_ = new BrowserClient(this, native_window_.get(), recorder_,
                              configuration_.self_test,
                              configuration_.IsProfileSelfTest());

  std::string url = configuration_.url;
  if (configuration_.self_test) {
    url = BuildSelfTestUrl();
  } else if (configuration_.IsProfileSelfTest()) {
    url = ProfileSelfTestUrl();
  }
  std::string error;
  if (!native_window_->CreateBrowser(configuration_, client_, request_context_,
                                     url, &error)) {
    OnFatalBrowserError("native.browser.create-rejected", {{"message", error}});
    return;
  }

  if (configuration_.IsAnySelfTest()) {
    CefPostDelayedTask(
        TID_UI,
        base::BindOnce(
            [](CefRefPtr<BrowserApp> app) { app->OnSelfTestTimeout(); },
            CefRefPtr<BrowserApp>(this)),
        30000);
  }
}

void BrowserApp::OnBeforeChildProcessLaunch(
    CefRefPtr<CefCommandLine> command_line) {
  std::string process_type = command_line->GetSwitchValue("type");
  if (process_type.empty()) {
    process_type = "unknown";
  }
  const unsigned int launch_count = recorder_->MarkChildProcess(process_type);

  if (process_type == "gpu-process" && launch_count > 1) {
    const bool posted = CefPostTask(
        TID_UI, base::BindOnce(
                    [](CefRefPtr<BrowserApp> app, unsigned int count) {
                      app->OnFatalBrowserError(
                          "native.gpu.process-restarted",
                          {{"launch_count", std::to_string(count)}});
                    },
                    CefRefPtr<BrowserApp>(this), launch_count));
    if (!posted) {
      recorder_->Fail("native.cef.ui-task-post-failed",
                      {{"operation", "gpu-process-restart"}});
      exit_code_.store(static_cast<int>(HostExitCode::kBrowserRuntimeError));
    }
  }

  if (configuration_.self_test &&
      (process_type == "renderer" || process_type == "gpu-process")) {
    CefPostTask(TID_UI, base::BindOnce(
                            [](CefRefPtr<BrowserApp> app) {
                              app->MaybeCompleteSelfTest();
                            },
                            CefRefPtr<BrowserApp>(this)));
  }
}

CefRefPtr<CefClient> BrowserApp::GetDefaultClient() { return client_; }

void BrowserApp::OnNativeCloseRequested() {
  CEF_REQUIRE_UI_THREAD();
  RequestProfileFlushAndClose();
}

void BrowserApp::OnNativeFatalError(
    const std::string &code,
    const std::map<std::string, std::string> &details) {
  CEF_REQUIRE_UI_THREAD();
  OnFatalBrowserError(code, details);
}

void BrowserApp::OnBrowserCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  if (!request_context_ ||
      !browser->GetHost()->GetRequestContext()->IsSame(request_context_)) {
    OnFatalBrowserError("native.profile.browser-context-mismatch", {});
    return;
  }
  browser_created_ = true;
  native_window_->OnBrowserCreated(browser);
}

void BrowserApp::OnBrowserDestroyed() {
  CEF_REQUIRE_UI_THREAD();
  browser_destroyed_ = true;
  if (native_window_) {
    native_window_->OnBrowserDestroyed();
  }
  recorder_->Record("browser_destroyed");
  if (!CefPostTask(TID_UI, base::BindOnce(&BrowserApp::QuitMessageLoop,
                                          CefRefPtr<BrowserApp>(this)))) {
    recorder_->Fail("native.cef.quit-post-failed");
    exit_code_.store(static_cast<int>(HostExitCode::kBrowserRuntimeError));
    QuitMessageLoop();
  }
}

void BrowserApp::QuitMessageLoop() {
  CEF_REQUIRE_UI_THREAD();
  recorder_->Record("cef_quit_requested");
  CefQuitMessageLoop();
  recorder_->Record("cef_quit_returned");
}

void BrowserApp::OnSelfTestPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  if (self_test_page_passed_) {
    return;
  }
  self_test_page_passed_ = true;
  recorder_->Record("native_input_self_test_passed", {{"result", result}});
  // Chromium's macOS wheel latching posts its phase-end task after 500ms.
  if (!CefPostDelayedTask(
          TID_UI,
          base::BindOnce(&BrowserApp::OnSelfTestInputSettled,
                         CefRefPtr<BrowserApp>(this)),
          750)) {
    OnFatalBrowserError("native.cef.ui-task-post-failed",
                        {{"operation", "input-settle"}});
  }
}

void BrowserApp::OnProfileSelfTestPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.IsProfileSelfTest() || profile_self_test_page_passed_) {
    return;
  }
  profile_self_test_page_passed_ = true;
  recorder_->Record(
      "profile_self_test_passed",
      {{"mode", ProfileSelfTestModeName(configuration_.profile_self_test_mode)},
       {"value", configuration_.profile_test_value},
       {"result", result}});
  MaybeCompleteProfileSelfTest();
}

void BrowserApp::OnProfileSelfTestPageLoaded() {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.IsProfileSelfTest() || profile_self_test_page_loaded_) {
    return;
  }
  profile_self_test_page_loaded_ = true;
  MaybeCompleteProfileSelfTest();
}

void BrowserApp::MaybeCompleteProfileSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (!profile_self_test_page_passed_ || !profile_self_test_page_loaded_) {
    return;
  }
  RequestProfileFlushAndClose();
}

void BrowserApp::RequestProfileFlushAndClose() {
  CEF_REQUIRE_UI_THREAD();
  if (profile_cookie_flush_started_ || fatal_error_reported_ ||
      close_requested_) {
    return;
  }
  CefRefPtr<CefCookieManager> cookie_manager =
      request_context_->GetCookieManager(nullptr);
  if (!cookie_manager) {
    OnFatalBrowserError("native.profile.cookie-manager-missing", {});
    return;
  }
  profile_cookie_flush_started_ = true;
  recorder_->Record("profile_cookie_flush_started");
  if (!CefPostDelayedTask(
          TID_UI,
          base::BindOnce(&BrowserApp::OnProfileCookieFlushTimeout,
                         CefRefPtr<BrowserApp>(this)),
          kProfileCookieFlushTimeoutMs)) {
    OnFatalBrowserError("native.cef.ui-task-post-failed",
                        {{"operation", "profile-cookie-flush-timeout"}});
    return;
  }
  if (!cookie_manager->FlushStore(new ProfileCookieFlushCallback(this))) {
    OnFatalBrowserError("native.profile.cookie-flush-rejected", {});
  }
}

void BrowserApp::OnProfileCookieFlushCompleted() {
  CEF_REQUIRE_UI_THREAD();
  if (!profile_cookie_flush_started_ || profile_cookie_flush_completed_) {
    OnFatalBrowserError("native.profile.cookie-flush-state-invalid", {});
    return;
  }
  profile_cookie_flush_completed_ = true;
  recorder_->Record("profile_cookie_flush_completed");
  CloseBrowser(false);
}

void BrowserApp::OnProfileCookieFlushTimeout() {
  CEF_REQUIRE_UI_THREAD();
  if (!profile_cookie_flush_started_ || profile_cookie_flush_completed_ ||
      fatal_error_reported_ || close_requested_) {
    return;
  }
  OnFatalBrowserError(
      "native.profile.cookie-flush-timeout",
      {{"timeout_ms", std::to_string(kProfileCookieFlushTimeoutMs)}});
}

void BrowserApp::OnFatalBrowserError(
    const std::string &code,
    const std::map<std::string, std::string> &details) {
  CEF_REQUIRE_UI_THREAD();
  if (fatal_error_reported_) {
    return;
  }
  fatal_error_reported_ = true;
  recorder_->Fail(code, details);
  exit_code_.store(static_cast<int>(HostExitCode::kBrowserRuntimeError));
  CloseBrowser(true);
}

int BrowserApp::exit_code() const { return exit_code_.load(); }

void BrowserApp::PrepareForShutdown() {
  CEF_REQUIRE_UI_THREAD();
  client_ = nullptr;
  native_window_.reset();
  request_context_ = nullptr;
}

void BrowserApp::MaybeCompleteSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.self_test || close_requested_ ||
      !self_test_page_passed_) {
    return;
  }
  if (!recorder_->saw_renderer_process()) {
    return;
  }
  if (!recorder_->saw_gpu_process()) {
    return;
  }

  recorder_->Record("native_self_test_passed");
  RequestProfileFlushAndClose();
}

void BrowserApp::OnSelfTestInputSettled() {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.self_test || close_requested_ || browser_destroyed_) {
    return;
  }
  recorder_->Record("native_input_settled");
  MaybeCompleteSelfTest();
}

void BrowserApp::OnSelfTestTimeout() {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.IsAnySelfTest() || close_requested_ ||
      browser_destroyed_) {
    return;
  }
  OnFatalBrowserError(
      "native.self-test.timeout",
      {{"page", self_test_page_passed_ ? "passed" : "pending"},
       {"profile_page", profile_self_test_page_passed_ ? "passed" : "pending"},
       {"profile_load",
        profile_self_test_page_loaded_ ? "completed" : "pending"},
       {"profile_cookie_flush",
        profile_cookie_flush_completed_ ? "completed" : "pending"},
       {"renderer_process",
        recorder_->saw_renderer_process() ? "seen" : "missing"},
       {"gpu_process", recorder_->saw_gpu_process() ? "seen" : "missing"}});
}

void BrowserApp::CloseBrowser(bool force_close) {
  CEF_REQUIRE_UI_THREAD();
  if (close_requested_) {
    return;
  }
  close_requested_ = true;
  if (client_ && browser_created_ && !browser_destroyed_) {
    client_->CloseBrowser(force_close);
  } else {
    CefQuitMessageLoop();
  }
}

} // namespace kwebshell
