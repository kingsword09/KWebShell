#include "preferences_internal.h"

#include <algorithm>
#include <atomic>
#include <cstring>

namespace {

std::mutex g_registry_mutex;
kwebshell::preferences::State g_state;
std::atomic<uint32_t> g_live_count{0};

kweb_preferences_status ValidateHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  if (struct_size < expected) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  if (abi_version != KWEB_PREFERENCES_ABI_VERSION) return KWEB_PREFERENCES_STATUS_ABI_MISMATCH;
  return KWEB_PREFERENCES_STATUS_OK;
}

bool ValidAppearance(uint32_t source) {
  return source >= KWEB_APPEARANCE_SYSTEM && source <= KWEB_APPEARANCE_DARK;
}

bool ValidKeyBits(uint32_t bits) { return (bits & ~KWEB_PREFERENCES_KEY_ALL) == 0; }

void ResetCapabilities(kweb_preferences_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_PREFERENCES_ABI_VERSION;
}

void ResetSnapshot(kweb_preferences_snapshot_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_PREFERENCES_ABI_VERSION;
}

void ResetAppearance(kweb_preferences_appearance_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_PREFERENCES_ABI_VERSION;
}

void ResetEvent(kweb_preferences_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_PREFERENCES_ABI_VERSION;
}

/** Resets every field except the mutex that protects them. */
void ResetStateLocked() {
  g_state.open = false;
  g_state.application_id.clear();
  g_state.package_identity.clear();
  g_state.sensitive_key_bits = 0;
  g_state.target_key_bit = 0;
  g_state.sequence = 1;
  g_state.appearance_source = KWEB_APPEARANCE_SYSTEM;
  g_state.suppress_updates = false;
  g_state.last = kwebshell::preferences::Facts();
  g_state.platform = nullptr;
  g_state.events.clear();
}

}  // namespace

namespace kwebshell::preferences {

bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr && size != 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first <= 0x7f) {
      ++index;
      continue;
    }
    if (first >= 0xc2 && first <= 0xdf) {
      continuation = 1;
    } else if (first == 0xe0) {
      continuation = 2;
      second_min = 0xa0;
    } else if (first >= 0xe1 && first <= 0xec) {
      continuation = 2;
    } else if (first == 0xed) {
      continuation = 2;
      second_max = 0x9f;
    } else if (first >= 0xee && first <= 0xef) {
      continuation = 2;
    } else if (first == 0xf0) {
      continuation = 3;
      second_min = 0x90;
    } else if (first >= 0xf1 && first <= 0xf3) {
      continuation = 3;
    } else if (first == 0xf4) {
      continuation = 3;
      second_max = 0x8f;
    } else {
      return false;
    }
    if (index + continuation >= size) return false;
    const unsigned char second = data[index + 1];
    if (second < second_min || second > second_max) return false;
    for (size_t offset = 2; offset <= continuation; ++offset) {
      const unsigned char value = data[index + offset];
      if (value < 0x80 || value > 0xbf) return false;
    }
    index += continuation + 1;
  }
  return true;
}

bool ReadString(const kweb_preferences_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) ||
      (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  if (capacity == 0) return;
  const size_t count = std::min(capacity - 1u, value.size());
  std::memcpy(target, value.data(), count);
  target[count] = '\0';
}

Facts ApplySensitivePolicy(const State &state, const Facts &raw) {
  Facts facts = raw;
  facts.published_bits |= KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE;
  const bool screen_reader_allowed =
      state.target_key_bit != 0 && (state.sensitive_key_bits & state.target_key_bit) != 0;
  if (!screen_reader_allowed) {
    facts.screen_reader = KWEB_PREFERENCE_UNKNOWN;
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_SCREEN_READER;
    facts.live_bits &= ~KWEB_PREFERENCE_FACT_SCREEN_READER;
  }
  if (facts.screen_reader == KWEB_PREFERENCE_UNKNOWN) {
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_SCREEN_READER;
    facts.live_bits &= ~KWEB_PREFERENCE_FACT_SCREEN_READER;
  }
  // A published fact must carry a value; a provider that declares one without a
  // value would publish a defaulted fact.
  if (facts.color_scheme == 0) facts.published_bits &= ~KWEB_PREFERENCE_FACT_COLOR_SCHEME;
  if (facts.contrast == 0) facts.published_bits &= ~KWEB_PREFERENCE_FACT_CONTRAST;
  if (facts.reduced_motion == KWEB_PREFERENCE_UNKNOWN) {
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_REDUCED_MOTION;
  }
  if (facts.reduced_transparency == KWEB_PREFERENCE_UNKNOWN) {
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY;
  }
  if (facts.differentiate_without_color == KWEB_PREFERENCE_UNKNOWN) {
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR;
  }
  if (facts.invert_colors == KWEB_PREFERENCE_UNKNOWN) {
    facts.published_bits &= ~KWEB_PREFERENCE_FACT_INVERT_COLORS;
  }
  if (facts.accent_source == 0) facts.published_bits &= ~KWEB_PREFERENCE_FACT_ACCENT_COLOR;
  if (facts.text_scale_percent == 0) facts.published_bits &= ~KWEB_PREFERENCE_FACT_TEXT_SCALE;
  facts.live_bits &= facts.published_bits;
  return facts;
}

