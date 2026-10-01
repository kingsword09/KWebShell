#import <Cocoa/Cocoa.h>
#include "shell_platform.h"

#include <dispatch/dispatch.h>
#include <string>

namespace kwebshell::shell {
namespace {
NSString *String(const std::string &value) {
  return [[NSString alloc] initWithBytes:value.data()
                                 length:value.size()
                               encoding:NSUTF8StringEncoding];
}
NSURL *FileUrl(const std::string &value) {
  return [NSURL fileURLWithPath:String(value) isDirectory:NO];
}
kweb_shell_status Open(const std::string &value, bool file) {
  __block bool accepted = false;
  dispatch_sync(dispatch_get_main_queue(), ^{
    @autoreleasepool {
      NSURL *url = file ? FileUrl(value) : [NSURL URLWithString:String(value)];
      accepted = url != nil && [[NSWorkspace sharedWorkspace] openURL:url];
    }
  });
  return accepted ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_HANDLER_REJECTED;
}
kweb_shell_status Reveal(const std::string &value) {
  __block bool accepted = false;
  dispatch_sync(dispatch_get_main_queue(), ^{
    @autoreleasepool {
      NSURL *url = FileUrl(value);
      if (url != nil) {
        [[NSWorkspace sharedWorkspace] activateFileViewerSelectingURLs:@[url]];
        accepted = true;
      }
    }
  });
  return accepted ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
}
kweb_shell_status Trash(const std::string &value) {
  dispatch_semaphore_t completion = dispatch_semaphore_create(0);
  __block bool accepted = false;
  dispatch_async(dispatch_get_main_queue(), ^{
    @autoreleasepool {
      NSURL *url = FileUrl(value);
      if (url != nil) {
        [[NSWorkspace sharedWorkspace] recycleURLs:@[url]
            completionHandler:^(NSDictionary<NSURL *, NSURL *> *new_urls, NSError *error) {
              accepted = error == nil && new_urls != nil;
              dispatch_semaphore_signal(completion);
            }];
      } else {
        dispatch_semaphore_signal(completion);
      }
    }
  });
  dispatch_semaphore_wait(completion, DISPATCH_TIME_FOREVER);
  if (!accepted) return KWEB_SHELL_STATUS_TRASH_FAILED;
  return [[NSFileManager defaultManager] fileExistsAtPath:String(value)]
      ? KWEB_SHELL_STATUS_TRASH_VERIFICATION_FAILED : KWEB_SHELL_STATUS_OK;
}
}  // namespace

const char *ProviderId() { return "macos.AppKit.NSWorkspace"; }

kweb_shell_status ExecutePlatform(
    uint32_t action, uint32_t, const std::string &value, uint32_t *outcome) {
  @autoreleasepool {
    kweb_shell_status status;
    if (action == KWEB_SHELL_ACTION_OPEN_EXTERNAL) {
      status = Open(value, false);
      if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
    } else if (action == KWEB_SHELL_ACTION_OPEN_RESOURCE) {
      status = Open(value, true);
      if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
    } else if (action == KWEB_SHELL_ACTION_REVEAL_RESOURCE) {
      status = Reveal(value);
      if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
    } else {
      status = Trash(value);
      if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_MOVED_TO_TRASH;
    }
    return status;
  }
}
}  // namespace kwebshell::shell
