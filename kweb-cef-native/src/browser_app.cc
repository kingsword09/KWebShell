#include "browser_app.h"

#include <utility>

#include "browser_client.h"
#include "host_main.h"
#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/cef_command_line.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "self_test_page.h"

namespace kwebshell {

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

  native_window_ = CreateNativeWindow(this, recorder_);
  client_ = new BrowserClient(this, native_window_.get(), recorder_,
                              configuration_.self_test);

  const std::string url =
      configuration_.self_test ? BuildSelfTestUrl() : configuration_.url;
  std::string error;
  if (!native_window_->CreateBrowser(configuration_, client_, url, &error)) {
    OnFatalBrowserError("native.browser.create-rejected", {{"message", error}});
    return;
  }

  if (configuration_.self_test) {
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
  CloseBrowser(false);
}

void BrowserApp::OnNativeFatalError(
    const std::string &code,
    const std::map<std::string, std::string> &details) {
  CEF_REQUIRE_UI_THREAD();
  OnFatalBrowserError(code, details);
}

void BrowserApp::OnBrowserCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
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
  CloseBrowser(false);
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
  if (!configuration_.self_test || close_requested_ || browser_destroyed_) {
    return;
  }
  OnFatalBrowserError(
      "native.self-test.timeout",
      {{"page", self_test_page_passed_ ? "passed" : "pending"},
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
