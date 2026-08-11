#include "browser_client.h"

#include <string>

#include "include/cef_parser.h"
#include "include/wrapper/cef_helpers.h"

namespace kwebshell {
namespace {

std::string DisplayUrl(const CefString &url) {
  const std::string value = url.ToString();
  if (value.starts_with("data:")) {
    return "data:<self-test>";
  }
  return value;
}

} // namespace

BrowserClient::BrowserClient(BrowserApp *app, NativeWindow *native_window,
                             std::shared_ptr<EventRecorder> recorder,
                             bool native_self_test, bool profile_self_test)
    : app_(app), native_window_(native_window), recorder_(std::move(recorder)),
      native_self_test_(native_self_test),
      profile_self_test_(profile_self_test) {}

CefRefPtr<CefDisplayHandler> BrowserClient::GetDisplayHandler() { return this; }

CefRefPtr<CefLifeSpanHandler> BrowserClient::GetLifeSpanHandler() {
  return this;
}

CefRefPtr<CefLoadHandler> BrowserClient::GetLoadHandler() { return this; }

CefRefPtr<CefRequestHandler> BrowserClient::GetRequestHandler() { return this; }

void BrowserClient::OnTitleChange(CefRefPtr<CefBrowser> browser,
                                  const CefString &title) {
  CEF_REQUIRE_UI_THREAD();
  const std::string title_string = title.ToString();
  native_window_->SetTitle(title_string);
  recorder_->Record("title_changed", {{"title", title_string}});

  if (profile_self_test_) {
    if (title_string.starts_with("KWEB_PROFILE_SELF_TEST_PASS|")) {
      app_->OnProfileSelfTestPagePassed(title_string);
    } else if (title_string.starts_with("KWEB_PROFILE_SELF_TEST_FAIL|")) {
      app_->OnFatalBrowserError("native.profile.self-test-failed",
                                {{"result", title_string}});
    }
    return;
  }
  if (!native_self_test_) {
    return;
  }
  if (title_string.starts_with("KWEB_SELF_TEST_GPU_FAIL|")) {
    const std::string encoded_renderer = title_string.substr(
        std::string("KWEB_SELF_TEST_GPU_FAIL|").size());
    constexpr auto kRendererUnescapeRules =
        static_cast<cef_uri_unescape_rule_t>(
            UU_NORMAL | UU_SPACES | UU_PATH_SEPARATORS |
            UU_URL_SPECIAL_CHARS_EXCEPT_PATH_SEPARATORS);
    const std::string renderer =
        CefURIDecode(encoded_renderer, true, kRendererUnescapeRules).ToString();
    app_->OnFatalBrowserError(
        "native.gpu.hardware-acceleration-unavailable",
        {{"renderer", renderer}});
    return;
  }
  if (title_string == "KWEB_SELF_TEST_READY" && !native_self_test_started_) {
    native_self_test_started_ = true;
    native_window_->RunNativeInputSelfTest();
  } else if (title_string.starts_with("KWEB_SELF_TEST_PASS|")) {
    native_window_->OnInputSelfTestPassed();
    app_->OnSelfTestPagePassed(title_string);
  }
}

void BrowserClient::OnAddressChange(CefRefPtr<CefBrowser> browser,
                                    CefRefPtr<CefFrame> frame,
                                    const CefString &url) {
  CEF_REQUIRE_UI_THREAD();
  if (frame->IsMain()) {
    recorder_->Record("address_changed", {{"url", DisplayUrl(url)}});
  }
}

#if defined(OS_WIN) || defined(OS_LINUX)
bool BrowserClient::GetRootWindowScreenRect(CefRefPtr<CefBrowser> browser,
                                            CefRect &rect) {
  CEF_REQUIRE_UI_THREAD();
  std::string error;
  if (!native_window_->GetRootWindowScreenRect(&rect, &error)) {
    app_->OnFatalBrowserError("native.window.root-screen-rect-failed",
                              {{"message", error}});
    return false;
  }
  if (!root_screen_rect_recorded_) {
    root_screen_rect_recorded_ = true;
    recorder_->Record("root_screen_rect_reported",
                      {{"x", std::to_string(rect.x)},
                       {"y", std::to_string(rect.y)},
                       {"width", std::to_string(rect.width)},
                       {"height", std::to_string(rect.height)}});
  }
  return true;
}
#endif

void BrowserClient::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  browser_ = browser;
  const auto runtime_style = browser->GetHost()->GetRuntimeStyle();
  const bool windowless = browser->GetHost()->IsWindowRenderingDisabled();
  const bool has_window =
      browser->GetHost()->GetWindowHandle() != kNullWindowHandle;
  recorder_->Record(
      "browser_created",
      {{"runtime_style",
        runtime_style == CEF_RUNTIME_STYLE_ALLOY ? "alloy" : "not-alloy"},
       {"windowless", windowless ? "true" : "false"},
       {"native_window", has_window ? "present" : "missing"}});

