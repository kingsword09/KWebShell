#import <Cocoa/Cocoa.h>

#include <string>
#include <vector>

#include "host_main.h"
#include "include/cef_application_mac.h"
#include "include/wrapper/cef_library_loader.h"

@interface KWebApplication : NSApplication <CefAppProtocol> {
@private
  BOOL handlingSendEvent_;
}
@end

@implementation KWebApplication
- (BOOL)isHandlingSendEvent {
  return handlingSendEvent_;
}

- (void)setHandlingSendEvent:(BOOL)handlingSendEvent {
  handlingSendEvent_ = handlingSendEvent;
}

- (void)sendEvent:(NSEvent *)event {
  CefScopedSendingEvent sending_event;
  [super sendEvent:event];
}
@end

int main(int argc, char *argv[]) {
  CefScopedLibraryLoader library_loader;
  if (!library_loader.LoadInMain()) {
    return static_cast<int>(kwebshell::HostExitCode::kCefInitializationError);
  }

  CefMainArgs main_args(argc, argv);
  std::vector<std::string> arguments;
  arguments.reserve(static_cast<size_t>(argc));
  for (int index = 1; index < argc; ++index) {
    arguments.emplace_back(argv[index]);
  }

  @autoreleasepool {
    [KWebApplication sharedApplication];
    if (![NSApp isKindOfClass:[KWebApplication class]]) {
      return static_cast<int>(kwebshell::HostExitCode::kCefInitializationError);
    }
    [NSApp setActivationPolicy:NSApplicationActivationPolicyRegular];
    return kwebshell::RunBrowserProcess(main_args, arguments, nullptr);
  }
}
