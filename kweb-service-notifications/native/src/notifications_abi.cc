#include "notifications_internal.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstring>

namespace {
std::mutex g_registry_mutex;
kwebshell::notifications::State g_state;
std::atomic<uint32_t> g_live_count{0};

bool ValidEnum(uint32_t value, uint32_t first, uint32_t last) {
  return value >= first && value <= last;
}

bool ValidRequest(const kweb_notifications_request &request) {
  if (!ValidEnum(request.icon, KWEB_NOTIFICATIONS_ICON_APPLICATION,
                 KWEB_NOTIFICATIONS_ICON_APPLICATION) ||
      !ValidEnum(request.urgency, KWEB_NOTIFICATIONS_URGENCY_LOW,
                 KWEB_NOTIFICATIONS_URGENCY_HIGH) ||
      !ValidEnum(request.timeout, KWEB_NOTIFICATIONS_TIMEOUT_SYSTEM,
                 KWEB_NOTIFICATIONS_TIMEOUT_PERSISTENT) ||
      request.action_count > KWEB_NOTIFICATIONS_MAX_ACTIONS) {
    return false;
  }
  std::string value;
  if (!kwebshell::notifications::ReadString(request.id, KWEB_NOTIFICATIONS_MAX_ID, &value) || value.empty()) return false;
  if (!kwebshell::notifications::ReadString(request.tag, KWEB_NOTIFICATIONS_MAX_TAG, &value)) return false;
  if (!kwebshell::notifications::ReadString(request.title, KWEB_NOTIFICATIONS_MAX_TITLE, &value) || value.empty()) return false;
  if (!kwebshell::notifications::ReadString(request.body, KWEB_NOTIFICATIONS_MAX_BODY, &value) || value.empty()) return false;
  for (uint32_t index = 0; index < request.action_count; ++index) {
    const auto &action = request.actions[index];
    if (!ValidEnum(action.kind, KWEB_NOTIFICATIONS_ACTION_BUTTON, KWEB_NOTIFICATIONS_ACTION_REPLY) ||
        !kwebshell::notifications::ReadString(action.id, KWEB_NOTIFICATIONS_MAX_ACTION_ID, &value) || value.empty() ||
        !kwebshell::notifications::ReadString(action.title, KWEB_NOTIFICATIONS_MAX_ACTION_TITLE, &value) || value.empty() ||
        !kwebshell::notifications::ReadString(action.reply_placeholder, KWEB_NOTIFICATIONS_MAX_REPLY, &value)) {
      return false;
    }
  }
  return true;
}

void ResetPermission(kweb_notifications_permission_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
}

void ResetCapabilities(kweb_notifications_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
}

void ResetEvent(kweb_notifications_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
}

bool ValidHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  return struct_size >= expected && abi_version == KWEB_NOTIFICATIONS_ABI_VERSION;
}
}  // namespace

namespace kwebshell::notifications {

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  if (capacity == 0) return;
  const size_t count = std::min(capacity - 1u, value.size());
  std::memcpy(target, value.data(), count);
  target[count] = '\0';
}

bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr && size != 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first <= 0x7f) { ++index; continue; }
    if (first >= 0xc2 && first <= 0xdf) continuation = 1;
    else if (first == 0xe0) { continuation = 2; second_min = 0xa0; }
    else if (first >= 0xe1 && first <= 0xec) continuation = 2;
    else if (first == 0xed) { continuation = 2; second_max = 0x9f; }
    else if (first >= 0xee && first <= 0xef) continuation = 2;
    else if (first == 0xf0) { continuation = 3; second_min = 0x90; }
    else if (first >= 0xf1 && first <= 0xf3) continuation = 3;
    else if (first == 0xf4) { continuation = 3; second_max = 0x8f; }
    else return false;
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

bool ReadString(const kweb_notifications_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) ||
      (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

void PushAction(State &state, const std::string &id, const std::string &action_id, const std::string &reply) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_NOTIFICATIONS_EVENT_CAPACITY) return;
  kweb_notifications_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  event.kind = KWEB_NOTIFICATIONS_EVENT_ACTION;
  event.sequence = ++state.sequence;
  CopyBounded(event.id, sizeof(event.id), id);
  CopyBounded(event.action_id, sizeof(event.action_id), action_id);
  CopyBounded(event.reply, sizeof(event.reply), reply);
  state.events.push_back(event);
  std::fprintf(
      stderr,
      "notification PushAction queued id=%s action=%s events=%zu\\n",
      id.c_str(), action_id.c_str(), state.events.size());
}

void PushClosed(State &state, const std::string &id, kweb_notifications_close_reason reason) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_NOTIFICATIONS_EVENT_CAPACITY) return;
  kweb_notifications_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  event.kind = KWEB_NOTIFICATIONS_EVENT_CLOSED;
  event.close_reason = reason;
  event.sequence = ++state.sequence;
  CopyBounded(event.id, sizeof(event.id), id);
  state.events.push_back(event);
}

