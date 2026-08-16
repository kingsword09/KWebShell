#ifndef KWEBSHELL_NATIVE_BROWSER_APP_H_
#define KWEBSHELL_NATIVE_BROWSER_APP_H_

#include <atomic>
#include <memory>
#include <string>

#include "include/cef_app.h"
#include "include/cef_request_context.h"
#include "kwebshell/native/event_recorder.h"
#include "kwebshell/native/host_configuration.h"
#include "native_window.h"

namespace kwebshell {

class BrowserClient;

class BrowserApp final : public CefApp,
                         public CefBrowserProcessHandler,
                         public NativeWindowDelegate {
public:
  BrowserApp(HostConfiguration configuration,
             std::shared_ptr<EventRecorder> recorder);
  ~BrowserApp() override;

  CefRefPtr<CefBrowserProcessHandler> GetBrowserProcessHandler() override;
  void OnBeforeCommandLineProcessing(
      const CefString &process_type,
      CefRefPtr<CefCommandLine> command_line) override;
  void OnContextInitialized() override;
  void
  OnBeforeChildProcessLaunch(CefRefPtr<CefCommandLine> command_line) override;
  CefRefPtr<CefClient> GetDefaultClient() override;

  void OnNativeCloseRequested() override;
  void OnNativeFatalError(
      const std::string &code,
      const std::map<std::string, std::string> &details) override;
  void OnBrowserCreated(CefRefPtr<CefBrowser> browser);
  void OnBrowserDestroyed();
  void OnSelfTestPagePassed(const std::string &result);
  void OnProfileRequestContextInitialized(
      CefRefPtr<CefRequestContext> request_context);
  void OnProfileSelfTestPagePassed(const std::string &result);
  void OnProfileSelfTestPageLoaded();
  void OnMv3CoreSelfTestPagePassed(const std::string &result);
  void OnMv3CoreSelfTestPageLoaded(const std::string &url);
  void OnMv3ContextMenuModelObserved(int command_id, int top_level_item_count,
                                     int x, int y, const std::string &page_url);
  void OnMv3ContextMenuSelectionDispatched(int command_id);
  void OnMv3ContextMenuCommandObserved(int command_id);
  void OnMv3ContextMenuDismissed();
  void OnMv3ContextMenuPagePassed(const std::string &result);
  void OnMv3ExtensionPagePassed(const std::string &result);
  void OnProfileCookieFlushCompleted();
  void OnFatalBrowserError(const std::string &code,
                           const std::map<std::string, std::string> &details);

  int exit_code() const;
  void PrepareForShutdown();

private:
  void CreateProfileRequestContext();
  void CreateBrowserForProfile();
  void RequestProfileFlushAndClose();
  void OnProfileCookieFlushTimeout();
  void MaybeCompleteProfileSelfTest();
  void MaybeCompleteMv3CoreSelfTest();
  void MaybeBeginMv3ContextMenuSelfTest();
  void BeginMv3ExtensionPageNavigation();
  void MaybeCompleteSelfTest();
  void OnSelfTestInputSettled();
  void OnSelfTestTimeout();
  void QuitMessageLoop();
  void CloseBrowser(bool force_close);

  const HostConfiguration configuration_;
  const std::shared_ptr<EventRecorder> recorder_;
  std::unique_ptr<NativeWindow> native_window_;
  CefRefPtr<BrowserClient> client_;
  CefRefPtr<CefRequestContext> request_context_;
  std::atomic<int> exit_code_{0};
  bool browser_created_ = false;
  bool browser_destroyed_ = false;
  bool self_test_page_passed_ = false;
  bool profile_self_test_page_passed_ = false;
  bool profile_self_test_page_loaded_ = false;
  bool mv3_core_self_test_page_passed_ = false;
  bool mv3_core_self_test_page_loaded_ = false;
  bool mv3_context_menu_input_requested_ = false;
  bool mv3_context_menu_model_observed_ = false;
  bool mv3_context_menu_selection_dispatched_ = false;
  bool mv3_context_menu_command_observed_ = false;
  bool mv3_context_menu_dismissed_ = false;
  bool mv3_context_menu_page_passed_ = false;
  int mv3_context_menu_command_id_ = -1;
  bool mv3_extension_page_navigation_requested_ = false;
  bool mv3_extension_page_passed_ = false;
  bool mv3_extension_page_loaded_ = false;
  bool profile_cookie_flush_started_ = false;
  bool profile_cookie_flush_completed_ = false;
  bool close_requested_ = false;
  bool fatal_error_reported_ = false;

  IMPLEMENT_REFCOUNTING(BrowserApp);
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BROWSER_APP_H_
