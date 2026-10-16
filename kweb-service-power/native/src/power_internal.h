#ifndef KWEB_POWER_INTERNAL_H_
#define KWEB_POWER_INTERNAL_H_

#include "kweb_power.h"

#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::power {

/** One live lease as Kotlin accepted it. */
struct Lease {
  std::string id;
  uint32_t type = 0;
  std::string reason;
  uint32_t granted_seconds = 0;
  /** Monotonic deadline in milliseconds. */
  int64_t deadline_millis = 0;
  uint32_t renew_count = 0;
  /** Provider-owned assertion handle, file descriptor slot, or power request. */
  uint64_t token = 0;
};

/** The provider's declared capability facts for the current target and session. */
struct Capabilities {
  std::string provider_id;
  uint32_t capability_bits = 0;
  uint32_t max_leases = KWEB_POWER_MAX_LEASES;
  uint32_t max_leases_per_type = 4;
  uint32_t min_lease_seconds = 1;
  uint32_t max_lease_seconds = 14400;
};

/** The published facts. A fact without its bit is absent. */
struct Facts {
  uint32_t fact_bits = 0;
  bool on_battery = false;
  uint32_t battery_percent = 0;
  uint32_t thermal = 0;
  bool locked = false;
};

/** The single process-wide power state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 1;
  Capabilities capabilities;
  Facts facts;
  std::vector<Lease> leases;
  void *platform = nullptr;
  std::deque<kweb_power_event> events;
};

bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_power_string &value, size_t maximum, std::string *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/** True when the value matches the published `[A-Za-z][A-Za-z0-9._-]{0,127}` form. */
bool ValidIdentifier(const std::string &value);
bool ValidType(uint32_t type);
bool ValidThermal(uint32_t level);
/** True when the reason is non-blank, control-free, and within the byte limit. */
bool ValidReason(const std::string &reason);
bool ValidFacts(const Facts &facts);

/** The monotonic clock every deadline uses, in milliseconds. */
int64_t MonotonicMillis();

/** The number of live leases. Caller holds the state mutex. */
size_t LeaseCountLocked(const State &state);
/** The index of one live lease, or -1. Caller holds the state mutex. */
int IndexOfLeaseLocked(const State &state, const std::string &id);
/** The number of live leases of one type. Caller holds the state mutex. */
size_t LeaseCountOfTypeLocked(const State &state, uint32_t type);

// Ordered event publication. The locked form requires the caller to hold the
// state mutex; the locking wrappers are the provider-facing surface.
void PushEventLocked(State &state, uint32_t kind, uint32_t value, uint32_t reason,
                     const std::string &lease_id, const std::string &code);
void PublishSuspended(State &state);
void PublishResumed(State &state);
void PublishLocked(State &state);
void PublishUnlocked(State &state);
void PublishPowerSource(State &state, bool on_battery);
void PublishThermal(State &state, uint32_t level);
void PushFailed(State &state, const std::string &code);

/* The platform provider surface implemented by power_<platform> sources. */
kweb_power_status NativeOpen(State &state);
kweb_power_status NativeReadFacts(State &state, Facts *facts);
kweb_power_status NativeAcquire(State &state, const Lease &lease, uint64_t *token);
kweb_power_status NativeRelease(State &state, const Lease &lease);
kweb_power_status NativeClose(State &state);

}  // namespace kwebshell::power

#endif
