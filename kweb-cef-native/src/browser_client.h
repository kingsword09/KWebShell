#ifndef KWEBSHELL_NATIVE_BROWSER_CLIENT_H_
#define KWEBSHELL_NATIVE_BROWSER_CLIENT_H_

#include <memory>
#include <string>

#include "browser_app.h"
#include "include/cef_client.h"
#include "native_window.h"

namespace kwebshell {

class BrowserClient final : public CefClient,
                            public CefDisplayHandler,
                            public CefLifeSpanHandler,
                            public CefLoadHandler,
                            public CefRequestHandler {
public:
  BrowserClient(BrowserApp *app, NativeWindow *native_window,
                std::shared_ptr<EventRecorder> recorder, bool native_self_test,
                bool profile_self_test);

  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override;
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override;
  CefRefPtr<CefLoadHandler> GetLoadHandler() override;
  CefRefPtr<CefRequestHandler> GetRequestHandler() override;

  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString &title) override;
  void OnAddressChange(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                       const CefString &url) override;
#if defined(OS_WIN) || defined(OS_LINUX)
  bool GetRootWindowScreenRect(CefRefPtr<CefBrowser> browser,
                               CefRect &rect) override;
#endif

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  bool DoClose(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;

  void OnLoadingStateChange(CefRefPtr<CefBrowser> browser, bool is_loading,
                            bool can_go_back, bool can_go_forward) override;
  void OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                 int http_status_code) override;
  void OnLoadError(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                   ErrorCode error_code, const CefString &error_text,
                   const CefString &failed_url) override;

  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefRequest> request, bool user_gesture,
                      bool is_redirect) override;
  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser,
                                 TerminationStatus status, int error_code,
                                 const CefString &error_string) override;

  void CloseBrowser(bool force_close);

private:
  BrowserApp *const app_;
  NativeWindow *const native_window_;
  const std::shared_ptr<EventRecorder> recorder_;
  const bool native_self_test_;
  const bool profile_self_test_;
  CefRefPtr<CefBrowser> browser_;
  bool native_self_test_started_ = false;
#if defined(OS_WIN) || defined(OS_LINUX)
  bool root_screen_rect_recorded_ = false;
#endif

  IMPLEMENT_REFCOUNTING(BrowserClient);
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BROWSER_CLIENT_H_
