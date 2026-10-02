#ifndef KWEB_NOTIFICATIONS_INTERNAL_H_
#define KWEB_NOTIFICATIONS_INTERNAL_H_

#include "kweb_notifications.h"

#include <deque>
#include <mutex>
#include <string>
#include <unordered_map>

namespace kwebshell::notifications {

struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 0;
  void *platform = nullptr;
  std::deque<kweb_notifications_event> events;
  std::unordered_map<std::string, uint32_t> native_ids;
};

const char *ProviderId();

bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_notifications_string &value, size_t maximum, std::string *output);
void PushAction(State &state, const std::string &id, const std::string &action_id, const std::string &reply);
void PushClosed(State &state, const std::string &id, kweb_notifications_close_reason reason);
void PushFailed(State &state, const std::string &id, const std::string &code);

kweb_notifications_status NativeOpen(State &state);
kweb_notifications_status NativePermission(State &state, bool request,
                                            kweb_notifications_permission_result *result);
kweb_notifications_status NativeCapabilities(State &state,
                                               kweb_notifications_capabilities_result *result);
kweb_notifications_status NativeShow(State &state, const kweb_notifications_request &request);
kweb_notifications_status NativeCloseNotification(State &state, const std::string &id);
kweb_notifications_status NativeClose(State &state);
void NativePump(State &state);

}  // namespace kwebshell::notifications

#endif
