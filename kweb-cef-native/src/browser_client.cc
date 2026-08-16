#include "browser_client.h"

#include <string>
#include <vector>

#include "include/cef_parser.h"
#include "include/wrapper/cef_helpers.h"
#include "mv3_core_test_page.h"

namespace kwebshell {
namespace {

struct ContextMenuMatch final {
  int command_id;
  bool enabled;
  bool visible;
};

void CollectContextMenuMatches(CefRefPtr<CefMenuModel> model,
                               std::vector<ContextMenuMatch> *matches) {
  if (!model) {
    return;
  }
  for (size_t index = 0; index < model->GetCount(); ++index) {
    const CefMenuModel::MenuItemType type = model->GetTypeAt(index);
    if (type == MENUITEMTYPE_SUBMENU) {
      CollectContextMenuMatches(model->GetSubMenuAt(index), matches);
      continue;
    }
    if (type == MENUITEMTYPE_COMMAND &&
        model->GetLabelAt(index).ToString() == Mv3CoreContextMenuItemLabel()) {
      matches->push_back({model->GetCommandIdAt(index),
                          model->IsEnabledAt(index),
                          model->IsVisibleAt(index)});
    }
  }
}

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
                             bool native_self_test, bool profile_self_test,
                             bool mv3_core_self_test,
                             bool mv3_context_menu_self_test)
    : app_(app), native_window_(native_window), recorder_(std::move(recorder)),
      native_self_test_(native_self_test),
      profile_self_test_(profile_self_test),
      mv3_core_self_test_(mv3_core_self_test),
      mv3_context_menu_self_test_(mv3_context_menu_self_test) {}

CefRefPtr<CefContextMenuHandler> BrowserClient::GetContextMenuHandler() {
  return mv3_context_menu_self_test_ ? this : nullptr;
}

CefRefPtr<CefDisplayHandler> BrowserClient::GetDisplayHandler() { return this; }

CefRefPtr<CefLifeSpanHandler> BrowserClient::GetLifeSpanHandler() {
  return this;
}

CefRefPtr<CefLoadHandler> BrowserClient::GetLoadHandler() { return this; }

CefRefPtr<CefRequestHandler> BrowserClient::GetRequestHandler() { return this; }

void BrowserClient::OnBeforeContextMenu(CefRefPtr<CefBrowser> browser,
                                        CefRefPtr<CefFrame> frame,
                                        CefRefPtr<CefContextMenuParams> params,
                                        CefRefPtr<CefMenuModel> model) {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_context_menu_self_test_) {
    return;
  }
  if (mv3_context_menu_model_seen_ || mv3_context_menu_model_rejected_) {
    mv3_context_menu_model_rejected_ = true;
    app_->OnFatalBrowserError("native.mv3.context-menu-model-duplicate", {});
    return;
  }
  if (!frame || !frame->IsMain() || !params || !model) {
    mv3_context_menu_model_rejected_ = true;
    app_->OnFatalBrowserError("native.mv3.context-menu-model-invalid", {});
    return;
  }
  const std::string page_url = params->GetPageUrl().ToString();
  if (page_url != Mv3CoreSelfTestUrl()) {
    mv3_context_menu_model_rejected_ = true;
    app_->OnFatalBrowserError(
        "native.mv3.context-menu-page-url-mismatch",
        {{"expected", Mv3CoreSelfTestUrl()}, {"actual", page_url}});
    return;
  }

  std::vector<ContextMenuMatch> matches;
  CollectContextMenuMatches(model, &matches);
  if (matches.size() != 1) {
    mv3_context_menu_model_rejected_ = true;
    app_->OnFatalBrowserError(
        "native.mv3.context-menu-item-count-invalid",
        {{"expected", "1"}, {"actual", std::to_string(matches.size())}});
    return;
  }
  const ContextMenuMatch &match = matches.front();
  if (match.command_id <= 0 || !match.enabled || !match.visible) {
    mv3_context_menu_model_rejected_ = true;
    app_->OnFatalBrowserError("native.mv3.context-menu-item-state-invalid",
                              {{"command_id", std::to_string(match.command_id)},
                               {"enabled", match.enabled ? "true" : "false"},
                               {"visible", match.visible ? "true" : "false"}});
    return;
  }
  mv3_context_menu_model_seen_ = true;
  mv3_context_menu_command_id_ = match.command_id;
  app_->OnMv3ContextMenuModelObserved(
      match.command_id, static_cast<int>(model->GetCount()),
      params->GetXCoord(), params->GetYCoord(), page_url);
}

bool BrowserClient::RunContextMenu(
    CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
    CefRefPtr<CefContextMenuParams> params, CefRefPtr<CefMenuModel> model,
    CefRefPtr<CefRunContextMenuCallback> callback) {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_context_menu_self_test_) {
    return false;
  }
  if (!callback) {
    app_->OnFatalBrowserError("native.mv3.context-menu-callback-missing", {});
    return true;
  }
  if (mv3_context_menu_model_rejected_ || !mv3_context_menu_model_seen_) {
    callback->Cancel();
    if (!mv3_context_menu_model_rejected_) {
      app_->OnFatalBrowserError("native.mv3.context-menu-model-missing", {});
    }
    return true;
  }
  if (mv3_context_menu_run_seen_) {
    callback->Cancel();
    app_->OnFatalBrowserError("native.mv3.context-menu-run-duplicate", {});
    return true;
  }
  mv3_context_menu_run_seen_ = true;
  app_->OnMv3ContextMenuSelectionDispatched(mv3_context_menu_command_id_);
  callback->Continue(mv3_context_menu_command_id_, EVENTFLAG_NONE);
  return true;
}

