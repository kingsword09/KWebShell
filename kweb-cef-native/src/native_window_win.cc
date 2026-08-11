#include <windows.h>

#include <memory>
#include <string>
#include <system_error>
#include <utility>

#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "kwebshell/native/event_recorder.h"
#include "native_window.h"
#include "self_test_page.h"

namespace kwebshell {
namespace {

constexpr wchar_t kHostWindowClass[] = L"KWebShellNativeHostWindow";

std::string Win32ErrorMessage(DWORD error) {
  return std::system_category().message(static_cast<int>(error));
}

} // namespace

class WinNativeWindow final : public NativeWindow {
public:
  WinNativeWindow(NativeWindowDelegate *delegate,
                  std::shared_ptr<EventRecorder> recorder)
      : delegate_(delegate), recorder_(std::move(recorder)) {}

  ~WinNativeWindow() override {
    if (window_ != nullptr) {
      ::SetWindowLongPtrW(window_, GWLP_USERDATA, 0);
      ::DestroyWindow(window_);
      window_ = nullptr;
    }
    browser_ = nullptr;
    browser_window_ = nullptr;
  }

  bool CreateBrowser(const HostConfiguration &configuration,
                     CefRefPtr<CefClient> client,
                     CefRefPtr<CefRequestContext> request_context,
                     const std::string &url, std::string *error) override {
    CEF_REQUIRE_UI_THREAD();

    instance_ = ::GetModuleHandleW(nullptr);
    WNDCLASSEXW window_class = {};
    window_class.cbSize = sizeof(window_class);
    window_class.style = CS_HREDRAW | CS_VREDRAW | CS_OWNDC;
    window_class.lpfnWndProc = &WinNativeWindow::WindowProcedure;
    window_class.hInstance = instance_;
    window_class.hCursor = ::LoadCursorW(nullptr, IDC_ARROW);
    window_class.hbrBackground =
        reinterpret_cast<HBRUSH>(::GetStockObject(BLACK_BRUSH));
    window_class.lpszClassName = kHostWindowClass;
    if (::RegisterClassExW(&window_class) == 0 &&
        ::GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
      const DWORD win32_error = ::GetLastError();
      *error = "RegisterClassExW failed: " + Win32ErrorMessage(win32_error);
      return false;
    }

    constexpr DWORD style = WS_OVERLAPPEDWINDOW;
    RECT window_rect = {0, 0, configuration.width, configuration.height};
    const UINT dpi = ::GetDpiForSystem();
    if (!::AdjustWindowRectExForDpi(&window_rect, style, FALSE, 0, dpi)) {
      const DWORD win32_error = ::GetLastError();
      *error =
          "AdjustWindowRectExForDpi failed: " + Win32ErrorMessage(win32_error);
      return false;
    }

    window_ = ::CreateWindowExW(0, kHostWindowClass, L"KWebShell", style,
                                CW_USEDEFAULT, CW_USEDEFAULT,
                                window_rect.right - window_rect.left,
                                window_rect.bottom - window_rect.top, nullptr,
                                nullptr, instance_, this);
    if (window_ == nullptr) {
      const DWORD win32_error = ::GetLastError();
      *error = "CreateWindowExW failed: " + Win32ErrorMessage(win32_error);
      return false;
    }

    RECT client_rect = {};
    if (!::GetClientRect(window_, &client_rect)) {
      const DWORD win32_error = ::GetLastError();
      *error = "GetClientRect failed: " + Win32ErrorMessage(win32_error);
      DestroyHostWindow();
      return false;
    }

    CefWindowInfo window_info;
    window_info.SetAsChild(window_,
                           CefRect(0, 0, client_rect.right - client_rect.left,
                                   client_rect.bottom - client_rect.top));
    window_info.runtime_style = CEF_RUNTIME_STYLE_ALLOY;
    window_info.windowless_rendering_enabled = false;

    CefBrowserSettings browser_settings;
    browser_settings.background_color = CefColorSetARGB(255, 16, 32, 51);
    if (!CefBrowserHost::CreateBrowser(window_info, client, url,
                                       browser_settings, nullptr,
                                       request_context)) {
      *error = "CefBrowserHost::CreateBrowser rejected the native child.";
      DestroyHostWindow();
      return false;
    }

    const double device_scale_factor =
        static_cast<double>(::GetDpiForWindow(window_)) / 96.0;
    recorder_->Record(
        "native_window_created",
        {{"width", std::to_string(configuration.width)},
         {"height", std::to_string(configuration.height)},
         {"device_scale_factor", std::to_string(device_scale_factor)}});
    ::ShowWindow(window_, SW_SHOWNORMAL);
    ::UpdateWindow(window_);
    return true;
  }

  void OnBrowserCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    browser_ = browser;
    browser_window_ = static_cast<HWND>(browser->GetHost()->GetWindowHandle());
    if (browser_window_ == nullptr || !::IsWindow(browser_window_) ||
        ::GetAncestor(browser_window_, GA_ROOT) != window_) {
      delegate_->OnNativeFatalError("native.windows.browser-window-invalid",
                                    {});
      return;
    }
    recorder_->Record("native_child_attached", {{"superview", "content-view"}});
    ResizeBrowserWindow();
  }

