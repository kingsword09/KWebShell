#import <Cocoa/Cocoa.h>

#include "browser_surface.h"

#include <memory>

namespace kwebshell {
namespace {

class MacBrowserSurface final : public BrowserSurface {
public:
  MacBrowserSurface(NSWindow *parent, int32_t x, int32_t y, int32_t width,
                    int32_t height)
      : parent_(parent) {
    NSView *root = [parent_ contentView];
    const CGFloat translated_y =
        NSHeight([root bounds]) - static_cast<CGFloat>(y + height);
    container_ = [[NSView alloc]
        initWithFrame:NSMakeRect(x, translated_y, width, height)];
    [container_ setWantsLayer:YES];
    [root addSubview:container_];
  }

  ~MacBrowserSurface() override {
    if (container_ != nil) {
      [container_ removeFromSuperview];
    }
  }

  CefWindowHandle parent_handle() const override {
    return (__bridge CefWindowHandle)container_;
  }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) override {
    browser_ = browser;
    browser_view_ =
        CAST_CEF_WINDOW_HANDLE_TO_NSVIEW(browser->GetHost()->GetWindowHandle());
  }

  kweb_status Resize(int32_t width, int32_t height, int32_t *actual_width,
                     int32_t *actual_height) override {
    if (container_ == nil || browser_view_ == nil) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
    NSRect container_frame = [container_ frame];
    container_frame.origin.y += container_frame.size.height - height;
    container_frame.size = NSMakeSize(width, height);
    [container_ setFrame:container_frame];
    [browser_view_ setFrame:NSMakeRect(0, 0, width, height)];
    [container_ layoutSubtreeIfNeeded];
    [browser_view_ layoutSubtreeIfNeeded];
    const NSRect applied = [browser_view_ frame];
    *actual_width = static_cast<int32_t>(NSWidth(applied));
    *actual_height = static_cast<int32_t>(NSHeight(applied));
    if (browser_) {
      browser_->GetHost()->NotifyMoveOrResizeStarted();
      browser_->GetHost()->NotifyScreenInfoChanged();
    }
    return *actual_width == width && *actual_height == height
               ? KWEB_STATUS_OK
               : KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
  }

  bool ValidateParentage() const override {
    return parent_ != nil && container_ != nil && browser_view_ != nil &&
           [container_ window] == parent_ &&
           [browser_view_ superview] == container_;
  }

  kweb_status RequestBrowserClose() override {
    if (browser_view_ != nil) {
      [browser_view_ removeFromSuperview];
      for (NSView *view in [browser_view_ subviews]) {
        [view removeFromSuperview];
      }
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
    browser_view_ = nil;
    [container_ removeFromSuperview];
    container_ = nil;
    parent_ = nil;
    return KWEB_STATUS_OK;
  }

private:
  NSWindow *__strong parent_;
  NSView *__strong container_;
  NSView *__weak browser_view_ = nil;
  CefRefPtr<CefBrowser> browser_;
};

} // namespace

void ConfigureDevToolsWindow(CefWindowInfo &window_info,
                             uintptr_t native_parent, int32_t width,
                             int32_t height) {
  (void)native_parent;
  CefString(&window_info.window_name) = "KWebShell DevTools";
  window_info.bounds = CefRect(120, 120, width, height);
  window_info.hidden = false;
}

std::unique_ptr<BrowserSurface>
CreateBrowserSurface(uintptr_t native_parent, int32_t x, int32_t y,
                     int32_t width, int32_t height, kweb_status *status_out) {
  if (status_out == nullptr || native_parent == 0) {
    if (status_out != nullptr) {
      *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    }
    return nullptr;
  }
  NSWindow *parent = (__bridge NSWindow *)(reinterpret_cast<void *>(native_parent));
  if (![parent isKindOfClass:[NSWindow class]] || [parent contentView] == nil) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return nullptr;
  }
  *status_out = KWEB_STATUS_OK;
  return std::make_unique<MacBrowserSurface>(parent, x, y, width, height);
}

} // namespace kwebshell
