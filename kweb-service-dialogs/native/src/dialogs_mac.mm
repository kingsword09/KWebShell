#include "dialog_operation.h"
#import <Cocoa/Cocoa.h>
#import <UniformTypeIdentifiers/UniformTypeIdentifiers.h>
#include <chrono>
#include <condition_variable>
#include <memory>

namespace kwebshell::dialogs {
namespace {
NSString *String(const std::string &value) {
  return [[NSString alloc] initWithBytes:value.data() length:value.size() encoding:NSUTF8StringEncoding];
}
struct PanelState {
  NSSavePanel *__strong panel = nil;
  NSWindow *__strong owner = nil;
  std::mutex mutex;
  std::condition_variable ready;
  bool complete = false;
};
}

void RunDialog(Operation &op) {
  auto state = std::make_shared<PanelState>();
  dispatch_sync(dispatch_get_main_queue(), ^{
    @autoreleasepool {
      // Validate identity against AppKit's live window list before dereferencing.
      for (NSWindow *window in NSApp.windows) {
        if (reinterpret_cast<uint64_t>((__bridge void *)window) == op.owner) state->owner = window;
      }
      if (state->owner == nil || !state->owner.visible || state->owner.attachedSheet != nil) {
        op.state = KWEB_DIALOG_FAILED;
        op.failure = KWEB_DIALOG_UNAVAILABLE;
        state->complete = true;
        return;
      }
      if (op.cancel_requested.load()) {
        op.state = KWEB_DIALOG_CANCELLED;
        state->complete = true;
        return;
      }
      NSSavePanel *panel;
      if (op.mode == 0) {
        NSOpenPanel *open = [NSOpenPanel openPanel];
        open.canChooseFiles = YES;
        open.canChooseDirectories = NO;
        open.allowsMultipleSelection = NO;
        open.resolvesAliases = NO;
        panel = open;
      } else {
        panel = [NSSavePanel savePanel];
      }
      panel.title = String(op.title);
      panel.message = String(op.title);
      panel.canCreateDirectories = YES;
      if (!op.directory.empty()) panel.directoryURL = [NSURL fileURLWithPath:String(op.directory) isDirectory:YES];
      if (!op.name.empty()) panel.nameFieldStringValue = String(op.name);
      if (!op.extensions.empty()) {
        NSMutableArray<UTType *> *types = [NSMutableArray array];
        for (const auto &extension : op.extensions) {
          UTType *type = [UTType typeWithFilenameExtension:String(extension)];
          if (type == nil) {
            op.state = KWEB_DIALOG_FAILED;
            op.failure = KWEB_DIALOG_UNAVAILABLE;
            state->complete = true;
            return;
          }
          [types addObject:type];
        }
        panel.allowedContentTypes = types;
        panel.allowsOtherFileTypes = NO;
      }
      state->panel = panel;
      [panel beginSheetModalForWindow:state->owner completionHandler:^(NSModalResponse response) {
        @autoreleasepool {
          if (response == NSModalResponseOK && !op.cancel_requested.load()) {
            NSURL *url = state->panel.URL;
            if (url.isFileURL && url.path.UTF8String != nullptr) {
              op.path = url.path.UTF8String;
              op.state = KWEB_DIALOG_SELECTED;
            } else {
              op.state = KWEB_DIALOG_FAILED;
              op.failure = KWEB_DIALOG_NATIVE_FAILED;
            }
          } else {
            op.state = KWEB_DIALOG_CANCELLED;
          }
          [state->panel orderOut:nil];
          {
            std::lock_guard lock(state->mutex);
            state->complete = true;
          }
          state->ready.notify_all();
        }
      }];
    }
  });
  bool cancel_sent = false;
  while (true) {
    {
      std::unique_lock lock(state->mutex);
      if (state->ready.wait_for(lock, std::chrono::milliseconds(10), [&] { return state->complete; })) break;
    }
    dispatch_sync(dispatch_get_main_queue(), ^{
      if (state->panel.visible && state->owner.attachedSheet == state->panel) op.visible.store(true);
    });
    if (op.cancel_requested.load() && !cancel_sent) {
      cancel_sent = true;
      dispatch_sync(dispatch_get_main_queue(), ^{
        [state->panel cancel:nil];
      });
    }
  }
  dispatch_sync(dispatch_get_main_queue(), ^{
    // Wait for the completion block to leave AppKit before publishing terminal state.
    [state->panel orderOut:nil];
    state->panel = nil;
    state->owner = nil;
  });
}
} // namespace kwebshell::dialogs
