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
#include "mv3_core_test_page.h"
#include "profile_test_page.h"
#include "self_test_page.h"

namespace kwebshell {
namespace {

constexpr int64_t kProfileCookieFlushTimeoutMs = 30000;
constexpr int64_t kDefaultSelfTestTimeoutMs = 30000;
constexpr int64_t kMv3CoreSelfTestTimeoutMs = 150000;

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

void BrowserApp::OnBeforeCommandLineProcessing(
    const CefString &process_type,
    CefRefPtr<CefCommandLine> command_line) {
  if (!process_type.empty() || !configuration_.IsMv3CoreSelfTest()) {
    return;
  }
  command_line->RemoveSwitch("disable-extensions");
  command_line->RemoveSwitch("disable-extensions-except");
  command_line->RemoveSwitch("load-extension");
  command_line->AppendSwitch("disable-background-networking");
  command_line->AppendSwitch("disable-component-update");
  command_line->AppendSwitch("no-first-run");
  command_line->AppendSwitch("no-proxy-server");
  CefString extension_path;
#if defined(OS_WIN)
  extension_path = configuration_.mv3_extension_path.wstring();
#else
  extension_path = configuration_.mv3_extension_path.string();
#endif
  command_line->AppendSwitchWithValue("disable-extensions-except",
                                     extension_path);
  command_line->AppendSwitchWithValue("load-extension", extension_path);
  if (configuration_.mv3_core_self_test_mode ==
      Mv3CoreSelfTestMode::kContextMenu) {
    command_line->AppendSwitch("kweb-chrome-context-menu");
  }
  recorder_->Record("mv3_extension_load_configured",
                    {{"mode", Mv3CoreSelfTestModeName(
                                  configuration_.mv3_core_self_test_mode)},
                     {"path", extension_path.ToString()},
                     {"background_networking", "disabled"},
                     {"component_updates", "disabled"},
                     {"proxy", "disabled"},
                     {"context_menu_backend",
                      configuration_.mv3_core_self_test_mode ==
                              Mv3CoreSelfTestMode::kContextMenu
                          ? "chrome-render-view"
                          : "alloy"}});
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
  if (configuration_.IsMv3CoreSelfTest() &&
      !request_context_->RegisterSchemeHandlerFactory(
          "https", "kwebshell.test",
          CreateMv3CoreSelfTestSchemeHandlerFactory(
              configuration_.mv3_core_self_test_mode, recorder_))) {
    OnFatalBrowserError("native.mv3.test-scheme-registration-failed", {});
    return;
  }
  CreateBrowserForProfile();
}

void BrowserApp::CreateBrowserForProfile() {
  CEF_REQUIRE_UI_THREAD();
  native_window_ = CreateNativeWindow(this, recorder_);
  client_ = new BrowserClient(
      this, native_window_.get(), recorder_, configuration_.self_test,
      configuration_.IsProfileSelfTest(), configuration_.IsMv3CoreSelfTest(),
      configuration_.mv3_core_self_test_mode ==
          Mv3CoreSelfTestMode::kContextMenu,
      configuration_.mv3_core_self_test_mode == Mv3CoreSelfTestMode::kDevTools);

  std::string url = configuration_.url;
  if (configuration_.self_test) {
    url = BuildSelfTestUrl();
  } else if (configuration_.IsProfileSelfTest()) {
    url = ProfileSelfTestUrl();
  } else if (configuration_.IsMv3CoreSelfTest()) {
    url = Mv3CoreSelfTestUrl();
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
        configuration_.IsMv3CoreSelfTest() ? kMv3CoreSelfTestTimeoutMs
                                           : kDefaultSelfTestTimeoutMs);
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

void BrowserApp::OnMv3CoreSelfTestPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.IsMv3CoreSelfTest() || mv3_core_self_test_page_passed_) {
    return;
  }
  const std::string expected =
      ExpectedMv3CoreSelfTestResult(configuration_.mv3_core_self_test_mode);
  if (result != expected) {
    OnFatalBrowserError("native.mv3.core-self-test-result-invalid",
                        {{"expected", expected}, {"actual", result}});
    return;
  }
  mv3_core_self_test_page_passed_ = true;
  recorder_->Record("mv3_core_self_test_passed",
                    {{"mode", Mv3CoreSelfTestModeName(
                                  configuration_.mv3_core_self_test_mode)},
                     {"result", result}});
  if (configuration_.mv3_core_self_test_mode ==
      Mv3CoreSelfTestMode::kContextMenu) {
    MaybeBeginMv3ContextMenuSelfTest();
    return;
  }
  if (configuration_.mv3_core_self_test_mode ==
      Mv3CoreSelfTestMode::kDevTools) {
    MaybeBeginMv3DevToolsSelfTest();
    return;
  }
  if (Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode) != nullptr) {
    BeginMv3ExtensionPageNavigation();
    return;
  }
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::OnMv3CoreSelfTestPageLoaded(const std::string &url) {
  CEF_REQUIRE_UI_THREAD();
  if (!configuration_.IsMv3CoreSelfTest()) {
    return;
  }
  const Mv3CoreExtensionPageSelfTest *extension_page =
      Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode);
  if (extension_page && mv3_extension_page_navigation_requested_) {
    if (url != extension_page->url) {
      OnFatalBrowserError("native.mv3.extension-page-url-mismatch",
                          {{"surface", extension_page->surface},
                           {"expected", extension_page->url},
                           {"actual", url}});
      return;
    }
    if (mv3_extension_page_loaded_) {
      OnFatalBrowserError("native.mv3.extension-page-duplicate-load",
                          {{"surface", extension_page->surface}});
      return;
    }
    mv3_extension_page_loaded_ = true;
    recorder_->Record("mv3_extension_page_loaded",
                      {{"surface", extension_page->surface}, {"url", url}});
    MaybeCompleteMv3CoreSelfTest();
    return;
  }
  if (url != Mv3CoreSelfTestUrl()) {
    OnFatalBrowserError("native.mv3.core-self-test-url-mismatch",
                        {{"expected", Mv3CoreSelfTestUrl()}, {"actual", url}});
    return;
  }
  if (mv3_core_self_test_page_loaded_) {
    return;
  }
  mv3_core_self_test_page_loaded_ = true;
  MaybeBeginMv3ContextMenuSelfTest();
  MaybeBeginMv3DevToolsSelfTest();
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::MaybeBeginMv3ContextMenuSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_core_self_test_page_passed_ || !mv3_core_self_test_page_loaded_ ||
      mv3_context_menu_input_requested_ || close_requested_ ||
      fatal_error_reported_) {
    return;
  }
  if (!client_) {
    OnFatalBrowserError("native.mv3.context-menu-client-missing", {});
    return;
  }
  mv3_context_menu_input_requested_ = true;
  recorder_->Record("mv3_context_menu_input_requested",
                    {{"x", std::to_string(Mv3CoreContextMenuX())},
                     {"y", std::to_string(Mv3CoreContextMenuY())},
                     {"url", Mv3CoreSelfTestUrl()}});
  if (!client_->TriggerMv3ContextMenuSelfTest()) {
    OnFatalBrowserError("native.mv3.context-menu-input-rejected",
                        {{"x", std::to_string(Mv3CoreContextMenuX())},
                         {"y", std::to_string(Mv3CoreContextMenuY())}});
  }
}

