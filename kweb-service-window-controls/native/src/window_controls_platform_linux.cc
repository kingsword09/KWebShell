#include "window_controls_platform.h"

#include <X11/Xatom.h>
#include <X11/Xlib.h>

namespace kwebshell::window_controls {
namespace {

struct DisplayGuard {
  Display *value = XOpenDisplay(nullptr);
  ~DisplayGuard() {
    if (value != nullptr) {
      XCloseDisplay(value);
    }
  }
};

kweb_window_controls_status Opened(DisplayGuard *display) {
  return display != nullptr && display->value != nullptr
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_NATIVE_UNAVAILABLE;
}

kweb_window_controls_status Validate(Display *display, Window window) {
  if (display == nullptr || display->value == nullptr) {
    return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_UNAVAILABLE;
  }
  XWindowAttributes attributes{};
  return XGetWindowAttributes(display->value, window, &attributes) != 0
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STALE_HANDLE;
}

bool ChangeState(Display *display, Window window, Atom state, bool enabled) {
  const Atom wm_state = XInternAtom(display, "_NET_WM_STATE", True);
  if (wm_state == None || state == None) {
    return false;
  }
  XEvent event{};
  event.xclient.type = ClientMessage;
  event.xclient.message_type = wm_state;
  event.xclient.display = display;
  event.xclient.window = window;
  event.xclient.format = 32;
  event.xclient.data.l[0] = enabled ? 1 : 0;
  event.xclient.data.l[1] = static_cast<long>(state);
  event.xclient.data.l[2] = 0;
  event.xclient.data.l[3] = 1;
  event.xclient.data.l[4] = 0;
  const Window root = DefaultRootWindow(display);
  const Status sent = XSendEvent(display, root, False,
                                  SubstructureRedirectMask |
                                      SubstructureNotifyMask,
                                  &event);
  XFlush(display);
  return sent != 0;
}

} // namespace

const char *ProviderId() { return "linux.X11"; }

kweb_window_controls_status Probe(kweb_window_handle handle) {
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  return Validate(display.value, static_cast<Window>(handle));
}

kweb_window_controls_status Attach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  const Window child_window = static_cast<Window>(child);
  const Window parent_window = static_cast<Window>(parent);
  const kweb_window_controls_status child_status =
      Validate(display.value, child_window);
  const kweb_window_controls_status parent_status =
      Validate(display.value, parent_window);
  if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return child_status;
  }
  if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return parent_status;
  }
  XSetTransientForHint(display.value, child_window, parent_window);
  if (modality != KWEB_WINDOW_MODALITY_NONE) {
    const Atom modal = XInternAtom(display.value, "_NET_WM_STATE_MODAL", True);
    if (!ChangeState(display.value, child_window, modal, true)) {
      return KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED;
    }
  }
  XFlush(display.value);
  return KWEB_WINDOW_CONTROLS_STATUS_OK;
}

kweb_window_controls_status Detach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  (void)parent;
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  const Window child_window = static_cast<Window>(child);
  const kweb_window_controls_status child_status =
      Validate(display.value, child_window);
  if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return child_status;
  }
  const Atom transient = XInternAtom(display.value, "WM_TRANSIENT_FOR", True);
  if (transient != None) {
    XDeleteProperty(display.value, child_window, transient);
  }
  if (modality != KWEB_WINDOW_MODALITY_NONE) {
    const Atom modal = XInternAtom(display.value, "_NET_WM_STATE_MODAL", True);
    if (modal != None && !ChangeState(display.value, child_window, modal, false)) {
      return KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED;
    }
  }
  XFlush(display.value);
  return KWEB_WINDOW_CONTROLS_STATUS_OK;
}

kweb_window_controls_status SetModalEnabled(kweb_window_handle handle,
                                            bool enabled) {
  (void)handle;
  (void)enabled;
  return KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED;
}

kweb_window_controls_status SetAlwaysOnTop(kweb_window_handle handle,
                                           bool enabled) {
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  const Window window = static_cast<Window>(handle);
  const kweb_window_controls_status window_status =
      Validate(display.value, window);
  if (window_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return window_status;
  }
  const Atom above = XInternAtom(display.value, "_NET_WM_STATE_ABOVE", True);
  return ChangeState(display.value, window, above, enabled)
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED;
}

kweb_window_controls_status RequestAttention(kweb_window_handle handle) {
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  const Window window = static_cast<Window>(handle);
  const kweb_window_controls_status window_status =
      Validate(display.value, window);
  if (window_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return window_status;
  }
  const Atom attention =
      XInternAtom(display.value, "_NET_WM_STATE_DEMANDS_ATTENTION", True);
  return ChangeState(display.value, window, attention, true)
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED;
}

kweb_window_controls_status VerifyParent(kweb_window_handle child,
                                         kweb_window_handle parent) {
  DisplayGuard display;
  const kweb_window_controls_status display_status = Opened(&display);
  if (display_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return display_status;
  }
  Window observed_parent = None;
  if (XGetTransientForHint(display.value, static_cast<Window>(child),
                           &observed_parent) == 0) {
    return KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
  }
  return observed_parent == static_cast<Window>(parent)
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

} // namespace kwebshell::window_controls
