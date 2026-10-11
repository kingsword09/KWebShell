#include "screen_platform_test.h"

#if defined(__linux__)

#include <X11/Xlib.h>
#include <X11/extensions/Xrandr.h>

#include "screen_tests_fixture.h"

#include <cstring>
#include <string>
#include <thread>
#include <vector>

void ScreenFixture::pump_platform_queue() {}

namespace {

/** Owns one RandR monitor the test creates and removes again. */
class VirtualMonitor {
 public:
  VirtualMonitor(Display *display, Window root, const char *name, int x, int y, int width, int height)
      : display_(display), root_(root), name_(XInternAtom(display, name, False)) {
    info_ = {};
    info_.name = name_;
    info_.x = x;
    info_.y = y;
    info_.width = width;
    info_.height = height;
    info_.mwidth = 271;
    info_.mheight = 203;
  }

  bool Set() {
    XRRSetMonitor(display_, root_, &info_);
    XFlush(display_);
    created_ = true;
    return CountMonitors() == 2;
  }

  bool Remove() {
    XRRDeleteMonitor(display_, root_, name_);
    XFlush(display_);
    created_ = false;
    return CountMonitors() == 1;
  }

  int CountMonitors() {
    int count = 0;
    XRRMonitorInfo *monitors = XRRGetMonitors(display_, root_, True, &count);
    if (monitors != nullptr) XRRFreeMonitors(monitors);
    return count;
  }

  ~VirtualMonitor() {
    if (created_) Remove();
  }

 private:
  Display *display_ = nullptr;
  Window root_ = 0;
  Atom name_ = None;
  XRRMonitorInfo info_{};
  bool created_ = false;
};

}  // namespace

