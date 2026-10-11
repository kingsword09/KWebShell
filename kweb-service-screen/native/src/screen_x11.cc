#include "screen_internal.h"

#if !defined(__linux__)
#error "The X11 screen provider must only be compiled on Linux."
#endif

#include <X11/Xatom.h>
#include <X11/Xlib.h>
#include <X11/extensions/Xrandr.h>

#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

using ScreenDisplay = kwebshell::screen::Display;
using kwebshell::screen::State;
using kwebshell::screen::Topology;

constexpr char kProviderId[] = "screen.linux.x11-randr";

/** One X11 connection with its RandR monitor view and its event thread. */
struct X11State {
  ::Display *display = nullptr;
  Window root = 0;
  int screen = 0;
  int randr_event_base = 0;
  int randr_error_base = 0;
  int randr_major = 0;
  int randr_minor = 0;
  Atom work_area = 0;
  Atom monitor_atom = 0;
  std::mutex mutex;
  std::thread thread;
  bool stopping = false;
};

State *g_state = nullptr;

X11State *StateOf(State &state) { return static_cast<X11State *>(state.platform); }

uint32_t RotationBitsToFact(unsigned long rotation) {
  switch (rotation) {
    case RR_Rotate_90:
      return KWEB_SCREEN_ROTATION_QUAD_1;
    case RR_Rotate_180:
      return KWEB_SCREEN_ROTATION_QUAD_2;
    case RR_Rotate_270:
      return KWEB_SCREEN_ROTATION_QUAD_3;
    case RR_Rotate_0:
      return KWEB_SCREEN_ROTATION_NONE;
    default:
      return 0;
  }
}

/** Reads the window-manager work area, or reports that the session publishes none. */
bool ReadWorkArea(X11State &x11, int32_t *left, int32_t *top, int32_t *width, int32_t *height) {
  if (x11.work_area == None) return false;
  Atom type = None;
  int format = 0;
  unsigned long count = 0;
  unsigned long remaining = 0;
  unsigned char *data = nullptr;
  if (XGetWindowProperty(x11.display, x11.root, x11.work_area, 0, 4, False, XA_CARDINAL, &type, &format,
                         &count, &remaining, &data) != Success ||
      data == nullptr || type != XA_CARDINAL || count < 4 || format != 32) {
    if (data != nullptr) XFree(data);
    return false;
  }
  const unsigned long *values = reinterpret_cast<const unsigned long *>(data);
  *left = static_cast<int32_t>(values[0]);
  *top = static_cast<int32_t>(values[1]);
  *width = static_cast<int32_t>(values[2]);
  *height = static_cast<int32_t>(values[3]);
  XFree(data);
  return true;
}

/**
 * Reads the refresh rate and rotation a monitor publishes. A monitor owns one or
 * more outputs; the values are published only when every output agrees, because a
 * monitor has one refresh rate and one rotation.
 */
void ReadOutputFacts(X11State &x11, XRRScreenResources *resources, const XRRMonitorInfo &monitor,
                     uint32_t *refresh_hertz, uint32_t *rotation) {
  if (monitor.noutput == 0 || resources == nullptr) return;
  int64_t agreed_rate = 0;
  uint32_t agreed_rotation = 0;
  for (int index = 0; index < monitor.noutput; ++index) {
    XRROutputInfo *output_info = XRRGetOutputInfo(x11.display, resources, monitor.outputs[index]);
    if (output_info == nullptr) continue;
    XRRCrtcInfo *crtc_info = nullptr;
    if (output_info->crtc != None && output_info->connection == RR_Connected) {
      crtc_info = XRRGetCrtcInfo(x11.display, resources, output_info->crtc);
    }
    if (crtc_info != nullptr) {
      for (int mode = 0; mode < resources->nmode; ++mode) {
        if (resources->modes[mode].id != crtc_info->mode) continue;
        const XRRModeInfo &info = resources->modes[mode];
        const int64_t pixels = static_cast<int64_t>(info.hTotal) * info.vTotal;
        if (pixels <= 0) break;
        const int64_t rate = (static_cast<int64_t>(info.dotClock) + pixels / 2) / pixels;
        if (rate <= 0) break;
        if (agreed_rate == 0) {
          agreed_rate = rate;
        } else if (agreed_rate != rate) {
          agreed_rate = -1;
        }
        break;
      }
      const uint32_t rotation_fact = RotationBitsToFact(crtc_info->rotation);
      if (rotation_fact != 0) {
        if (agreed_rotation == 0) {
          agreed_rotation = rotation_fact;
        } else if (agreed_rotation != rotation_fact) {
          agreed_rotation = 0u - 1u;
        }
      }
      XRRFreeCrtcInfo(crtc_info);
    }
    XRRFreeOutputInfo(output_info);
  }
  if (agreed_rate > 0 && agreed_rate <= static_cast<int64_t>(KWEB_SCREEN_MAX_REFRESH_HERTZ)) {
    *refresh_hertz = static_cast<uint32_t>(agreed_rate);
  }
  if (agreed_rotation != 0 && agreed_rotation != 0u - 1u) *rotation = agreed_rotation;
}

