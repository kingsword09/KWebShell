#include "preferences_internal.h"
#include "preferences_platform_test.h"
#include "preferences_tests_fixture.h"

#include <cstring>
#include <vector>

namespace {

/** True when the current target publishes the service at all. */
bool PlatformAvailable(PreferencesFixture &fixture, uint64_t *handle) {
  kweb_preferences_configuration configuration = fixture.Configuration(0);
  const kweb_preferences_status status = kweb_preferences_open(&configuration, handle);
  if (status == KWEB_PREFERENCES_STATUS_PLATFORM_UNAVAILABLE ||
      status == KWEB_PREFERENCES_STATUS_FACILITY_UNSUPPORTED) {
    std::printf("SKIP: the declared preferences host is unavailable (status %u).\n", status);
    return false;
  }
  KWEB_CHECK(status == KWEB_PREFERENCES_STATUS_OK);
  return status == KWEB_PREFERENCES_STATUS_OK;
}

void ConfigurationValidationRejectsBadHeaders() {
  PreferencesFixture fixture;
  uint64_t handle = 0;
  KWEB_CHECK(kweb_preferences_open(nullptr, &handle) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);
  kweb_preferences_configuration configuration = fixture.Configuration(0);
  KWEB_CHECK(kweb_preferences_open(&configuration, nullptr) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);

  configuration = fixture.Configuration(0);
  configuration.struct_size = sizeof(configuration) - 1u;
  KWEB_CHECK(kweb_preferences_open(&configuration, &handle) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);

  configuration = fixture.Configuration(0);
  configuration.abi_version = KWEB_PREFERENCES_ABI_VERSION + 1u;
  KWEB_CHECK(kweb_preferences_open(&configuration, &handle) == KWEB_PREFERENCES_STATUS_ABI_MISMATCH);

  configuration = fixture.Configuration(0);
  configuration.application_id = kweb_preferences_string{nullptr, 0};
  KWEB_CHECK(kweb_preferences_open(&configuration, &handle) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);

  configuration = fixture.Configuration(0);
  static const uint8_t kInvalid[] = {0xffu, 0xfeu};
  configuration.application_id.data = kInvalid;
  configuration.application_id.size = sizeof(kInvalid);
  KWEB_CHECK(kweb_preferences_open(&configuration, &handle) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);

  // An undeclared sensitive key is rejected instead of being ignored.
  configuration = fixture.Configuration(KWEB_PREFERENCES_KEY_ALL | 0x10u);
  KWEB_CHECK(kweb_preferences_open(&configuration, &handle) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);
}

void SecondOpenIsRejectedAndCloseReleasesTheOwner() {
  PreferencesFixture fixture;
  uint64_t handle = 0;
  if (!PlatformAvailable(fixture, &handle)) return;
  uint64_t second = 0;
  kweb_preferences_configuration configuration = fixture.Configuration(0);
  KWEB_CHECK(kweb_preferences_open(&configuration, &second) == KWEB_PREFERENCES_STATUS_ALREADY_OPEN);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);

  // A closed handle reports the typed owner state for every call.
  kweb_preferences_snapshot_result snapshot{};
  snapshot.struct_size = sizeof(snapshot);
  snapshot.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_snapshot(handle, &snapshot) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  kweb_preferences_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_capabilities(handle, &capabilities) ==
             KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  kweb_preferences_appearance_result appearance{};
  appearance.struct_size = sizeof(appearance);
  appearance.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_DARK, &appearance) ==
             KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  kweb_preferences_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_poll_event(handle, &event) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
}

void ArgumentValidationIsTypedAndChangesNothing() {
  PreferencesFixture fixture;
  uint64_t handle = 0;
  if (!PlatformAvailable(fixture, &handle)) return;
  kweb_preferences_snapshot_result snapshot = fixture.Snapshot(handle);
  const uint64_t sequence = snapshot.sequence;

  snapshot = fixture.Snapshot(handle);
  snapshot.struct_size = sizeof(snapshot) - 1u;
  KWEB_CHECK(kweb_preferences_snapshot(handle, &snapshot) == KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT);

  kweb_preferences_appearance_result appearance{};
  appearance.struct_size = sizeof(appearance);
  appearance.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, 0u, &appearance) ==
             KWEB_PREFERENCES_STATUS_APPEARANCE_INVALID);
  KWEB_CHECK(kweb_preferences_request_appearance(handle, 9u, &appearance) ==
             KWEB_PREFERENCES_STATUS_APPEARANCE_INVALID);
  KWEB_CHECK(appearance.requested == 0u);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());
  KWEB_CHECK(fixture.Snapshot(handle).sequence == sequence);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
}