bool BrowserClient::OnContextMenuCommand(CefRefPtr<CefBrowser> browser,
                                         CefRefPtr<CefFrame> frame,
                                         CefRefPtr<CefContextMenuParams> params,
                                         int command_id,
                                         EventFlags event_flags) {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_context_menu_self_test_) {
    return false;
  }
  if (!mv3_context_menu_run_seen_ || mv3_context_menu_command_seen_ ||
      command_id != mv3_context_menu_command_id_) {
    app_->OnFatalBrowserError(
        "native.mv3.context-menu-command-invalid",
        {{"expected", std::to_string(mv3_context_menu_command_id_)},
         {"actual", std::to_string(command_id)},
         {"duplicate", mv3_context_menu_command_seen_ ? "true" : "false"}});
    return true;
  }
  mv3_context_menu_command_seen_ = true;
  app_->OnMv3ContextMenuCommandObserved(command_id);
  return false;
}

void BrowserClient::OnContextMenuDismissed(CefRefPtr<CefBrowser> browser,
                                           CefRefPtr<CefFrame> frame) {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_context_menu_self_test_ || mv3_context_menu_model_rejected_) {
    return;
  }
  if (!mv3_context_menu_command_seen_ || mv3_context_menu_dismissed_) {
    app_->OnFatalBrowserError(
        "native.mv3.context-menu-dismiss-state-invalid",
        {{"command", mv3_context_menu_command_seen_ ? "seen" : "missing"},
         {"duplicate", mv3_context_menu_dismissed_ ? "true" : "false"}});
    return;
  }
  mv3_context_menu_dismissed_ = true;
  app_->OnMv3ContextMenuDismissed();
}

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
  if (mv3_core_self_test_) {
    if (title_string.starts_with("KWEB_MV3_CONTEXT_MENU_PASS|")) {
      app_->OnMv3ContextMenuPagePassed(title_string);
    } else if (title_string.starts_with("KWEB_MV3_CONTEXT_MENU_FAIL|")) {
      app_->OnFatalBrowserError("native.mv3.context-menu-self-test-failed",
                                {{"result", title_string}});
    } else if (IsMv3CoreExtensionPagePassResult(title_string)) {
      app_->OnMv3ExtensionPagePassed(title_string);
    } else if (IsMv3CoreExtensionPageFailureResult(title_string)) {
      app_->OnFatalBrowserError("native.mv3.extension-page-self-test-failed",
                                {{"result", title_string}});
    } else if (title_string.starts_with("KWEB_MV3_CORE_PASS|")) {
      app_->OnMv3CoreSelfTestPagePassed(title_string);
    } else if (title_string.starts_with("KWEB_MV3_CORE_FAIL|")) {
      app_->OnFatalBrowserError("native.mv3.core-self-test-failed",
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
    const std::string url = frame->GetURL().ToString();
    recorder_->Record("load_end",
                      {{"http_status", std::to_string(http_status_code)},
                       {"url", DisplayUrl(frame->GetURL())}});
    if (profile_self_test_) {
      app_->OnProfileSelfTestPageLoaded();
    } else if (mv3_core_self_test_) {
      app_->OnMv3CoreSelfTestPageLoaded(url);
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

bool BrowserClient::NavigateSelfTestMainFrame(const std::string &url) {
  CEF_REQUIRE_UI_THREAD();
  if (!browser_) {
    return false;
  }
  CefRefPtr<CefFrame> frame = browser_->GetMainFrame();
  if (!frame) {
    return false;
  }
  frame->LoadURL(url);
  return true;
}

bool BrowserClient::TriggerMv3ContextMenuSelfTest() {
  CEF_REQUIRE_UI_THREAD();
  if (!mv3_context_menu_self_test_ || !browser_ ||
      mv3_context_menu_input_sent_) {
    return false;
  }
  mv3_context_menu_input_sent_ = true;
  CefRefPtr<CefBrowserHost> host = browser_->GetHost();
  if (!host) {
    return false;
  }
  CefMouseEvent mouse_event;
  mouse_event.x = Mv3CoreContextMenuX();
  mouse_event.y = Mv3CoreContextMenuY();
  mouse_event.modifiers = 0;
  host->SendMouseMoveEvent(mouse_event, false);
  host->SendMouseClickEvent(mouse_event, MBT_RIGHT, false, 1);
  host->SendMouseClickEvent(mouse_event, MBT_RIGHT, true, 1);
  return true;
}

void BrowserClient::CloseBrowser(bool force_close) {
  CEF_REQUIRE_UI_THREAD();
  if (browser_) {
    browser_->GetHost()->CloseBrowser(force_close);
  }
}

} // namespace kwebshell
