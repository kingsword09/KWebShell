#include "browser_surface.h"

#include <cstdio>
#include <cstdlib>
#include <memory>

#if defined(OS_WIN)
#include <windows.h>
#elif defined(OS_LINUX)
#include <X11/Xlib.h>

#include "include/internal/cef_types_linux.h"
#endif

namespace kwebshell {
namespace {

#if defined(OS_WIN)

// Windowing model proven on the Windows matrix: the CEF browser window is a
// direct child of the embedding-owned parent and every member and Win32 call
// happens on the CEF UI thread. A session close destroys the browser window
// explicitly; CEF runs its own close sequence from the WM_DESTROY
// notification. Intermediate container windows and shutdown-root re-parenting
// conflict with Aura's window tracking under stress (repeated
// "Check failed: !is_destroyed_" and a wedged UI thread), so this class keeps
// the foreign-parent hierarchy exactly as CEF created it.
class WindowsBrowserSurface final : public BrowserSurface {
 public:
  WindowsBrowserSurface(HWND parent, int32_t x, int32_t y, int32_t width,
                        int32_t height)
      : parent_(parent), x_(x), y_(y) {
    (void)width;
    (void)height;
  }

  ~WindowsBrowserSurface() override {
    if (std::getenv("KWEBSHELL_TRACE_CLOSE") != nullptr) {
      std::fprintf(stderr,
                   "KWEBSHELL_CLOSE_TRACE stage=surface-destroyed "
                   "browser_window=%p\n",
                   static_cast<void *>(browser_window_));
    }
  }

  bool IsValid() const {
    return parent_ != nullptr && ::IsWindow(parent_);
  }

  CefWindowHandle parent_handle() const override { return parent_; }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) override {
    browser_ = browser;
    browser_window_ = browser->GetHost()->GetWindowHandle();
    if (browser_window_ != nullptr && ::IsWindow(browser_window_)) {
      ::SetWindowPos(browser_window_, nullptr, x_, y_, 0, 0,
                     SWP_NOSIZE | SWP_NOACTIVATE | SWP_NOZORDER);
    }
  }

