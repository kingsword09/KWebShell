#include "shortcuts_platform_test.h"

#if defined(__linux__)

#include "shortcuts_tests_fixture.h"

#include <cstring>

/** A Wayland session declares no global-hotkey facility and never falls back. */
int RunWaylandStructureTest() {
  const int failures_before = g_failures;
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  const kweb_shortcuts_status opened = kweb_shortcuts_open(&configuration, &handle);
  if (opened != KWEB_SHORTCUTS_STATUS_OK) {
    std::printf("SKIP: the Wayland shortcut session is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_shortcuts_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "shortcuts.linux.wayland-unavailable") == 0);
  KWEB_CHECK(capabilities.capability_bits == 0u);
  KWEB_CHECK(capabilities.media_key_bits == 0u);
  kweb_shortcuts_registration_result result{};
  auto request = fixture.Request("copy", "text.copy",
                                 KWEB_SHORTCUTS_MOD_CONTROL | KWEB_SHORTCUTS_MOD_ALT,
                                 KWEB_SHORTCUTS_KEY_NAMED, 'A');
  KWEB_CHECK(fixture.Register(handle, request, &result) == KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE);
  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
