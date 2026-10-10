#ifndef KWEB_PREFERENCES_INTERNAL_H_
#define KWEB_PREFERENCES_INTERNAL_H_

#include "kweb_system_preferences.h"

#include <deque>
#include <mutex>
#include <string>

namespace kwebshell::preferences {

/**
 * The raw facts one provider reads. Providers fill every fact they publish and
 * leave the others at their unknown value; the portable layer turns the values
 * into the published fact set and enforces the sensitive-key policy.
 */
struct Facts {
  uint32_t color_scheme = 0;
  uint32_t contrast = 0;
  uint32_t reduced_motion = KWEB_PREFERENCE_UNKNOWN;
  uint32_t reduced_transparency = KWEB_PREFERENCE_UNKNOWN;
  uint32_t differentiate_without_color = KWEB_PREFERENCE_UNKNOWN;
  uint32_t invert_colors = KWEB_PREFERENCE_UNKNOWN;
  uint32_t accent_source = 0;
  uint32_t accent_red = 0;
  uint32_t accent_green = 0;
  uint32_t accent_blue = 0;
  uint32_t text_scale_percent = 0;
  uint32_t screen_reader = KWEB_PREFERENCE_UNKNOWN;
  /* Facts this provider can read at all, and facts it keeps current at runtime. */
  uint32_t published_bits = 0;
  uint32_t live_bits = 0;
};

/** The single process-wide preferences state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  /* Declared sensitive keys and the one key that belongs to this target. */
  uint32_t sensitive_key_bits = 0;
  uint32_t target_key_bit = 0;
  uint64_t sequence = 1;
  uint32_t appearance_source = KWEB_APPEARANCE_SYSTEM;
  /* While an appearance request is being applied the provider observers are
   * suppressed, so the request publishes one coalesced update. */
  bool suppress_updates = false;
  Facts last;
  void *platform = nullptr;
  std::deque<kweb_preferences_event> events;
};

const char *ProviderId();
bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_preferences_string &value, size_t maximum, std::string *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/** Applies the sensitive-key policy and reports the facts that stay published. */
Facts ApplySensitivePolicy(const State &state, const Facts &raw);

/** The fact bits the facts actually publish. */
uint32_t PublishedBits(const Facts &facts);

/** True when two published fact sets carry identical values. */
bool SameFacts(const Facts &left, const Facts &right);

/** The facts that differ between two published fact sets. */
uint32_t DifferingBits(const Facts &left, const Facts &right);

void FillSnapshot(const State &state, const Facts &facts, uint64_t sequence,
                  kweb_preferences_snapshot_result *result);

/** Provider-side hooks: a native change re-reads the provider and emits at most one event. */
void PushChanged(State &state);
void PushFailed(State &state, const std::string &code);

/* The platform provider surface implemented by preferences_<platform> sources. */
kweb_preferences_status NativeOpen(State &state);
kweb_preferences_status NativeReadFacts(State &state, Facts *facts);
kweb_preferences_status NativeRequestAppearance(State &state, uint32_t source, uint32_t *effective);
kweb_preferences_status NativeClose(State &state);

}  // namespace kwebshell::preferences

#endif
