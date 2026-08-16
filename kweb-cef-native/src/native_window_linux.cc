#include <X11/Xlib.h>
#include <gdk/gdkx.h>
#include <gtk/gtk.h>

#include <cstdint>
#include <memory>
#include <string>
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

constexpr int kBrowserParentValidationMaxAttempts = 20;
constexpr int64_t kBrowserParentValidationDelayMs = 100;

bool UseDefaultX11Visual(GtkWidget *widget) {
  GdkScreen *screen = gdk_screen_get_default();
  if (screen == nullptr || !GDK_IS_X11_SCREEN(screen)) {
    return false;
  }

  Visual *default_visual =
      DefaultVisual(GDK_SCREEN_XDISPLAY(screen), GDK_SCREEN_XNUMBER(screen));
  GList *visuals = gdk_screen_list_visuals(screen);
  for (GList *current = visuals; current != nullptr; current = current->next) {
    GdkVisual *visual = GDK_VISUAL(current->data);
    if (gdk_x11_visual_get_xvisual(visual)->visualid ==
        default_visual->visualid) {
      gtk_widget_set_visual(widget, visual);
      g_list_free(visuals);
      return true;
    }
  }
  g_list_free(visuals);
  return false;
}

} // namespace

class LinuxNativeWindow final : public NativeWindow {
public:
  LinuxNativeWindow(NativeWindowDelegate *delegate,
                    std::shared_ptr<EventRecorder> recorder)
      : delegate_(delegate), recorder_(std::move(recorder)) {}

  ~LinuxNativeWindow() override {
    DestroyGtkWindow(false);
    browser_ = nullptr;
  }

  bool CreateBrowser(const HostConfiguration &configuration,
                     CefRefPtr<CefClient> client,
                     CefRefPtr<CefRequestContext> request_context,
                     const std::string &url, std::string *error) override {
    CEF_REQUIRE_UI_THREAD();

    window_ = gtk_window_new(GTK_WINDOW_TOPLEVEL);
    if (window_ == nullptr) {
      *error = "GTK failed to allocate the native host window.";
      DestroyGtkWindow(false);
      return false;
    }

    gtk_window_set_title(GTK_WINDOW(window_), "KWebShell");
    gtk_window_set_default_size(GTK_WINDOW(window_), configuration.width,
                                configuration.height);
    gtk_widget_set_can_focus(window_, TRUE);
    g_signal_connect(window_, "delete-event", G_CALLBACK(HandleDeleteEvent),
                     this);
    g_signal_connect(window_, "size-allocate", G_CALLBACK(HandleSizeAllocate),
                     this);
    g_signal_connect(window_, "focus-in-event", G_CALLBACK(HandleFocusIn),
                     this);
    g_signal_connect(window_, "focus-out-event", G_CALLBACK(HandleFocusOut),
                     this);
    g_signal_connect(window_, "configure-event", G_CALLBACK(HandleConfigure),
                     this);
    if (!UseDefaultX11Visual(window_)) {
      *error = "GTK could not select the default X11 visual required by CEF.";
      DestroyGtkWindow(false);
      return false;
    }
    last_device_scale_factor_ = gtk_widget_get_scale_factor(window_);
    gtk_widget_show_all(window_);
    gtk_widget_realize(window_);
    gdk_display_flush(gtk_widget_get_display(window_));

    GdkWindow *content_window = gtk_widget_get_window(window_);
    if (content_window == nullptr ||
        !GDK_IS_X11_DISPLAY(gdk_window_get_display(content_window))) {
      *error = "KWebShell requires an X11 or XWayland GTK display for the "
               "CEF native-child backend.";
      DestroyGtkWindow(false);
      return false;
    }

    display_ =
        gdk_x11_display_get_xdisplay(gdk_window_get_display(content_window));
    parent_window_ = gdk_x11_window_get_xid(content_window);
    if (display_ == nullptr || parent_window_ == None) {
      *error = "GTK did not expose a valid X11 parent window.";
      DestroyGtkWindow(false);
      return false;
    }

    const int width = gtk_widget_get_allocated_width(window_);
    const int height = gtk_widget_get_allocated_height(window_);
    CefWindowInfo window_info;
    window_info.SetAsChild(static_cast<CefWindowHandle>(parent_window_),
                           CefRect(0, 0,
                                   width > 1 ? width : configuration.width,
                                   height > 1 ? height : configuration.height));
    window_info.runtime_style = CEF_RUNTIME_STYLE_ALLOY;
    window_info.windowless_rendering_enabled = false;

    CefBrowserSettings browser_settings;
    browser_settings.background_color = CefColorSetARGB(255, 16, 32, 51);
    if (!CefBrowserHost::CreateBrowser(window_info, client, url,
                                       browser_settings, nullptr,
                                       request_context)) {
      *error = "CefBrowserHost::CreateBrowser rejected the native child.";
      DestroyGtkWindow(false);
      return false;
    }

    recorder_->Record("native_window_created",
                      {{"width", std::to_string(configuration.width)},
                       {"height", std::to_string(configuration.height)},
                       {"device_scale_factor",
                        std::to_string(gtk_widget_get_scale_factor(window_))}});
    return true;
  }

