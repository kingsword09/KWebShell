#ifndef KWEB_SHORTCUTS_INTERNAL_H_
#define KWEB_SHORTCUTS_INTERNAL_H_

#include "kweb_shortcuts.h"

#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::shortcuts {

/** One live registration as Kotlin accepted it. */
struct Registration {
  std::string id;
  std::string command_id;
  uint32_t modifier_bits = 0;
  uint32_t key_kind = 0;
  uint32_t key_code = 0;
};

/** The provider's declared capability facts for the current target and session. */
struct Capabilities {
  std::string provider_id;
  uint32_t capability_bits = 0;
  uint32_t modifier_bits = 0;
  uint32_t media_key_bits = 0;
  uint32_t max_registrations = KWEB_SHORTCUTS_MAX_REGISTRATIONS;
};

/** The single process-wide shortcut state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 1;
  Capabilities capabilities;
  std::vector<Registration> registrations;
  void *platform = nullptr;
  std::deque<kweb_shortcuts_event> events;
};

bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_shortcuts_string &value, size_t maximum, std::string *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/** True when the value matches the published `[A-Za-z][A-Za-z0-9._-]{0,127}` form. */
bool ValidIdentifier(const std::string &value);
bool ValidModifierBits(uint32_t bits);
bool ValidKey(uint32_t key_kind, uint32_t key_code);
bool SameAccelerator(const Registration &left, const Registration &right);

/** The declared-registration capacity in effect, clamped to the ABI ceiling. */
uint32_t EffectiveMaxRegistrations(const Capabilities &capabilities);

// Ordered event publication. The locked form requires the caller to hold the
// state mutex; the locking wrappers are the provider-facing surface.
void PushEventLocked(State &state, uint32_t kind, uint32_t source, uint32_t reason,
                     const std::string &registration_id, const std::string &command_id,
                     const std::string &code);
void PublishInvocation(State &state, const Registration &registration, uint32_t source);
void PublishLost(State &state, const Registration &registration, uint32_t reason);
void PushFailed(State &state, const std::string &code);

/* The platform provider surface implemented by shortcuts_<platform> sources. */
kweb_shortcuts_status NativeOpen(State &state);
kweb_shortcuts_status NativeRegister(State &state, const Registration &registration);
kweb_shortcuts_status NativeUnregister(State &state, const Registration &registration);
kweb_shortcuts_status NativeClose(State &state);

}  // namespace kwebshell::shortcuts

#endif
