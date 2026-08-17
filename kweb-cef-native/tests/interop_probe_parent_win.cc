#include "interop_probe_abi.h"

#if defined(_WIN32)

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

extern "C" kweb_status KWEB_ABI_CALL
kweb_probe_validate_native_parent(uintptr_t native_parent) {
  const HWND window = reinterpret_cast<HWND>(native_parent);
  return window != nullptr && ::IsWindow(window) != FALSE &&
                 ::GetAncestor(window, GA_ROOT) == window
             ? KWEB_STATUS_OK
             : KWEB_STATUS_PARENT_SURFACE_INVALID;
}

#endif
