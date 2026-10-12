#include "shortcuts_internal.h"

#include <algorithm>
#include <atomic>
#include <cstring>

namespace {

std::mutex g_registry_mutex;
kwebshell::shortcuts::State g_state;
std::atomic<uint32_t> g_live_count{0};

kweb_shortcuts_status ValidateHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  if (struct_size < expected) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  if (abi_version != KWEB_SHORTCUTS_ABI_VERSION) return KWEB_SHORTCUTS_STATUS_ABI_MISMATCH;
  return KWEB_SHORTCUTS_STATUS_OK;
}

void ResetRegistrationResult(kweb_shortcuts_registration_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

void ResetReplacementResult(kweb_shortcuts_replacement_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

void ResetUnregistrationResult(kweb_shortcuts_unregistration_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

void ResetPresenceResult(kweb_shortcuts_presence_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

void ResetCapabilitiesResult(kweb_shortcuts_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

void ResetEvent(kweb_shortcuts_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
}

/** Resets every field except the mutex that protects them. */
void ResetStateLocked() {
  g_state.open = false;
  g_state.application_id.clear();
  g_state.package_identity.clear();
  g_state.sequence = 1;
  g_state.capabilities = kwebshell::shortcuts::Capabilities();
  g_state.registrations.clear();
  g_state.platform = nullptr;
  g_state.events.clear();
}

/** The index of one live identifier, or -1. Caller holds the state mutex. */
int IndexOfIdLocked(const std::string &id) {
  for (size_t index = 0; index < g_state.registrations.size(); ++index) {
    if (g_state.registrations[index].id == id) return static_cast<int>(index);
  }
  return -1;
}

}  // namespace

namespace kwebshell::shortcuts {

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

bool ReadString(const kweb_shortcuts_string &value, size_t maximum, std::string *output) {
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

bool ValidModifierBits(uint32_t bits) { return (bits & ~KWEB_SHORTCUTS_MOD_ALL) == 0; }

namespace {

bool ValidNamedCode(uint32_t code) {
  if (code >= 'A' && code <= 'Z') return true;
  if (code >= '0' && code <= '9') return true;
  if (code >= KWEB_SHORTCUTS_NAMED_F1 && code <= KWEB_SHORTCUTS_NAMED_F24) return true;
  if (code >= KWEB_SHORTCUTS_NAMED_SPACE && code <= KWEB_SHORTCUTS_NAMED_BACKQUOTE) return true;
  return false;
}

}  // namespace

bool ValidKey(uint32_t key_kind, uint32_t key_code) {
  if (key_kind == KWEB_SHORTCUTS_KEY_NAMED) return ValidNamedCode(key_code);
  if (key_kind == KWEB_SHORTCUTS_KEY_MEDIA) {
    return key_code >= KWEB_SHORTCUTS_MEDIA_PLAY_PAUSE && key_code <= KWEB_SHORTCUTS_MEDIA_MUTE;
  }
  return false;
}

bool SameAccelerator(const Registration &left, const Registration &right) {
  return left.modifier_bits == right.modifier_bits && left.key_kind == right.key_kind &&
         left.key_code == right.key_code;
}

uint32_t EffectiveMaxRegistrations(const Capabilities &capabilities) {
  return std::min(capabilities.max_registrations, KWEB_SHORTCUTS_MAX_REGISTRATIONS);
}

void PushEventLocked(State &state, uint32_t kind, uint32_t source, uint32_t reason,
                     const std::string &registration_id, const std::string &command_id,
                     const std::string &code) {
  state.sequence += 1;
  kweb_shortcuts_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  event.kind = kind;
  event.source = source;
  event.reason = reason;
  event.sequence = state.sequence;
  CopyBounded(event.registration_id, sizeof(event.registration_id), registration_id);
  CopyBounded(event.command_id, sizeof(event.command_id), command_id);
  CopyBounded(event.code, sizeof(event.code), code);
  if (state.events.size() >= KWEB_SHORTCUTS_EVENT_CAPACITY) state.events.pop_front();
  state.events.push_back(event);
}

void PublishInvocation(State &state, const Registration &registration, uint32_t source) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_SHORTCUTS_EVENT_INVOKED, source, 0, registration.id,
                  registration.command_id, "");
}

void PublishLost(State &state, const Registration &registration, uint32_t reason) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_SHORTCUTS_EVENT_LOST, 0, reason, registration.id,
                  registration.command_id, "");
}

void PushFailed(State &state, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open) return;
  PushEventLocked(state, KWEB_SHORTCUTS_EVENT_FAILED, 0, 0, "", "", code);
}

/** Fills one ABI registration record from a value the caller already holds. */
void FillRegistration(kweb_shortcuts_registration *target, const Registration &registration) {
  std::memset(target, 0, sizeof(*target));
  CopyBounded(target->registration_id, sizeof(target->registration_id), registration.id);
  CopyBounded(target->command_id, sizeof(target->command_id), registration.command_id);
  target->modifier_bits = registration.modifier_bits;
  target->key_kind = registration.key_kind;
  target->key_code = registration.key_code;
}

/**
 * Reads and validates one registration request. Caller must have validated the
 * request header; reads only, never mutates state.
 */
kweb_shortcuts_status ReadRegistration(const kweb_shortcuts_registration_request &request,
                                       Registration *registration) {
  std::string id;
  std::string command_id;
  if (!ReadString(request.registration_id, KWEB_SHORTCUTS_MAX_ID, &id) ||
      !ReadString(request.command_id, KWEB_SHORTCUTS_MAX_ID, &command_id) ||
      !ValidIdentifier(id) || !ValidIdentifier(command_id)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  if (!ValidModifierBits(request.modifier_bits) || !ValidKey(request.key_kind, request.key_code)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  if (request.key_kind == KWEB_SHORTCUTS_KEY_NAMED && request.modifier_bits == 0) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  registration->id = id;
  registration->command_id = command_id;
  registration->modifier_bits = request.modifier_bits;
  registration->key_kind = request.key_kind;
  registration->key_code = request.key_code;
  return KWEB_SHORTCUTS_STATUS_OK;
}

/** Maps one validated registration onto the declared provider capabilities. */
kweb_shortcuts_status CheckAgainstCapabilities(const Capabilities &capabilities,
                                               const Registration &registration) {
  if ((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION) == 0) {
    return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
  if ((registration.modifier_bits & ~capabilities.modifier_bits) != 0) {
    return KWEB_SHORTCUTS_STATUS_MODIFIER_UNSUPPORTED;
  }
  if (registration.key_kind == KWEB_SHORTCUTS_KEY_MEDIA) {
    if ((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_MEDIA_KEYS) == 0) {
      return KWEB_SHORTCUTS_STATUS_MEDIA_PERMISSION_DENIED;
    }
    const uint32_t bit = 1u << (registration.key_code - 1u);
    if ((capabilities.media_key_bits & bit) == 0) return KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED;
  }
  return KWEB_SHORTCUTS_STATUS_OK;
}

}  // namespace kwebshell::shortcuts

extern "C" {

uint32_t KWEB_SHORTCUTS_CALL kweb_shortcuts_abi_version(void) { return KWEB_SHORTCUTS_ABI_VERSION; }

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_open(
    const kweb_shortcuts_configuration *configuration, uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header = ValidateHeader(configuration->struct_size,
                                                      configuration->abi_version,
                                                      sizeof(kweb_shortcuts_configuration));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::shortcuts::ReadString(configuration->application_id, KWEB_SHORTCUTS_MAX_ID,
                                        &application_id) ||
      application_id.empty() ||
      !kwebshell::shortcuts::ReadString(configuration->package_identity, KWEB_SHORTCUTS_MAX_ID,
                                       &package_identity)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_SHORTCUTS_STATUS_ALREADY_OPEN;
    ResetStateLocked();
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.open = true;
  }
  const kweb_shortcuts_status opened = kwebshell::shortcuts::NativeOpen(g_state);
  if (opened != KWEB_SHORTCUTS_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return opened;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_capabilities(
    uint64_t handle, kweb_shortcuts_capabilities_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                      sizeof(kweb_shortcuts_capabilities_result));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  ResetCapabilitiesResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  kwebshell::shortcuts::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                    g_state.capabilities.provider_id);
  result->capability_bits = g_state.capabilities.capability_bits;
  result->modifier_bits = g_state.capabilities.modifier_bits;
  result->media_key_bits = g_state.capabilities.media_key_bits;
  result->max_registrations = kwebshell::shortcuts::EffectiveMaxRegistrations(g_state.capabilities);
  result->registration_count = static_cast<uint32_t>(g_state.registrations.size());
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_register(
    uint64_t handle, const kweb_shortcuts_registration_request *request,
    kweb_shortcuts_registration_result *result) {
  if (handle != 1 || request == nullptr || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header = ValidateHeader(request->struct_size, request->abi_version,
                                                      sizeof(kweb_shortcuts_registration_request));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  const kweb_shortcuts_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_shortcuts_registration_result));
  if (result_header != KWEB_SHORTCUTS_STATUS_OK) return result_header;
  kwebshell::shortcuts::Registration registration;
  const kweb_shortcuts_status parsed = kwebshell::shortcuts::ReadRegistration(*request, &registration);
  if (parsed != KWEB_SHORTCUTS_STATUS_OK) return parsed;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  if (IndexOfIdLocked(registration.id) >= 0) return KWEB_SHORTCUTS_STATUS_DUPLICATE_ID;
  // The service owns the accelerator space: two live registrations may never
  // share one combination, whether or not the platform would report it.
  for (const kwebshell::shortcuts::Registration &existing : g_state.registrations) {
    if (kwebshell::shortcuts::SameAccelerator(existing, registration)) {
      return KWEB_SHORTCUTS_STATUS_CONFLICT;
    }
  }
  if (g_state.registrations.size() >=
      kwebshell::shortcuts::EffectiveMaxRegistrations(g_state.capabilities)) {
    return KWEB_SHORTCUTS_STATUS_LIMIT_EXCEEDED;
  }
  const kweb_shortcuts_status allowed =
      kwebshell::shortcuts::CheckAgainstCapabilities(g_state.capabilities, registration);
  if (allowed != KWEB_SHORTCUTS_STATUS_OK) return allowed;
  const kweb_shortcuts_status native = kwebshell::shortcuts::NativeRegister(g_state, registration);
  if (native != KWEB_SHORTCUTS_STATUS_OK) return native;
  g_state.registrations.push_back(registration);
  ResetRegistrationResult(result);
  kwebshell::shortcuts::CopyBounded(result->registration_id, sizeof(result->registration_id),
                                    registration.id);
  result->sequence = g_state.sequence;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_replace_all(
    uint64_t handle, const kweb_shortcuts_registration_request *requests, uint32_t count,
    kweb_shortcuts_replacement_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  if (count != 0 && requests == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_shortcuts_replacement_result));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  if (count > KWEB_SHORTCUTS_MAX_REGISTRATIONS) return KWEB_SHORTCUTS_STATUS_LIMIT_EXCEEDED;
  std::vector<kwebshell::shortcuts::Registration> next;
  next.reserve(count);
  for (uint32_t index = 0; index < count; ++index) {
    const kweb_shortcuts_registration_request &request = requests[index];
    const kweb_shortcuts_status element_header = ValidateHeader(
        request.struct_size, request.abi_version, sizeof(kweb_shortcuts_registration_request));
    if (element_header != KWEB_SHORTCUTS_STATUS_OK) return element_header;
    kwebshell::shortcuts::Registration registration;
    const kweb_shortcuts_status parsed = kwebshell::shortcuts::ReadRegistration(request, &registration);
    if (parsed != KWEB_SHORTCUTS_STATUS_OK) return parsed;
    for (const kwebshell::shortcuts::Registration &existing : next) {
      if (existing.id == registration.id) return KWEB_SHORTCUTS_STATUS_DUPLICATE_ID;
    }
    next.push_back(registration);
  }
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  const uint32_t maximum = kwebshell::shortcuts::EffectiveMaxRegistrations(g_state.capabilities);
  if (next.size() > maximum) return KWEB_SHORTCUTS_STATUS_LIMIT_EXCEEDED;
  for (size_t index = 0; index < next.size(); ++index) {
    const kweb_shortcuts_status allowed =
        kwebshell::shortcuts::CheckAgainstCapabilities(g_state.capabilities, next[index]);
    if (allowed != KWEB_SHORTCUTS_STATUS_OK) return allowed;
    for (size_t other = index + 1; other < next.size(); ++other) {
      if (kwebshell::shortcuts::SameAccelerator(next[index], next[other])) {
        return KWEB_SHORTCUTS_STATUS_CONFLICT;
      }
    }
  }
  const std::vector<kwebshell::shortcuts::Registration> previous = g_state.registrations;
  // Atomic swap: release the previous set, install the new one, and restore the
  // previous set exactly if any native registration fails.
  for (const kwebshell::shortcuts::Registration &registration : previous) {
    kwebshell::shortcuts::NativeUnregister(g_state, registration);
  }
  std::vector<kwebshell::shortcuts::Registration> installed;
  for (const kwebshell::shortcuts::Registration &registration : next) {
    const kweb_shortcuts_status native = kwebshell::shortcuts::NativeRegister(g_state, registration);
    if (native != KWEB_SHORTCUTS_STATUS_OK) {
      for (const kwebshell::shortcuts::Registration &undo : installed) {
        kwebshell::shortcuts::NativeUnregister(g_state, undo);
      }
      for (const kwebshell::shortcuts::Registration &restore : previous) {
        kwebshell::shortcuts::NativeRegister(g_state, restore);
      }
      return native;
    }
    installed.push_back(registration);
  }
  g_state.registrations = next;
  ResetReplacementResult(result);
  for (const kwebshell::shortcuts::Registration &registration : previous) {
    const bool kept = std::any_of(next.begin(), next.end(),
                                  [&registration](const kwebshell::shortcuts::Registration &candidate) {
                                    return candidate.id == registration.id;
                                  });
    if (!kept) {
      kwebshell::shortcuts::PushEventLocked(g_state, KWEB_SHORTCUTS_EVENT_REMOVED, 0,
                                            KWEB_SHORTCUTS_REMOVAL_REPLACED, registration.id,
                                            registration.command_id, "");
      result->removed_count += 1u;
    }
  }
  result->applied_count = static_cast<uint32_t>(next.size());
  result->sequence = g_state.sequence;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_unregister(
    uint64_t handle, const kweb_shortcuts_query *query, kweb_shortcuts_unregistration_result *result) {
  if (handle != 1 || query == nullptr || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header =
      ValidateHeader(query->struct_size, query->abi_version, sizeof(kweb_shortcuts_query));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  const kweb_shortcuts_status result_header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_shortcuts_unregistration_result));
  if (result_header != KWEB_SHORTCUTS_STATUS_OK) return result_header;
  std::string id;
  if (!kwebshell::shortcuts::ReadString(query->value, KWEB_SHORTCUTS_MAX_ID, &id) ||
      !kwebshell::shortcuts::ValidIdentifier(id)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  ResetUnregistrationResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  const int index = IndexOfIdLocked(id);
  if (index < 0) return KWEB_SHORTCUTS_STATUS_OK;
  const kwebshell::shortcuts::Registration registration = g_state.registrations[static_cast<size_t>(index)];
  const kweb_shortcuts_status native = kwebshell::shortcuts::NativeUnregister(g_state, registration);
  g_state.registrations.erase(g_state.registrations.begin() + index);
  kwebshell::shortcuts::PushEventLocked(g_state, KWEB_SHORTCUTS_EVENT_REMOVED, 0,
                                        KWEB_SHORTCUTS_REMOVAL_UNREGISTERED, registration.id,
                                        registration.command_id, "");
  result->was_registered = 1u;
  if (native != KWEB_SHORTCUTS_STATUS_OK) return native;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_is_registered(
    uint64_t handle, const kweb_shortcuts_query *query, kweb_shortcuts_presence_result *result) {
  if (handle != 1 || query == nullptr || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header =
      ValidateHeader(query->struct_size, query->abi_version, sizeof(kweb_shortcuts_query));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  const kweb_shortcuts_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_shortcuts_presence_result));
  if (result_header != KWEB_SHORTCUTS_STATUS_OK) return result_header;
  std::string id;
  if (!kwebshell::shortcuts::ReadString(query->value, KWEB_SHORTCUTS_MAX_ID, &id) ||
      !kwebshell::shortcuts::ValidIdentifier(id)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  ResetPresenceResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  result->registered = IndexOfIdLocked(id) >= 0 ? 1u : 0u;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_registrations(
    uint64_t handle, kweb_shortcuts_list_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_shortcuts_list_result));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  if (result->capacity != 0 && result->registrations == nullptr) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  const uint32_t capacity = result->capacity;
  kweb_shortcuts_registration *buffer = result->registrations;
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  result->capacity = capacity;
  result->registrations = buffer;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  result->count = static_cast<uint32_t>(g_state.registrations.size());
  if (result->count > capacity) return KWEB_SHORTCUTS_STATUS_BUFFER_TOO_SMALL;
  for (size_t index = 0; index < g_state.registrations.size(); ++index) {
    kwebshell::shortcuts::FillRegistration(&buffer[index], g_state.registrations[index]);
  }
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_dispatch(
    uint64_t handle, const kweb_shortcuts_query *command, kweb_shortcuts_registration_result *result) {
  if (handle != 1 || command == nullptr || result == nullptr) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  const kweb_shortcuts_status header =
      ValidateHeader(command->struct_size, command->abi_version, sizeof(kweb_shortcuts_query));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  const kweb_shortcuts_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_shortcuts_registration_result));
  if (result_header != KWEB_SHORTCUTS_STATUS_OK) return result_header;
  std::string command_id;
  if (!kwebshell::shortcuts::ReadString(command->value, KWEB_SHORTCUTS_MAX_ID, &command_id) ||
      !kwebshell::shortcuts::ValidIdentifier(command_id)) {
    return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  }
  ResetRegistrationResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  for (const kwebshell::shortcuts::Registration &registration : g_state.registrations) {
    if (registration.command_id != command_id) continue;
    kwebshell::shortcuts::PushEventLocked(g_state, KWEB_SHORTCUTS_EVENT_INVOKED,
                                          KWEB_SHORTCUTS_SOURCE_RENDERER, 0, registration.id,
                                          registration.command_id, "");
    kwebshell::shortcuts::CopyBounded(result->registration_id, sizeof(result->registration_id),
                                      registration.id);
    result->sequence = g_state.sequence;
    return KWEB_SHORTCUTS_STATUS_OK;
  }
  return KWEB_SHORTCUTS_STATUS_COMMAND_UNDECLARED;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_poll_event(uint64_t handle,
                                                                    kweb_shortcuts_event *event) {
  if (handle != 1 || event == nullptr) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  const kweb_shortcuts_status header =
      ValidateHeader(event->struct_size, event->abi_version, sizeof(kweb_shortcuts_event));
  if (header != KWEB_SHORTCUTS_STATUS_OK) return header;
  ResetEvent(event);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_SHORTCUTS_STATUS_NO_EVENT;
  const kweb_shortcuts_event next = g_state.events.front();
  g_state.events.pop_front();
  *event = next;
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_close(uint64_t handle) {
  if (handle != 1) return KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SHORTCUTS_STATUS_OWNER_CLOSED;
    g_state.open = false;
    g_state.events.clear();
    g_state.registrations.clear();
  }
  const kweb_shortcuts_status status = kwebshell::shortcuts::NativeClose(g_state);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
  }
  g_live_count.fetch_sub(1);
  return status;
}

}  // extern "C"
