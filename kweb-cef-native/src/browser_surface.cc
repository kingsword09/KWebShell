#include "browser_surface.h"

#include <atomic>
#include <memory>

#if defined(OS_WIN)
#include <windows.h>
#include <commctrl.h>

#pragma comment(lib, "comctl32.lib")
#elif defined(OS_LINUX)
#include <X11/Xlib.h>

#include "include/internal/cef_types_linux.h"
#endif

namespace kwebshell {
namespace {

#if defined(OS_WIN)

// Window state shared between the CEF UI thread (surface methods) and the
// subclass procedure, which Windows invokes on the parent window's owning
// thread. The subclass procedure must never touch the surface object itself
// because the surface is destroyed on the CEF UI thread without waiting for
// in-flight procedure calls. The proxy is released through a posted message
// that the subclass procedure handles, so the proxy strictly outlives every
// procedure call that can still reach it.
struct WindowsWindowProxy {
  WindowsWindowProxy(HWND parent_window, HWND container_window,
                     UINT_PTR subclass)
      : parent(parent_window), container(container_window),
        subclass_id(subclass) {}

  std::atomic<bool> close_requested{false};
  std::atomic<bool> close_forwarded{false};
  std::atomic<bool> surface_released{false};
  std::atomic<HWND> browser_window{nullptr};
  const HWND parent;
  const HWND container;
  const UINT_PTR subclass_id;
};

constexpr UINT kProxyReleaseMessage = WM_APP + 0x0B57;

class WindowsBrowserSurface final : public BrowserSurface {
 public:
  WindowsBrowserSurface(HWND parent, int32_t x, int32_t y, int32_t width,
                        int32_t height)
      : parent_(parent), x_(x), y_(y) {
    const HINSTANCE instance = ::GetModuleHandleW(nullptr);
    if (parent_ != nullptr && ::IsWindow(parent_)) {
      container_ = ::CreateWindowExW(
          WS_EX_NOPARENTNOTIFY, L"STATIC", L"",
          WS_CHILD | WS_VISIBLE | WS_CLIPCHILDREN | WS_CLIPSIBLINGS, x, y,
          width, height, parent, nullptr, instance, nullptr);
    }
    if (container_ != nullptr) {
      proxy_ = new WindowsWindowProxy(parent_, container_, NextSubclassId());
      parent_subclassed_ = ::SetWindowSubclass(
          parent_, ParentWindowSubclassProc, proxy_->subclass_id,
          reinterpret_cast<DWORD_PTR>(proxy_)) != FALSE;
      if (!parent_subclassed_) {
        delete proxy_;
        proxy_ = nullptr;
      }
    }
  }

  ~WindowsBrowserSurface() override {
    // The container window was created on this (CEF UI) thread, so destroying
    // it here is thread-correct. The subclass procedure runs on the parent
    // window's owning thread instead, so the proxy must be released there.
    if (proxy_ != nullptr) {
      proxy_->surface_released.store(true, std::memory_order_release);
      if (!::PostMessageW(proxy_->parent, kProxyReleaseMessage, 0,
                          reinterpret_cast<LPARAM>(proxy_))) {
        // The parent window is gone, so the subclass chain is gone with it
        // and no procedure call can reach this proxy anymore.
        ::RemoveWindowSubclass(proxy_->parent, ParentWindowSubclassProc,
                               proxy_->subclass_id);
        delete proxy_;
      }
      proxy_ = nullptr;
    }
    if (container_ != nullptr && ::IsWindow(container_)) {
      ::DestroyWindow(container_);
    }
  }

  bool IsValid() const {
    return parent_subclassed_ && proxy_ != nullptr && parent_ != nullptr &&
           ::IsWindow(parent_) && container_ != nullptr &&
           ::IsWindow(container_) && ::GetParent(container_) == parent_;
  }

  CefWindowHandle parent_handle() const override { return container_; }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) override {
    browser_ = browser;
    const HWND browser_window = browser->GetHost()->GetWindowHandle();
    proxy_->browser_window.store(browser_window, std::memory_order_release);
    if (browser_window != nullptr && ::IsWindow(browser_window)) {
      RECT bounds{};
      if (::GetClientRect(container_, &bounds)) {
        ::SetWindowPos(browser_window, nullptr, 0, 0, bounds.right,
                       bounds.bottom, SWP_NOACTIVATE | SWP_NOZORDER);
      }
    }
  }

