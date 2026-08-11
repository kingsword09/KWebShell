#ifndef KWEBSHELL_NATIVE_BROWSER_APP_H_
#define KWEBSHELL_NATIVE_BROWSER_APP_H_

#include <atomic>
#include <memory>
#include <string>

#include "include/cef_app.h"
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
  void OnFatalBrowserError(const std::string &code,
                           const std::map<std::string, std::string> &details);

  int exit_code() const;
  void PrepareForShutdown();

private:
  void MaybeCompleteSelfTest();
  void OnSelfTestInputSettled();
  void OnSelfTestTimeout();
  void QuitMessageLoop();
  void CloseBrowser(bool force_close);

  const HostConfiguration configuration_;
  const std::shared_ptr<EventRecorder> recorder_;
  std::unique_ptr<NativeWindow> native_window_;
  CefRefPtr<BrowserClient> client_;
  std::atomic<int> exit_code_{0};
  bool browser_created_ = false;
  bool browser_destroyed_ = false;
  bool self_test_page_passed_ = false;
  bool close_requested_ = false;
  bool fatal_error_reported_ = false;

  IMPLEMENT_REFCOUNTING(BrowserApp);
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BROWSER_APP_H_
