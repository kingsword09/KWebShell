#include "mv3_core_test_page.h"

#include <string>
#include <utility>

#include "include/cef_request.h"
#include "include/cef_stream.h"
#include "include/wrapper/cef_byte_read_handler.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_stream_resource_handler.h"
#include "kwebshell/native/event_recorder.h"

namespace kwebshell {
namespace {

constexpr char kMv3CoreSelfTestUrl[] =
    "https://kwebshell.test/mv3-core-self-test";
constexpr char kMv3CoreExtensionId[] =
    "dhhnhmffjehhodphofnkingncijnaona";
constexpr char kMv3CoreOptionsPageUrl[] =
    "chrome-extension://dhhnhmffjehhodphofnkingncijnaona/options.html";
constexpr char kMv3CoreActionPopupUrl[] =
    "chrome-extension://dhhnhmffjehhodphofnkingncijnaona/popup.html";
constexpr char kMv3CoreContextMenuItemId[] = "kwebshell-mv3-context-menu";
constexpr char kMv3CoreContextMenuItemLabel[] =
    "KWebShell MV3 context item";
constexpr char kMv3CoreDevToolsInspectedValue[] =
    "kwebshell-devtools-inspected";
constexpr int kMv3CoreContextMenuX = 120;
constexpr int kMv3CoreContextMenuY = 120;
constexpr int kServiceWorkerIdleDelayMs = 40000;

constexpr Mv3CoreExtensionPageSelfTest kOptionsPageSelfTest = {
    "options", kMv3CoreOptionsPageUrl};
constexpr Mv3CoreExtensionPageSelfTest kActionPopupSelfTest = {
    "action-popup", kMv3CoreActionPopupUrl};

int FirstExpectedStorageCount(Mv3CoreSelfTestMode mode) {
  return mode == Mv3CoreSelfTestMode::kRestart ? 3 : 1;
}

std::string BuildMv3CoreSelfTestHtml(Mv3CoreSelfTestMode mode) {
  const int first_count = FirstExpectedStorageCount(mode);
  const int second_count = first_count + 1;
  return "<!doctype html><html data-mode=\"" +
         std::string(Mv3CoreSelfTestModeName(mode)) +
         "\" data-first-count=\"" + std::to_string(first_count) +
         "\" data-second-count=\"" + std::to_string(second_count) +
         "\" data-idle-delay-ms=\"" +
         std::to_string(kServiceWorkerIdleDelayMs) +
         "\" data-extension-id=\"" + kMv3CoreExtensionId +
         "\"><head><meta charset=\"utf-8\"><script>"
         "globalThis.KWEB_PAGE_WORLD_MARKER='page-world';"
         "globalThis.KWEB_DEVTOOLS_INSPECTED_VALUE='" +
         kMv3CoreDevToolsInspectedValue +
         "';"
         "document.documentElement.dataset.pageWorldRuntime="
         "typeof globalThis.chrome?.runtime;"
         "</script><title>"
         "KWEB_MV3_CORE_LOADING</title></head><body></body></html>";
}

class Mv3CoreSelfTestSchemeHandlerFactory final
    : public CefSchemeHandlerFactory {
public:
  Mv3CoreSelfTestSchemeHandlerFactory(
      Mv3CoreSelfTestMode mode, std::shared_ptr<EventRecorder> recorder)
      : html_(BuildMv3CoreSelfTestHtml(mode)),
        recorder_(std::move(recorder)) {}

  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser,
                                       CefRefPtr<CefFrame> frame,
                                       const CefString &scheme_name,
                                       CefRefPtr<CefRequest> request) override {
    CEF_REQUIRE_IO_THREAD();
    const std::string url = request->GetURL().ToString();
    if (scheme_name != "https" || url != kMv3CoreSelfTestUrl) {
      return nullptr;
    }
    recorder_->Record("mv3_test_request_intercepted", {{"url", url}});
    auto source = CefRefPtr<CefBaseRefCounted>(this);
    auto read_handler = new CefByteReadHandler(
        reinterpret_cast<const unsigned char *>(html_.data()), html_.size(),
        source);
    CefRefPtr<CefStreamReader> stream =
        CefStreamReader::CreateForHandler(read_handler);
    return new CefStreamResourceHandler("text/html", stream);
  }

private:
  const std::string html_;
  const std::shared_ptr<EventRecorder> recorder_;

  IMPLEMENT_REFCOUNTING(Mv3CoreSelfTestSchemeHandlerFactory);
};

} // namespace

const char *Mv3CoreSelfTestModeName(Mv3CoreSelfTestMode mode) {
  switch (mode) {
  case Mv3CoreSelfTestMode::kNone:
    return "none";
  case Mv3CoreSelfTestMode::kInitial:
    return "initial";
  case Mv3CoreSelfTestMode::kRestart:
    return "restart";
  case Mv3CoreSelfTestMode::kIsolated:
    return "isolated";
  case Mv3CoreSelfTestMode::kOptions:
    return "options";
  case Mv3CoreSelfTestMode::kActionPopup:
    return "action-popup";
  case Mv3CoreSelfTestMode::kContextMenu:
    return "context-menu";
  case Mv3CoreSelfTestMode::kDevTools:
    return "devtools";
  case Mv3CoreSelfTestMode::kOffscreen:
    return "offscreen";
  }
  return "invalid";
}