  void OnBrowserCloseAccepted() override {
    CEF_REQUIRE_UI_THREAD();
    allow_window_close_ = true;
    recorder_->Record("native_window_close_accepted");
  }

  void OnBrowserDestroyed() override {
    CEF_REQUIRE_UI_THREAD();
    browser_ = nullptr;
    browser_window_ = nullptr;
    allow_window_close_ = true;
    if (window_ != nullptr) {
      DispatchAcceptedClose();
    }
  }

  void SetTitle(const std::string &title) override {
    CEF_REQUIRE_UI_THREAD();
    if (window_ != nullptr) {
      ::SetWindowTextW(window_, CefString(title).ToWString().c_str());
    }
  }

  void RunNativeInputSelfTest() override {
    CEF_REQUIRE_UI_THREAD();
    if (self_test_started_ || allow_window_close_) {
      return;
    }
    if (window_ == nullptr || !browser_) {
      delegate_->OnNativeFatalError(
          "native.windows.self-test-window-unavailable", {});
      return;
    }
    self_test_started_ = true;
    if (!SetClientSize(960, 720)) {
      delegate_->OnNativeFatalError(
          "native.windows.self-test-resize-failed",
          {{"message", Win32ErrorMessage(::GetLastError())}});
      return;
    }
    recorder_->Record("native_resize_sent",
                      {{"width", "960"}, {"height", "720"}});
    CefPostDelayedTask(TID_UI,
                       base::BindOnce(&WinNativeWindow::SendSelfTestInput,
                                      base::Unretained(this)),
                       500);
  }

  void OnInputSelfTestPassed() override {
    CEF_REQUIRE_UI_THREAD();
    self_test_passed_ = true;
  }

  bool GetRootWindowScreenRect(CefRect *rect,
                               std::string *error) const override {
    if (rect == nullptr || error == nullptr) {
      return false;
    }
    if (window_ == nullptr || !::IsWindow(window_)) {
      *error = "Win32 root window is unavailable.";
      return false;
    }

    RECT window_rect = {};
    if (!::GetWindowRect(window_, &window_rect)) {
      *error = "GetWindowRect failed: " + Win32ErrorMessage(::GetLastError());
      return false;
    }
    const UINT dpi = ::GetDpiForWindow(window_);
    if (dpi == 0) {
      *error = "GetDpiForWindow returned zero.";
      return false;
    }
    *rect = CefRect(::MulDiv(window_rect.left, 96, static_cast<int>(dpi)),
                    ::MulDiv(window_rect.top, 96, static_cast<int>(dpi)),
                    ::MulDiv(window_rect.right - window_rect.left, 96,
                             static_cast<int>(dpi)),
                    ::MulDiv(window_rect.bottom - window_rect.top, 96,
                             static_cast<int>(dpi)));
    return true;
  }

private:
  static LRESULT CALLBACK WindowProcedure(HWND window, UINT message,
                                          WPARAM wparam, LPARAM lparam) {
    WinNativeWindow *owner = reinterpret_cast<WinNativeWindow *>(
        ::GetWindowLongPtrW(window, GWLP_USERDATA));
    if (message == WM_NCCREATE) {
      const auto *create = reinterpret_cast<const CREATESTRUCTW *>(lparam);
      owner = static_cast<WinNativeWindow *>(create->lpCreateParams);
      owner->window_ = window;
      ::SetWindowLongPtrW(window, GWLP_USERDATA,
                          reinterpret_cast<LONG_PTR>(owner));
    }
    if (owner != nullptr) {
      return owner->HandleWindowMessage(window, message, wparam, lparam);
    }
    return ::DefWindowProcW(window, message, wparam, lparam);
  }

  LRESULT HandleWindowMessage(HWND window, UINT message, WPARAM wparam,
                              LPARAM lparam) {
    switch (message) {
    case WM_CLOSE:
      if (!allow_window_close_) {
        delegate_->OnNativeCloseRequested();
      } else {
        DispatchAcceptedClose();
      }
      return 0;
    case WM_SIZE:
      ResizeBrowserWindow();
      return 0;
    case WM_SETFOCUS:
      if (browser_) {
        browser_->GetHost()->SetFocus(true);
      }
      return 0;
    case WM_KILLFOCUS:
      if (browser_) {
        browser_->GetHost()->SetFocus(false);
      }
      return 0;
    case WM_MOVE:
      if (browser_) {
        browser_->GetHost()->NotifyMoveOrResizeStarted();
        browser_->GetHost()->NotifyScreenInfoChanged();
      }
      return 0;
    case WM_DPICHANGED: {
      const auto *suggested_rect = reinterpret_cast<const RECT *>(lparam);
      ::SetWindowPos(window, nullptr, suggested_rect->left, suggested_rect->top,
                     suggested_rect->right - suggested_rect->left,
                     suggested_rect->bottom - suggested_rect->top,
                     SWP_NOACTIVATE | SWP_NOZORDER);
      recorder_->Record("native_dpi_changed",
                        {{"dpi", std::to_string(HIWORD(wparam))}});
      if (browser_) {
        browser_->GetHost()->NotifyScreenInfoChanged();
      }
      return 0;
    }
    case WM_ERASEBKGND:
      return 1;
    case WM_NCDESTROY:
      ::SetWindowLongPtrW(window, GWLP_USERDATA, 0);
      window_ = nullptr;
      if (!native_window_destroyed_) {
        native_window_destroyed_ = true;
        recorder_->Record("native_window_destroyed");
      }
      return ::DefWindowProcW(window, message, wparam, lparam);
    default:
      return ::DefWindowProcW(window, message, wparam, lparam);
    }
  }

