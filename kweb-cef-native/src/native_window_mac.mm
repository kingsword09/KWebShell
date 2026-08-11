#include "native_window.h"

#import <Cocoa/Cocoa.h>

#include <memory>
#include <string>
#include <utility>

#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/cef_browser.h"
#include "include/internal/cef_types_mac.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "kwebshell/native/event_recorder.h"
#include "self_test_page.h"

namespace kwebshell {
class MacNativeWindow;
}

@interface KWebWindowDelegate
    : NSObject <NSWindowDelegate, NSApplicationDelegate>
@property(nonatomic, assign) kwebshell::MacNativeWindow *owner;
- (void)sendSelfTestInput;
- (void)windowDidChangeBackingProperties:(NSNotification *)notification;
- (void)windowDidBecomeKey:(NSNotification *)notification;
- (void)windowDidResignKey:(NSNotification *)notification;
@end

namespace kwebshell {
namespace {

NSString *ToNSString(const std::string &value) {
  return [[NSString alloc] initWithBytes:value.data()
                                  length:value.size()
                                encoding:NSUTF8StringEncoding];
}

bool SendDirectedWheelEvent(NSView *browser_view, std::string *error) {
  NSWindow *window = browser_view.window;
  if (window == nil) {
    *error = "The CEF browser view is not attached to an NSWindow.";
    return false;
  }

  const NSPoint view_point = NSMakePoint(120, 120);
  NSView *event_target = [browser_view hitTest:view_point];
  if (event_target == nil) {
    *error = "The CEF browser view did not expose a wheel event target.";
    return false;
  }

  const NSPoint window_point = [browser_view convertPoint:view_point toView:nil];
  const NSPoint screen_point = [window convertPointToScreen:window_point];
  NSScreen *primary_screen = NSScreen.screens.firstObject;
  if (primary_screen == nil) {
    *error = "AppKit did not expose a primary screen for wheel coordinates.";
    return false;
  }

  const CGPoint quartz_point =
      CGPointMake(screen_point.x,
                  primary_screen.frame.size.height - screen_point.y);
  const auto create_wheel_event =
      [quartz_point](int32_t delta, int64_t phase) -> NSEvent * {
    CGEventRef cg_event = CGEventCreateScrollWheelEvent(
        nullptr, kCGScrollEventUnitPixel, 1, delta);
    if (cg_event == nullptr) {
      return nil;
    }
    CGEventSetLocation(cg_event, quartz_point);
    CGEventSetIntegerValueField(cg_event, kCGScrollWheelEventScrollPhase,
                                phase);
    NSEvent *event = [NSEvent eventWithCGEvent:cg_event];
    CFRelease(cg_event);
    return event;
  };

  // CoreGraphics encodes began as 1 and ended as 4 in the scroll phase field.
  NSEvent *wheel_began = create_wheel_event(-120, 1);
  NSEvent *wheel_ended = create_wheel_event(0, 4);
  if (wheel_began == nil || wheel_ended == nil) {
    *error = "CoreGraphics could not create a complete wheel phase sequence.";
    return false;
  }

  [event_target scrollWheel:wheel_began];
  [NSApp postEvent:wheel_ended atStart:YES];
  return true;
}

} // namespace

class MacNativeWindow final : public NativeWindow {
public:
  MacNativeWindow(NativeWindowDelegate *delegate,
                  std::shared_ptr<EventRecorder> recorder)
      : delegate_(delegate), recorder_(std::move(recorder)) {}

  ~MacNativeWindow() override {
    [NSObject cancelPreviousPerformRequestsWithTarget:window_delegate_];
    window_delegate_.owner = nullptr;
    if (NSApp.delegate == window_delegate_) {
      NSApp.delegate = nil;
    }
    window_.delegate = nil;
    [window_ orderOut:nil];
    browser_ = nullptr;
    browser_view_ = nil;
    window_ = nil;
    window_delegate_ = nil;
  }