  void OnBrowserCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    browser_ = browser;
    browser_window_ =
        static_cast<Window>(browser->GetHost()->GetWindowHandle());
    if (browser_window_ == None) {
      delegate_->OnNativeFatalError("native.linux.browser-window-missing", {});
      return;
    }
    ValidateBrowserParent();
  }

  void OnBrowserCloseAccepted() override {
    CEF_REQUIRE_UI_THREAD();
    allow_window_close_ = true;
    recorder_->Record("native_window_close_accepted");
  }

  void OnBrowserDestroyed() override {
    CEF_REQUIRE_UI_THREAD();
    if (!close_dispatched_) {
      close_dispatched_ = true;
      recorder_->Record("native_window_close_dispatched");
    }
    browser_ = nullptr;
    browser_window_ = None;
    allow_window_close_ = true;
    DestroyGtkWindow(true);
  }

  void SetTitle(const std::string &title) override {
    CEF_REQUIRE_UI_THREAD();
    if (window_ != nullptr) {
      gtk_window_set_title(GTK_WINDOW(window_), title.c_str());
    }
  }

  uintptr_t GetRootWindowHandle() const override {
    return static_cast<uintptr_t>(parent_window_);
  }

  void RunNativeInputSelfTest() override {
    CEF_REQUIRE_UI_THREAD();
    if (self_test_started_ || allow_window_close_) {
      return;
    }
    if (window_ == nullptr || !browser_) {
      delegate_->OnNativeFatalError("native.linux.self-test-window-unavailable",
                                    {});
      return;
    }
    if (!browser_parent_validated_) {
      self_test_pending_ = true;
      return;
    }
    self_test_started_ = true;
    self_test_pending_ = false;
    gtk_window_resize(GTK_WINDOW(window_), 960, 720);
    gtk_widget_queue_resize(window_);
    recorder_->Record("native_resize_sent",
                      {{"width", "960"}, {"height", "720"}});
    CefPostDelayedTask(TID_UI,
                       base::BindOnce(&LinuxNativeWindow::SendSelfTestInput,
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
    if (window_ == nullptr || display_ == nullptr || parent_window_ == None) {
      *error = "GTK/X11 root window is unavailable.";
      return false;
    }

    const int width = gtk_widget_get_allocated_width(window_);
    const int height = gtk_widget_get_allocated_height(window_);
    const int scale_factor = gtk_widget_get_scale_factor(window_);
    int root_x = 0;
    int root_y = 0;
    Window child = None;
    if (width <= 0 || height <= 0 || scale_factor <= 0 ||
        XTranslateCoordinates(display_, parent_window_,
                              DefaultRootWindow(display_), 0, 0, &root_x,
                              &root_y, &child) == 0) {
      *error = "X11 could not translate the GTK root window bounds.";
      return false;
    }
    *rect =
        CefRect(root_x / scale_factor, root_y / scale_factor, width, height);
    return true;
  }

private:
  static gboolean HandleDeleteEvent(GtkWidget *widget, GdkEvent *event,
                                    gpointer user_data) {
    auto *owner = static_cast<LinuxNativeWindow *>(user_data);
    if (!owner->allow_window_close_) {
      owner->delegate_->OnNativeCloseRequested();
    }
    return TRUE;
  }

  static void HandleSizeAllocate(GtkWidget *widget, GtkAllocation *allocation,
                                 gpointer user_data) {
    auto *owner = static_cast<LinuxNativeWindow *>(user_data);
    owner->RecordDeviceScaleFactor();
    owner->ResizeBrowserWindow(allocation->width, allocation->height);
  }

  static gboolean HandleFocusIn(GtkWidget *widget, GdkEventFocus *event,
                                gpointer user_data) {
    auto *owner = static_cast<LinuxNativeWindow *>(user_data);
    if (owner->browser_) {
      owner->browser_->GetHost()->SetFocus(true);
    }
    return FALSE;
  }

  static gboolean HandleFocusOut(GtkWidget *widget, GdkEventFocus *event,
                                 gpointer user_data) {
    auto *owner = static_cast<LinuxNativeWindow *>(user_data);
    if (owner->browser_) {
      owner->browser_->GetHost()->SetFocus(false);
    }
    return FALSE;
  }

  static gboolean HandleConfigure(GtkWidget *widget, GdkEventConfigure *event,
                                  gpointer user_data) {
    auto *owner = static_cast<LinuxNativeWindow *>(user_data);
    if (owner->browser_) {
      owner->browser_->GetHost()->NotifyMoveOrResizeStarted();
      owner->browser_->GetHost()->NotifyScreenInfoChanged();
    }
    return FALSE;
  }

  void ValidateBrowserParent() {
    CEF_REQUIRE_UI_THREAD();
    if (browser_parent_validated_ || allow_window_close_ || !browser_ ||
        display_ == nullptr || browser_window_ == None) {
      return;
    }

    ++browser_parent_validation_attempts_;
    Window root = None;
    Window parent = None;
    Window *children = nullptr;
    unsigned int child_count = 0;
    const Status query_status = XQueryTree(display_, browser_window_, &root,
                                           &parent, &children, &child_count);
    if (children != nullptr) {
      XFree(children);
    }
    if (query_status != 0 && parent == parent_window_) {
      browser_parent_validated_ = true;
      recorder_->Record(
          "native_child_attached",
          {{"superview", "content-view"},
           {"validation_attempt",
            std::to_string(browser_parent_validation_attempts_)}});
      ResizeBrowserWindow(gtk_widget_get_allocated_width(window_),
                          gtk_widget_get_allocated_height(window_));
      if (self_test_pending_) {
        RunNativeInputSelfTest();
      }
      return;
    }

    if (browser_parent_validation_attempts_ >=
        kBrowserParentValidationMaxAttempts) {
      delegate_->OnNativeFatalError(
          "native.linux.browser-parent-invalid",
          {{"expected", std::to_string(parent_window_)},
           {"actual", std::to_string(parent)},
           {"query_status", std::to_string(query_status)},
           {"attempts", std::to_string(browser_parent_validation_attempts_)}});
      return;
    }

    CefPostDelayedTask(TID_UI,
                       base::BindOnce(&LinuxNativeWindow::ValidateBrowserParent,
                                      base::Unretained(this)),
                       kBrowserParentValidationDelayMs);
  }

  void ResizeBrowserWindow(int width, int height) {
    if (display_ == nullptr || browser_window_ == None || width <= 0 ||
        height <= 0) {
      return;
    }
    XMoveResizeWindow(display_, browser_window_, 0, 0,
                      static_cast<unsigned int>(width),
                      static_cast<unsigned int>(height));
    XFlush(display_);
    if (browser_) {
      browser_->GetHost()->NotifyMoveOrResizeStarted();
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
  }

  void RecordDeviceScaleFactor() {
    if (window_ == nullptr) {
      return;
    }
    const int scale_factor = gtk_widget_get_scale_factor(window_);
    if (scale_factor == last_device_scale_factor_) {
      return;
    }
    last_device_scale_factor_ = scale_factor;
    recorder_->Record("native_dpi_changed",
                      {{"device_scale_factor", std::to_string(scale_factor)}});
    if (browser_) {
      browser_->GetHost()->NotifyMoveOrResizeStarted();
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
  }

  void SendSelfTestInput() {
    CEF_REQUIRE_UI_THREAD();
    if (allow_window_close_ || self_test_passed_) {
      return;
    }
    if (window_ == nullptr || !browser_) {
      delegate_->OnNativeFatalError("native.linux.self-test-window-unavailable",
                                    {});
      return;
    }
    ++self_test_input_attempts_;
    gtk_window_present(GTK_WINDOW(window_));
    gtk_widget_grab_focus(window_);
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

    SendSelfTestKeyboardInput(browser_, 45);
    recorder_->Record("native_keyboard_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"key", "k"},
                       {"transport", "cef-windowed-host"}});

    if (self_test_input_attempts_ < 3) {
      CefPostDelayedTask(TID_UI,
                         base::BindOnce(&LinuxNativeWindow::SendSelfTestInput,
                                        base::Unretained(this)),
                         400);
    }
  }

  void DestroyGtkWindow(bool record_event) {
    if (window_ == nullptr) {
      return;
    }
    g_signal_handlers_disconnect_by_data(window_, this);
    gtk_widget_destroy(window_);
    window_ = nullptr;
    display_ = nullptr;
    parent_window_ = None;
    if (record_event && !native_window_destroyed_) {
      native_window_destroyed_ = true;
      recorder_->Record("native_window_destroyed");
    }
  }

  NativeWindowDelegate *const delegate_;
  const std::shared_ptr<EventRecorder> recorder_;
  GtkWidget *window_ = nullptr;
  Display *display_ = nullptr;
  Window parent_window_ = None;
  Window browser_window_ = None;
  CefRefPtr<CefBrowser> browser_;
  bool allow_window_close_ = false;
  bool close_dispatched_ = false;
  bool native_window_destroyed_ = false;
  bool self_test_started_ = false;
  bool self_test_passed_ = false;
  bool self_test_pending_ = false;
  bool browser_parent_validated_ = false;
  int browser_parent_validation_attempts_ = 0;
  int self_test_input_attempts_ = 0;
  int last_device_scale_factor_ = 0;
};

std::unique_ptr<NativeWindow>
CreateNativeWindow(NativeWindowDelegate *delegate,
                   std::shared_ptr<EventRecorder> recorder) {
  return std::make_unique<LinuxNativeWindow>(delegate, std::move(recorder));
}

} // namespace kwebshell
