#ifndef KWEBSHELL_NATIVE_NATIVE_WINDOW_H_
#define KWEBSHELL_NATIVE_NATIVE_WINDOW_H_

#include <map>
#include <memory>
#include <string>

#include "include/cef_browser.h"
#include "include/cef_client.h"
#include "kwebshell/native/host_configuration.h"

namespace kwebshell {

class EventRecorder;

class NativeWindowDelegate {
public:
  virtual ~NativeWindowDelegate() = default;
  virtual void OnNativeCloseRequested() = 0;
  virtual void
  OnNativeFatalError(const std::string &code,
                     const std::map<std::string, std::string> &details) = 0;
};

class NativeWindow {
public:
  virtual ~NativeWindow() = default;

  virtual bool CreateBrowser(const HostConfiguration &configuration,
                             CefRefPtr<CefClient> client,
                             const std::string &url, std::string *error) = 0;
  virtual void OnBrowserCreated(CefRefPtr<CefBrowser> browser) = 0;
  virtual void OnBrowserCloseAccepted() = 0;
  virtual void OnBrowserDestroyed() = 0;
  virtual void SetTitle(const std::string &title) = 0;
  virtual void RunNativeInputSelfTest() = 0;
  virtual void OnInputSelfTestPassed() = 0;
#if defined(OS_WIN) || defined(OS_LINUX)
  virtual bool GetRootWindowScreenRect(CefRect *rect,
                                       std::string *error) const = 0;
#endif
};

std::unique_ptr<NativeWindow>
CreateNativeWindow(NativeWindowDelegate *delegate,
                   std::shared_ptr<EventRecorder> recorder);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_NATIVE_WINDOW_H_
