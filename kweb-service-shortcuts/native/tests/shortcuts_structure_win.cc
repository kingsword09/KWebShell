#include "shortcuts_platform_test.h"

#if defined(_WIN32)

#include "shortcuts_tests_fixture.h"

#include <cstring>
#include <string>

/** The Win32 message loop runs on the provider thread; the test thread is idle. */
void ShortcutsFixture::pump_platform_queue() {}

/** The real Win32 registration, conflict, and capability facts. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  const kweb_shortcuts_status opened = kweb_shortcuts_open(&configuration, &handle);
  if (opened != KWEB_SHORTCUTS_STATUS_OK) {
    std::printf("SKIP: the Win32 shortcut host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_shortcuts_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "shortcuts.windows.register-hotkey") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_MEDIA_KEYS) != 0);
  KWEB_CHECK(capabilities.media_key_bits == KWEB_SHORTCUTS_MEDIA_ALL);

  const uint32_t combo = KWEB_SHORTCUTS_MOD_CONTROL | KWEB_SHORTCUTS_MOD_ALT;
  kweb_shortcuts_registration_result result{};
  auto copy = fixture.Request("copy", "text.copy", combo, KWEB_SHORTCUTS_KEY_NAMED,
                              'A');
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_OK);
  auto clash = fixture.Request("clash", "text.clash", combo, KWEB_SHORTCUTS_KEY_NAMED,
                               'A');
  KWEB_CHECK(fixture.Register(handle, clash, &result) == KWEB_SHORTCUTS_STATUS_CONFLICT);
  kweb_shortcuts_unregistration_result removed{};
  KWEB_CHECK(fixture.Unregister(handle, "copy", &removed) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(removed.was_registered == 1u);
  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