uint32_t PublishedBits(const Facts &facts) {
  uint32_t bits = KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE;
  if (facts.color_scheme != 0) bits |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
  if (facts.contrast != 0) bits |= KWEB_PREFERENCE_FACT_CONTRAST;
  if (facts.reduced_motion != KWEB_PREFERENCE_UNKNOWN) bits |= KWEB_PREFERENCE_FACT_REDUCED_MOTION;
  if (facts.reduced_transparency != KWEB_PREFERENCE_UNKNOWN) {
    bits |= KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY;
  }
  if (facts.differentiate_without_color != KWEB_PREFERENCE_UNKNOWN) {
    bits |= KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR;
  }
  if (facts.invert_colors != KWEB_PREFERENCE_UNKNOWN) bits |= KWEB_PREFERENCE_FACT_INVERT_COLORS;
  if (facts.accent_source != 0) bits |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
  if (facts.text_scale_percent != 0) bits |= KWEB_PREFERENCE_FACT_TEXT_SCALE;
  if (facts.screen_reader != KWEB_PREFERENCE_UNKNOWN) bits |= KWEB_PREFERENCE_FACT_SCREEN_READER;
  return (bits & facts.published_bits) | KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE;
}

bool SameFacts(const Facts &left, const Facts &right) {
  return DifferingBits(left, right) == 0 &&
         left.published_bits == right.published_bits &&
         left.live_bits == right.live_bits;
}

uint32_t DifferingBits(const Facts &left, const Facts &right) {
  uint32_t bits = 0;
  if (left.color_scheme != right.color_scheme) bits |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
  if (left.contrast != right.contrast) bits |= KWEB_PREFERENCE_FACT_CONTRAST;
  if (left.reduced_motion != right.reduced_motion) bits |= KWEB_PREFERENCE_FACT_REDUCED_MOTION;
  if (left.reduced_transparency != right.reduced_transparency) {
    bits |= KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY;
  }
  if (left.differentiate_without_color != right.differentiate_without_color) {
    bits |= KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR;
  }
  if (left.invert_colors != right.invert_colors) bits |= KWEB_PREFERENCE_FACT_INVERT_COLORS;
  if (left.accent_source != right.accent_source || left.accent_red != right.accent_red ||
      left.accent_green != right.accent_green || left.accent_blue != right.accent_blue) {
    bits |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
  }
  if (left.text_scale_percent != right.text_scale_percent) bits |= KWEB_PREFERENCE_FACT_TEXT_SCALE;
  if (left.screen_reader != right.screen_reader) bits |= KWEB_PREFERENCE_FACT_SCREEN_READER;
  return bits;
}