void BrowserApp::MaybeBeginMv3DevToolsSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kDevTools ||
      !mv3_core_self_test_page_passed_ || !mv3_core_self_test_page_loaded_ ||
      mv3_devtools_open_requested_ || close_requested_ ||
      fatal_error_reported_) {
    return;
  }
  if (!client_) {
    OnFatalBrowserError("native.mv3.devtools-client-missing", {});
    return;
  }
  mv3_devtools_open_requested_ = true;
  recorder_->Record("mv3_devtools_open_requested",
                    {{"inspected_url", Mv3CoreSelfTestUrl()}});
  if (!client_->OpenMv3DevToolsSelfTest()) {
    OnFatalBrowserError("native.mv3.devtools-open-rejected", {});
  }
}

void BrowserApp::OnMv3ContextMenuModelObserved(int command_id,
                                               int top_level_item_count, int x,
                                               int y,
                                               const std::string &page_url) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_context_menu_input_requested_ || mv3_context_menu_model_observed_ ||
      mv3_context_menu_selection_dispatched_ ||
      mv3_context_menu_command_observed_ || mv3_context_menu_dismissed_) {
    OnFatalBrowserError("native.mv3.context-menu-model-state-invalid",
                        {{"command_id", std::to_string(command_id)}});
    return;
  }
  if (command_id <= 0 || top_level_item_count <= 0 ||
      x != Mv3CoreContextMenuX() || y != Mv3CoreContextMenuY() ||
      page_url != Mv3CoreSelfTestUrl()) {
    OnFatalBrowserError(
        "native.mv3.context-menu-model-invalid",
        {{"command_id", std::to_string(command_id)},
         {"top_level_item_count", std::to_string(top_level_item_count)},
         {"expected_x", std::to_string(Mv3CoreContextMenuX())},
         {"actual_x", std::to_string(x)},
         {"expected_y", std::to_string(Mv3CoreContextMenuY())},
         {"actual_y", std::to_string(y)},
         {"expected_url", Mv3CoreSelfTestUrl()},
         {"actual_url", page_url}});
    return;
  }
  mv3_context_menu_model_observed_ = true;
  mv3_context_menu_command_id_ = command_id;
  recorder_->Record(
      "mv3_context_menu_model_observed",
      {{"command_id", std::to_string(command_id)},
       {"top_level_item_count", std::to_string(top_level_item_count)},
       {"x", std::to_string(x)},
       {"y", std::to_string(y)},
       {"url", page_url},
       {"label", Mv3CoreContextMenuItemLabel()}});
}