  kweb_status Resize(int32_t width, int32_t height, int32_t *actual_width,
                     int32_t *actual_height) override {
    const HWND browser_window =
        proxy_->browser_window.load(std::memory_order_acquire);
    if (!IsValid() || browser_window == nullptr ||
        !::IsWindow(browser_window) ||
        !::SetWindowPos(container_, nullptr, x_, y_, width, height,
                        SWP_NOACTIVATE | SWP_NOZORDER) ||
        !::SetWindowPos(browser_window, nullptr, 0, 0, width, height,
                        SWP_NOACTIVATE | SWP_NOZORDER)) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    RECT bounds{};
    if (!::GetClientRect(browser_window, &bounds)) {
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
    const HWND browser_window =
        proxy_->browser_window.load(std::memory_order_acquire);
    return parent_ != nullptr && ::IsWindow(parent_) && IsValid() &&
           browser_window != nullptr && ::IsWindow(browser_window) &&
           ::GetParent(browser_window) == container_;
  }

  kweb_status RequestBrowserClose() override {
    if (proxy_->close_requested.load(std::memory_order_acquire)) {
      // A close is already in flight; requesting another one would race with
      // the destruction that CEF has already accepted.
      return KWEB_STATUS_OK;
    }
    const HWND browser_window =
        proxy_->browser_window.load(std::memory_order_acquire);
    if (!IsValid() || !browser_ || browser_window == nullptr ||
        !::IsWindow(browser_window) ||
        ::GetParent(browser_window) != container_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    proxy_->close_requested.store(true, std::memory_order_release);
    browser_->GetHost()->CloseBrowser(true);
    return KWEB_STATUS_OK;
  }

  kweb_status CompleteBrowserClose(bool *handled_out) override {
    if (handled_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    *handled_out = false;
    if (!proxy_->close_requested.load(std::memory_order_acquire)) {
      // CEF initiated this close itself (for example the host window received
      // WM_CLOSE) instead of a session RequestBrowserClose call. Accept CEF's
      // default destruction instead of tearing the session down fatally.
      proxy_->close_requested.store(true, std::memory_order_release);
      return KWEB_STATUS_OK;
    }
    const HWND browser_window =
        proxy_->browser_window.load(std::memory_order_acquire);
    if (!IsValid() || browser_window == nullptr ||
        !::IsWindow(browser_window) ||
        ::GetParent(browser_window) != container_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    return KWEB_STATUS_OK;
  }

  kweb_status BrowserDestroyed() override {
    browser_ = nullptr;
    proxy_->browser_window.store(nullptr, std::memory_order_release);
    return KWEB_STATUS_OK;
  }

 private:
  static UINT_PTR NextSubclassId() {
    static std::atomic<UINT_PTR> next_id = 1;
    return next_id.fetch_add(1, std::memory_order_relaxed);
  }

  static LRESULT CALLBACK ParentWindowSubclassProc(HWND window, UINT message,
                                                   WPARAM w_param,
                                                   LPARAM l_param,
                                                   UINT_PTR subclass_id,
                                                   DWORD_PTR ref_data) {
    (void)subclass_id;
    if (message == kProxyReleaseMessage && l_param != 0) {
      // The release message is addressed to one proxy but travels through
      // every installed subclass procedure, so the first procedure that sees
      // it performs the removal on the window's owning thread.
      auto *released = reinterpret_cast<WindowsWindowProxy *>(l_param);
      ::RemoveWindowSubclass(window, ParentWindowSubclassProc,
                             released->subclass_id);
      delete released;
      return 0;
    }
    auto *proxy = reinterpret_cast<WindowsWindowProxy *>(ref_data);
    if (proxy != nullptr && message == WM_CLOSE &&
        !proxy->surface_released.load(std::memory_order_acquire) &&
        ForwardAcceptedClose(proxy)) {
      return 0;
    }
    return ::DefSubclassProc(window, message, w_param, l_param);
  }

  static bool ForwardAcceptedClose(WindowsWindowProxy *proxy) {
    const HWND browser_window =
        proxy->browser_window.load(std::memory_order_acquire);
    if (!proxy->close_requested.load(std::memory_order_acquire) ||
        proxy->close_forwarded.load(std::memory_order_acquire) ||
        browser_window == nullptr || !::IsWindow(browser_window) ||
        ::GetParent(browser_window) != proxy->container ||
        ::GetAncestor(browser_window, GA_ROOT) != proxy->parent) {
      return false;
    }
    proxy->close_forwarded.store(true, std::memory_order_release);
    // DoClose has already returned false, so CEF has accepted destruction and
    // its host WndProc can now destroy only the browser child window.
    ::SendMessageW(browser_window, WM_CLOSE, 0, 0);
    return true;
  }

  const HWND parent_;
  const int32_t x_;
  const int32_t y_;
  HWND container_ = nullptr;
  WindowsWindowProxy *proxy_ = nullptr;
  bool parent_subclassed_ = false;
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