  kweb_status Resize(int32_t width, int32_t height, int32_t *actual_width,
                     int32_t *actual_height) override {
    if (browser_window_ == nullptr || !::IsWindow(browser_window_) ||
        !::SetWindowPos(browser_window_, nullptr, x_, y_, width, height,
                        SWP_NOACTIVATE | SWP_NOZORDER)) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    RECT bounds{};
    if (!::GetClientRect(browser_window_, &bounds)) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    *actual_width = bounds.right - bounds.left;
    *actual_height = bounds.bottom - bounds.top;
    if (browser_) {
      browser_->GetHost()->NotifyMoveOrResizeStarted();
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
    return *actual_width == width && *actual_height == height
               ? KWEB_STATUS_OK
               : KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
  }

  bool ValidateParentage() const override {
    return parent_ != nullptr && ::IsWindow(parent_) &&
           browser_window_ != nullptr && ::IsWindow(browser_window_) &&
           ::GetParent(browser_window_) == parent_;
  }

  kweb_status RequestBrowserClose() override {
    if (!browser_ || browser_window_ == nullptr ||
        !::IsWindow(browser_window_) ||
        ::GetParent(browser_window_) != parent_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    ::DestroyWindow(browser_window_);
    return KWEB_STATUS_OK;
  }

  kweb_status CompleteBrowserClose(bool *handled_out) override {
    if (handled_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    // Accept CEF-initiated closes (for example a stray WM_CLOSE) through
    // CEF's default destruction path.
    *handled_out = false;
    return KWEB_STATUS_OK;
  }

  kweb_status BrowserDestroyed() override {
    browser_ = nullptr;
    browser_window_ = nullptr;
    return KWEB_STATUS_OK;
  }

 private:
  const HWND parent_;
  const int32_t x_;
  const int32_t y_;
  HWND browser_window_ = nullptr;
  CefRefPtr<CefBrowser> browser_;
};

#elif defined(OS_LINUX)

class LinuxBrowserSurface final : public BrowserSurface {
public:
  LinuxBrowserSurface(Display *display, Window parent, int32_t x, int32_t y)
      : display_(display), parent_(parent), x_(x), y_(y) {}

  CefWindowHandle parent_handle() const override { return parent_; }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) override {
    browser_ = browser;
    browser_window_ = browser->GetHost()->GetWindowHandle();
    if (display_ != nullptr && browser_window_ != None) {
      XMoveWindow(display_, browser_window_, x_, y_);
      XSync(display_, False);
    }
  }

  kweb_status Resize(int32_t width, int32_t height, int32_t *actual_width,
                     int32_t *actual_height) override {
    if (display_ == nullptr || browser_window_ == None) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    XMoveResizeWindow(display_, browser_window_, x_, y_,
                      static_cast<unsigned int>(width),
                      static_cast<unsigned int>(height));
    XSync(display_, False);
    XWindowAttributes attributes{};
    if (XGetWindowAttributes(display_, browser_window_, &attributes) == 0) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    *actual_width = attributes.width;
    *actual_height = attributes.height;
    if (browser_) {
      browser_->GetHost()->NotifyMoveOrResizeStarted();
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
    return *actual_width == width && *actual_height == height
               ? KWEB_STATUS_OK
               : KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
  }

  bool ValidateParentage() const override {
    if (display_ == nullptr || parent_ == None || browser_window_ == None) {
      return false;
    }
    Window root = None;
    Window parent = None;
    Window *children = nullptr;
    unsigned int child_count = 0;
    const Status result = XQueryTree(display_, browser_window_, &root, &parent,
                                     &children, &child_count);
    if (children != nullptr) {
      XFree(children);
    }
    return result != 0 && parent == parent_;
  }

  kweb_status RequestBrowserClose() override {
    if (browser_) {
      browser_->GetHost()->CloseBrowser(true);
      return KWEB_STATUS_OK;
    }
    return KWEB_STATUS_BROWSER_NOT_READY;
  }

  kweb_status CompleteBrowserClose(bool *handled_out) override {
    if (handled_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    *handled_out = false;
    return KWEB_STATUS_OK;
  }

  kweb_status BrowserDestroyed() override {
    browser_ = nullptr;
    browser_window_ = None;
    return KWEB_STATUS_OK;
  }

private:
  Display *const display_;
  const Window parent_;
  const int32_t x_;
  const int32_t y_;
  Window browser_window_ = None;
  CefRefPtr<CefBrowser> browser_;
};

#endif

} // namespace

void ConfigureDevToolsWindow(CefWindowInfo &window_info,
                             uintptr_t native_parent, int32_t width,
                             int32_t height) {
#if defined(OS_WIN)
  window_info.SetAsPopup(reinterpret_cast<HWND>(native_parent),
                         CefString("KWebShell DevTools"));
#elif defined(OS_LINUX)
  (void)native_parent;
  CefString(&window_info.window_name) = "KWebShell DevTools";
  window_info.parent_window = 0;
#endif
  window_info.bounds = CefRect(120, 120, width, height);
}

std::unique_ptr<BrowserSurface>
CreateBrowserSurface(uintptr_t native_parent, int32_t x, int32_t y,
                     int32_t width, int32_t height, kweb_status *status_out) {
  (void)width;
  (void)height;
  if (status_out == nullptr || native_parent == 0) {
    if (status_out != nullptr) {
      *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    }
    return nullptr;
  }
#if defined(OS_WIN)
  auto parent = reinterpret_cast<HWND>(native_parent);
  if (!::IsWindow(parent)) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return nullptr;
  }
  auto surface =
      std::make_unique<WindowsBrowserSurface>(parent, x, y, width, height);
  if (!surface->IsValid()) {
    *status_out = KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    return nullptr;
  }
  *status_out = KWEB_STATUS_OK;
  return surface;
#elif defined(OS_LINUX)
  Display *display = cef_get_xdisplay();
  const Window parent = static_cast<Window>(native_parent);
  XWindowAttributes attributes{};
  if (display == nullptr || parent == None ||
      XGetWindowAttributes(display, parent, &attributes) == 0) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return nullptr;
  }
  *status_out = KWEB_STATUS_OK;
  return std::make_unique<LinuxBrowserSurface>(display, parent, x, y);
#else
  *status_out = KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
  return nullptr;
#endif
}

} // namespace kwebshell
