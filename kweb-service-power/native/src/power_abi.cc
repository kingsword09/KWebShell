#include "power_internal.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>

namespace {

std::mutex g_registry_mutex;
kwebshell::power::State g_state;
std::atomic<uint32_t> g_live_count{0};

kweb_power_status ValidateHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  if (struct_size < expected) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  if (abi_version != KWEB_POWER_ABI_VERSION) return KWEB_POWER_STATUS_ABI_MISMATCH;
  return KWEB_POWER_STATUS_OK;
}

void ResetCapabilities(kweb_power_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_POWER_ABI_VERSION;
}

void ResetState(kweb_power_state_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_POWER_ABI_VERSION;
}

void ResetLeaseResult(kweb_power_lease_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_POWER_ABI_VERSION;
}

void ResetReleaseResult(kweb_power_release_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_POWER_ABI_VERSION;
}

void ResetEvent(kweb_power_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_POWER_ABI_VERSION;
}

/** Resets every field except the mutex that protects them. */
void ResetStateLocked() {
  g_state.open = false;
  g_state.application_id.clear();
  g_state.package_identity.clear();
  g_state.sequence = 1;
  g_state.capabilities = kwebshell::power::Capabilities();
  g_state.facts = kwebshell::power::Facts();
  g_state.leases.clear();
  g_state.platform = nullptr;
  g_state.events.clear();
}

void FillLease(const kwebshell::power::Lease &lease, int64_t now, kweb_power_lease_result *result) {
  kwebshell::power::CopyBounded(result->lease_id, sizeof(result->lease_id), lease.id);
  result->type = lease.type;
  result->granted_seconds = lease.granted_seconds;
  const int64_t remaining = lease.deadline_millis - now;
  result->remaining_seconds =
      remaining <= 0 ? 0u : static_cast<uint32_t>((remaining + 999) / 1000);
  result->renew_count = lease.renew_count;
}

}  // namespace

namespace kwebshell::power {

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

bool ReadString(const kweb_power_string &value, size_t maximum, std::string *output) {
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

bool ValidIdentifier(const std::string &value) {
  if (value.empty() || value.size() > 128) return false;
  const unsigned char first = static_cast<unsigned char>(value[0]);
  if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) return false;
  for (size_t index = 1; index < value.size(); ++index) {
    const unsigned char character = static_cast<unsigned char>(value[index]);
    const bool allowed = (character >= 'A' && character <= 'Z') ||
                         (character >= 'a' && character <= 'z') ||
                         (character >= '0' && character <= '9') || character == '.' ||
                         character == '_' || character == '-';
    if (!allowed) return false;
  }
  return true;
}

bool ValidType(uint32_t type) {
  return type == KWEB_POWER_TYPE_SYSTEM_SLEEP || type == KWEB_POWER_TYPE_DISPLAY_SLEEP;
}

bool ValidThermal(uint32_t level) {
  return level >= KWEB_POWER_THERMAL_NOMINAL && level <= KWEB_POWER_THERMAL_CRITICAL;
}

bool ValidReason(const std::string &reason) {
  if (reason.empty()) return false;
  if (reason.size() > KWEB_POWER_MAX_REASON) return false;
  bool blank = true;
  for (const char character : reason) {
    const unsigned char value = static_cast<unsigned char>(character);
    if (value < 0x20 || value == 0x7f) return false;
    if (character != ' ') blank = false;
  }
  return !blank;
}

bool ValidFacts(const Facts &facts) {
  if ((facts.fact_bits & ~(KWEB_POWER_FACT_ON_BATTERY | KWEB_POWER_FACT_BATTERY_PERCENT |
                           KWEB_POWER_FACT_THERMAL | KWEB_POWER_FACT_LOCKED)) != 0) {
    return false;
  }
  if ((facts.fact_bits & KWEB_POWER_FACT_BATTERY_PERCENT) != 0 && facts.battery_percent > 100) return false;
  if ((facts.fact_bits & KWEB_POWER_FACT_THERMAL) != 0 && !ValidThermal(facts.thermal)) return false;
  return true;
}

int64_t MonotonicMillis() {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

size_t LeaseCountLocked(const State &state) { return state.leases.size(); }

int IndexOfLeaseLocked(const State &state, const std::string &id) {
  for (size_t index = 0; index < state.leases.size(); ++index) {
    if (state.leases[index].id == id) return static_cast<int>(index);
  }
  return -1;
}

size_t LeaseCountOfTypeLocked(const State &state, uint32_t type) {
  size_t count = 0;
  for (const Lease &lease : state.leases) {
    if (lease.type == type) count += 1;
  }
  return count;
}

void PushEventLocked(State &state, uint32_t kind, uint32_t value, uint32_t reason,
                     const std::string &lease_id, const std::string &code) {
  state.sequence += 1;
  kweb_power_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_POWER_ABI_VERSION;
  event.kind = kind;
  event.value = value;
  event.reason = reason;
  event.sequence = state.sequence;
  CopyBounded(event.lease_id, sizeof(event.lease_id), lease_id);
  CopyBounded(event.code, sizeof(event.code), code);
  if (state.events.size() >= KWEB_POWER_EVENT_CAPACITY) state.events.pop_front();
  state.events.push_back(event);
}

void PublishSuspended(State &state) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_POWER_EVENT_SUSPENDED, 0, 0, "", "");
}