const char *Mv3CoreSelfTestUrl() { return kMv3CoreSelfTestUrl; }

const Mv3CoreExtensionPageSelfTest *
Mv3CoreExtensionPageSelfTestForMode(Mv3CoreSelfTestMode mode) {
  switch (mode) {
  case Mv3CoreSelfTestMode::kOptions:
    return &kOptionsPageSelfTest;
  case Mv3CoreSelfTestMode::kActionPopup:
    return &kActionPopupSelfTest;
  case Mv3CoreSelfTestMode::kNone:
  case Mv3CoreSelfTestMode::kInitial:
  case Mv3CoreSelfTestMode::kRestart:
  case Mv3CoreSelfTestMode::kIsolated:
  case Mv3CoreSelfTestMode::kContextMenu:
  case Mv3CoreSelfTestMode::kDevTools:
  case Mv3CoreSelfTestMode::kOffscreen:
    return nullptr;
  }
  return nullptr;
}

bool IsMv3CoreExtensionPagePassResult(std::string_view result) {
  return result.starts_with("KWEB_MV3_OPTIONS_PASS|") ||
         result.starts_with("KWEB_MV3_ACTION_POPUP_PASS|");
}

bool IsMv3CoreExtensionPageFailureResult(std::string_view result) {
  return result.starts_with("KWEB_MV3_OPTIONS_FAIL|") ||
         result.starts_with("KWEB_MV3_ACTION_POPUP_FAIL|");
}

const char *Mv3CoreContextMenuItemId() { return kMv3CoreContextMenuItemId; }

const char *Mv3CoreContextMenuItemLabel() {
  return kMv3CoreContextMenuItemLabel;
}

int Mv3CoreContextMenuX() { return kMv3CoreContextMenuX; }

int Mv3CoreContextMenuY() { return kMv3CoreContextMenuY; }

std::string ExpectedMv3CoreSelfTestResult(Mv3CoreSelfTestMode mode) {
  const int first_count = FirstExpectedStorageCount(mode);
  return "KWEB_MV3_CORE_PASS|" + std::string(Mv3CoreSelfTestModeName(mode)) +
         "|first=" + std::to_string(first_count) +
         "|second=" + std::to_string(first_count + 1) +
         "|suspended=true|isolated=true|id=" + kMv3CoreExtensionId;
}

std::string ExpectedMv3CoreExtensionPageResult(Mv3CoreSelfTestMode mode) {
  if (mode == Mv3CoreSelfTestMode::kOptions) {
    return "KWEB_MV3_OPTIONS_PASS|id=" + std::string(kMv3CoreExtensionId) +
           "|manifest=KWebShell%20MV3%20core%20conformance"
           "|messageCount=2|path=/options.html";
  }
  if (mode == Mv3CoreSelfTestMode::kActionPopup) {
    return "KWEB_MV3_ACTION_POPUP_PASS|id=" + std::string(kMv3CoreExtensionId) +
           "|manifest=KWebShell%20MV3%20core%20conformance"
           "|popup=popup.html|defaultTitle=KWebShell%20MV3%20action"
           "|badge=2|title=KWebShell%20MV3%20action%20count%3A%202"
           "|messageCount=2|path=/popup.html";
  }
  return {};
}

std::string ExpectedMv3CoreContextMenuResult() {
  return "KWEB_MV3_CONTEXT_MENU_PASS|id=" + std::string(kMv3CoreExtensionId) +
         "|menu=" + std::string(kMv3CoreContextMenuItemId) +
         "|clickCount=1"
         "|page=https%3A%2F%2Fkwebshell.test%2Fmv3-core-self-test";
}

std::string ExpectedMv3CoreDevToolsResult() {
  return "KWEB_MV3_DEVTOOLS_PASS|id=" + std::string(kMv3CoreExtensionId) +
         "|origin=chrome-extension%3A%2F%2F" + kMv3CoreExtensionId +
         "|page=%2Fdevtools.html"
         "|panel=KWebShell%20MV3%20panel"
         "|panelPage=devtools-panel.html"
         "|inspected=kwebshell-devtools-inspected"
         "|eval=true|created=true";
}

std::string ExpectedMv3CoreOffscreenResult() {
  return "KWEB_MV3_OFFSCREEN_PASS|id=" + std::string(kMv3CoreExtensionId) +
         "|origin=chrome-extension%3A%2F%2F" + kMv3CoreExtensionId +
         "|page=%2Foffscreen.html"
         "|reason=DOM_PARSER"
         "|parser=KWebShell%20offscreen%20parser"
         "|before=false|during=true|closed=true|after=false|ready=1";
}

CefRefPtr<CefSchemeHandlerFactory> CreateMv3CoreSelfTestSchemeHandlerFactory(
    Mv3CoreSelfTestMode mode, std::shared_ptr<EventRecorder> recorder) {
  return new Mv3CoreSelfTestSchemeHandlerFactory(mode, std::move(recorder));
}

} // namespace kwebshell
