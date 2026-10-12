#include "screen_internal.h"

#if !defined(__APPLE__)
#error "The macOS screen provider must only be compiled on macOS."
#endif

#import <AppKit/AppKit.h>
#import <CoreGraphics/CoreGraphics.h>
#import <Foundation/Foundation.h>
#include <dispatch/dispatch.h>

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace {

using ScreenDisplay = kwebshell::screen::Display;
using kwebshell::screen::State;
using kwebshell::screen::Topology;

constexpr char kProviderId[] = "screen.macos.screen-cg";

/** The observer tokens one opened service owns. */
struct MacState {
  id screen_parameters_observer = nil;
  bool reconfiguration_registered = false;
};

State *g_state = nullptr;

MacState *StateOf(State &state) { return static_cast<MacState *>(state.platform); }

/** AppKit owns the screen list, so every read runs on the main queue. */
template <typename Block>
void OnAppKitThread(Block block) {
  if ([NSThread isMainThread]) {
    block();
    return;
  }
  dispatch_sync(dispatch_get_main_queue(), ^{
    block();
  });
}

std::string DisplayId(CGDirectDisplayID display) {
  return std::string("display.") + std::to_string(static_cast<unsigned long long>(display));
}

uint32_t RotationFact(CGDirectDisplayID display) {
  const double degrees = CGDisplayRotation(display);
  const int rounded = static_cast<int>(std::lround(degrees)) % 360;
  switch (rounded) {
    case 0:
      return KWEB_SCREEN_ROTATION_NONE;
    case 90:
      return KWEB_SCREEN_ROTATION_QUAD_1;
    case 180:
      return KWEB_SCREEN_ROTATION_QUAD_2;
    case 270:
      return KWEB_SCREEN_ROTATION_QUAD_3;
    default:
      return 0;
  }
}

uint32_t Permille(CGFloat scale) {
  if (!(scale > 0.0)) return 0;
  const long permille = std::lround(scale * 1000.0);
  if (permille < static_cast<long>(KWEB_SCREEN_MIN_SCALE_PERMILLE) ||
      permille > static_cast<long>(KWEB_SCREEN_MAX_SCALE_PERMILLE)) {
    return 0;
  }
  return static_cast<uint32_t>(permille);
}

kweb_screen_status ReadTopology(Topology *topology) {
  kweb_screen_status status = KWEB_SCREEN_STATUS_OK;
  OnAppKitThread([&status, topology] {
    NSArray<NSScreen *> *screens = NSScreen.screens;
    if (screens == nil || screens.count == 0) {
      status = KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
      return;
    }
    // AppKit reports a bottom-left origin; the published desktop coordinates use
    // the top-left origin every other target uses, measured against the primary
    // screen's height.
    NSScreen *primary = screens.firstObject;
    const CGFloat primary_height = primary.frame.size.height;
    topology->provider_id = kProviderId;
    topology->session = KWEB_SCREEN_SESSION_MACOS;
    topology->capability_bits = KWEB_SCREEN_CAP_WORK_AREA | KWEB_SCREEN_CAP_SCALE |
                                KWEB_SCREEN_CAP_CURSOR | KWEB_SCREEN_CAP_TOPOLOGY_EVENTS |
                                KWEB_SCREEN_CAP_MULTI_DISPLAY | KWEB_SCREEN_CAP_COLOR_DEPTH;
    topology->displays.clear();
    for (NSUInteger index = 0; index < screens.count && index < KWEB_SCREEN_MAX_DISPLAYS; ++index) {
      NSScreen *screen = screens[index];
      NSNumber *number = screen.deviceDescription[@"NSScreenNumber"];
      if (number == nil) continue;
      const CGDirectDisplayID display_id = static_cast<CGDirectDisplayID>(number.unsignedIntValue);
      ScreenDisplay display;
      display.id = DisplayId(display_id);
      const NSString *name = screen.localizedName;
      if (name != nil && name.length > 0) {
        const std::string candidate(name.UTF8String);
        // A name beyond the published bound stays absent instead of being truncated.
        if (candidate.size() <= KWEB_SCREEN_MAX_NAME) display.name = candidate;
      }
      const NSRect frame = screen.frame;
      display.left = static_cast<int32_t>(std::lround(frame.origin.x));
      display.top = static_cast<int32_t>(std::lround(primary_height - frame.origin.y - frame.size.height));
      display.width = static_cast<int32_t>(std::lround(frame.size.width));
      display.height = static_cast<int32_t>(std::lround(frame.size.height));
      const uint32_t scale_permille = Permille(screen.backingScaleFactor);
      // Native geometry carries the same rectangle in the display's own device
      // pixels, so the ratio between the two rectangles is the published scale.
      const int64_t native_left = static_cast<int64_t>(display.left) * (scale_permille == 0 ? 1000 : scale_permille) / 1000;
      const int64_t native_top = static_cast<int64_t>(display.top) * (scale_permille == 0 ? 1000 : scale_permille) / 1000;
      const size_t pixel_width = CGDisplayPixelsWide(display_id);
      const size_t pixel_height = CGDisplayPixelsHigh(display_id);
      display.native_left = static_cast<int32_t>(native_left);
      display.native_top = static_cast<int32_t>(native_top);
      display.native_width = pixel_width > 0 ? static_cast<int32_t>(pixel_width) : display.width;
      display.native_height = pixel_height > 0 ? static_cast<int32_t>(pixel_height) : display.height;
      if (scale_permille != 0) {
        display.scale_permille = scale_permille;
        display.fact_bits |= KWEB_SCREEN_FACT_SCALE;
      }
      const NSRect visible = screen.visibleFrame;
      const int32_t work_left = static_cast<int32_t>(std::lround(visible.origin.x));
      const int32_t work_top = static_cast<int32_t>(std::lround(primary_height - visible.origin.y - visible.size.height));
      const int32_t work_width = static_cast<int32_t>(std::lround(visible.size.width));
      const int32_t work_height = static_cast<int32_t>(std::lround(visible.size.height));
      if (work_width > 0 && work_height > 0) {
        display.work_left = work_left;
        display.work_top = work_top;
        display.work_width = work_width;
        display.work_height = work_height;
        display.fact_bits |= KWEB_SCREEN_FACT_WORK_AREA;
      }
      const uint32_t rotation = RotationFact(display_id);
      if (rotation != 0) {
        display.rotation = rotation;
        display.fact_bits |= KWEB_SCREEN_FACT_ROTATION;
        topology->capability_bits |= KWEB_SCREEN_CAP_ROTATION;
      }
      CGDisplayModeRef mode = CGDisplayCopyDisplayMode(display_id);
      if (mode != nullptr) {
        const double refresh = CGDisplayModeGetRefreshRate(mode);
        if (refresh > 0.0 && refresh <= static_cast<double>(KWEB_SCREEN_MAX_REFRESH_HERTZ)) {
          display.refresh_hertz = static_cast<uint32_t>(std::lround(refresh));
          display.fact_bits |= KWEB_SCREEN_FACT_REFRESH_RATE;
          topology->capability_bits |= KWEB_SCREEN_CAP_REFRESH_RATE;
        }
        CGDisplayModeRelease(mode);
      }
      // AppKit publishes the window depth of the screen this display backs.
      const int depth = NSBitsPerPixelFromDepth(screen.depth);
      if (depth > 0 && depth <= static_cast<int>(KWEB_SCREEN_MAX_COLOR_DEPTH)) {
        display.color_depth_bits = static_cast<uint32_t>(depth);
        display.fact_bits |= KWEB_SCREEN_FACT_COLOR_DEPTH;
      }
      const CGSize millimetres = CGDisplayScreenSize(display_id);
      if (millimetres.width > 0.0 && millimetres.height > 0.0) {
        display.physical_width_mm = static_cast<uint32_t>(std::lround(millimetres.width));
        display.physical_height_mm = static_cast<uint32_t>(std::lround(millimetres.height));
        display.fact_bits |= KWEB_SCREEN_FACT_PHYSICAL_SIZE;
        topology->capability_bits |= KWEB_SCREEN_CAP_PHYSICAL_SIZE;
      }
      // The menu bar lives on the first screen, which is the declared primary.
      if (index == 0) display.fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
      topology->displays.push_back(display);
    }
    if (topology->displays.empty()) status = KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  });
  return status;
}

void PublishCurrentTopology() {
  if (g_state == nullptr) return;
  Topology topology;
  if (ReadTopology(&topology) != KWEB_SCREEN_STATUS_OK) return;
  if (!kwebshell::screen::ValidTopology(topology)) {
    kwebshell::screen::PushFailed(*g_state, "screen.topology-invalid");
    return;
  }
  kwebshell::screen::PublishTopology(*g_state, topology);
}

void OnDisplayReconfigured(CGDirectDisplayID display, CGDisplayChangeSummaryFlags flags, void *user_info) {
  (void)display;
  (void)user_info;
  // Both the begin and the end of a reconfiguration arrive; only the settled pass
  // re-reads the topology.
  if ((flags & kCGDisplayBeginConfigurationFlag) != 0) return;
  PublishCurrentTopology();
}

}  // namespace

