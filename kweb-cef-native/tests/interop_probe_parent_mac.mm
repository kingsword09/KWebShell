#import <Cocoa/Cocoa.h>

#include "interop_probe_abi.h"

extern "C" kweb_status KWEB_ABI_CALL
kweb_probe_validate_native_parent(uintptr_t native_parent) {
  if (native_parent == 0) {
    return KWEB_STATUS_PARENT_SURFACE_INVALID;
  }
  NSWindow *window = (__bridge NSWindow *)(reinterpret_cast<void *>(native_parent));
  return [window isKindOfClass:[NSWindow class]] && [window contentView] != nil
             ? KWEB_STATUS_OK
             : KWEB_STATUS_PARENT_SURFACE_INVALID;
}