void FillSnapshot(const State &state, const Facts &facts, uint64_t sequence,
                  kweb_preferences_snapshot_result *result) {
  const uint32_t bits = PublishedBits(facts);
  result->fact_bits = bits;
  result->color_scheme = (bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0 ? facts.color_scheme : 0;
  result->contrast = (bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0 ? facts.contrast : 0;
  result->reduced_motion =
      (bits & KWEB_PREFERENCE_FACT_REDUCED_MOTION) != 0 ? facts.reduced_motion : KWEB_PREFERENCE_UNKNOWN;
  result->reduced_transparency =
      (bits & KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY) != 0 ? facts.reduced_transparency
                                                             : KWEB_PREFERENCE_UNKNOWN;
  result->differentiate_without_color =
      (bits & KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR) != 0 ? facts.differentiate_without_color
                                                                    : KWEB_PREFERENCE_UNKNOWN;
  result->invert_colors =
      (bits & KWEB_PREFERENCE_FACT_INVERT_COLORS) != 0 ? facts.invert_colors : KWEB_PREFERENCE_UNKNOWN;
  result->screen_reader =
      (bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0 ? facts.screen_reader : KWEB_PREFERENCE_UNKNOWN;
  if ((bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0) {
    result->accent_source = facts.accent_source;
    result->accent_red = facts.accent_red;
    result->accent_green = facts.accent_green;
    result->accent_blue = facts.accent_blue;
  }
  result->text_scale_percent =
      (bits & KWEB_PREFERENCE_FACT_TEXT_SCALE) != 0 ? facts.text_scale_percent : 0;
  result->appearance_source = state.appearance_source;
  result->sequence = sequence;
}

namespace {

/**
 * Compares freshly read facts with the published set and emits at most one
 * ordered change event. The provider is never called while the state lock is
 * held, so a provider thread can push a change at any time.
 */
void EnqueueLocked(State &state, const kweb_preferences_event &event) {
  if (state.events.size() >= KWEB_PREFERENCES_EVENT_CAPACITY) state.events.pop_front();
  state.events.push_back(event);
}

/**
 * Emits one change event for the facts that moved plus any fact the caller
 * already applied, and returns the new sequence. Repeated reads that observe the
 * same facts emit nothing.
 */
uint64_t PublishLocked(State &state, const Facts &facts, uint32_t extra_changed_bits = 0) {
  const Facts published = ApplySensitivePolicy(state, facts);
  const uint32_t changed = DifferingBits(state.last, published) | extra_changed_bits;
  const uint32_t structural = state.last.published_bits ^ published.published_bits;
  state.last = published;
  if (changed == 0 && structural == 0) return state.sequence;
  state.sequence += 1;
  kweb_preferences_event event{};
  ResetEvent(&event);
  event.kind = KWEB_PREFERENCES_EVENT_CHANGED;
  event.changed_fact_bits = changed != 0 ? changed : structural;
  event.sequence = state.sequence;
  EnqueueLocked(state, event);
  return state.sequence;
}

}  // namespace

void ObserveFacts(State &state, const Facts &facts) {
  std::lock_guard<std::mutex> lock(state.mutex);
  PublishLocked(state, facts);
}

/**
 * Records the effective appearance and publishes the facts it changed together
 * with every fact observed while the request was applied, as one ordered update.
 */
uint64_t PublishAppearance(State &state, uint32_t effective, const Facts &facts) {
  std::lock_guard<std::mutex> lock(state.mutex);
  const uint32_t extra =
      state.appearance_source != effective ? KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE : 0u;
  state.appearance_source = effective;
  const uint64_t sequence = PublishLocked(state, facts, extra);
  state.suppress_updates = false;
  return sequence;
}

void PushChanged(State &state) {
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    // The appearance request publishes its own coalesced update.
    if (state.suppress_updates) return;
  }
  Facts raw;
  const kweb_preferences_status status = NativeReadFacts(state, &raw);
  if (status != KWEB_PREFERENCES_STATUS_OK) {
    PushFailed(state, "preferences.native-failed");
    return;
  }
  ObserveFacts(state, raw);
}

void PushFailed(State &state, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  state.sequence += 1;
  kweb_preferences_event event{};
  ResetEvent(&event);
  event.kind = KWEB_PREFERENCES_EVENT_FAILED;
  event.sequence = state.sequence;
  CopyBounded(event.code, sizeof(event.code), code);
  EnqueueLocked(state, event);
}

}  // namespace kwebshell::preferences

extern "C" {

uint32_t KWEB_PREFERENCES_CALL kweb_preferences_abi_version(void) { return KWEB_PREFERENCES_ABI_VERSION; }

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_open(
    const kweb_preferences_configuration *configuration, uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  const kweb_preferences_status header =
      ValidateHeader(configuration->struct_size, configuration->abi_version,
                     sizeof(kweb_preferences_configuration));
  if (header != KWEB_PREFERENCES_STATUS_OK) return header;
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::preferences::ReadString(configuration->application_id, 128, &application_id) ||
      application_id.empty() ||
      !kwebshell::preferences::ReadString(configuration->package_identity, 128, &package_identity) ||
      !ValidKeyBits(configuration->sensitive_key_bits)) {
    return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_PREFERENCES_STATUS_ALREADY_OPEN;
    ResetStateLocked();
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.sensitive_key_bits = configuration->sensitive_key_bits;
    g_state.open = true;
  }
  const kweb_preferences_status opened = kwebshell::preferences::NativeOpen(g_state);
  if (opened != KWEB_PREFERENCES_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return opened;
  }
  kwebshell::preferences::Facts raw;
  const kweb_preferences_status read = kwebshell::preferences::NativeReadFacts(g_state, &raw);
  if (read != KWEB_PREFERENCES_STATUS_OK) {
    kwebshell::preferences::NativeClose(g_state);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return read;
  }
  {
    // The starting state is read once, without announcing a change: a consumer
    // reads it with a snapshot and receives events only for later moves.
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.last = kwebshell::preferences::ApplySensitivePolicy(g_state, raw);
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_capabilities(
    uint64_t handle, kweb_preferences_capabilities_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  const kweb_preferences_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                        sizeof(kweb_preferences_capabilities_result));
  if (header != KWEB_PREFERENCES_STATUS_OK) return header;
  ResetCapabilities(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_PREFERENCES_STATUS_OWNER_CLOSED;
  kwebshell::preferences::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                      kwebshell::preferences::ProviderId());
  result->published_fact_bits = kwebshell::preferences::PublishedBits(g_state.last);
  result->live_fact_bits = g_state.last.live_bits & result->published_fact_bits;
  result->sensitive_key_bits = g_state.sensitive_key_bits;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_snapshot(
    uint64_t handle, kweb_preferences_snapshot_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  const kweb_preferences_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                        sizeof(kweb_preferences_snapshot_result));
  if (header != KWEB_PREFERENCES_STATUS_OK) return header;
  ResetSnapshot(result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_PREFERENCES_STATUS_OWNER_CLOSED;
  }
  kwebshell::preferences::Facts raw;
  const kweb_preferences_status status = kwebshell::preferences::NativeReadFacts(g_state, &raw);
  if (status != KWEB_PREFERENCES_STATUS_OK) return status;
  kwebshell::preferences::ObserveFacts(g_state, raw);
  std::lock_guard<std::mutex> snapshot_lock(g_state.mutex);
  const uint64_t sequence = g_state.sequence;
  kwebshell::preferences::FillSnapshot(g_state, g_state.last, sequence, result);
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_request_appearance(
    uint64_t handle, uint32_t source, kweb_preferences_appearance_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  const kweb_preferences_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                        sizeof(kweb_preferences_appearance_result));
  if (header != KWEB_PREFERENCES_STATUS_OK) return header;
  if (!ValidAppearance(source)) return KWEB_PREFERENCES_STATUS_APPEARANCE_INVALID;
  ResetAppearance(result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_PREFERENCES_STATUS_OWNER_CLOSED;
    g_state.suppress_updates = true;
  }
  uint32_t effective = source;
  const kweb_preferences_status status =
      kwebshell::preferences::NativeRequestAppearance(g_state, source, &effective);
  if (status != KWEB_PREFERENCES_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.suppress_updates = false;
    return status;
  }
  if (!ValidAppearance(effective)) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.suppress_updates = false;
    return KWEB_PREFERENCES_STATUS_NATIVE_FAILED;
  }
  kwebshell::preferences::Facts raw;
  const kweb_preferences_status read = kwebshell::preferences::NativeReadFacts(g_state, &raw);
  const uint64_t sequence = read == KWEB_PREFERENCES_STATUS_OK
                                ? kwebshell::preferences::PublishAppearance(g_state, effective, raw)
                                : 0u;
  if (read != KWEB_PREFERENCES_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.suppress_updates = false;
    return read;
  }
  result->requested = source;
  result->effective = effective;
  result->sequence = sequence;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_poll_event(
    uint64_t handle, kweb_preferences_event *event) {
  if (handle != 1 || event == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  const kweb_preferences_status header =
      ValidateHeader(event->struct_size, event->abi_version, sizeof(kweb_preferences_event));
  if (header != KWEB_PREFERENCES_STATUS_OK) return header;
  ResetEvent(event);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_PREFERENCES_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_PREFERENCES_STATUS_NO_EVENT;
  const kweb_preferences_event next = g_state.events.front();
  g_state.events.pop_front();
  *event = next;
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_PREFERENCES_ABI_VERSION;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_close(uint64_t handle) {
  if (handle != 1) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_PREFERENCES_STATUS_OWNER_CLOSED;
    g_state.open = false;
    g_state.events.clear();
  }
  const kweb_preferences_status status = kwebshell::preferences::NativeClose(g_state);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
  }
  g_live_count.fetch_sub(1);
  return status;
}

}  // extern "C"