  void DispatchAcceptedClose() {
    CEF_REQUIRE_UI_THREAD();
    if (window_ == nullptr) {
      return;
    }
    if (!close_dispatched_) {
      close_dispatched_ = true;
      recorder_->Record("native_window_close_dispatched");
    }
    ::DestroyWindow(window_);
  }

  void DestroyHostWindow() {
    if (window_ != nullptr) {
      ::SetWindowLongPtrW(window_, GWLP_USERDATA, 0);
      ::DestroyWindow(window_);
      window_ = nullptr;
    }
  }

  void ResizeBrowserWindow() {
    if (window_ == nullptr || browser_window_ == nullptr ||
        !::IsWindow(browser_window_)) {
      return;
    }
    RECT client_rect = {};
    if (::GetClientRect(window_, &client_rect)) {
      ::SetWindowPos(
          browser_window_, nullptr, 0, 0, client_rect.right - client_rect.left,
          client_rect.bottom - client_rect.top, SWP_NOACTIVATE | SWP_NOZORDER);
      if (browser_) {
        browser_->GetHost()->NotifyMoveOrResizeStarted();
        browser_->GetHost()->NotifyScreenInfoChanged();
      }
    }
  }

  bool SetClientSize(int width, int height) {
    RECT window_rect = {0, 0, width, height};
    const DWORD style =
        static_cast<DWORD>(::GetWindowLongPtrW(window_, GWL_STYLE));
    const DWORD ex_style =
        static_cast<DWORD>(::GetWindowLongPtrW(window_, GWL_EXSTYLE));
    if (!::AdjustWindowRectExForDpi(&window_rect, style, FALSE, ex_style,
                                    ::GetDpiForWindow(window_))) {
      return false;
    }
    return ::SetWindowPos(window_, nullptr, 0, 0,
                          window_rect.right - window_rect.left,
                          window_rect.bottom - window_rect.top,
                          SWP_NOMOVE | SWP_NOACTIVATE | SWP_NOZORDER) != FALSE;
  }

  void SendSelfTestInput() {
    CEF_REQUIRE_UI_THREAD();
    if (allow_window_close_ || self_test_passed_) {
      return;
    }
    if (window_ == nullptr || !browser_) {
      delegate_->OnNativeFatalError(
          "native.windows.self-test-window-unavailable", {});
      return;
    }
    ++self_test_input_attempts_;
    ::ShowWindow(window_, SW_SHOWNORMAL);
    ::SetForegroundWindow(window_);
    browser_->GetHost()->SetFocus(true);
    recorder_->Record("native_focus_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)}});

    SendSelfTestMouseInput(browser_);
    recorder_->Record("native_mouse_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"transport", "cef-windowed-host"}});

    SendSelfTestWheelInput(browser_);
    recorder_->Record("native_wheel_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"transport", "cef-windowed-host"}});

    const UINT scan_code = ::MapVirtualKeyW(0x4B, MAPVK_VK_TO_VSC);
    SendSelfTestKeyboardInput(browser_, static_cast<int>(scan_code << 16));
    recorder_->Record("native_keyboard_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"key", "k"},
                       {"transport", "cef-windowed-host"}});

    if (self_test_input_attempts_ < 3) {
      CefPostDelayedTask(TID_UI,
                         base::BindOnce(&WinNativeWindow::SendSelfTestInput,
                                        base::Unretained(this)),
                         400);
    }
  }

  NativeWindowDelegate *const delegate_;
  const std::shared_ptr<EventRecorder> recorder_;
  HINSTANCE instance_ = nullptr;
  HWND window_ = nullptr;
  HWND browser_window_ = nullptr;
  CefRefPtr<CefBrowser> browser_;
  bool allow_window_close_ = false;
  bool close_dispatched_ = false;
  bool native_window_destroyed_ = false;
  bool self_test_started_ = false;
  bool self_test_passed_ = false;
  int self_test_input_attempts_ = 0;
};

std::unique_ptr<NativeWindow>
CreateNativeWindow(NativeWindowDelegate *delegate,
                   std::shared_ptr<EventRecorder> recorder) {
  return std::make_unique<WinNativeWindow>(delegate, std::move(recorder));
}

} // namespace kwebshell
