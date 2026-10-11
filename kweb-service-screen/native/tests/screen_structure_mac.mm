#include "screen_platform_test.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>

#include "screen_tests_fixture.h"

#include <cstring>
#include <string>

/** The AppKit provider owns the main queue, so the test thread has nothing to pump. */
void ScreenFixture::pump_platform_queue() {}

/** The real AppKit and CoreGraphics facts, and the conversion they imply. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  ScreenFixture fixture;
  uint64_t handle = 0;
  kweb_screen_configuration configuration = fixture.Configuration();
  const kweb_screen_status opened = kweb_screen_open(&configuration, &handle);
  if (opened != KWEB_SCREEN_STATUS_OK) {
    std::printf("SKIP: the AppKit screen host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_screen_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "screen.macos.screen-cg") == 0);
  KWEB_CHECK(capabilities.session == KWEB_SCREEN_SESSION_MACOS);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_WORK_AREA) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_SCALE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_CURSOR) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_COLOR_DEPTH) != 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_LOGICAL - 1u))) != 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_NATIVE - 1u))) != 0);

  kweb_screen_topology_result topology{};
  KWEB_CHECK(fixture.Topology(handle, &topology) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(topology.display_count >= 1);
  int primaries = 0;
  for (int index = 0; index < static_cast<int>(topology.display_count); ++index) {
    const kweb_screen_display &display = topology.displays[index];
    KWEB_CHECK(display.width > 0 && display.height > 0);
    KWEB_CHECK(display.native_width > 0 && display.native_height > 0);
    KWEB_CHECK((display.fact_bits & KWEB_SCREEN_FACT_COLOR_DEPTH) != 0);
    if ((display.fact_bits & KWEB_SCREEN_FACT_PRIMARY) != 0) primaries += 1;
    if ((display.fact_bits & KWEB_SCREEN_FACT_WORK_AREA) != 0) {
      KWEB_CHECK(display.work_left >= display.left);
      KWEB_CHECK(display.work_top >= display.top);
      KWEB_CHECK(display.work_left + display.work_width <= display.left + display.width);
      KWEB_CHECK(display.work_top + display.work_height <= display.top + display.height);
    }
    if ((display.fact_bits & KWEB_SCREEN_FACT_SCALE) != 0) {
      // The published scale and the two published rectangles agree.
      const int64_t expected_width =
          (static_cast<int64_t>(display.width) * display.scale_permille + 500) / 1000;
      KWEB_CHECK(display.native_width == expected_width);
      KWEB_CHECK(display.scale_permille >= KWEB_SCREEN_MIN_SCALE_PERMILLE);
    }
  }
  KWEB_CHECK(primaries == 1);

  const kweb_screen_display &primary = topology.displays[0];
  const std::string primary_id = ScreenFixture::IdOf(primary);
  // A conversion inside the primary display follows its published rectangles.
  kweb_screen_point_result converted{};
  const int32_t sample_x = primary.left + primary.width / 2;
  const int32_t sample_y = primary.top + primary.height / 3;
  KWEB_CHECK(fixture.Convert(handle, primary_id, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE,
                             sample_x, sample_y, &converted) == KWEB_SCREEN_STATUS_OK);
  const int64_t expected_x =
      (static_cast<int64_t>(sample_x - primary.left) * primary.native_width + primary.width / 2) /
          primary.width +
      primary.native_left;
  const int64_t expected_y =
      (static_cast<int64_t>(sample_y - primary.top) * primary.native_height + primary.height / 2) /
          primary.height +
      primary.native_top;
  KWEB_CHECK(converted.x == expected_x);
  KWEB_CHECK(converted.y == expected_y);
  // The reverse conversion returns the original point exactly for this sample.
  kweb_screen_point_result restored{};
  KWEB_CHECK(fixture.Convert(handle, primary_id, KWEB_SCREEN_SPACE_NATIVE, KWEB_SCREEN_SPACE_LOGICAL,
                             converted.x, converted.y, &restored) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(restored.x == sample_x);
  KWEB_CHECK(restored.y == sample_y);
  // Physical space is published for a real display.
  const kweb_screen_status physical = fixture.Convert(handle, primary_id, KWEB_SCREEN_SPACE_LOGICAL,
                                                      KWEB_SCREEN_SPACE_PHYSICAL, sample_x, sample_y, &converted);
  KWEB_CHECK(physical == KWEB_SCREEN_STATUS_OK || physical == KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED);

  // Resolution and the cursor use the real desktop.
  kweb_screen_id_result found{};
  KWEB_CHECK(fixture.DisplayAt(handle, sample_x, sample_y, KWEB_SCREEN_SPACE_LOGICAL, &found) ==
             KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(found.found != 0);
  KWEB_CHECK(primary_id == std::string(found.id));
  KWEB_CHECK(fixture.DisplayAt(handle, KWEB_SCREEN_MAX_COORDINATE, KWEB_SCREEN_MAX_COORDINATE,
                               KWEB_SCREEN_SPACE_LOGICAL, &found) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(found.found == 0);
  kweb_screen_point_result cursor{};
  cursor.struct_size = sizeof(cursor);
  cursor.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_cursor(handle, &cursor) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(cursor.space == KWEB_SCREEN_SPACE_NATIVE);
  KWEB_CHECK(cursor.display_known != 0);

  // A screen-parameter notification that changes nothing publishes no event.
  [NSNotificationCenter.defaultCenter postNotificationName:NSApplicationDidChangeScreenParametersNotification
                                                    object:nil];
  kweb_screen_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_SCREEN_ABI_VERSION;
  const bool spurious = fixture.AwaitEvent(handle, &event, 500);
  KWEB_CHECK(!spurious);

  // Removing a display identifier the session never published is typed.
  KWEB_CHECK(fixture.Convert(handle, "display.999999", KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE,
                             0, 0, &converted) == KWEB_SCREEN_STATUS_DISPLAY_UNKNOWN);

  KWEB_CHECK(kweb_screen_close(handle) == KWEB_SCREEN_STATUS_OK);
  return g_failures - failures_before;
}

#endif
