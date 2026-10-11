#include "preferences_platform_test.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>

#include "preferences_tests_fixture.h"

#include <cstring>

/** macOS needs no test environment of its own. */
void PreparePlatformEnvironment() {}

void ReleasePlatformEnvironment() {}

/** Drains the main run loop so queued KVO and notification work is delivered. */
void PreferencesFixture::pump_platform_queue() {
  if ([NSThread isMainThread]) {
    [[NSRunLoop mainRunLoop] runMode:NSDefaultRunLoopMode
                          beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
  }
}

/** The real AppKit facts, the sensitive-key policy, and the ordered change stream. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  PreferencesFixture fixture;

  // 1. Without the declared key the assistive-technology fact stays unpublished.
  uint64_t handle = 0;
  kweb_preferences_configuration without_key = fixture.Configuration(0);
  const kweb_preferences_status opened = kweb_preferences_open(&without_key, &handle);
  if (opened != KWEB_PREFERENCES_STATUS_OK) {
    std::printf("SKIP: the AppKit preferences host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_preferences_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "preferences.macos.appkit") == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_MOTION) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_INVERT_COLORS) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0);
  // macOS publishes no global text scale, so that fact stays absent.
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_TEXT_SCALE) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) == 0);
  KWEB_CHECK((capabilities.live_fact_bits & capabilities.published_fact_bits) == capabilities.live_fact_bits);
  const kweb_preferences_snapshot_result initial = fixture.Snapshot(handle);
  if ((initial.fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) == 0) {
    // A session without a window server publishes no appearance; that is a typed
    // environment boundary, not a fixture failure.
    std::printf("SKIP: the AppKit session publishes no appearance.\n");
    KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
    return g_failures - failures_before;
  }
  KWEB_CHECK(initial.text_scale_percent == 0u);
  KWEB_CHECK(initial.screen_reader == KWEB_PREFERENCE_UNKNOWN);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);

  // 2. With the declared key the assistive-technology fact is published.
  kweb_preferences_configuration with_key = fixture.Configuration(KWEB_PREFERENCES_KEY_MACOS_VOICEOVER);
  KWEB_CHECK(kweb_preferences_open(&with_key, &handle) == KWEB_PREFERENCES_STATUS_OK);
  const kweb_preferences_capabilities_result declared = fixture.Capabilities(handle);
  KWEB_CHECK(declared.sensitive_key_bits == KWEB_PREFERENCES_KEY_MACOS_VOICEOVER);
  KWEB_CHECK((declared.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0);
  const kweb_preferences_snapshot_result declared_snapshot = fixture.Snapshot(handle);
  KWEB_CHECK((declared_snapshot.fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0);
  KWEB_CHECK(declared_snapshot.screen_reader == KWEB_PREFERENCE_TRUE ||
             declared_snapshot.screen_reader == KWEB_PREFERENCE_FALSE);

  // 3. The application appearance is controllable, and the appearance key path
  //    delivers exactly one ordered color-scheme change per real change.
  const uint32_t platform_scheme = declared_snapshot.color_scheme;
  const uint32_t requested = platform_scheme == KWEB_COLOR_SCHEME_DARK ? KWEB_APPEARANCE_LIGHT
                                                                      : KWEB_APPEARANCE_DARK;
  const uint32_t expected_scheme =
      requested == KWEB_APPEARANCE_DARK ? KWEB_COLOR_SCHEME_DARK : KWEB_COLOR_SCHEME_LIGHT;
  kweb_preferences_appearance_result applied{};
  applied.struct_size = sizeof(applied);
  applied.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, requested, &applied) ==
             KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(applied.effective == requested);
  kweb_preferences_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(fixture.AwaitEvent(handle, &event));
  KWEB_CHECK(event.kind == KWEB_PREFERENCES_EVENT_CHANGED);
  KWEB_CHECK((event.changed_fact_bits & KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE) != 0);
  KWEB_CHECK(event.sequence == applied.sequence);
  KWEB_CHECK(fixture.Snapshot(handle).color_scheme == expected_scheme);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  // 3b. An appearance change made outside the service is observed exactly once
  //     through the AppKit key path, and the published scheme follows it.
  const uint32_t out_of_band = expected_scheme == KWEB_COLOR_SCHEME_DARK ? KWEB_APPEARANCE_LIGHT
                                                                        : KWEB_APPEARANCE_DARK;
  const uint32_t out_of_band_scheme =
      out_of_band == KWEB_APPEARANCE_DARK ? KWEB_COLOR_SCHEME_DARK : KWEB_COLOR_SCHEME_LIGHT;
  [NSApp setAppearance:[NSAppearance appearanceNamed:out_of_band == KWEB_APPEARANCE_DARK
                                                          ? NSAppearanceNameDarkAqua
                                                          : NSAppearanceNameAqua]];
  bool observed_out_of_band = false;
  kweb_preferences_event observed{};
  observed.struct_size = sizeof(observed);
  observed.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  for (int attempt = 0; attempt < 100 && !observed_out_of_band; ++attempt) {
    if (fixture.AwaitEvent(handle, &observed, 100) &&
        (observed.changed_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0) {
      observed_out_of_band = true;
    }
  }
  KWEB_CHECK(observed_out_of_band);
  KWEB_CHECK(observed.kind == KWEB_PREFERENCES_EVENT_CHANGED);
  KWEB_CHECK(observed.sequence > applied.sequence);
  KWEB_CHECK(fixture.Snapshot(handle).color_scheme == out_of_band_scheme);
  // The same change is never delivered twice.
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  // 4. Restoring the system appearance returns the published scheme.
  kweb_preferences_appearance_result restored{};
  restored.struct_size = sizeof(restored);
  restored.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_SYSTEM, &restored) ==
             KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(restored.effective == KWEB_APPEARANCE_SYSTEM);
  const kweb_preferences_snapshot_result final_snapshot = fixture.Snapshot(handle);
  KWEB_CHECK(final_snapshot.appearance_source == KWEB_APPEARANCE_SYSTEM);
  KWEB_CHECK(final_snapshot.color_scheme == platform_scheme);

  // 5. Closing releases the observers: later calls are typed and no event arrives.
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
  kweb_preferences_event late{};
  late.struct_size = sizeof(late);
  late.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_poll_event(handle, &late) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  return g_failures - failures_before;
}

#endif