kweb_screen_status ReadTopology(X11State &x11, Topology *topology) {
  int count = 0;
  XRRMonitorInfo *monitors = XRRGetMonitors(x11.display, x11.root, True, &count);
  if (monitors == nullptr || count <= 0) {
    if (monitors != nullptr) XRRFreeMonitors(monitors);
    return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  }
  if (count > static_cast<int>(KWEB_SCREEN_MAX_DISPLAYS)) count = static_cast<int>(KWEB_SCREEN_MAX_DISPLAYS);
  XRRScreenResources *resources = XRRGetScreenResourcesCurrent(x11.display, x11.root);
  int32_t work_left = 0;
  int32_t work_top = 0;
  int32_t work_width = 0;
  int32_t work_height = 0;
  const bool has_work_area = ReadWorkArea(x11, &work_left, &work_top, &work_width, &work_height);
  const int depth = DefaultDepth(x11.display, x11.screen);

  topology->provider_id = kProviderId;
  topology->session = KWEB_SCREEN_SESSION_X11;
  topology->capability_bits = KWEB_SCREEN_CAP_CURSOR | KWEB_SCREEN_CAP_TOPOLOGY_EVENTS |
                              KWEB_SCREEN_CAP_MULTI_DISPLAY | KWEB_SCREEN_CAP_COLOR_DEPTH;
  topology->displays.clear();
  for (int index = 0; index < count; ++index) {
    const XRRMonitorInfo &monitor = monitors[index];
    ScreenDisplay display;
    char *name = XGetAtomName(x11.display, monitor.name);
    display.id = name != nullptr ? std::string(name) : std::string("monitor.") + std::to_string(index);
    if (name != nullptr) XFree(name);
    display.name = display.id;
    // RandR publishes device pixels and X11 has no scale concept, so the logical
    // and native rectangles are the same published rectangle.
    display.left = monitor.x;
    display.top = monitor.y;
    display.width = monitor.width;
    display.height = monitor.height;
    display.native_left = monitor.x;
    display.native_top = monitor.y;
    display.native_width = monitor.width;
    display.native_height = monitor.height;
    uint32_t refresh = 0;
    uint32_t rotation = 0;
    ReadOutputFacts(x11, resources, monitor, &refresh, &rotation);
    if (refresh != 0) {
      display.refresh_hertz = refresh;
      display.fact_bits |= KWEB_SCREEN_FACT_REFRESH_RATE;
      topology->capability_bits |= KWEB_SCREEN_CAP_REFRESH_RATE;
    }
    if (rotation != 0) {
      display.rotation = rotation;
      display.fact_bits |= KWEB_SCREEN_FACT_ROTATION;
      topology->capability_bits |= KWEB_SCREEN_CAP_ROTATION;
    }
    if (monitor.mwidth > 0 && monitor.mheight > 0) {
      display.physical_width_mm = static_cast<uint32_t>(monitor.mwidth);
      display.physical_height_mm = static_cast<uint32_t>(monitor.mheight);
      display.fact_bits |= KWEB_SCREEN_FACT_PHYSICAL_SIZE;
      topology->capability_bits |= KWEB_SCREEN_CAP_PHYSICAL_SIZE;
    }
    if (depth > 0) {
      display.color_depth_bits = static_cast<uint32_t>(depth);
      display.fact_bits |= KWEB_SCREEN_FACT_COLOR_DEPTH;
    }
    if (has_work_area) {
      // The session publishes one desktop work area, so each display publishes the
      // part of it that lies inside its own rectangle.
      const int32_t left = std::max(work_left, monitor.x);
      const int32_t top = std::max(work_top, monitor.y);
      const int32_t right = std::min(work_left + work_width, monitor.x + monitor.width);
      const int32_t bottom = std::min(work_top + work_height, monitor.y + monitor.height);
      if (right > left && bottom > top) {
        display.work_left = left;
        display.work_top = top;
        display.work_width = right - left;
        display.work_height = bottom - top;
        display.fact_bits |= KWEB_SCREEN_FACT_WORK_AREA;
        topology->capability_bits |= KWEB_SCREEN_CAP_WORK_AREA;
      }
    }
    if (monitor.primary != 0) display.fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
    topology->displays.push_back(display);
  }
  if (resources != nullptr) XRRFreeScreenResources(resources);
  XRRFreeMonitors(monitors);
  return KWEB_SCREEN_STATUS_OK;
}

