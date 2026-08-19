#include "browser_surface.h"

#include <cstdio>
#include <cwchar>
#include <cstdlib>
#include <memory>

#if defined(OS_WIN)
#include <windows.h>

#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/wrapper/cef_closure_task.h"
#elif defined(OS_LINUX)
#include <X11/Xlib.h>

#include "include/internal/cef_types_linux.h"
#endif

namespace kwebshell {
namespace {

#if defined(OS_WIN)

constexpr int kBrowserWindowDestructionQuiescenceTasks = 8;
constexpr wchar_t kBrowserWidgetWindowClassPrefix[] = L"Chrome_WidgetWin_";

bool BrowserWindowOwnsFocus(HWND window) {
  const HWND focused = ::GetFocus();
  return focused != nullptr &&
         (focused == window || ::IsChild(window, focused));
}

bool ReleaseBrowserWindowFocus(HWND window) {
  if (!BrowserWindowOwnsFocus(window)) {
    return true;
  }
  ::SetFocus(nullptr);
  return !BrowserWindowOwnsFocus(window);
}

BOOL CALLBACK ReleaseBrowserWindowPointerState(HWND window, LPARAM) {
  TRACKMOUSEEVENT tracking{};
  tracking.cbSize = sizeof(tracking);
  tracking.dwFlags = TME_CANCEL | TME_HOVER | TME_LEAVE;
  tracking.hwndTrack = window;
  ::TrackMouseEvent(&tracking);

  const HWND capture = ::GetCapture();
  if (capture == window) {
    ::ReleaseCapture();
  }
  ::SendMessageW(window, WM_MOUSELEAVE, 0, 0);
  ::SendMessageW(window, WM_CANCELMODE, 0, 0);
  return TRUE;
}

void DrainBrowserWindowPointerMessages(HWND window) {
  if (window == nullptr || !::IsWindow(window)) {
    return;
  }
  MSG message{};
  while (::PeekMessageW(&message, window, WM_MOUSEFIRST, WM_MOUSELAST,
                        PM_REMOVE)) {
  }
  while (::PeekMessageW(&message, window, WM_NCMOUSEMOVE, WM_NCXBUTTONDBLCLK,
                        PM_REMOVE)) {
  }
  while (::PeekMessageW(&message, window, WM_SETCURSOR, WM_SETCURSOR,
                        PM_REMOVE)) {
  }
  while (::PeekMessageW(&message, window, WM_MOUSEACTIVATE, WM_MOUSEACTIVATE,
                        PM_REMOVE)) {
  }
  while (::PeekMessageW(&message, window, WM_CAPTURECHANGED, WM_CAPTURECHANGED,
                        PM_REMOVE)) {
  }
}

BOOL CALLBACK DrainBrowserWindowPointerMessagesCallback(HWND window, LPARAM) {
  DrainBrowserWindowPointerMessages(window);
  return TRUE;
}

void DrainBrowserWindowPointerMessagesInSubtree(HWND window) {
  DrainBrowserWindowPointerMessages(window);
  ::EnumChildWindows(window, DrainBrowserWindowPointerMessagesCallback, 0);
}

bool IsBrowserWidgetWindow(HWND window) {
  wchar_t class_name[64]{};
  const int class_name_length =
      ::GetClassNameW(window, class_name, static_cast<int>(sizeof(class_name) /
                                                          sizeof(wchar_t)));
  constexpr int prefix_length =
      static_cast<int>(sizeof(kBrowserWidgetWindowClassPrefix) /
                       sizeof(wchar_t)) -
      1;
  return class_name_length >= prefix_length &&
         std::wcsncmp(class_name, kBrowserWidgetWindowClassPrefix,
                      prefix_length) == 0;
}

HWND FindBrowserWidgetWindow(HWND browser_window) {
  HWND result = nullptr;
  for (HWND child = ::GetWindow(browser_window, GW_CHILD); child != nullptr;
       child = ::GetWindow(child, GW_HWNDNEXT)) {
    if (!IsBrowserWidgetWindow(child)) {
      continue;
    }
    if (result != nullptr) {
      return nullptr;
    }
    result = child;
  }
  return result;
}

void ReleaseBrowserWindowInputState(HWND window) {
  ReleaseBrowserWindowPointerState(window, 0);
  ::EnumChildWindows(window, ReleaseBrowserWindowPointerState, 0);
}

void DestroyBrowserWindowAfterQuiescence(HWND window, HWND parent,
                                         int remaining_tasks) {
  DrainBrowserWindowPointerMessagesInSubtree(window);
  if (remaining_tasks == 0) {
    if (::IsWindow(window) && ::GetParent(window) == parent) {
      ::DestroyWindow(window);
    }
    return;
  }
  if (!CefPostTask(
          TID_UI,
          base::BindOnce(DestroyBrowserWindowAfterQuiescence, window, parent,
                         remaining_tasks - 1))) {
    std::fprintf(stderr,
                 "KWEBSHELL_CLOSE_ERROR stage=destruction-quiescence-post "
                 "browser_window=%p remaining_tasks=%d\n",
                 static_cast<void *>(window), remaining_tasks);
    if (::IsWindow(window) && ::GetParent(window) == parent) {
      ::DestroyWindow(window);
    }
  }
}

void DestroyBrowserWindowAfterWidgetClose(HWND window, HWND parent,
                                          HWND widget_window,
                                          int remaining_tasks) {
  DrainBrowserWindowPointerMessagesInSubtree(window);
  if (::IsWindow(widget_window)) {
    if (remaining_tasks > 0 &&
        CefPostTask(TID_UI,
                    base::BindOnce(DestroyBrowserWindowAfterWidgetClose,
                                   window, parent, widget_window,
                                   remaining_tasks - 1))) {
      return;
    }
    std::fprintf(stderr,
                 "KWEBSHELL_CLOSE_ERROR stage=widget-close-timeout "
                 "browser_window=%p widget_window=%p remaining_tasks=%d\n",
                 static_cast<void *>(window),
                 static_cast<void *>(widget_window), remaining_tasks);
    ::DestroyWindow(widget_window);
  }
  if (::IsWindow(widget_window)) {
    std::fprintf(stderr,
                 "KWEBSHELL_CLOSE_ERROR stage=widget-destruction-failed "
                 "browser_window=%p widget_window=%p\n",
                 static_cast<void *>(window),
                 static_cast<void *>(widget_window));
  }
  DestroyBrowserWindowAfterQuiescence(
      window, parent, kBrowserWindowDestructionQuiescenceTasks - 1);
}

// Windowing model proven on the Windows matrix: the CEF browser window is a
// direct child of the embedding-owned parent and every member and Win32 call
// happens on the CEF UI thread. A session close destroys the browser window
// explicitly after closing CEF's inner Aura Widget; CEF runs its own close
// sequence from the outer host's WM_DESTROY notification. Intermediate
// container windows and shutdown-root re-parenting conflict with Aura's window
// tracking under stress (repeated
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
    if (close_accepted_) {
      // A close is already in flight; requesting another one would race with
      // the destruction that CEF has already accepted.
      return KWEB_STATUS_OK;
    }
    if (!browser_ || browser_window_ == nullptr ||
        !::IsWindow(browser_window_) ||
        ::GetParent(browser_window_) != parent_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    browser_->GetHost()->CloseBrowser(true);
    return KWEB_STATUS_OK;
  }

