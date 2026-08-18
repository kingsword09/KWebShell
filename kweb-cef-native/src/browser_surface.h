#ifndef KWEBSHELL_NATIVE_BROWSER_SURFACE_H_
#define KWEBSHELL_NATIVE_BROWSER_SURFACE_H_

#include <cstdint>
#include <memory>

#include "include/cef_browser.h"
#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

class BrowserSurface {
public:
  virtual ~BrowserSurface() = default;

  virtual CefWindowHandle parent_handle() const = 0;
  virtual void BrowserCreated(CefRefPtr<CefBrowser> browser) = 0;
  virtual kweb_status Resize(int32_t width, int32_t height,
                             int32_t *actual_width,
                             int32_t *actual_height) = 0;
  virtual bool ValidateParentage() const = 0;
  virtual kweb_status RequestBrowserClose() = 0;
  virtual kweb_status CompleteBrowserClose(bool *handled_out) = 0;
  virtual kweb_status BrowserDestroyed() = 0;
};

std::unique_ptr<BrowserSurface>
CreateBrowserSurface(uintptr_t native_parent, int32_t x, int32_t y,
                     int32_t width, int32_t height, kweb_status *status_out);

void ConfigureDevToolsWindow(CefWindowInfo &window_info,
                             uintptr_t native_parent, int32_t width,
                             int32_t height);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BROWSER_SURFACE_H_