void BrowserApp::OnMv3ContextMenuSelectionDispatched(int command_id) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_context_menu_model_observed_ ||
      mv3_context_menu_selection_dispatched_ ||
      command_id != mv3_context_menu_command_id_) {
    OnFatalBrowserError(
        "native.mv3.context-menu-selection-invalid",
        {{"expected", std::to_string(mv3_context_menu_command_id_)},
         {"actual", std::to_string(command_id)},
         {"duplicate",
          mv3_context_menu_selection_dispatched_ ? "true" : "false"}});
    return;
  }
  mv3_context_menu_selection_dispatched_ = true;
  recorder_->Record("mv3_context_menu_selection_dispatched",
                    {{"command_id", std::to_string(command_id)}});
}

void BrowserApp::OnMv3ContextMenuCommandObserved(int command_id) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_context_menu_selection_dispatched_ ||
      mv3_context_menu_command_observed_ ||
      command_id != mv3_context_menu_command_id_) {
    OnFatalBrowserError(
        "native.mv3.context-menu-command-state-invalid",
        {{"expected", std::to_string(mv3_context_menu_command_id_)},
         {"actual", std::to_string(command_id)},
         {"duplicate", mv3_context_menu_command_observed_ ? "true" : "false"}});
    return;
  }
  mv3_context_menu_command_observed_ = true;
  recorder_->Record("mv3_context_menu_command_observed",
                    {{"command_id", std::to_string(command_id)},
                     {"client_handled", "false"},
                     {"default_dispatch", "requested"}});
}

void BrowserApp::OnMv3ContextMenuDismissed() {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_context_menu_command_observed_ || mv3_context_menu_dismissed_) {
    OnFatalBrowserError(
        "native.mv3.context-menu-dismiss-state-invalid",
        {{"command", mv3_context_menu_command_observed_ ? "seen" : "missing"},
         {"duplicate", mv3_context_menu_dismissed_ ? "true" : "false"}});
    return;
  }
  mv3_context_menu_dismissed_ = true;
  recorder_->Record("mv3_context_menu_dismissed");
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::OnMv3ContextMenuPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kContextMenu ||
      !mv3_context_menu_dismissed_ || mv3_context_menu_page_passed_) {
    OnFatalBrowserError("native.mv3.context-menu-page-state-invalid",
                        {{"result", result}});
    return;
  }
  const std::string expected = ExpectedMv3CoreContextMenuResult();
  if (result != expected) {
    OnFatalBrowserError("native.mv3.context-menu-result-invalid",
                        {{"expected", expected}, {"actual", result}});
    return;
  }
  mv3_context_menu_page_passed_ = true;
  recorder_->Record("mv3_context_menu_page_passed", {{"result", result}});
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::OnMv3DevToolsOpened() {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kDevTools ||
      !mv3_devtools_open_requested_ || mv3_devtools_opened_ ||
      mv3_devtools_close_requested_ || mv3_devtools_closed_) {
    OnFatalBrowserError("native.mv3.devtools-open-state-invalid", {});
    return;
  }
  mv3_devtools_opened_ = true;
  recorder_->Record("mv3_devtools_opened",
                    {{"popup", "true"},
                     {"runtime_style", "chrome"},
                     {"windowless", "false"},
                     {"native_window", "present"},
                     {"profile_match", "true"},
                     {"inspected_url", Mv3CoreSelfTestUrl()}});
}