void PublishResumed(State &state) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_POWER_EVENT_RESUMED, 0, 0, "", "");
}

void PublishLocked(State &state) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  state.facts.fact_bits |= KWEB_POWER_FACT_LOCKED;
  state.facts.locked = true;
  PushEventLocked(state, KWEB_POWER_EVENT_LOCKED, 0, 0, "", "");
}

void PublishUnlocked(State &state) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  state.facts.fact_bits |= KWEB_POWER_FACT_LOCKED;
  state.facts.locked = false;
  PushEventLocked(state, KWEB_POWER_EVENT_UNLOCKED, 0, 0, "", "");
}

void PublishPowerSource(State &state, bool on_battery) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  if ((state.facts.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0 &&
      state.facts.on_battery == on_battery) {
    return;
  }
  state.facts.fact_bits |= KWEB_POWER_FACT_ON_BATTERY;
  state.facts.on_battery = on_battery;
  PushEventLocked(state, KWEB_POWER_EVENT_POWER_SOURCE_CHANGED, on_battery ? 1u : 0u, 0, "", "");
}

void PublishThermal(State &state, uint32_t level) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  if ((state.facts.fact_bits & KWEB_POWER_FACT_THERMAL) != 0 && state.facts.thermal == level) return;
  state.facts.fact_bits |= KWEB_POWER_FACT_THERMAL;
  state.facts.thermal = level;
  PushEventLocked(state, KWEB_POWER_EVENT_THERMAL_CHANGED, level, 0, "", "");
}

void PushFailed(State &state, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_POWER_EVENT_FAILED, 0, 0, "", code);
}

namespace {

/**
 * Expires every lease whose deadline has passed, publishes one ordered
 * `LeaseEnded` per lease exactly once, and releases the native assertion outside
 * the state lock so a provider that blocks on its own facility cannot deadlock.
 */
void Expire(State &state) {
  std::vector<Lease> expired;
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.open) return;
    const int64_t now = MonotonicMillis();
    for (auto it = state.leases.begin(); it != state.leases.end();) {
      if (it->deadline_millis > now) {
        ++it;
        continue;
      }
      expired.push_back(*it);
      it = state.leases.erase(it);
    }
    for (const Lease &lease : expired) {
      PushEventLocked(state, KWEB_POWER_EVENT_LEASE_ENDED, 0, KWEB_POWER_END_EXPIRED, lease.id, "");
    }
  }
  for (const Lease &lease : expired) {
    NativeRelease(state, lease);
  }
}

}  // namespace

}  // namespace kwebshell::power

