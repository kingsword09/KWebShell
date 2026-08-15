#include "kwebshell/native/engine_abi.h"
#include "kwebshell/native/cef_extension_abi.h"

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
_Static_assert(KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISSING == 47,
               "the missing extension adapter status is ABI-stable");
_Static_assert(KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISMATCH == 48,
               "the extension adapter mismatch status is ABI-stable");
_Static_assert(KWEB_STATUS_EXTENSION_OPERATION_INVALID == 49,
               "the invalid extension operation status is ABI-stable");
_Static_assert(KWEB_STATUS_EXTENSION_OPERATION_ACTIVE == 50,
               "the active extension operation status is ABI-stable");
_Static_assert(KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND == 51,
               "the missing extension operation status is ABI-stable");
_Static_assert(KWEB_STATUS_EXTENSION_RESULT_INVALID == 52,
               "the invalid extension result status is ABI-stable");
_Static_assert(KWEB_INVALID_EXTENSION_OPERATION_HANDLE == 0,
               "the invalid extension operation handle must remain zero");
_Static_assert(KWEB_EXTENSION_OPERATION_INSTALL == 1,
               "the install operation is ABI-stable");
_Static_assert(KWEB_EXTENSION_OPERATION_QUERY == 5,
               "the query operation is ABI-stable");
_Static_assert(KWEB_EXTENSION_OUTCOME_AMBIGUOUS == 3,
               "the ambiguous outcome is ABI-stable");
_Static_assert(KWEB_EXTENSION_STATE_BLOCKED == 6,
               "the blocked extension state is ABI-stable");
_Static_assert(CEF_KWEB_EXTENSION_ABI_VERSION == 1,
               "the CEF extension adapter ABI version is stable");
_Static_assert(sizeof(CEF_KWEB_EXTENSION_ABI_FINGERPRINT) == 65,
               "the CEF extension adapter fingerprint is SHA-256 hex");
_Static_assert(CEF_KWEB_EXTENSION_OPERATION_INSTALL ==
                   KWEB_EXTENSION_OPERATION_INSTALL,
               "CEF and engine install values must match");
_Static_assert(CEF_KWEB_EXTENSION_OUTCOME_AMBIGUOUS ==
                   KWEB_EXTENSION_OUTCOME_AMBIGUOUS,
               "CEF and engine ambiguous values must match");
_Static_assert(CEF_KWEB_EXTENSION_STATE_BLOCKED == KWEB_EXTENSION_STATE_BLOCKED,
               "CEF and engine state values must match");
#if UINTPTR_MAX == UINT64_MAX
_Static_assert(sizeof(cef_kweb_extension_string_view) == 16,
               "the 64-bit CEF string view layout is ABI-stable");
_Static_assert(sizeof(cef_kweb_extension_result) == 112,
               "the 64-bit CEF result layout is ABI-stable");
_Static_assert(offsetof(cef_kweb_extension_result, extension_id) == 32,
               "the CEF result string payload offset is ABI-stable");
_Static_assert(sizeof(cef_kweb_extension_request) == 104,
               "the 64-bit CEF request layout is ABI-stable");
_Static_assert(offsetof(cef_kweb_extension_request, callback) == 88,
               "the CEF request callback offset is ABI-stable");
_Static_assert(offsetof(cef_kweb_extension_request, user_data) == 96,
               "the CEF request user-data offset is ABI-stable");
_Static_assert(sizeof(kweb_extension_config) == 80,
               "the 64-bit engine extension config layout is ABI-stable");
_Static_assert(offsetof(kweb_extension_config, callback) == 64,
               "the engine extension callback offset is ABI-stable");
_Static_assert(sizeof(kweb_extension_result) == 128,
               "the 64-bit engine extension result layout is ABI-stable");
_Static_assert(offsetof(kweb_extension_result, extension_id) == 48,
               "the engine extension result payload offset is ABI-stable");
#endif
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

static void KWEB_ABI_CALL receive_extension_result(
    void *user_data, const kweb_extension_result *result) {
  uint64_t *operation = (uint64_t *)user_data;
  if (result != NULL) {
    *operation = result->operation_handle;
  }
}

static void KWEB_CEF_CALLBACK receive_cef_extension_result(
    void *user_data, const cef_kweb_extension_result *result) {
  uint64_t *operation = (uint64_t *)user_data;
  if (result != NULL) {
    *operation = result->operation_id;
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
  const kweb_extension_config extension_configuration = {
      (uint32_t)sizeof(kweb_extension_config),
      KWEB_ABI_VERSION,
      KWEB_EXTENSION_OPERATION_INSTALL,
      0,
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {"1.0.0", 5},
      {"/managed/extension", 18},
      receive_extension_result,
      &sequence,
  };
  const kweb_extension_result extension_result = {
      (uint32_t)sizeof(kweb_extension_result),
      KWEB_ABI_VERSION,
      4,
      KWEB_EXTENSION_OPERATION_INSTALL,
      KWEB_EXTENSION_OUTCOME_SUCCESS,
      KWEB_EXTENSION_STATE_ENABLED,
      0,
      1,
      1,
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {"1.0.0", 5},
      {"/managed/extension", 18},
      {NULL, 0},
      {NULL, 0},
  };
  const cef_kweb_extension_request cef_extension_request = {
      (uint32_t)sizeof(cef_kweb_extension_request),
      CEF_KWEB_EXTENSION_ABI_VERSION,
      5,
      CEF_KWEB_EXTENSION_OPERATION_QUERY,
      0,
      {"/cache/Profile", 14},
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {NULL, 0},
      {NULL, 0},
      receive_cef_extension_result,
      &sequence,
  };
  const cef_kweb_extension_result cef_extension_result = {
      (uint32_t)sizeof(cef_kweb_extension_result),
      CEF_KWEB_EXTENSION_ABI_VERSION,
      5,
      CEF_KWEB_EXTENSION_OPERATION_QUERY,
      CEF_KWEB_EXTENSION_OUTCOME_SUCCESS,
      CEF_KWEB_EXTENSION_STATE_ABSENT,
      0,
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {NULL, 0},
      {NULL, 0},
      {NULL, 0},
      {NULL, 0},
  };
  configuration.callback(configuration.user_data, &event);
  browser_configuration.callback(browser_configuration.user_data,
                                 &browser_event);
  browser_configuration.bridge_callback(
      browser_configuration.bridge_user_data, &bridge_event);
  extension_configuration.callback(extension_configuration.user_data,
                                   &extension_result);
  cef_extension_request.callback(cef_extension_request.user_data,
                                 &cef_extension_result);
  if (configuration.struct_size != sizeof(kweb_engine_config) ||
      browser_configuration.struct_size != sizeof(kweb_browser_config) ||
      event.struct_size != sizeof(kweb_engine_event) ||
      browser_event.struct_size != sizeof(kweb_browser_event) ||
      bridge_event.struct_size != sizeof(kweb_bridge_event) ||
      extension_configuration.struct_size != sizeof(kweb_extension_config) ||
      extension_result.struct_size != sizeof(kweb_extension_result) ||
      cef_extension_request.struct_size != sizeof(cef_kweb_extension_request) ||
      cef_extension_result.struct_size != sizeof(cef_kweb_extension_result) ||
      sequence != 5) {
    return 1;
  }
  return 0;
}
