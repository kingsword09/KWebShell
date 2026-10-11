#include "preferences_platform_test.h"

#if defined(_WIN32)

#include <windows.h>

#include "preferences_tests_fixture.h"

#include <cstdlib>
#include <cstring>

namespace {

/** Writes the application-theme preference of an isolated session. */
bool WriteAppTheme(bool dark) {
  HKEY key = nullptr;
  if (RegOpenKeyExW(HKEY_CURRENT_USER,
                    L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", 0,
                    KEY_READ | KEY_WRITE, &key) != ERROR_SUCCESS) {
    return false;
  }
  const DWORD value = dark ? 0u : 1u;
  const bool written = RegSetValueExW(key, L"AppsUseLightTheme", 0, REG_DWORD,
                                      reinterpret_cast<const BYTE *>(&value), sizeof(value)) ==
                       ERROR_SUCCESS;
  RegCloseKey(key);
  return written;
}

}  // namespace

/** macOS needs no test environment of its own. */
void PreparePlatformEnvironment() {}

void ReleasePlatformEnvironment() {}

/** The AppKit provider owns the main queue, so the platform pump is a no-op here. */
void PreferencesFixture::pump_platform_queue() {}

/** The real Win32 facts, the declared appearance boundary, and the change stream. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  PreferencesFixture fixture;

  // 1. Without the declared key the assistive-technology fact stays unpublished.
  uint64_t handle = 0;
  kweb_preferences_configuration without_key = fixture.Configuration(0);
  const kweb_preferences_status opened = kweb_preferences_open(&without_key, &handle);
  if (opened != KWEB_PREFERENCES_STATUS_OK) {
    std::printf("SKIP: the Win32 preferences host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_preferences_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "preferences.windows.system-parameters") == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_MOTION) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0);
  // Windows publishes no differentiate-without-color or invert-colors fact.
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_INVERT_COLORS) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) == 0);
  const kweb_preferences_snapshot_result initial = fixture.Snapshot(handle);
  KWEB_CHECK((initial.fact_bits & KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE) != 0);
  if ((initial.fact_bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0) {
    KWEB_CHECK(initial.contrast == KWEB_CONTRAST_NONE || initial.contrast == KWEB_CONTRAST_FORCED_COLORS);
  }
  if ((initial.fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0) {
    KWEB_CHECK(initial.color_scheme == KWEB_COLOR_SCHEME_LIGHT || initial.color_scheme == KWEB_COLOR_SCHEME_DARK);
  }
  KWEB_CHECK(initial.screen_reader == KWEB_PREFERENCE_UNKNOWN);

  // 2. A setting notification that changes nothing emits no event.
  HWND window = FindWindowExW(HWND_MESSAGE, nullptr, L"KWebShellPreferencesMessageWindow", nullptr);
  KWEB_CHECK(window != nullptr);
  if (window != nullptr) {
    SendMessageW(window, WM_SETTINGCHANGE, 0, 0);
    SendMessageW(window, WM_THEMECHANGED, 0, 0);
    SendMessageW(window, WM_DWMCOLORIZATIONCOLORCHANGED, 0, 0);
    Sleep(200);
    KWEB_CHECK(fixture.DrainEvents(handle).empty());
  }

  // 3. Win32 publishes no documented per-application appearance override.
  kweb_preferences_appearance_result system{};
  system.struct_size = sizeof(system);
  system.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_SYSTEM, &system) ==
             KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(system.effective == KWEB_APPEARANCE_SYSTEM);
  kweb_preferences_appearance_result dark{};
  dark.struct_size = sizeof(dark);
  dark.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_DARK, &dark) ==
             KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED);
  KWEB_CHECK(dark.requested == 0u);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);

  // 4. With the declared key the assistive-technology fact is published.
  kweb_preferences_configuration with_key =
      fixture.Configuration(KWEB_PREFERENCES_KEY_WINDOWS_SCREEN_READER);
  KWEB_CHECK(kweb_preferences_open(&with_key, &handle) == KWEB_PREFERENCES_STATUS_OK);
  const kweb_preferences_capabilities_result declared = fixture.Capabilities(handle);
  KWEB_CHECK(declared.sensitive_key_bits == KWEB_PREFERENCES_KEY_WINDOWS_SCREEN_READER);
  KWEB_CHECK((declared.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0);
  const kweb_preferences_snapshot_result declared_snapshot = fixture.Snapshot(handle);
  KWEB_CHECK(declared_snapshot.screen_reader == KWEB_PREFERENCE_TRUE ||
             declared_snapshot.screen_reader == KWEB_PREFERENCE_FALSE);

  // 5. Changing the application theme in an isolated session publishes exactly one
  //    ordered update and follows the new value.
  if (std::getenv("KWEBSHELL_ISOLATED_DESKTOP") == nullptr) {
    std::printf("SKIP: the application-theme change requires an isolated desktop session.\n");
  } else if ((declared_snapshot.fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) == 0) {
    std::printf("SKIP: the preference store publishes no application theme.\n");
  } else {
    const bool was_dark = declared_snapshot.color_scheme == KWEB_COLOR_SCHEME_DARK;
    KWEB_CHECK(WriteAppTheme(!was_dark));
    kweb_preferences_event changed{};
    changed.struct_size = sizeof(changed);
    changed.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    KWEB_CHECK(fixture.AwaitEvent(handle, &changed));
    KWEB_CHECK(changed.kind == KWEB_PREFERENCES_EVENT_CHANGED);
    KWEB_CHECK((changed.changed_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0);
    KWEB_CHECK(changed.sequence == declared_snapshot.sequence + 1u);
    const kweb_preferences_snapshot_result flipped = fixture.Snapshot(handle);
    KWEB_CHECK(flipped.color_scheme == (was_dark ? KWEB_COLOR_SCHEME_LIGHT : KWEB_COLOR_SCHEME_DARK));
    KWEB_CHECK(fixture.DrainEvents(handle).empty());
    // The original value is restored so the session is left as it was found.
    KWEB_CHECK(WriteAppTheme(was_dark));
    kweb_preferences_event restored{};
    restored.struct_size = sizeof(restored);
    restored.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    KWEB_CHECK(fixture.AwaitEvent(handle, &restored));
    KWEB_CHECK((restored.changed_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0);
    KWEB_CHECK(fixture.Snapshot(handle).color_scheme ==
               (was_dark ? KWEB_COLOR_SCHEME_DARK : KWEB_COLOR_SCHEME_LIGHT));
    KWEB_CHECK(fixture.DrainEvents(handle).empty());
  }

  // 6. Closing releases the observers and the provider thread.
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
  kweb_preferences_event late{};
  late.struct_size = sizeof(late);
  late.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_poll_event(handle, &late) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  return g_failures - failures_before;
}

#endif
