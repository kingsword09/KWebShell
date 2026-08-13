#include "kwebshell/native/engine_abi.h"

#include <stddef.h>
#include <stdint.h>

_Static_assert(KWEB_INVALID_ENGINE_HANDLE == 0,
               "the invalid engine handle must remain zero");
_Static_assert(KWEB_ENGINE_EVENT_OPENED != KWEB_ENGINE_EVENT_CLOSED,
               "engine lifecycle events must remain distinct");
_Static_assert(KWEB_INVALID_BROWSER_HANDLE == 0,
               "the invalid browser handle must remain zero");
_Static_assert(KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE == 39,
               "the remote debugging port unavailable status is ABI-stable");
_Static_assert(KWEB_STATUS_DEVTOOLS_ALREADY_OPEN == 40,
               "the DevTools duplicate-open status is ABI-stable");
_Static_assert(KWEB_STATUS_DEVTOOLS_NOT_OPEN == 41,
               "the DevTools missing-close status is ABI-stable");
_Static_assert(KWEB_STATUS_DEVTOOLS_OPEN_FAILED == 42,
               "the DevTools open failure status is ABI-stable");
_Static_assert(KWEB_STATUS_DEVTOOLS_CLOSING == 43,
               "the DevTools closing status is ABI-stable");
_Static_assert(KWEB_STATUS_BRIDGE_ORIGIN_INVALID == 44,
               "the bridge origin status is ABI-stable");
_Static_assert(KWEB_STATUS_BRIDGE_REQUEST_NOT_FOUND == 45,
               "the bridge request lookup status is ABI-stable");
_Static_assert(KWEB_STATUS_BRIDGE_RESPONSE_INVALID == 46,
               "the bridge response validation status is ABI-stable");
_Static_assert(KWEB_BROWSER_EVENT_DEVTOOLS_OPENED == 11,
               "the DevTools opened event is ABI-stable");
_Static_assert(KWEB_BROWSER_EVENT_DEVTOOLS_CLOSED == 12,
               "the DevTools closed event is ABI-stable");
_Static_assert(KWEB_BROWSER_EVENT_DEVTOOLS_FAILED == 13,
               "the DevTools failed event is ABI-stable");
_Static_assert(KWEB_BROWSER_EVENT_CREATED != KWEB_BROWSER_EVENT_CLOSED,
               "browser lifecycle events must remain distinct");

static void KWEB_ABI_CALL receive_engine_event(void *user_data,
                                               const kweb_engine_event *event) {
  uint64_t *sequence = (uint64_t *)user_data;
  if (event != NULL) {
    *sequence = event->sequence;
  }
}

static void KWEB_ABI_CALL receive_browser_event(
    void *user_data, const kweb_browser_event *event) {
  uint64_t *sequence = (uint64_t *)user_data;
  if (event != NULL) {
    *sequence = event->sequence;
  }
}

static void KWEB_ABI_CALL receive_bridge_event(
    void *user_data, const kweb_bridge_event *event) {
  uint64_t *request_id = (uint64_t *)user_data;
  if (event != NULL) {
    *request_id = event->request_id;
  }
}

int main(void) {
  uint64_t sequence = 0;
  const kweb_engine_config configuration = {
      (uint32_t)sizeof(kweb_engine_config),
      KWEB_ABI_VERSION,
      receive_engine_event,
      &sequence,
      {"/runtime", 8},
      {"/subprocess", 11},
      {"/resources", 10},
      {"/locales", 8},
      {"/cache", 6},
      {"/cache/cef.log", 14},
      0,
      0,
  };
  const kweb_engine_event event = {(uint32_t)sizeof(kweb_engine_event),
                                   KWEB_ABI_VERSION,
                                   KWEB_ENGINE_EVENT_OPENED,
                                   0,
                                   1,
                                   1};
  const kweb_browser_config browser_configuration = {
      (uint32_t)sizeof(kweb_browser_config),
      KWEB_ABI_VERSION,
      1,
      0,
      (uintptr_t)1,
      0,
      0,
      800,
      600,
      {"/cache/Profile", 14},
      {"https://example.test", 20},
      receive_browser_event,
      &sequence,
      {"https://example.test", 20},
      receive_bridge_event,
      &sequence,
  };
  const kweb_browser_event browser_event = {
      (uint32_t)sizeof(kweb_browser_event),
      KWEB_ABI_VERSION,
      KWEB_BROWSER_EVENT_CREATED,
      0,
      1,
      1,
      2,
      {"", 0},
      0,
      800,
      600,
                                   0,
  };
  const kweb_bridge_event bridge_event = {
      (uint32_t)sizeof(kweb_bridge_event), KWEB_ABI_VERSION,
      KWEB_BRIDGE_EVENT_REQUEST, 0, 1, 1, 3, {"{}", 2}};
  configuration.callback(configuration.user_data, &event);
  browser_configuration.callback(browser_configuration.user_data,
                                 &browser_event);
  browser_configuration.bridge_callback(
      browser_configuration.bridge_user_data, &bridge_event);
  if (configuration.struct_size != sizeof(kweb_engine_config) ||
      browser_configuration.struct_size != sizeof(kweb_browser_config) ||
      event.struct_size != sizeof(kweb_engine_event) ||
      browser_event.struct_size != sizeof(kweb_browser_event) ||
      bridge_event.struct_size != sizeof(kweb_bridge_event) || sequence != 3) {
    return 1;
  }
  return 0;
}