extern "C" {

uint32_t KWEB_POWER_CALL kweb_power_abi_version(void) { return KWEB_POWER_ABI_VERSION; }

kweb_power_status KWEB_POWER_CALL kweb_power_open(const kweb_power_configuration *configuration,
                                                  uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header = ValidateHeader(configuration->struct_size, configuration->abi_version,
                                                  sizeof(kweb_power_configuration));
  if (header != KWEB_POWER_STATUS_OK) return header;
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::power::ReadString(configuration->application_id, KWEB_POWER_MAX_ID, &application_id) ||
      application_id.empty() ||
      !kwebshell::power::ReadString(configuration->package_identity, KWEB_POWER_MAX_ID,
                                    &package_identity)) {
    return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_POWER_STATUS_ALREADY_OPEN;
    ResetStateLocked();
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.open = true;
  }
  const kweb_power_status opened = kwebshell::power::NativeOpen(g_state);
  if (opened != KWEB_POWER_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return opened;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_capabilities(
    uint64_t handle, kweb_power_capabilities_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                  sizeof(kweb_power_capabilities_result));
  if (header != KWEB_POWER_STATUS_OK) return header;
  ResetCapabilities(result);
  kwebshell::power::Expire(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  kwebshell::power::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                g_state.capabilities.provider_id);
  result->capability_bits = g_state.capabilities.capability_bits;
  result->max_leases = g_state.capabilities.max_leases;
  result->max_leases_per_type = g_state.capabilities.max_leases_per_type;
  result->min_lease_seconds = g_state.capabilities.min_lease_seconds;
  result->max_lease_seconds = g_state.capabilities.max_lease_seconds;
  result->lease_count = static_cast<uint32_t>(g_state.leases.size());
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_state(uint64_t handle, kweb_power_state_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_power_state_result));
  if (header != KWEB_POWER_STATUS_OK) return header;
  ResetState(result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  }
  kwebshell::power::Facts read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    read = g_state.facts;
  }
  const kweb_power_status status = kwebshell::power::NativeReadFacts(g_state, &read);
  if (status != KWEB_POWER_STATUS_OK && status != KWEB_POWER_STATUS_SERVICE_UNAVAILABLE) return status;
  if (!kwebshell::power::ValidFacts(read)) return KWEB_POWER_STATUS_NATIVE_FAILED;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  // A pure read refreshes the published facts without announcing a change; the
  // provider's own notification is what publishes a change event.
  if (status == KWEB_POWER_STATUS_OK) g_state.facts = read;
  result->fact_bits = g_state.facts.fact_bits;
  result->on_battery = g_state.facts.on_battery ? 1u : 0u;
  result->battery_percent = g_state.facts.battery_percent;
  result->thermal = g_state.facts.thermal;
  result->locked = g_state.facts.locked ? 1u : 0u;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_acquire(uint64_t handle,
                                                     const kweb_power_lease_request *request,
                                                     kweb_power_lease_result *result) {
  if (handle != 1 || request == nullptr || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header = ValidateHeader(request->struct_size, request->abi_version,
                                                  sizeof(kweb_power_lease_request));
  if (header != KWEB_POWER_STATUS_OK) return header;
  const kweb_power_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_power_lease_result));
  if (result_header != KWEB_POWER_STATUS_OK) return result_header;
  std::string lease_id;
  std::string reason;
  if (!kwebshell::power::ReadString(request->lease_id, KWEB_POWER_MAX_ID, &lease_id) ||
      !kwebshell::power::ReadString(request->reason, KWEB_POWER_MAX_REASON, &reason) ||
      !kwebshell::power::ValidIdentifier(lease_id) || !kwebshell::power::ValidReason(reason) ||
      !kwebshell::power::ValidType(request->type)) {
    return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  }
  kwebshell::power::Expire(g_state);
  kwebshell::power::Lease lease;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
    if (request->max_duration_seconds > g_state.capabilities.max_lease_seconds) {
      return KWEB_POWER_STATUS_DURATION_EXCEEDED;
    }
    if (request->max_duration_seconds < g_state.capabilities.min_lease_seconds) {
      return KWEB_POWER_STATUS_INVALID_ARGUMENT;
    }
    const uint32_t type_bit = request->type == KWEB_POWER_TYPE_SYSTEM_SLEEP
                                  ? KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER
                                  : KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER;
    if ((g_state.capabilities.capability_bits & type_bit) == 0) {
      return KWEB_POWER_STATUS_TYPE_UNSUPPORTED;
    }
    if (kwebshell::power::IndexOfLeaseLocked(g_state, lease_id) >= 0) {
      return KWEB_POWER_STATUS_ALREADY_EXISTS;
    }
    if (kwebshell::power::LeaseCountLocked(g_state) >= g_state.capabilities.max_leases ||
        kwebshell::power::LeaseCountOfTypeLocked(g_state, request->type) >=
            g_state.capabilities.max_leases_per_type) {
      return KWEB_POWER_STATUS_LIMIT_EXCEEDED;
    }
    lease.id = lease_id;
    lease.type = request->type;
    lease.reason = reason;
    lease.granted_seconds = request->max_duration_seconds;
  }
  uint64_t token = 0;
  const kweb_power_status native = kwebshell::power::NativeAcquire(g_state, lease, &token);
  if (native != KWEB_POWER_STATUS_OK) return native;
  lease.token = token;
  lease.deadline_millis =
      kwebshell::power::MonotonicMillis() + static_cast<int64_t>(lease.granted_seconds) * 1000;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) {
    kwebshell::power::NativeRelease(g_state, lease);
    return KWEB_POWER_STATUS_OWNER_CLOSED;
  }
  if (kwebshell::power::IndexOfLeaseLocked(g_state, lease.id) >= 0 ||
      kwebshell::power::LeaseCountLocked(g_state) >= g_state.capabilities.max_leases) {
    kwebshell::power::NativeRelease(g_state, lease);
    return KWEB_POWER_STATUS_LIMIT_EXCEEDED;
  }
  g_state.leases.push_back(lease);
  ResetLeaseResult(result);
  FillLease(lease, kwebshell::power::MonotonicMillis(), result);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_renew(uint64_t handle,
                                                   const kweb_power_lease_query *query,
                                                   kweb_power_lease_result *result) {
  if (handle != 1 || query == nullptr || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header =
      ValidateHeader(query->struct_size, query->abi_version, sizeof(kweb_power_lease_query));
  if (header != KWEB_POWER_STATUS_OK) return header;
  const kweb_power_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_power_lease_result));
  if (result_header != KWEB_POWER_STATUS_OK) return result_header;
  std::string lease_id;
  if (!kwebshell::power::ReadString(query->lease_id, KWEB_POWER_MAX_ID, &lease_id) ||
      !kwebshell::power::ValidIdentifier(lease_id)) {
    return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  }
  kwebshell::power::Expire(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  const int index = kwebshell::power::IndexOfLeaseLocked(g_state, lease_id);
  if (index < 0) return KWEB_POWER_STATUS_LEASE_UNKNOWN;
  if ((g_state.capabilities.capability_bits & KWEB_POWER_CAP_LEASE_RENEWAL) == 0) {
    return KWEB_POWER_STATUS_RENEWAL_EXCEEDED;
  }
  kwebshell::power::Lease &lease = g_state.leases[static_cast<size_t>(index)];
  lease.deadline_millis =
      kwebshell::power::MonotonicMillis() + static_cast<int64_t>(lease.granted_seconds) * 1000;
  lease.renew_count += 1;
  ResetLeaseResult(result);
  FillLease(lease, kwebshell::power::MonotonicMillis(), result);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_release(uint64_t handle,
                                                     const kweb_power_lease_query *query,
                                                     kweb_power_release_result *result) {
  if (handle != 1 || query == nullptr || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header =
      ValidateHeader(query->struct_size, query->abi_version, sizeof(kweb_power_lease_query));
  if (header != KWEB_POWER_STATUS_OK) return header;
  const kweb_power_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_power_release_result));
  if (result_header != KWEB_POWER_STATUS_OK) return result_header;
  std::string lease_id;
  if (!kwebshell::power::ReadString(query->lease_id, KWEB_POWER_MAX_ID, &lease_id) ||
      !kwebshell::power::ValidIdentifier(lease_id)) {
    return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  }
  kwebshell::power::Expire(g_state);
  kwebshell::power::Lease removed;
  bool found = false;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
    const int index = kwebshell::power::IndexOfLeaseLocked(g_state, lease_id);
    if (index >= 0) {
      removed = g_state.leases[static_cast<size_t>(index)];
      g_state.leases.erase(g_state.leases.begin() + index);
      kwebshell::power::PushEventLocked(g_state, KWEB_POWER_EVENT_LEASE_ENDED, 0,
                                        KWEB_POWER_END_RELEASED, removed.id, "");
      found = true;
    }
  }
  ResetReleaseResult(result);
  if (!found) return KWEB_POWER_STATUS_OK;
  result->released = 1u;
  const kweb_power_status native = kwebshell::power::NativeRelease(g_state, removed);
  return native == KWEB_POWER_STATUS_OK ? KWEB_POWER_STATUS_OK : native;
}

