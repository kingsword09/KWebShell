#include "shortcuts_platform_test.h"

#if defined(__APPLE__)

#import <Carbon/Carbon.h>

#include "shortcuts_tests_fixture.h"

#include <cstring>
#include <string>

/** The Carbon handler runs on the application event target; nothing else to pump. */
void ShortcutsFixture::pump_platform_queue() {}

/** The real Carbon registration, conflict, and activation facts. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  const kweb_shortcuts_status opened = kweb_shortcuts_open(&configuration, &handle);
  if (opened != KWEB_SHORTCUTS_STATUS_OK) {
    std::printf("SKIP: the Carbon shortcut host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_shortcuts_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "shortcuts.macos.carbon-hotkey") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_INVOCATION_REPORTING) != 0);
  KWEB_CHECK((capabilities.modifier_bits & KWEB_SHORTCUTS_MOD_META) != 0);
  // Media keys need an accessibility trust the service does not assume, so the
  // macOS provider publishes the typed absence instead of a permission.
  KWEB_CHECK(capabilities.media_key_bits == 0);

  const uint32_t combo = KWEB_SHORTCUTS_MOD_CONTROL | KWEB_SHORTCUTS_MOD_ALT;
  kweb_shortcuts_registration_result result{};
  auto copy = fixture.Request("copy", "text.copy", combo, KWEB_SHORTCUTS_KEY_NAMED,
                              'A');
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_OK);
  // The same combination under a different identifier is a typed conflict.
  auto clash = fixture.Request("clash", "text.clash", combo, KWEB_SHORTCUTS_KEY_NAMED,
                               'A');
  KWEB_CHECK(fixture.Register(handle, clash, &result) == KWEB_SHORTCUTS_STATUS_CONFLICT);
  // A media key is refused because the session publishes no media facility.
  auto media = fixture.Request("media", "media.play", 0u, KWEB_SHORTCUTS_KEY_MEDIA,
                               KWEB_SHORTCUTS_MEDIA_PLAY_PAUSE);
  KWEB_CHECK(fixture.Register(handle, media, &result) == KWEB_SHORTCUTS_STATUS_MEDIA_PERMISSION_DENIED);

  // A real Carbon activation delivers exactly one ordered invocation. The first
  // registration of this session owns hotkey id 1.
  EventRef event = nullptr;
  if (CreateEvent(nullptr, kEventClassKeyboard, kEventHotKeyPressed, GetCurrentEventTime(),
                  kEventAttributeNone, &event) == noErr) {
    const EventHotKeyID hotkey_id{'KWBS', 1};
    SetEventParameter(event, kEventParamDirectObject, typeEventHotKeyID, sizeof(hotkey_id), &hotkey_id);
    SendEventToEventTarget(event, GetApplicationEventTarget());
    ReleaseEvent(event);
    kweb_shortcuts_event delivered{};
    delivered.struct_size = sizeof(delivered);
    delivered.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    const bool invoked = fixture.AwaitEvent(handle, &delivered, 1000);
    KWEB_CHECK(invoked);
    if (invoked) {
      KWEB_CHECK(delivered.kind == KWEB_SHORTCUTS_EVENT_INVOKED);
      KWEB_CHECK(delivered.source == KWEB_SHORTCUTS_SOURCE_PLATFORM);
      KWEB_CHECK(std::strcmp(delivered.command_id, "text.copy") == 0);
    }
  } else {
    std::printf("SKIP: the Carbon event target is unavailable.\n");
  }

  kweb_shortcuts_unregistration_result removed{};
  KWEB_CHECK(fixture.Unregister(handle, "copy", &removed) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(removed.was_registered == 1u);

  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
