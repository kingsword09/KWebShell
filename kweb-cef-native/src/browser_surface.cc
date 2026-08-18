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

constexpr wchar_t kShutdownRootClassName[] = L"KWebShellBrowserShutdownRoot";

LRESULT CALLBACK ShutdownRootWindowProc(HWND window, UINT message,
                                        WPARAM w_param, LPARAM l_param) {
  if (message == WM_CLOSE) {
    const HWND browser_window = reinterpret_cast<HWND>(
        ::GetWindowLongPtrW(window, GWLP_USERDATA));
    ::SetWindowLongPtrW(window, GWLP_USERDATA, 0);
    if (browser_window != nullptr && ::IsWindow(browser_window) &&
        ::GetAncestor(browser_window, GA_ROOT) == window) {
      // Close through CEF's host WndProc now that DoClose has returned and
      // AlloyBrowserHostImpl has accepted destruction.
      ::SendMessageW(browser_window, WM_CLOSE, 0, 0);
    }
    return 0;
  }
  return ::DefWindowProcW(window, message, w_param, l_param);
}

ATOM RegisterShutdownRootClass(HINSTANCE instance) {
  WNDCLASSEXW window_class{};
  window_class.cbSize = sizeof(window_class);
  window_class.lpfnWndProc = ShutdownRootWindowProc;
  window_class.hInstance = instance;
  window_class.lpszClassName = kShutdownRootClassName;
  return ::RegisterClassExW(&window_class);
}

// Every member and every Win32 call in this class happen on the CEF UI
// thread, which also creates the container and the hidden shutdown root.
// The embedding-owned parent HWND is only inspected with thread-safe
// queries. A browser child window created with SetAsChild under a foreign
// parent stalls CEF's default destruction for roughly thirty seconds, so
// CompleteBrowserClose re-parents the container under our own hidden popup
// root where CEF's close sequence proceeds immediately.
class WindowsBrowserSurface final : public BrowserSurface {
 public:
  WindowsBrowserSurface(HWND parent, int32_t x, int32_t y, int32_t width,
                        int32_t height)
      : parent_(parent), x_(x), y_(y) {
    const HINSTANCE instance = ::GetModuleHandleW(nullptr);
    static const ATOM shutdown_root_class = RegisterShutdownRootClass(instance);
    if (shutdown_root_class != 0) {
      shutdown_root_ = ::CreateWindowExW(
          WS_EX_NOACTIVATE | WS_EX_TOOLWINDOW, kShutdownRootClassName, L"",
          WS_POPUP, 0, 0, 0, 0, nullptr, nullptr, instance, nullptr);
    }
    if (shutdown_root_ != nullptr && parent_ != nullptr &&
        ::IsWindow(parent_)) {
      container_ = ::CreateWindowExW(
          WS_EX_NOPARENTNOTIFY, L"STATIC", L"",
          WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS, x, y,
          width, height, parent, nullptr, instance, nullptr);
      if (container_ == nullptr) {
        create_error_ = ::GetLastError();
      }
    }
  }

  ~WindowsBrowserSurface() override {
    if (std::getenv("KWEBSHELL_TRACE_CLOSE") != nullptr) {
      std::fprintf(stderr,
                   "KWEBSHELL_CLOSE_TRACE stage=surface-destroyed root=%p "
                   "container=%p\n",
                   static_cast<void *>(shutdown_root_),
                   static_cast<void *>(container_));
    }
    if (container_ != nullptr && ::IsWindow(container_)) {
      ::DestroyWindow(container_);
    }
    if (shutdown_root_ != nullptr && ::IsWindow(shutdown_root_)) {
      ::DestroyWindow(shutdown_root_);
    }
  }

  bool IsValid() const {
    return shutdown_root_ != nullptr && ::IsWindow(shutdown_root_) &&
           ::GetParent(shutdown_root_) == nullptr && container_ != nullptr &&
           ::IsWindow(container_) && ::GetParent(container_) == parent_;
  }

