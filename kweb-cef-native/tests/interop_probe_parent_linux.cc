#include "interop_probe_abi.h"

#if defined(__linux__)

#include <X11/Xlib.h>

extern "C" kweb_status KWEB_ABI_CALL
kweb_probe_validate_native_parent(uintptr_t native_parent) {
  Display *display = XOpenDisplay(nullptr);
  if (display == nullptr || native_parent == 0) {
    if (display != nullptr) {
      XCloseDisplay(display);
    }
    return KWEB_STATUS_PARENT_SURFACE_INVALID;
  }
  XWindowAttributes attributes{};
  const Status attributes_result = XGetWindowAttributes(
      display, static_cast<Window>(native_parent), &attributes);
  XCloseDisplay(display);
  return attributes_result != 0 && attributes.c_class == InputOutput &&
                 attributes.width > 0 && attributes.height > 0
             ? KWEB_STATUS_OK
             : KWEB_STATUS_PARENT_SURFACE_INVALID;
}

#endif