kweb_power_status KWEB_POWER_CALL kweb_power_leases(uint64_t handle,
                                                    kweb_power_lease_list_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_power_lease_list_result));
  if (header != KWEB_POWER_STATUS_OK) return header;
  if (result->capacity != 0 && result->leases == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const uint32_t capacity = result->capacity;
  kweb_power_lease *buffer = result->leases;
  kwebshell::power::Expire(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  const int64_t now = kwebshell::power::MonotonicMillis();
  const uint32_t count = static_cast<uint32_t>(g_state.leases.size());
  if (count > capacity) {
    std::memset(result, 0, sizeof(*result));
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_POWER_ABI_VERSION;
    result->count = count;
    result->capacity = capacity;
    result->leases = buffer;
    return KWEB_POWER_STATUS_BUFFER_TOO_SMALL;
  }
  for (uint32_t index = 0; index < count; ++index) {
    const kwebshell::power::Lease &lease = g_state.leases[index];
    kweb_power_lease &target = buffer[index];
    std::memset(&target, 0, sizeof(target));
    kwebshell::power::CopyBounded(target.lease_id, sizeof(target.lease_id), lease.id);
    kwebshell::power::CopyBounded(target.reason, sizeof(target.reason), lease.reason);
    target.type = lease.type;
    target.granted_seconds = lease.granted_seconds;
    const int64_t remaining = lease.deadline_millis - now;
    target.remaining_seconds = remaining <= 0 ? 0u : static_cast<uint32_t>((remaining + 999) / 1000);
    target.renew_count = lease.renew_count;
  }
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_POWER_ABI_VERSION;
  result->count = count;
  result->capacity = capacity;
  result->leases = buffer;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_poll_event(uint64_t handle, kweb_power_event *event) {
  if (handle != 1 || event == nullptr) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  const kweb_power_status header =
      ValidateHeader(event->struct_size, event->abi_version, sizeof(kweb_power_event));
  if (header != KWEB_POWER_STATUS_OK) return header;
  ResetEvent(event);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  }
  // The expiry check runs on every poll so an owner that never releases a lease
  // still observes its deadline promptly.
  kwebshell::power::Expire(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_POWER_STATUS_NO_EVENT;
  const kweb_power_event next = g_state.events.front();
  g_state.events.pop_front();
  *event = next;
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_POWER_ABI_VERSION;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status KWEB_POWER_CALL kweb_power_close(uint64_t handle) {
  if (handle != 1) return KWEB_POWER_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  std::vector<kwebshell::power::Lease> live;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_POWER_STATUS_OWNER_CLOSED;
    g_state.open = false;
    live = g_state.leases;
    g_state.leases.clear();
    g_state.events.clear();
  }
  for (const kwebshell::power::Lease &lease : live) {
    kwebshell::power::NativeRelease(g_state, lease);
  }
  const kweb_power_status status = kwebshell::power::NativeClose(g_state);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
  }
  g_live_count.fetch_sub(1);
  return status;
}

}  // extern "C"
