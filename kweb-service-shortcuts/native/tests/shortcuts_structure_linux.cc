#include "shortcuts_platform_test.h"

#include "shortcuts_tests_fixture.h"

#if defined(__linux__)

#include <cstdlib>

int RunX11StructureTest();
int RunWaylandStructureTest();

/** Neither Linux provider needs the test thread to pump platform work. */
void ShortcutsFixture::pump_platform_queue() {}

/** Runs the structure test of the session this environment declares. */
int RunPlatformNativeStructureTest() {
  const char *wayland = std::getenv("WAYLAND_DISPLAY");
  if (wayland != nullptr && wayland[0] != '\0') return RunWaylandStructureTest();
  return RunX11StructureTest();
}

#endif
