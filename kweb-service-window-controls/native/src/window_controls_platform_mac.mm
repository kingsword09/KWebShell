#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

#include "window_controls_platform.h"

@interface KWebWindowControlsAppKitCall : NSObject
@property(nonatomic, copy) void (^block)(void);
- (void)invoke;
@end

@implementation KWebWindowControlsAppKitCall

- (void)invoke {
  if (self.block != nil) {
    self.block();
  }
}

@end

namespace kwebshell::window_controls {
namespace {

NSWindow *WindowForHandle(kweb_window_handle handle) {
  return (__bridge NSWindow *)(reinterpret_cast<void *>(handle));
}

template <typename Function>
kweb_window_controls_status OnAppKitThread(Function function) {
  if ([NSThread isMainThread]) {
    return function();
  }
  __block kweb_window_controls_status result =
      KWEB_WINDOW_CONTROLS_STATUS_WRONG_THREAD;
  KWebWindowControlsAppKitCall *call =
      [[KWebWindowControlsAppKitCall alloc] init];
  call.block = ^{
    result = function();
  };
  [call performSelectorOnMainThread:@selector(invoke)
                         withObject:nil
                      waitUntilDone:YES
                              modes:@[NSRunLoopCommonModes]];
  return result;
}

kweb_window_controls_status Validate(NSWindow *window) {
  if (window == nil || ![window isKindOfClass:[NSWindow class]] ||
      window.contentView == nil) {
    return KWEB_WINDOW_CONTROLS_STATUS_STALE_HANDLE;
  }
  return KWEB_WINDOW_CONTROLS_STATUS_OK;
}

} // namespace

const char *ProviderId() { return "macos.AppKit"; }

kweb_window_controls_status Probe(kweb_window_handle handle) {
  return OnAppKitThread([handle] {
    return Validate(WindowForHandle(handle));
  });
}

kweb_window_controls_status Attach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  (void)modality;
  return OnAppKitThread([child, parent] {
    @try {
    NSWindow *child_window = WindowForHandle(child);
    NSWindow *parent_window = WindowForHandle(parent);
    const kweb_window_controls_status child_status = Validate(child_window);
    const kweb_window_controls_status parent_status = Validate(parent_window);
    if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return child_status;
    }
    if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return parent_status;
    }
    if ([child_window parentWindow] != nil &&
        [child_window parentWindow] != parent_window) {
      return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
    }
    const BOOL was_visible = child_window.isVisible;
    if (was_visible) {
      [child_window orderOut:nil];
    }
    if (![parent_window.childWindows containsObject:child_window]) {
      [parent_window addChildWindow:child_window ordered:NSWindowAbove];
    }
    if (was_visible) {
      [child_window orderFront:nil];
    }
    return [parent_window.childWindows containsObject:child_window]
               ? KWEB_WINDOW_CONTROLS_STATUS_OK
               : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
    } @catch (NSException *exception) {
      (void)exception;
      return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
    }
  });
}

kweb_window_controls_status Detach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  (void)modality;
  return OnAppKitThread([child, parent] {
    @try {
    NSWindow *child_window = WindowForHandle(child);
    NSWindow *parent_window = WindowForHandle(parent);
    const kweb_window_controls_status child_status = Validate(child_window);
    const kweb_window_controls_status parent_status = Validate(parent_window);
    if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return child_status;
    }
    if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return parent_status;
    }
    if ([parent_window.childWindows containsObject:child_window]) {
      [parent_window removeChildWindow:child_window];
    }
    return [parent_window.childWindows containsObject:child_window]
               ? KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED
               : KWEB_WINDOW_CONTROLS_STATUS_OK;
    } @catch (NSException *exception) {
      (void)exception;
      return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
    }
  });
}

kweb_window_controls_status SetModalEnabled(kweb_window_handle handle,
                                            bool enabled) {
  return OnAppKitThread([handle, enabled] {
    NSWindow *window = WindowForHandle(handle);
    const kweb_window_controls_status status = Validate(window);
    if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return status;
    }
    [window setIgnoresMouseEvents:!enabled];
    return window.ignoresMouseEvents == !enabled
               ? KWEB_WINDOW_CONTROLS_STATUS_OK
               : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
  });
}

kweb_window_controls_status SetAlwaysOnTop(kweb_window_handle handle,
                                           bool enabled) {
  return OnAppKitThread([handle, enabled] {
    NSWindow *window = WindowForHandle(handle);
    const kweb_window_controls_status status = Validate(window);
    if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return status;
    }
    [window setLevel:enabled ? NSFloatingWindowLevel : NSNormalWindowLevel];
    return window.level == (enabled ? NSFloatingWindowLevel : NSNormalWindowLevel)
               ? KWEB_WINDOW_CONTROLS_STATUS_OK
               : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
  });
}

kweb_window_controls_status RequestAttention(kweb_window_handle handle) {
  return OnAppKitThread([handle] {
    NSWindow *window = WindowForHandle(handle);
    const kweb_window_controls_status status = Validate(window);
    if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return status;
    }
    if (NSApp == nil) {
      return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_UNAVAILABLE;
    }
    [NSApp requestUserAttention:NSCriticalRequest];
    return KWEB_WINDOW_CONTROLS_STATUS_OK;
  });
}

kweb_window_controls_status VerifyParent(kweb_window_handle child,
                                         kweb_window_handle parent) {
  return OnAppKitThread([child, parent] {
    NSWindow *child_window = WindowForHandle(child);
    NSWindow *parent_window = WindowForHandle(parent);
    const kweb_window_controls_status child_status = Validate(child_window);
    const kweb_window_controls_status parent_status = Validate(parent_window);
    if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return child_status;
    }
    if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
      return parent_status;
    }
    return [parent_window.childWindows containsObject:child_window]
               ? KWEB_WINDOW_CONTROLS_STATUS_OK
               : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
  });
}

} // namespace kwebshell::window_controls
