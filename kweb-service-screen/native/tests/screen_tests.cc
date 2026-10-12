#include "screen_internal.h"
#include "screen_platform_test.h"
#include "screen_tests_fixture.h"

#include <cstring>
#include <vector>

namespace {

using kwebshell::screen::ConvertPoint;
using kwebshell::screen::Display;
using kwebshell::screen::Topology;

/** One synthetic display for the portable conversion cases. */
Display Synthetic(const std::string &id, int32_t left, int32_t top, int32_t width, int32_t height,
                  int32_t native_width, int32_t native_height, uint32_t scale_permille,
                  uint32_t physical_width_mm, uint32_t physical_height_mm) {
  Display display;
  display.id = id;
  display.name = id;
  display.left = left;
  display.top = top;
  display.width = width;
  display.height = height;
  display.native_left = left;
  display.native_top = top;
  display.native_width = native_width;
  display.native_height = native_height;
  if (scale_permille != 0) {
    display.scale_permille = scale_permille;
    display.fact_bits |= KWEB_SCREEN_FACT_SCALE;
  }
  if (physical_width_mm != 0) {
    display.physical_width_mm = physical_width_mm;
    display.physical_height_mm = physical_height_mm;
    display.fact_bits |= KWEB_SCREEN_FACT_PHYSICAL_SIZE;
  }
  return display;
}

Topology SyntheticTopology(std::vector<Display> displays) {
  Topology topology;
  topology.provider_id = "screen.test";
  topology.session = KWEB_SCREEN_SESSION_X11;
  topology.capability_bits = KWEB_SCREEN_CAP_MULTI_DISPLAY | KWEB_SCREEN_CAP_CURSOR;
  topology.displays = std::move(displays);
  return topology;
}

void PortableConversionsAreExact() {
  const Display logical_only = Synthetic("display.plain", -1920, -200, 1920, 1080, 1920, 1080, 0, 0, 0);
  const Display fractional =
      Synthetic("display.fractional", 1600, 0, 1280, 1024, 1920, 1536, 1500, 340, 272);
  const Display doubled = Synthetic("display.doubled", 0, 0, 800, 600, 1600, 1200, 2000, 0, 0);
  const Topology topology = SyntheticTopology({logical_only, fractional, doubled});
  int32_t x = 0;
  int32_t y = 0;

  // A session without a scale publishes the same rectangle, so the conversion is
  // the identity and never invents a factor.
  KWEB_CHECK(ConvertPoint(topology, logical_only, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE,
                          -1900, -100, &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == -1900 && y == -100);

  // A fractional scale converts through device pixels exactly.
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE, 1600,
                          0, &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 1600 && y == 0);
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE, 1700,
                          100, &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 1750 && y == 150);
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_NATIVE, KWEB_SCREEN_SPACE_LOGICAL, 1750,
                          150, &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 1700 && y == 100);

  // Physical space is millimetres from the display's own corner.
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_PHYSICAL, KWEB_SCREEN_SPACE_NATIVE, 0, 0,
                          &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 1600 && y == 0);
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_PHYSICAL, KWEB_SCREEN_SPACE_NATIVE, 340,
                          272, &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 1600 + 1920 && y == 1536);
  KWEB_CHECK(ConvertPoint(topology, doubled, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE, 400, 300,
                          &x, &y) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(x == 800 && y == 600);

  // A display without a published physical size cannot be converted into it.
  KWEB_CHECK(ConvertPoint(topology, doubled, KWEB_SCREEN_SPACE_NATIVE, KWEB_SCREEN_SPACE_PHYSICAL, 10, 10, &x,
                          &y) == KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED);
  // An undeclared space fails typed instead of guessing.
  const Topology no_physical = SyntheticTopology({doubled});
  KWEB_CHECK(ConvertPoint(no_physical, doubled, KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_PHYSICAL, 10, 10,
                          &x, &y) == KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED);
  KWEB_CHECK(ConvertPoint(topology, fractional, KWEB_SCREEN_SPACE_LOGICAL, 99u, 10, 10, &x, &y) ==
             KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
}

void PortableValidationRejectsInconsistentReads() {
  Topology topology = SyntheticTopology({Synthetic("display.ok", 0, 0, 100, 100, 100, 100, 0, 0, 0)});
  KWEB_CHECK(kwebshell::screen::ValidTopology(topology));

  Topology duplicate = SyntheticTopology({Synthetic("display.same", 0, 0, 100, 100, 100, 100, 0, 0, 0),
                                          Synthetic("display.same", 100, 0, 100, 100, 100, 100, 0, 0, 0)});
  KWEB_CHECK(!kwebshell::screen::ValidTopology(duplicate));

  Topology two_primaries = SyntheticTopology({Synthetic("display.one", 0, 0, 100, 100, 100, 100, 0, 0, 0),
                                              Synthetic("display.two", 100, 0, 100, 100, 100, 100, 0, 0, 0)});
  two_primaries.displays[0].fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
  two_primaries.displays[1].fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
  KWEB_CHECK(!kwebshell::screen::ValidTopology(two_primaries));

  Topology escaping_work_area =
      SyntheticTopology({Synthetic("display.work", 0, 0, 100, 100, 100, 100, 0, 0, 0)});
  escaping_work_area.displays[0].work_left = 0;
  escaping_work_area.displays[0].work_top = 0;
  escaping_work_area.displays[0].work_width = 200;
  escaping_work_area.displays[0].work_height = 100;
  escaping_work_area.displays[0].fact_bits |= KWEB_SCREEN_FACT_WORK_AREA;
  KWEB_CHECK(!kwebshell::screen::ValidTopology(escaping_work_area));

  // A fact without its bit is absent, and the capability report follows the facts.
  Topology scaled = SyntheticTopology({Synthetic("display.scale", 0, 0, 100, 100, 150, 150, 1500, 0, 0)});
  KWEB_CHECK((kwebshell::screen::EffectiveCapabilityBits(scaled) & KWEB_SCREEN_CAP_SCALE) != 0);
  scaled.displays[0].scale_permille = 0;
  scaled.displays[0].fact_bits &= ~KWEB_SCREEN_FACT_SCALE;
  KWEB_CHECK((kwebshell::screen::EffectiveCapabilityBits(scaled) & KWEB_SCREEN_CAP_SCALE) == 0);
}

void ConfigurationValidationRejectsBadHeaders() {
  ScreenFixture fixture;
  uint64_t handle = 0;
  KWEB_CHECK(kweb_screen_open(nullptr, &handle) == KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
  kweb_screen_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_screen_open(&configuration, nullptr) == KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.struct_size = sizeof(configuration) - 1u;
  KWEB_CHECK(kweb_screen_open(&configuration, &handle) == KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.abi_version = KWEB_SCREEN_ABI_VERSION + 1u;
  KWEB_CHECK(kweb_screen_open(&configuration, &handle) == KWEB_SCREEN_STATUS_ABI_MISMATCH);
  configuration = fixture.Configuration();
  configuration.application_id = kweb_screen_string{nullptr, 0};
  KWEB_CHECK(kweb_screen_open(&configuration, &handle) == KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
}

void ArgumentsAndClosedHandlesAreTyped() {
  ScreenFixture fixture;
  uint64_t handle = 0;
  kweb_screen_configuration configuration = fixture.Configuration();
  const kweb_screen_status opened = kweb_screen_open(&configuration, &handle);
  if (opened == KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE ||
      opened == KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE) {
    std::printf("SKIP: the declared screen session is unavailable (status %u).\n", opened);
    return;
  }
  KWEB_CHECK(opened == KWEB_SCREEN_STATUS_OK);
  uint64_t second = 0;
  KWEB_CHECK(kweb_screen_open(&configuration, &second) == KWEB_SCREEN_STATUS_ALREADY_OPEN);

  kweb_screen_id_result id_result{};
  id_result.struct_size = sizeof(id_result);
  id_result.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_display_at(handle, 0, 0, 99u, &id_result) == KWEB_SCREEN_STATUS_INVALID_ARGUMENT);
  KWEB_CHECK(kweb_screen_nearest(handle, KWEB_SCREEN_MAX_COORDINATE + 1, 0, KWEB_SCREEN_SPACE_LOGICAL,
                                &id_result) == KWEB_SCREEN_STATUS_POINT_INVALID);

  kweb_screen_point_result point{};
  point.struct_size = sizeof(point);
  point.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(fixture.Convert(handle, "display.absent", KWEB_SCREEN_SPACE_LOGICAL, KWEB_SCREEN_SPACE_NATIVE, 0,
                             0, &point) == KWEB_SCREEN_STATUS_DISPLAY_UNKNOWN);

  // A topology buffer that cannot hold the session's displays reports the size.
  kweb_screen_display one_display{};
  kweb_screen_topology_result small{};
  small.struct_size = sizeof(small);
  small.abi_version = KWEB_SCREEN_ABI_VERSION;
  small.displays = &one_display;
  small.capacity = 1;
  const kweb_screen_status small_status = kweb_screen_topology(handle, &small);
  KWEB_CHECK(small_status == KWEB_SCREEN_STATUS_OK || small_status == KWEB_SCREEN_STATUS_BUFFER_TOO_SMALL);
  if (small_status == KWEB_SCREEN_STATUS_BUFFER_TOO_SMALL) KWEB_CHECK(small.display_count > 1);

  KWEB_CHECK(kweb_screen_close(handle) == KWEB_SCREEN_STATUS_OK);
  KWEB_CHECK(kweb_screen_close(handle) == KWEB_SCREEN_STATUS_OWNER_CLOSED);
  kweb_screen_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_capabilities(handle, &capabilities) == KWEB_SCREEN_STATUS_OWNER_CLOSED);
  kweb_screen_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_SCREEN_ABI_VERSION;
  KWEB_CHECK(kweb_screen_poll_event(handle, &event) == KWEB_SCREEN_STATUS_OWNER_CLOSED);
}

}  // namespace

int main() {
  PortableConversionsAreExact();
  PortableValidationRejectsInconsistentReads();
  ConfigurationValidationRejectsBadHeaders();
  ArgumentsAndClosedHandlesAreTyped();
  std::printf("kweb screen portable checks: %d failures.\n", g_failures);
  const int platform_failures = RunPlatformNativeStructureTest();
  if (platform_failures > 0) {
    std::printf("kweb screen provider checks: %d failures.\n", platform_failures);
  }
  if (g_failures == 0) {
    std::printf("kweb screen native checks passed.\n");
    return 0;
  }
  std::printf("%d native checks failed.\n", g_failures);
  return 1;
}