  bool CreateBrowser(const HostConfiguration &configuration,
                     CefRefPtr<CefClient> client, const std::string &url,
                     std::string *error) override {
    CEF_REQUIRE_UI_THREAD();

    const NSWindowStyleMask style =
        NSWindowStyleMaskTitled | NSWindowStyleMaskClosable |
        NSWindowStyleMaskMiniaturizable | NSWindowStyleMaskResizable;
    const NSRect content_rect =
        NSMakeRect(0, 0, configuration.width, configuration.height);
    window_ = [[NSWindow alloc] initWithContentRect:content_rect
                                          styleMask:style
                                            backing:NSBackingStoreBuffered
                                              defer:NO];
    if (window_ == nil) {
      *error = "NSWindow creation returned nil.";
      return false;
    }

    window_delegate_ = [[KWebWindowDelegate alloc] init];
    window_delegate_.owner = this;
    window_.delegate = window_delegate_;
    NSApp.delegate = window_delegate_;
    [window_ setReleasedWhenClosed:NO];
    [window_ setTitle:@"KWebShell"];
    [window_ setBackgroundColor:NSColor.blackColor];
    [window_ center];

    NSView *content_view = window_.contentView;
    content_view.wantsLayer = YES;
    const NSRect bounds = content_view.bounds;

    CefWindowInfo window_info;
    window_info.SetAsChild(CAST_NSVIEW_TO_CEF_WINDOW_HANDLE(content_view),
                           CefRect(0, 0, static_cast<int>(bounds.size.width),
                                   static_cast<int>(bounds.size.height)));
    window_info.runtime_style = CEF_RUNTIME_STYLE_ALLOY;
    window_info.windowless_rendering_enabled = false;

    CefBrowserSettings browser_settings;
    browser_settings.background_color = CefColorSetARGB(255, 16, 32, 51);
    const bool accepted = CefBrowserHost::CreateBrowser(
        window_info, client, url, browser_settings, nullptr, nullptr);
    if (!accepted) {
      *error = "CefBrowserHost::CreateBrowser rejected the native child.";
      return false;
    }

    recorder_->Record(
        "native_window_created",
        {{"width", std::to_string(configuration.width)},
         {"height", std::to_string(configuration.height)},
         {"device_scale_factor", std::to_string(window_.backingScaleFactor)}});
    last_device_scale_factor_ = window_.backingScaleFactor;
    [window_ makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
    return true;
  }

  void OnBrowserCreated(CefRefPtr<CefBrowser> browser) override {
    CEF_REQUIRE_UI_THREAD();
    browser_ = browser;
    browser_view_ =
        CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(browser->GetHost()->GetWindowHandle());
    if (browser_view_ == nil) {
      delegate_->OnNativeFatalError("native.macos.browser-view-missing", {});
      return;
    }
    browser_view_.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    browser_view_.frame = window_.contentView.bounds;
    recorder_->Record(
        "native_child_attached",
        {{"superview", browser_view_.superview == window_.contentView
                           ? "content-view"
                           : "unexpected"}});
  }

  void OnBrowserDestroyed() override {
    CEF_REQUIRE_UI_THREAD();
    allow_window_close_ = true;
    browser_ = nullptr;
    browser_view_ = nil;
  }

  void OnBrowserCloseAccepted() override {
    CEF_REQUIRE_UI_THREAD();
    allow_window_close_ = true;
    recorder_->Record("native_window_close_accepted");
    CefPostTask(TID_UI, base::BindOnce(&MacNativeWindow::CloseAcceptedWindow,
                                       base::Unretained(this)));
  }

  void SetTitle(const std::string &title) override {
    CEF_REQUIRE_UI_THREAD();
    NSString *value = ToNSString(title);
    if (value != nil) {
      window_.title = value;
    }
  }

  void OnBackingScaleFactorChanged() {
    CEF_REQUIRE_UI_THREAD();
    if (window_ == nil) {
      return;
    }
    const double scale_factor = window_.backingScaleFactor;
    if (scale_factor == last_device_scale_factor_) {
      return;
    }
    last_device_scale_factor_ = scale_factor;
    recorder_->Record("native_dpi_changed",
                      {{"device_scale_factor", std::to_string(scale_factor)}});
    if (browser_) {
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
  }

  void OnWindowKeyChanged(bool is_key) {
    CEF_REQUIRE_UI_THREAD();
    if (browser_) {
      browser_->GetHost()->SetFocus(is_key);
    }
  }

  void RunNativeInputSelfTest() override {
    CEF_REQUIRE_UI_THREAD();
    if (self_test_started_ || allow_window_close_) {
      return;
    }
    if (browser_view_ == nil || !browser_ || window_ == nil) {
      delegate_->OnNativeFatalError("native.macos.self-test-window-unavailable",
                                    {});
      return;
    }
    self_test_started_ = true;
    [window_ setContentSize:NSMakeSize(960, 720)];
    browser_view_.frame = window_.contentView.bounds;
    recorder_->Record("native_resize_sent",
                      {{"width", "960"}, {"height", "720"}});
    [window_delegate_ performSelector:@selector(sendSelfTestInput)
                           withObject:nil
                           afterDelay:0.5];
  }

  void OnInputSelfTestPassed() override {
    CEF_REQUIRE_UI_THREAD();
    if (self_test_passed_) {
      return;
    }
    self_test_passed_ = true;
    [NSObject cancelPreviousPerformRequestsWithTarget:window_delegate_
                                             selector:@selector(sendSelfTestInput)
                                               object:nil];
  }

  void OnWindowCloseRequested() {
    CEF_REQUIRE_UI_THREAD();
    if (allow_window_close_) {
      return;
    }
    delegate_->OnNativeCloseRequested();
  }

  bool allow_window_close() const { return allow_window_close_; }

  void CloseAcceptedWindow() {
    CEF_REQUIRE_UI_THREAD();
    if (!allow_window_close_ || window_ == nil) {
      return;
    }
    if (!close_dispatched_) {
      close_dispatched_ = true;
      recorder_->Record("native_window_close_dispatched");
    }
    PrepareNativeWindowClose();
  }

  void PrepareNativeWindowClose() {
    CEF_REQUIRE_UI_THREAD();
    if (window_ == nil) {
      return;
    }
    NSWindow *closing_window = window_;
    NSView *__weak closing_browser_view = browser_view_;
    @autoreleasepool {
      [browser_view_ removeFromSuperview];
      closing_window.delegate = nil;
      closing_window.contentView = [[NSView alloc] initWithFrame:NSZeroRect];
      [closing_window close];
      window_ = nil;
    }
    const bool child_view_released = closing_browser_view == nil;
    browser_view_ = nil;
    recorder_->Record(
        "native_window_destroyed",
        {{"child_view_released", child_view_released ? "true" : "false"}});
  }

  void SendSelfTestInput() {
    CEF_REQUIRE_UI_THREAD();
    if (allow_window_close_ || self_test_passed_) {
      return;
    }
    if (browser_view_ == nil || !browser_ || window_ == nil) {
      delegate_->OnNativeFatalError("native.macos.self-test-window-unavailable",
                                    {});
      return;
    }

    ++self_test_input_attempts_;

    [window_ makeKeyAndOrderFront:nil];
    [NSApp activateIgnoringOtherApps:YES];
    const BOOL focus_accepted = [window_ makeFirstResponder:browser_view_];
    OnWindowKeyChanged(true);
    recorder_->Record("native_focus_sent",
                      {{"first_responder", focus_accepted ? "true" : "false"}});

    const NSTimeInterval timestamp = NSProcessInfo.processInfo.systemUptime;
    SendSelfTestMouseInput(browser_);
    recorder_->Record("native_mouse_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"transport", "cef-windowed-host"}});

    std::string wheel_error;
    if (!SendDirectedWheelEvent(browser_view_, &wheel_error)) {
      delegate_->OnNativeFatalError(
          "native.macos.self-test-wheel-event-unavailable",
          {{"message", wheel_error}});
      return;
    }
    recorder_->Record("native_wheel_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"transport", "cocoa-child-view"}});

    NSEvent *key_down = [NSEvent keyEventWithType:NSEventTypeKeyDown
                                         location:NSZeroPoint
                                    modifierFlags:0
                                        timestamp:timestamp + 0.01
                                     windowNumber:window_.windowNumber
                                          context:nil
                                       characters:@"k"
                      charactersIgnoringModifiers:@"k"
                                        isARepeat:NO
                                          keyCode:40];
    NSEvent *key_up = [NSEvent keyEventWithType:NSEventTypeKeyUp
                                       location:NSZeroPoint
                                  modifierFlags:0
                                      timestamp:timestamp + 0.02
                                   windowNumber:window_.windowNumber
                                        context:nil
                                     characters:@"k"
                    charactersIgnoringModifiers:@"k"
                                      isARepeat:NO
                                        keyCode:40];
    [NSApp sendEvent:key_down];
    [NSApp sendEvent:key_up];
    recorder_->Record("native_keyboard_input_sent",
                      {{"attempt", std::to_string(self_test_input_attempts_)},
                       {"key", "k"},
                       {"transport", "cocoa"}});
    ScheduleSelfTestRetry();
  }

private:
  void ScheduleSelfTestRetry() {
    if (self_test_input_attempts_ < 3) {
      [window_delegate_ performSelector:@selector(sendSelfTestInput)
                             withObject:nil
                             afterDelay:0.4];
    }
  }

  NativeWindowDelegate *const delegate_;
  const std::shared_ptr<EventRecorder> recorder_;
  NSWindow *__strong window_ = nil;
  KWebWindowDelegate *__strong window_delegate_ = nil;
  NSView *__weak browser_view_ = nil;
  CefRefPtr<CefBrowser> browser_;
  bool allow_window_close_ = false;
  bool close_dispatched_ = false;
  bool self_test_started_ = false;
  bool self_test_passed_ = false;
  int self_test_input_attempts_ = 0;
  double last_device_scale_factor_ = 0;
};

std::unique_ptr<NativeWindow>
CreateNativeWindow(NativeWindowDelegate *delegate,
                   std::shared_ptr<EventRecorder> recorder) {
  return std::make_unique<MacNativeWindow>(delegate, std::move(recorder));
}

} // namespace kwebshell

@implementation KWebWindowDelegate
- (BOOL)windowShouldClose:(NSWindow *)sender {
  if (self.owner == nullptr) {
    return YES;
  }
  if (self.owner->allow_window_close()) {
    self.owner->CloseAcceptedWindow();
    return YES;
  }
  self.owner->OnWindowCloseRequested();
  return NO;
}

- (NSApplicationTerminateReply)applicationShouldTerminate:
    (NSApplication *)sender {
  if (self.owner == nullptr || self.owner->allow_window_close()) {
    return NSTerminateNow;
  }
  self.owner->OnWindowCloseRequested();
  return NSTerminateCancel;
}

- (BOOL)applicationSupportsSecureRestorableState:(NSApplication *)app {
  return YES;
}

- (void)sendSelfTestInput {
  if (self.owner != nullptr) {
    self.owner->SendSelfTestInput();
  }
}
- (void)windowDidChangeBackingProperties:(NSNotification *)notification {
  if (self.owner != nullptr) {
    self.owner->OnBackingScaleFactorChanged();
  }
}
- (void)windowDidBecomeKey:(NSNotification *)notification {
  if (self.owner != nullptr) {
    self.owner->OnWindowKeyChanged(true);
  }
}
- (void)windowDidResignKey:(NSNotification *)notification {
  if (self.owner != nullptr) {
    self.owner->OnWindowKeyChanged(false);
  }
}
@end