  if (runtime_style != CEF_RUNTIME_STYLE_ALLOY || windowless || !has_window) {
    app_->OnFatalBrowserError(
        "native.browser.invalid-rendering-mode",
        {{"runtime_style", std::to_string(runtime_style)},
         {"windowless", windowless ? "true" : "false"},
         {"native_window", has_window ? "present" : "missing"}});
    return;
  }

  app_->OnBrowserCreated(browser);
}

bool BrowserClient::DoClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  recorder_->Record("browser_close_accepted");
  native_window_->OnBrowserCloseAccepted();
  return false;
}

void BrowserClient::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  browser_ = nullptr;
  app_->OnBrowserDestroyed();
}

void BrowserClient::OnLoadingStateChange(CefRefPtr<CefBrowser> browser,
                                         bool is_loading, bool can_go_back,
                                         bool can_go_forward) {
  CEF_REQUIRE_UI_THREAD();
  recorder_->Record("loading_state",
                    {{"loading", is_loading ? "true" : "false"},
                     {"can_go_back", can_go_back ? "true" : "false"},
                     {"can_go_forward", can_go_forward ? "true" : "false"}});
}

void BrowserClient::OnLoadEnd(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame, int http_status_code) {
  CEF_REQUIRE_UI_THREAD();
  if (frame->IsMain()) {
    recorder_->Record("load_end",
                      {{"http_status", std::to_string(http_status_code)},
                       {"url", DisplayUrl(frame->GetURL())}});
    if (profile_self_test_) {
      app_->OnProfileSelfTestPageLoaded();
    }
  }
}

void BrowserClient::OnLoadError(CefRefPtr<CefBrowser> browser,
                                CefRefPtr<CefFrame> frame, ErrorCode error_code,
                                const CefString &error_text,
                                const CefString &failed_url) {
  CEF_REQUIRE_UI_THREAD();
  if (!frame->IsMain() || error_code == ERR_ABORTED) {
    return;
  }
  app_->OnFatalBrowserError("native.navigation.failed",
                            {{"error_code", std::to_string(error_code)},
                             {"error", error_text.ToString()},
                             {"url", failed_url.ToString()}});
}

bool BrowserClient::OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefRequest> request,
                                   bool user_gesture, bool is_redirect) {
  CEF_REQUIRE_UI_THREAD();
  if (frame->IsMain()) {
    recorder_->Record("navigation_started",
                      {{"url", DisplayUrl(request->GetURL())},
                       {"user_gesture", user_gesture ? "true" : "false"},
                       {"redirect", is_redirect ? "true" : "false"}});
  }
  return false;
}

void BrowserClient::OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser,
                                              TerminationStatus status,
                                              int error_code,
                                              const CefString &error_string) {
  CEF_REQUIRE_UI_THREAD();
  app_->OnFatalBrowserError("native.renderer.terminated",
                            {{"status", std::to_string(status)},
                             {"error_code", std::to_string(error_code)},
                             {"error", error_string.ToString()}});
}

void BrowserClient::CloseBrowser(bool force_close) {
  CEF_REQUIRE_UI_THREAD();
  if (browser_) {
    browser_->GetHost()->CloseBrowser(force_close);
  }
}

} // namespace kwebshell