void BrowserApp::OnMv3DevToolsFrontendLoaded(const std::string &url,
                                             int http_status_code) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kDevTools ||
      !mv3_devtools_opened_ || mv3_devtools_frontend_loaded_ ||
      mv3_devtools_page_passed_ || mv3_devtools_close_requested_) {
    OnFatalBrowserError("native.mv3.devtools-frontend-state-invalid",
                        {{"url", url}});
    return;
  }
  mv3_devtools_frontend_loaded_ = true;
  recorder_->Record(
      "mv3_devtools_frontend_loaded",
      {{"url", url}, {"http_status", std::to_string(http_status_code)}});
}

void BrowserApp::OnMv3DevToolsPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kDevTools ||
      !mv3_devtools_opened_ || !mv3_devtools_frontend_loaded_ ||
      mv3_devtools_page_passed_ || mv3_devtools_close_requested_ ||
      mv3_devtools_closed_) {
    OnFatalBrowserError("native.mv3.devtools-page-state-invalid",
                        {{"result", result}});
    return;
  }
  const std::string expected = ExpectedMv3CoreDevToolsResult();
  if (result != expected) {
    OnFatalBrowserError("native.mv3.devtools-page-result-invalid",
                        {{"expected", expected}, {"actual", result}});
    return;
  }
  mv3_devtools_page_passed_ = true;
  recorder_->Record("mv3_devtools_page_passed", {{"result", result}});
  mv3_devtools_close_requested_ = true;
  recorder_->Record("mv3_devtools_close_requested");
  if (!client_ || !client_->CloseMv3DevToolsSelfTest()) {
    OnFatalBrowserError("native.mv3.devtools-close-rejected", {});
  }
}

void BrowserApp::OnMv3DevToolsClosed() {
  CEF_REQUIRE_UI_THREAD();
  if (configuration_.mv3_core_self_test_mode !=
          Mv3CoreSelfTestMode::kDevTools ||
      !mv3_devtools_open_requested_ || !mv3_devtools_opened_ ||
      !mv3_devtools_frontend_loaded_ || !mv3_devtools_page_passed_ ||
      !mv3_devtools_close_requested_ || mv3_devtools_closed_) {
    OnFatalBrowserError("native.mv3.devtools-close-state-invalid", {});
    return;
  }
  mv3_devtools_opened_ = false;
  mv3_devtools_closed_ = true;
  recorder_->Record("mv3_devtools_closed");
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::OnMv3ExtensionPagePassed(const std::string &result) {
  CEF_REQUIRE_UI_THREAD();
  const Mv3CoreExtensionPageSelfTest *extension_page =
      Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode);
  if (!configuration_.IsMv3CoreSelfTest() || !extension_page ||
      !mv3_extension_page_navigation_requested_) {
    OnFatalBrowserError("native.mv3.extension-page-unexpected-terminal-event",
                        {{"result", result}});
    return;
  }
  if (mv3_extension_page_passed_) {
    OnFatalBrowserError(
        "native.mv3.extension-page-duplicate-terminal-event",
        {{"surface", extension_page->surface}, {"result", result}});
    return;
  }
  const std::string expected = ExpectedMv3CoreExtensionPageResult(
      configuration_.mv3_core_self_test_mode);
  if (result != expected) {
    OnFatalBrowserError("native.mv3.extension-page-result-invalid",
                        {{"surface", extension_page->surface},
                         {"expected", expected},
                         {"actual", result}});
    return;
  }
  mv3_extension_page_passed_ = true;
  recorder_->Record("mv3_extension_page_passed",
                    {{"surface", extension_page->surface}, {"result", result}});
  MaybeCompleteMv3CoreSelfTest();
}

void BrowserApp::MaybeCompleteProfileSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (!profile_self_test_page_passed_ || !profile_self_test_page_loaded_) {
    return;
  }
  RequestProfileFlushAndClose();
}