/** Re-reads the topology after any RandR or work-area notification. */
void OnX11Event(X11State &x11) {
  if (g_state == nullptr) return;
  Topology topology;
  if (ReadTopology(x11, &topology) != KWEB_SCREEN_STATUS_OK) return;
  if (!kwebshell::screen::ValidTopology(topology)) {
    kwebshell::screen::PushFailed(*g_state, "screen.topology-invalid");
    return;
  }
  kwebshell::screen::PublishTopology(*g_state, topology);
}

}  // namespace

namespace kwebshell::screen {

const char *ProviderId() { return kProviderId; }

kweb_screen_status NativeOpen(State &state) {
  auto *x11 = new X11State();
  state.platform = x11;
  x11->display = XOpenDisplay(nullptr);
  if (x11->display == nullptr) {
    delete x11;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
  x11->screen = DefaultScreen(x11->display);
  x11->root = RootWindow(x11->display, x11->screen);
  if (!XRRQueryExtension(x11->display, &x11->randr_event_base, &x11->randr_error_base) ||
      !XRRQueryVersion(x11->display, &x11->randr_major, &x11->randr_minor)) {
    XCloseDisplay(x11->display);
    delete x11;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
  // RandR 1.5 publishes monitor objects; the provider never substitutes the older
  // output list, so a server without them reports the typed unavailable boundary.
  if (x11->randr_major < 1 || (x11->randr_major == 1 && x11->randr_minor < 5)) {
    XCloseDisplay(x11->display);
    delete x11;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  }
  x11->work_area = XInternAtom(x11->display, "_NET_WORKAREA", True);
  x11->monitor_atom = XInternAtom(x11->display, "MONITOR", False);
  XRRSelectInput(x11->display, x11->root, RRScreenChangeNotifyMask | RRCrtcChangeNotifyMask |
                                             RROutputChangeNotifyMask | RRResourceChangeNotifyMask);
  XSelectInput(x11->display, x11->root, PropertyChangeMask | StructureNotifyMask);
  g_state = &state;
  Topology topology;
  const kweb_screen_status read = ReadTopology(*x11, &topology);
  if (read != KWEB_SCREEN_STATUS_OK) {
    g_state = nullptr;
    XCloseDisplay(x11->display);
    delete x11;
    state.platform = nullptr;
    return read;
  }
  x11->thread = std::thread([x11] {
    for (;;) {
      {
        std::lock_guard<std::mutex> lock(x11->mutex);
        if (x11->stopping) return;
        while (XPending(x11->display) > 0) {
          XEvent event{};
          XNextEvent(x11->display, &event);
          const int type = event.type - x11->randr_event_base;
          const bool randr_event = type == RRScreenChangeNotify || type == RRNotify;
          const bool work_area_event = event.type == PropertyNotify && x11->work_area != None &&
                                       event.xproperty.atom == x11->work_area;
          if (randr_event) {
            XRRUpdateConfiguration(&event);
            OnX11Event(*x11);
          } else if (work_area_event) {
            OnX11Event(*x11);
          }
        }
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(50));
    }
  });
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status NativeReadTopology(State &state, Topology *topology) {
  X11State *x11 = StateOf(state);
  if (x11 == nullptr || topology == nullptr) return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  std::lock_guard<std::mutex> lock(x11->mutex);
  return ReadTopology(*x11, topology);
}

kweb_screen_status NativeReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                                    bool *display_known) {
  X11State *x11 = StateOf(state);
  if (x11 == nullptr || x == nullptr || y == nullptr || display_id == nullptr || display_known == nullptr) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(x11->mutex);
  Window root_return = None;
  Window child_return = None;
  int root_x = 0;
  int root_y = 0;
  int window_x = 0;
  int window_y = 0;
  unsigned int mask = 0;
  if (XQueryPointer(x11->display, x11->root, &root_return, &child_return, &root_x, &root_y, &window_x,
                    &window_y, &mask) == False) {
    return KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE;
  }
  *x = root_x;
  *y = root_y;
  *display_known = false;
  display_id->clear();
  Topology topology;
  if (ReadTopology(*x11, &topology) == KWEB_SCREEN_STATUS_OK) {
    for (const ScreenDisplay &display : topology.displays) {
      if (root_x >= display.left && root_x < display.left + display.width && root_y >= display.top &&
          root_y < display.top + display.height) {
        *display_id = display.id;
        *display_known = true;
        break;
      }
    }
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status NativeClose(State &state) {
  X11State *x11 = StateOf(state);
  if (x11 == nullptr) return KWEB_SCREEN_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  {
    std::lock_guard<std::mutex> lock(x11->mutex);
    x11->stopping = true;
  }
  if (x11->thread.joinable()) x11->thread.join();
  if (x11->display != nullptr) XCloseDisplay(x11->display);
  delete x11;
  state.platform = nullptr;
  return KWEB_SCREEN_STATUS_OK;
}

}  // namespace kwebshell::screen