void PublishedFactsAlwaysCarryTheirValue() {
  PreferencesFixture fixture;
  uint64_t handle = 0;
  if (!PlatformAvailable(fixture, &handle)) return;
  const kweb_preferences_capabilities_result capabilities = fixture.Capabilities(handle);
  const kweb_preferences_snapshot_result snapshot = fixture.Snapshot(handle);
  KWEB_CHECK(std::strlen(capabilities.provider_id) > 0);
  KWEB_CHECK((snapshot.fact_bits & ~capabilities.published_fact_bits) == 0);
  KWEB_CHECK((capabilities.live_fact_bits & ~capabilities.published_fact_bits) == 0);
  KWEB_CHECK((snapshot.fact_bits & KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE) != 0);
  KWEB_CHECK(snapshot.sequence >= 1u);
  KWEB_CHECK(snapshot.appearance_source >= KWEB_APPEARANCE_SYSTEM &&
             snapshot.appearance_source <= KWEB_APPEARANCE_DARK);
  // A published fact carries its value; an unpresent fact stays at its unknown value.
  if ((snapshot.fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0) {
    KWEB_CHECK(snapshot.color_scheme == KWEB_COLOR_SCHEME_LIGHT ||
               snapshot.color_scheme == KWEB_COLOR_SCHEME_DARK);
  } else {
    KWEB_CHECK(snapshot.color_scheme == 0u);
  }
  if ((snapshot.fact_bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0) {
    KWEB_CHECK(snapshot.contrast >= KWEB_CONTRAST_NONE && snapshot.contrast <= KWEB_CONTRAST_FORCED_COLORS);
  } else {
    KWEB_CHECK(snapshot.contrast == 0u);
  }
  const uint32_t tri_states[] = {
      snapshot.reduced_motion,
      snapshot.reduced_transparency,
      snapshot.differentiate_without_color,
      snapshot.invert_colors,
      snapshot.screen_reader,
  };
  const uint32_t tri_state_facts[] = {
      KWEB_PREFERENCE_FACT_REDUCED_MOTION,
      KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY,
      KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR,
      KWEB_PREFERENCE_FACT_INVERT_COLORS,
      KWEB_PREFERENCE_FACT_SCREEN_READER,
  };
  for (size_t index = 0; index < 5; ++index) {
    const bool published = (snapshot.fact_bits & tri_state_facts[index]) != 0;
    KWEB_CHECK(tri_states[index] >= KWEB_PREFERENCE_UNKNOWN && tri_states[index] <= KWEB_PREFERENCE_TRUE);
    KWEB_CHECK(published == (tri_states[index] != KWEB_PREFERENCE_UNKNOWN));
  }
  if ((snapshot.fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0) {
    KWEB_CHECK(snapshot.accent_source >= KWEB_ACCENT_SOURCE_SYSTEM_COLORIZATION &&
               snapshot.accent_source <= KWEB_ACCENT_SOURCE_DESKTOP_PORTAL);
    KWEB_CHECK(snapshot.accent_red <= 255u && snapshot.accent_green <= 255u && snapshot.accent_blue <= 255u);
  }
  if ((snapshot.fact_bits & KWEB_PREFERENCE_FACT_TEXT_SCALE) != 0) {
    KWEB_CHECK(snapshot.text_scale_percent >= KWEB_PREFERENCES_MIN_TEXT_SCALE &&
               snapshot.text_scale_percent <= KWEB_PREFERENCES_MAX_TEXT_SCALE);
  } else {
    KWEB_CHECK(snapshot.text_scale_percent == 0u);
  }
  // Without the declared key the assistive-technology fact is absent everywhere.
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) == 0);
  KWEB_CHECK((snapshot.fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) == 0);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
}

void AppearanceRequestsRecordTheEffectiveSourceAndDeduplicate() {
  PreferencesFixture fixture;
  uint64_t handle = 0;
  if (!PlatformAvailable(fixture, &handle)) return;
  const kweb_preferences_snapshot_result initial = fixture.Snapshot(handle);
  KWEB_CHECK(initial.appearance_source == KWEB_APPEARANCE_SYSTEM);

  kweb_preferences_appearance_result dark{};
  dark.struct_size = sizeof(dark);
  dark.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  const kweb_preferences_status status =
      kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_DARK, &dark);
  if (status == KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED) {
    std::printf("SKIP: the declared target cannot apply an application appearance.\n");
    KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
    return;
  }
  KWEB_CHECK(status == KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(dark.requested == KWEB_APPEARANCE_DARK && dark.effective == KWEB_APPEARANCE_DARK);
  KWEB_CHECK(dark.sequence == initial.sequence + 1u);
  kweb_preferences_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(fixture.AwaitEvent(handle, &event));
  if (event.kind == KWEB_PREFERENCES_EVENT_CHANGED) {
    KWEB_CHECK(event.sequence == dark.sequence);
    KWEB_CHECK((event.changed_fact_bits & KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE) != 0);
  }
  // Repeating the same request is not a change.
  const kweb_preferences_snapshot_result applied = fixture.Snapshot(handle);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());
  kweb_preferences_appearance_result again{};
  again.struct_size = sizeof(again);
  again.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_DARK, &again) ==
             KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(again.sequence == applied.sequence);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  kweb_preferences_appearance_result system{};
  system.struct_size = sizeof(system);
  system.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_SYSTEM, &system) ==
             KWEB_PREFERENCES_STATUS_OK);
  KWEB_CHECK(system.effective == KWEB_APPEARANCE_SYSTEM);
  KWEB_CHECK(fixture.Snapshot(handle).appearance_source == KWEB_APPEARANCE_SYSTEM);
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
}

}  // namespace

int main() {
  ConfigurationValidationRejectsBadHeaders();
  SecondOpenIsRejectedAndCloseReleasesTheOwner();
  ArgumentValidationIsTypedAndChangesNothing();
  PublishedFactsAlwaysCarryTheirValue();
  AppearanceRequestsRecordTheEffectiveSourceAndDeduplicate();
  g_failures += RunPlatformNativeStructureTest();
  if (g_failures == 0) {
    std::printf("kweb preferences native checks passed.\n");
    return 0;
  }
  std::printf("%d native checks failed.\n", g_failures);
  return 1;
}