  void ReportInvalidSurface() const {
    std::fprintf(
        stderr,
        "KWEBSHELL_SURFACE_INVALID parent_is_window=%d root=%p root_is_window=%d "
        "container=%p container_is_window=%d parentage=%d create_win_error=%lu\n",
        parent_ != nullptr && ::IsWindow(parent_) ? 1 : 0,
        static_cast<void *>(shutdown_root_),
        shutdown_root_ != nullptr && ::IsWindow(shutdown_root_) ? 1 : 0,
        static_cast<void *>(container_),
        container_ != nullptr && ::IsWindow(container_) ? 1 : 0,
        container_ != nullptr && parent_ != nullptr &&
                ::GetParent(container_) == parent_
            ? 1
            : 0,
        static_cast<unsigned long>(create_error_));
  }

  CefWindowHandle parent_handle() const override { return container_; }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) override {
    browser_ = browser;
    browser_window_ = browser->GetHost()->GetWindowHandle();
    if (browser_window_ != nullptr && ::IsWindow(browser_window_)) {
      RECT bounds{};
      if (::GetClientRect(container_, &bounds)) {
        ::SetWindowPos(browser_window_, nullptr, 0, 0, bounds.right,
                       bounds.bottom, SWP_NOACTIVATE | SWP_NOZORDER);
      }
    }
  }

  kweb_status Resize(int32_t width, int32_t height, int32_t *actual_width,
                     int32_t *actual_height) override {
    if (!IsValid() || browser_window_ == nullptr ||
        !::IsWindow(browser_window_) ||
        !::SetWindowPos(container_, nullptr, x_, y_, width, height,
                        SWP_NOACTIVATE | SWP_NOZORDER) ||
        !::SetWindowPos(browser_window_, nullptr, 0, 0, width, height,
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
    return parent_ != nullptr && ::IsWindow(parent_) && IsValid() &&
           browser_window_ != nullptr && ::IsWindow(browser_window_) &&
           ::GetParent(browser_window_) == container_;
  }

  kweb_status RequestBrowserClose() override {
    if (close_hierarchy_detached_) {
      // A close is already in flight; requesting another one would race with
      // the destruction that CEF has already accepted.
      return KWEB_STATUS_OK;
    }
    if (!IsValid() || !browser_ || browser_window_ == nullptr ||
        !::IsWindow(browser_window_) ||
        ::GetParent(browser_window_) != container_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    close_requested_ = true;
    browser_->GetHost()->CloseBrowser(true);
    return KWEB_STATUS_OK;
  }

  kweb_status CompleteBrowserClose(bool *handled_out) override {
    if (handled_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    *handled_out = false;
    if (close_hierarchy_detached_) {
      return KWEB_STATUS_OK;
    }
    if (!IsValid() || browser_window_ == nullptr ||
        !::IsWindow(browser_window_) ||
        ::GetParent(browser_window_) != container_) {
      if (!close_requested_) {
        // CEF initiated this close itself while the window hierarchy was not
        // in the expected state; accept CEF's default destruction instead of
        // tearing the session down fatally.
        close_requested_ = true;
        close_hierarchy_detached_ = true;
        return KWEB_STATUS_OK;
      }
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    ::ShowWindow(container_, SW_HIDE);
    const HWND previous_parent = ::SetParent(container_, shutdown_root_);
    if (previous_parent != parent_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    if (::GetParent(container_) != shutdown_root_ ||
        ::GetParent(browser_window_) != container_ ||
        ::GetAncestor(browser_window_, GA_ROOT) != shutdown_root_) {
      ::SetParent(container_, parent_);
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    ::SetWindowLongPtrW(shutdown_root_, GWLP_USERDATA,
                        reinterpret_cast<LONG_PTR>(browser_window_));
    if (reinterpret_cast<HWND>(
            ::GetWindowLongPtrW(shutdown_root_, GWLP_USERDATA)) !=
        browser_window_) {
      ::SetParent(container_, parent_);
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    close_requested_ = true;
    close_hierarchy_detached_ = true;
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
  HWND shutdown_root_ = nullptr;
  HWND container_ = nullptr;
  HWND browser_window_ = nullptr;
  DWORD create_error_ = 0;
  bool close_requested_ = false;
  bool close_hierarchy_detached_ = false;
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
    surface->ReportInvalidSurface();
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
