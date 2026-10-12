#include "screen_platform_test.h"

#if defined(__linux__)

#include "screen_tests_fixture.h"

#include <cstring>
#include <string>

/** GDK owns the compositor connection, so the test thread has nothing to pump. */
/** The real Wayland session through the compositor-reported GDK monitors. */
int RunWaylandStructureTest() {
  const int failures_before = g_failures;
  ScreenFixture fixture;
  uint64_t handle = 0;
  kweb_screen_configuration configuration = fixture.Configuration();
  const kweb_screen_status opened = kweb_screen_open(&configuration, &handle);
  if (opened != KWEB_SCREEN_STATUS_OK) {
    std::printf("SKIP: the Wayland screen session is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_screen_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "screen.linux.wayland-gdk") == 0);
  KWEB_CHECK(capabilities.session == KWEB_SCREEN_SESSION_WAYLAND);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_WORK_AREA) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_SCALE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_TOPOLOGY_EVENTS) != 0);
  // A compositor publishes no global cursor position, so the session never claims one.
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_CURSOR) == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_REFRESH_RATE) == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SCREEN_CAP_ROTATION) == 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_LOGICAL - 1u))) != 0);
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_NATIVE - 1u))) != 0);
  // Physical space stays unpublished because the compositor publishes no size.
  KWEB_CHECK((capabilities.space_bits & (1u << (KWEB_SCREEN_SPACE_PHYSICAL - 1u))) == 0);

  kweb_screen_topology_result topology{};
  KWEB_CHECK(fixture.Topology(handle, &topology) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(topology.display_count >= 1);
  const kweb_screen_display &primary = topology.displays[0];
  const std::string primary_id = ScreenFixture::IdOf(primary);
  KWEB_CHECK(!primary_id.empty());
  KWEB_CHECK(primary.width > 0 && primary.height > 0);
  KWEB_CHECK((primary.fact_bits & KWEB_SCREEN_FACT_SCALE) != 0);
  KWEB_CHECK(primary.scale_permille >= KWEB_SCREEN_MIN_SCALE_PERMILLE);
  KWEB_CHECK(primary.native_width == primary.width && primary.native_height == primary.height);
  int primaries = 0;
  for (int index = 0; index < static_cast<int>(topology.display_count); ++index) {
    if ((topology.displays[index].fact_bits & KWEB_SCREEN_FACT_PRIMARY) != 0) primaries += 1;
    KWEB_CHECK((topology.displays[index].fact_bits & KWEB_SCREEN_FACT_REFRESH_RATE) == 0);
  }
  // A compositor publishes a primary monitor or none; headless composers publish
  // none, so the fact stays absent instead of being guessed.
  KWEB_CHECK(primaries <= 1);
  if (primaries == 1) {
    KWEB_CHECK((topology.displays[0].fact_bits & KWEB_SCREEN_FACT_PRIMARY) != 0);
  }

  // The declared conversion follows the published rectangles exactly.
  kweb_screen_point_result converted{};
  KWEB_CHECK(fixture.Convert(handle, primary_id, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE,
                             primary.left + 7, primary.top + 11, &converted) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(converted.x == primary.left + 7 && converted.y == primary.top + 11);
  // Physical space is not published on this session.
  KWEB_CHECK(fixture.Convert(handle, primary_id, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_PHYSICAL, 5, 5,
                             &converted) == KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED);

  // Resolution uses the compositor's rectangle and reports nothing outside it.
  kweb_screen_id_result found{};
  KWEB_CHECK(fixture.DisplayAt(handle, primary.left + 3, primary.top + 3, KWEB_SCREEN_SPACE_LOGICAL,
                               &found) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(found.found != 0 && primary_id == std::string(found.id));
  KWEB_CHECK(fixture.DisplayAt(handle, KWEB_SCREEN_MAX_COORDINATE, KWEB_SCREEN_MAX_COORDINATE,
                               KWEB_SCREEN_SPACE_LOGICAL, &found) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(found.found == 0);

  // The cursor boundary is typed, never synthesized.
  kweb_screen_point_result cursor{};
  cursor.struct_size = sizeof(cursor);
  cursor.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_cursor(handle, &cursor) == KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE);

  // A re-read that observes no change publishes no event.
  kweb_screen_topology_result reread{};
  KWEB_CHECK(fixture.Topology(handle, &reread) == KWEB_SCREEN_STATUS_OK);
  kweb_screen_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(!fixture.AwaitEvent(handle, &event, 300));

  KWEB_CHECK(kweb_screen_close(handle) == KWEB_SCREEN_STATUS_OK);
  return g_failures - failures_before;
}

#endif
