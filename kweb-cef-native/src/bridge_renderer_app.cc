#include "bridge_renderer_app.h"

#include <algorithm>
#include <map>
#include <string>
#include <vector>

#include "bridge_protocol.h"
#include "include/cef_render_process_handler.h"
#include "include/wrapper/cef_helpers.h"

namespace kwebshell {
namespace {

class BridgeRendererApplication final : public CefApp,
                                        public CefRenderProcessHandler {
public:
  BridgeRendererApplication()
      : router_(CefMessageRouterRendererSide::Create(BridgeRouterConfig())) {}

  CefRefPtr<CefRenderProcessHandler> GetRenderProcessHandler() override {
    return this;
  }

  void OnBrowserCreated(
      CefRefPtr<CefBrowser> browser,
      CefRefPtr<CefDictionaryValue> extra_info) override {
    CEF_REQUIRE_RENDERER_THREAD();
    if (browser && extra_info && extra_info->GetBool(kBridgeEnabledKey) &&
        extra_info->GetType(kBridgeOriginKey) == VTYPE_STRING) {
      const std::string origin = extra_info->GetString(kBridgeOriginKey);
      const kweb_string_view origin_view = {origin.data(), origin.size()};
      if (ValidateBridgeOrigin(origin_view) == origin) {
        browser_origins_[browser->GetIdentifier()] = {browser, origin};
      }
    }
  }

  void OnBrowserDestroyed(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_RENDERER_THREAD();
    if (browser) {
      const auto found = browser_origins_.find(browser->GetIdentifier());
      if (found != browser_origins_.end() &&
          found->second.browser->IsSame(browser)) {
        browser_origins_.erase(found);
      }
    }
  }

  void OnContextCreated(CefRefPtr<CefBrowser> browser,
                        CefRefPtr<CefFrame> frame,
                        CefRefPtr<CefV8Context> context) override {
    CEF_REQUIRE_RENDERER_THREAD();
    if (IsAllowedMainFrame(browser, frame)) {
      router_->OnContextCreated(browser, frame, context);
      routed_contexts_.push_back(context);
    }
  }

  void OnContextReleased(CefRefPtr<CefBrowser> browser,
                         CefRefPtr<CefFrame> frame,
    CefRefPtr<CefV8Context> context) override {
    CEF_REQUIRE_RENDERER_THREAD();
    const auto found = std::find_if(
        routed_contexts_.begin(), routed_contexts_.end(),
        [&context](const CefRefPtr<CefV8Context> &routed) {
          return routed->IsSame(context);
        });
    if (found != routed_contexts_.end()) {
      router_->OnContextReleased(browser, frame, *found);
      routed_contexts_.erase(found);
    }
  }

  bool OnProcessMessageReceived(
      CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
      CefProcessId source_process,
      CefRefPtr<CefProcessMessage> message) override {
    CEF_REQUIRE_RENDERER_THREAD();
    return IsAllowedMainFrame(browser, frame) &&
           router_->OnProcessMessageReceived(browser, frame, source_process,
                                             message);
  }

private:
  bool IsAllowedMainFrame(CefRefPtr<CefBrowser> browser,
                          CefRefPtr<CefFrame> frame) const {
    if (!browser || !frame || !frame->IsMain()) {
      return false;
    }
    const auto found = browser_origins_.find(browser->GetIdentifier());
    return found != browser_origins_.end() &&
           found->second.browser->IsSame(browser) &&
           BridgeOriginFromUrl(frame->GetURL()) == found->second.origin;
  }

  struct BrowserBridge final {
    CefRefPtr<CefBrowser> browser;
    std::string origin;
  };

  const CefRefPtr<CefMessageRouterRendererSide> router_;
  std::map<int, BrowserBridge> browser_origins_;
  std::vector<CefRefPtr<CefV8Context>> routed_contexts_;

  IMPLEMENT_REFCOUNTING(BridgeRendererApplication);
};

} // namespace

CefRefPtr<CefApp> CreateBridgeRendererApplication() {
  return new BridgeRendererApplication();
}

} // namespace kwebshell