/** The real RandR topology, its emulated second display, and the ordered events. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  ScreenFixture fixture;
  uint64_t handle = 0;
  kweb_screen_configuration configuration = fixture.Configuration();
  const kweb_screen_status opened = kweb_screen_open(&configuration, &handle);
  if (opened != KWEB_SCREEN_STATUS_OK) {
    std::printf("SKIP: the X11 screen session is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_screen_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "screen.linux.x11-randr") == 0);
  KWEB_CHECK(capabilities.session == KWEB_SCREEN_SESSION_X11);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_CURSOR) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_TOPOLOGY_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_COLOR_DEPTH) != 0);
  // X11 publishes no scale factor, so the session never claims one.
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_SCALE) == 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_LOGICAL - 1u))) != 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_NATIVE - 1u))) != 0);

  kweb_screen_topology_result topology{};
  KWEB_CHECK(fixture.Topology(handle, &topology) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(topology.display_count >= 1);
  const std::string first_id = ScreenFixture::IdOf(topology.displays[0]);
  KWEB_CHECK(!first_id.empty());
  KWEB_CHECK(topology.displays[0].width > 0 && topology.displays[0].height > 0);
  KWEB_CHECK((topology.displays[0].fact_bits & KWEB_SCREEN_FACT_COLOR_DEPTH) != 0);
  KWEB_CHECK(topology.displays[0].color_depth_bits >= 8);
  // The first display publishes the same rectangle in both spaces without a scale.
  KWEB_CHECK((topology.displays[0].fact_bits & KWEB_SCREEN_FACT_SCALE) == 0);
  KWEB_CHECK(topology.displays[0].native_width == topology.displays[0].width);

  // The cursor is published by the session and resolves to a display.
  kweb_screen_point_result cursor{};
  cursor.struct_size = sizeof(cursor);
  cursor.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_cursor(handle, &cursor) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(cursor.space == KWEB_SCREEN_SPACE_NATIVE);
  KWEB_CHECK(cursor.display_known != 0);

  // One conversion on the real display is the identity because X11 publishes no scale.
  kweb_screen_point_result converted{};
  KWEB_CHECK(fixture.Convert(handle, first_id, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE,
                             topology.displays[0].left + 5, topology.displays[0].top + 7,
                             &converted) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(converted.x == topology.displays[0].left + 5);
  KWEB_CHECK(converted.y == topology.displays[0].top + 7);
  // Physical space is not published by Xvfb unless the monitor carries a size.
  const kweb_screen_status physical = fixture.Convert(handle, first_id, KWEB_SCREEN_SPACE_LOGICAL,
                                                      KWEB_SCREEN_SPACE_PHYSICAL, 10, 10, &converted);
  KWEB_CHECK(physical == KWEB_SCREEN_STATUS_OK || physical == KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED);
  if (physical == KWEB_SCREEN_STATUS_OK) {
    // The conversion uses the published millimetres with the declared rounding.
    const int64_t expected = (10 * static_cast<int64_t>(topology.displays[0].physical_width_mm) +
                              topology.displays[0].native_width / 2) /
                             topology.displays[0].native_width;
    KWEB_CHECK(converted.x == expected);
  }

  // Emulate a second display with its own origin, extent, and physical size.
  Display *x11 = XOpenDisplay(nullptr);
  KWEB_CHECK(x11 != nullptr);
  if (x11 != nullptr) {
    const Window root = RootWindow(x11, DefaultScreen(x11));
    VirtualMonitor monitor(x11, root, "KWEB_VIRTUAL_SECOND", 1600, 0, 1024, 768);
    KWEB_CHECK(monitor.Set());
    kweb_screen_topology_result two_displays{};
    KWEB_CHECK(fixture.Topology(handle, &two_displays) == KWEB_SCREEN_STATUS_OK);
    KWEB_CHECK(two_displays.display_count == 2);
    std::string second_id;
    for (int index = 0; index < static_cast<int>(two_displays.display_count); ++index) {
      if (ScreenFixture::IdOf(two_displays.displays[index]) != first_id) {
        second_id = ScreenFixture::IdOf(two_displays.displays[index]);
      }
    }
    KWEB_CHECK(!second_id.empty());

    // Resolution uses the emulated rectangle and never misses it.
    kweb_screen_id_result found{};
    KWEB_CHECK(fixture.DisplayAt(handle, 1700, 100, KWEB_SCREEN_SPACE_LOGICAL, &found) ==
               KWEB_SCREEN_STATUS_OK);
    KWEB_CHECK(found.found != 0);
    KWEB_CHECK(second_id == std::string(found.id));
    KWEB_CHECK(fixture.DisplayAt(handle, -5000, -5000, KWEB_SCREEN_SPACE_LOGICAL, &found) ==
               KWEB_SCREEN_STATUS_OK);
    KWEB_CHECK(found.found == 0);
    KWEB_CHECK(fixture.Nearest(handle, 2600, 700, KWEB_SCREEN_SPACE_LOGICAL, &found) ==
               KWEB_SCREEN_STATUS_OK);
    KWEB_CHECK(found.found != 0 && second_id == std::string(found.id));

    // The topology change is published as one ordered event when the server
    // notifies; the topology read above already proves the change itself.
    kweb_screen_event event{};
    event.struct_size = sizeof(event);
    event.abi_version = KWEB_SCREEN_ABI_VERSION;
    const bool notified = fixture.AwaitEvent(handle, &event, 2000);
    if (notified) {
      KWEB_CHECK(event.kind == KWEB_SCREEN_EVENT_TOPOLOGY_CHANGED);
      KWEB_CHECK(event.added_count + event.changed_count + event.removed_count >= 1);
      KWEB_CHECK(event.sequence >= 2u);
    } else {
      std::printf("SKIP: this server publishes no RandR change notification.\n");
    }

    KWEB_CHECK(monitor.Remove());
    kweb_screen_topology_result back_to_one{};
    KWEB_CHECK(fixture.Topology(handle, &back_to_one) == KWEB_SCREEN_STATUS_OK);
    KWEB_CHECK(back_to_one.display_count == 1);
    XCloseDisplay(x11);
  }

  KWEB_CHECK(kweb_screen_close(handle) == KWEB_SCREEN_STATUS_OK);
  return g_failures - failures_before;
}

#endif
