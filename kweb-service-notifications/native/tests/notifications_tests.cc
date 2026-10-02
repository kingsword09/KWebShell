#include "kweb_notifications.h"

#include <cassert>
#include <cstring>

int main() {
  assert(kweb_notifications_abi_version() == KWEB_NOTIFICATIONS_ABI_VERSION);
  assert(kweb_notifications_provider_id() != nullptr);
  assert(kweb_notifications_live_count() == 0);

  kweb_notifications_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  assert(kweb_notifications_poll_event(0, &event) == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE);

  kweb_notifications_configuration configuration{};
  configuration.struct_size = sizeof(configuration);
  configuration.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  const char application[] = "io.github.kwebshell.test";
  configuration.application_id = {reinterpret_cast<const uint8_t *>(application), std::strlen(application)};
  uint64_t handle = 0;
  const auto status = kweb_notifications_open(&configuration, &handle);
#if defined(__linux__) || defined(__APPLE__)
  if (status == KWEB_NOTIFICATIONS_STATUS_OK) {
    assert(handle == 1);
    assert(kweb_notifications_close(handle) == KWEB_NOTIFICATIONS_STATUS_OK);
    assert(kweb_notifications_live_count() == 0);
  } else {
    assert(status == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE ||
           status == KWEB_NOTIFICATIONS_STATUS_PERMISSION_DENIED);
  }
#else
  assert(status == KWEB_NOTIFICATIONS_STATUS_OK ||
         status == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE);
  if (status == KWEB_NOTIFICATIONS_STATUS_OK) {
    assert(kweb_notifications_close(handle) == KWEB_NOTIFICATIONS_STATUS_OK);
  }
#endif
  return 0;
}