  kweb_status CompleteBrowserClose(bool *handled_out) override {
    if (handled_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    // KWebShell owns this destruction path. Returning false makes CEF 151
    // forward WM_CLOSE to the top-level Compose ancestor for windowed browsers.
    *handled_out = true;
    if (close_accepted_) {
      return KWEB_STATUS_OK;
    }
    // CEF has entered DoClose. Destroying the window is
    // required for a SetAsChild browser under a foreign parent: CEF's default
    // notification targets the top-level Compose ancestor. Drain the UI queue
    // before destroying the child so concurrent navigation, focus and Widget
    // callbacks cannot target the torn-down Aura hierarchy. DoClose returns
    // true because this is the application-owned destruction path; posting
    // WM_CLOSE again would re-enter CEF's TryCloseBrowser while its destruction
    // state has been reset to NONE.
    const HWND browser_window = browser_window_;
    if (browser_window == nullptr || !::IsWindow(browser_window) ||
        ::GetParent(browser_window) != parent_) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    // CEF's inner Chrome_WidgetWin owns the UI-thread pointer and focus state
    // while the browser is open. Recursively destroying the outer
    // CefBrowserWindow with that state live can leave Aura's tooltip or focus
    // controller targeting a Window after it has been marked destroyed. Clear
    // capture, hover tracking and focus synchronously before destroying any
    // native window in the browser subtree.
    ReleaseBrowserWindowInputState(browser_window);
    ::EnableWindow(browser_window, FALSE);
    ::ShowWindow(browser_window, SW_HIDE);
    if (!ReleaseBrowserWindowFocus(browser_window)) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    DrainBrowserWindowPointerMessagesInSubtree(browser_window);
    const HWND widget_window = FindBrowserWidgetWindow(browser_window);
    if (widget_window == nullptr || !::IsWindow(widget_window) ||
        ::GetParent(widget_window) != browser_window) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    // CEF owns a top-level Aura Widget inside CefBrowserWindow. Close that
    // Widget through its native message path and let Chromium tear down the
    // Aura root before destroying the outer CEF host HWND.
    ::SendMessageW(widget_window, WM_CLOSE, 0, 0);
    if (!CefPostTask(TID_UI,
                     base::BindOnce(DestroyBrowserWindowAfterWidgetClose,
                                    browser_window, parent_, widget_window,
                                    kBrowserWindowDestructionQuiescenceTasks -
                                        1))) {
      return KWEB_STATUS_CEF_UI_TASK_FAILED;
    }
    close_accepted_ = true;
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
  bool close_accepted_ = false;
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