void PushFailed(State &state, const std::string &id, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_NOTIFICATIONS_EVENT_CAPACITY) return;
  kweb_notifications_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  event.kind = KWEB_NOTIFICATIONS_EVENT_FAILED;
  event.sequence = ++state.sequence;
  CopyBounded(event.id, sizeof(event.id), id);
  CopyBounded(event.code, sizeof(event.code), code);
  state.events.push_back(event);
}

}  // namespace kwebshell::notifications

extern "C" {

uint32_t kweb_notifications_abi_version(void) { return KWEB_NOTIFICATIONS_ABI_VERSION; }

const char *kweb_notifications_status_name(kweb_notifications_status status) {
  switch (status) {
    case KWEB_NOTIFICATIONS_STATUS_OK: return "ok";
    case KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_NOTIFICATIONS_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_NOTIFICATIONS_STATUS_PERMISSION_DENIED: return "permission-denied";
    case KWEB_NOTIFICATIONS_STATUS_PERMISSION_UNDETERMINED: return "permission-undetermined";
    case KWEB_NOTIFICATIONS_STATUS_ACTIONS_UNSUPPORTED: return "actions-unsupported";
    case KWEB_NOTIFICATIONS_STATUS_REPLY_UNSUPPORTED: return "reply-unsupported";
    case KWEB_NOTIFICATIONS_STATUS_TIMEOUT_UNSUPPORTED: return "timeout-unsupported";
    case KWEB_NOTIFICATIONS_STATUS_NOT_FOUND: return "not-found";
    case KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED: return "native-failed";
    case KWEB_NOTIFICATIONS_STATUS_NO_EVENT: return "no-event";
    default: return "unknown";
  }
}

const char *kweb_notifications_provider_id(void) { return kwebshell::notifications::ProviderId(); }

kweb_notifications_status kweb_notifications_open(
    const kweb_notifications_configuration *configuration, uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  if (!ValidHeader(configuration->struct_size, configuration->abi_version, sizeof(*configuration)) ||
      configuration->reserved != 0) {
    return KWEB_NOTIFICATIONS_STATUS_ABI_MISMATCH;
  }
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::notifications::ReadString(configuration->application_id, 127, &application_id) ||
      application_id.empty() ||
      !kwebshell::notifications::ReadString(configuration->package_identity, 255, &package_identity)) {
    return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  g_state.application_id = std::move(application_id);
  g_state.package_identity = std::move(package_identity);
  const auto status = kwebshell::notifications::NativeOpen(g_state);
  if (status != KWEB_NOTIFICATIONS_STATUS_OK) return status;
  g_state.open = true;
  g_live_count.store(1);
  *handle = 1;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status kweb_notifications_permission(
    uint64_t handle, kweb_notifications_permission_result *result) {
  if (result == nullptr) return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  ResetPermission(result);
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  kwebshell::notifications::NativePump(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return kwebshell::notifications::NativePermission(g_state, false, result);
}

kweb_notifications_status kweb_notifications_request_permission(
    uint64_t handle, kweb_notifications_permission_result *result) {
  if (result == nullptr) return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  ResetPermission(result);
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return kwebshell::notifications::NativePermission(g_state, true, result);
}

kweb_notifications_status kweb_notifications_capabilities(
    uint64_t handle, kweb_notifications_capabilities_result *result) {
  if (result == nullptr) return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  ResetCapabilities(result);
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return kwebshell::notifications::NativeCapabilities(g_state, result);
}

kweb_notifications_status kweb_notifications_show(
    uint64_t handle, const kweb_notifications_request *request) {
  if (request == nullptr || !ValidHeader(request->struct_size, request->abi_version, sizeof(*request))) {
    return KWEB_NOTIFICATIONS_STATUS_ABI_MISMATCH;
  }
  if (!ValidRequest(*request)) return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return kwebshell::notifications::NativeShow(g_state, *request);
}

kweb_notifications_status kweb_notifications_close_notification(
    uint64_t handle, kweb_notifications_string id) {
  std::string value;
  if (!kwebshell::notifications::ReadString(id, KWEB_NOTIFICATIONS_MAX_ID, &value) || value.empty()) {
    return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  return kwebshell::notifications::NativeCloseNotification(g_state, value);
}

kweb_notifications_status kweb_notifications_poll_event(
    uint64_t handle, kweb_notifications_event *event) {
  if (event == nullptr || !ValidHeader(event->struct_size, event->abi_version, sizeof(*event))) {
    return KWEB_NOTIFICATIONS_STATUS_ABI_MISMATCH;
  }
  ResetEvent(event);
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  kwebshell::notifications::NativePump(g_state);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (g_state.events.empty()) return KWEB_NOTIFICATIONS_STATUS_NO_EVENT;
  *event = g_state.events.front();
  g_state.events.pop_front();
  std::fprintf(
      stderr,
      "notification poll_event kind=%u id=%s action=%s remaining=%zu\\n",
      event->kind, event->id, event->action_id, g_state.events.size());
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status kweb_notifications_close(uint64_t handle) {
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  if (handle != 1 || !g_state.open) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  const auto status = kwebshell::notifications::NativeClose(g_state);
  if (status == KWEB_NOTIFICATIONS_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.open = false;
    g_state.events.clear();
    g_live_count.store(0);
  }
  return status;
}

uint32_t kweb_notifications_live_count(void) { return g_live_count.load(); }

}  // extern "C"