namespace kwebshell::screen {

const char *ProviderId() { return kProviderId; }

kweb_screen_status NativeOpen(State &state) {
  auto *mac = new MacState();
  state.platform = mac;
  OnAppKitThread([mac] {
    [NSApplication sharedApplication];
    mac->screen_parameters_observer = [NSNotificationCenter.defaultCenter
        addObserverForName:NSApplicationDidChangeScreenParametersNotification
                    object:nil
                     queue:NSOperationQueue.mainQueue
                usingBlock:^(NSNotification *note) {
                  (void)note;
                  PublishCurrentTopology();
                }];
    mac->reconfiguration_registered =
        CGDisplayRegisterReconfigurationCallback(OnDisplayReconfigured, nullptr) == kCGErrorSuccess;
  });
  g_state = &state;
  Topology topology;
  const kweb_screen_status status = ReadTopology(&topology);
  if (status != KWEB_SCREEN_STATUS_OK) {
    NativeClose(state);
    return status;
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status NativeReadTopology(State &state, Topology *topology) {
  if (StateOf(state) == nullptr || topology == nullptr) return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  return ReadTopology(topology);
}

kweb_screen_status NativeReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                                    bool *display_known) {
  if (StateOf(state) == nullptr || x == nullptr || y == nullptr || display_id == nullptr ||
      display_known == nullptr) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  kweb_screen_status status = KWEB_SCREEN_STATUS_OK;
  OnAppKitThread([&status, x, y, display_id, display_known] {
    // CoreGraphics reports the cursor in top-left desktop coordinates.
    CGEventRef event = CGEventCreate(nullptr);
    if (event == nullptr) {
      status = KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE;
      return;
    }
    const CGPoint location = CGEventGetLocation(event);
    CFRelease(event);
    *x = static_cast<int32_t>(std::lround(location.x));
    *y = static_cast<int32_t>(std::lround(location.y));
    *display_known = false;
    display_id->clear();
    CGDirectDisplayID under_cursor = 0;
    uint32_t matches = 0;
    if (CGGetDisplaysWithPoint(location, 1, &under_cursor, &matches) == kCGErrorSuccess && matches > 0) {
      *display_id = DisplayId(under_cursor);
      *display_known = true;
    }
  });
  return status;
}

kweb_screen_status NativeClose(State &state) {
  MacState *mac = StateOf(state);
  if (mac == nullptr) return KWEB_SCREEN_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  OnAppKitThread([mac] {
    if (mac->screen_parameters_observer != nil) {
      [NSNotificationCenter.defaultCenter removeObserver:mac->screen_parameters_observer];
      mac->screen_parameters_observer = nil;
    }
    if (mac->reconfiguration_registered) {
      CGDisplayRemoveReconfigurationCallback(OnDisplayReconfigured, nullptr);
      mac->reconfiguration_registered = false;
    }
  });
  delete mac;
  state.platform = nullptr;
  return KWEB_SCREEN_STATUS_OK;
}

}  // namespace kwebshell::screen