void BrowserApp::MaybeCompleteMv3CoreSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_core_self_test_page_passed_ || !mv3_core_self_test_page_loaded_) {
    return;
  }
  if (configuration_.mv3_core_self_test_mode ==
      Mv3CoreSelfTestMode::kContextMenu) {
    if (!mv3_context_menu_input_requested_ ||
        !mv3_context_menu_model_observed_ ||
        !mv3_context_menu_selection_dispatched_ ||
        !mv3_context_menu_command_observed_ || !mv3_context_menu_dismissed_ ||
        !mv3_context_menu_page_passed_) {
      return;
    }
    RequestProfileFlushAndClose();
    return;
  }
  if (configuration_.mv3_core_self_test_mode ==
      Mv3CoreSelfTestMode::kDevTools) {
    if (!mv3_devtools_open_requested_ || mv3_devtools_opened_ ||
        !mv3_devtools_frontend_loaded_ || !mv3_devtools_page_passed_ ||
        !mv3_devtools_close_requested_ || !mv3_devtools_closed_) {
      return;
    }
    RequestProfileFlushAndClose();
    return;
  }
  if (Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode) != nullptr &&
      (!mv3_extension_page_passed_ || !mv3_extension_page_loaded_)) {
    return;
  }
  RequestProfileFlushAndClose();
}

void BrowserApp::BeginMv3ExtensionPageNavigation() {
  CEF_REQUIRE_UI_THREAD();
  const Mv3CoreExtensionPageSelfTest *extension_page =
      Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode);
  if (!extension_page) {
    OnFatalBrowserError("native.mv3.extension-page-mode-invalid", {});
    return;
  }
  if (mv3_extension_page_navigation_requested_) {
    OnFatalBrowserError("native.mv3.extension-page-duplicate-navigation",
                        {{"surface", extension_page->surface}});
    return;
  }
  mv3_extension_page_navigation_requested_ = true;
  recorder_->Record(
      "mv3_extension_page_navigation_requested",
      {{"surface", extension_page->surface}, {"url", extension_page->url}});
  if (!client_ || !client_->NavigateSelfTestMainFrame(extension_page->url)) {
    OnFatalBrowserError("native.mv3.extension-page-navigation-rejected",
                        {{"surface", extension_page->surface}});
  }
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
  const Mv3CoreExtensionPageSelfTest *extension_page =
      Mv3CoreExtensionPageSelfTestForMode(
          configuration_.mv3_core_self_test_mode);
  OnFatalBrowserError(
      "native.self-test.timeout",
      {{"page", self_test_page_passed_ ? "passed" : "pending"},
       {"profile_page", profile_self_test_page_passed_ ? "passed" : "pending"},
       {"profile_load",
        profile_self_test_page_loaded_ ? "completed" : "pending"},
       {"mv3_core_page",
        mv3_core_self_test_page_passed_ ? "passed" : "pending"},
       {"mv3_core_load",
        mv3_core_self_test_page_loaded_ ? "completed" : "pending"},
       {"mv3_context_menu_input",
        mv3_context_menu_input_requested_ ? "requested" : "pending"},
       {"mv3_context_menu_model",
        mv3_context_menu_model_observed_ ? "observed" : "pending"},
       {"mv3_context_menu_command_id",
        std::to_string(mv3_context_menu_command_id_)},
       {"mv3_context_menu_selection",
        mv3_context_menu_selection_dispatched_ ? "dispatched" : "pending"},
       {"mv3_context_menu_command",
        mv3_context_menu_command_observed_ ? "observed" : "pending"},
       {"mv3_context_menu_dismiss",
        mv3_context_menu_dismissed_ ? "completed" : "pending"},
       {"mv3_context_menu_page",
        mv3_context_menu_page_passed_ ? "passed" : "pending"},
       {"mv3_devtools_open_request",
        mv3_devtools_open_requested_ ? "requested" : "pending"},
       {"mv3_devtools_window",
        mv3_devtools_opened_ ? "opened"
                             : (mv3_devtools_closed_ ? "closed" : "pending")},
       {"mv3_devtools_frontend",
        mv3_devtools_frontend_loaded_ ? "loaded" : "pending"},
       {"mv3_devtools_page", mv3_devtools_page_passed_ ? "passed" : "pending"},
       {"mv3_devtools_close_request",
        mv3_devtools_close_requested_ ? "requested" : "pending"},
       {"mv3_extension_page_surface",
        extension_page ? extension_page->surface : "none"},
       {"mv3_extension_page_navigation",
        mv3_extension_page_navigation_requested_ ? "requested" : "pending"},
       {"mv3_extension_page",
        mv3_extension_page_passed_ ? "passed" : "pending"},
       {"mv3_extension_page_load",
        mv3_extension_page_loaded_ ? "completed" : "pending"},
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
