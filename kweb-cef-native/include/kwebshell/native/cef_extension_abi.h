#ifndef KWEBSHELL_NATIVE_CEF_EXTENSION_ABI_H_
#define KWEBSHELL_NATIVE_CEF_EXTENSION_ABI_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define KWEB_CEF_CALLBACK __stdcall
#else
#define KWEB_CEF_CALLBACK
#endif

#define CEF_KWEB_EXTENSION_ABI_VERSION ((uint32_t)1)
#define CEF_KWEB_EXTENSION_ABI_FINGERPRINT                                     \
  "8561856986d1c16cbb95294d7ad3f1e27bed9e102abc0f669a073161614a9c44"

typedef uint32_t cef_kweb_extension_status_t;
typedef uint32_t cef_kweb_extension_operation_t;
typedef uint32_t cef_kweb_extension_outcome_t;
typedef uint32_t cef_kweb_extension_state_t;

#define CEF_KWEB_EXTENSION_STATUS_OK ((cef_kweb_extension_status_t)0)
#define CEF_KWEB_EXTENSION_STATUS_INVALID_ARGUMENT                             \
  ((cef_kweb_extension_status_t)1)
#define CEF_KWEB_EXTENSION_STATUS_ABI_MISMATCH ((cef_kweb_extension_status_t)2)
#define CEF_KWEB_EXTENSION_STATUS_WRONG_THREAD ((cef_kweb_extension_status_t)3)
#define CEF_KWEB_EXTENSION_STATUS_DUPLICATE_OPERATION                          \
  ((cef_kweb_extension_status_t)4)
#define CEF_KWEB_EXTENSION_STATUS_OPERATION_NOT_FOUND                          \
  ((cef_kweb_extension_status_t)5)
#define CEF_KWEB_EXTENSION_STATUS_INTERNAL_ERROR                               \
  ((cef_kweb_extension_status_t)6)

#define CEF_KWEB_EXTENSION_OPERATION_INSTALL ((cef_kweb_extension_operation_t)1)
#define CEF_KWEB_EXTENSION_OPERATION_UPDATE ((cef_kweb_extension_operation_t)2)
#define CEF_KWEB_EXTENSION_OPERATION_RELOAD ((cef_kweb_extension_operation_t)3)
#define CEF_KWEB_EXTENSION_OPERATION_UNINSTALL                                 \
  ((cef_kweb_extension_operation_t)4)
#define CEF_KWEB_EXTENSION_OPERATION_QUERY ((cef_kweb_extension_operation_t)5)

#define CEF_KWEB_EXTENSION_OUTCOME_SUCCESS ((cef_kweb_extension_outcome_t)1)
#define CEF_KWEB_EXTENSION_OUTCOME_REJECTED ((cef_kweb_extension_outcome_t)2)
#define CEF_KWEB_EXTENSION_OUTCOME_AMBIGUOUS ((cef_kweb_extension_outcome_t)3)

#define CEF_KWEB_EXTENSION_STATE_UNKNOWN ((cef_kweb_extension_state_t)0)
#define CEF_KWEB_EXTENSION_STATE_ABSENT ((cef_kweb_extension_state_t)1)
#define CEF_KWEB_EXTENSION_STATE_ENABLED ((cef_kweb_extension_state_t)2)
#define CEF_KWEB_EXTENSION_STATE_DISABLED ((cef_kweb_extension_state_t)3)
#define CEF_KWEB_EXTENSION_STATE_TERMINATED ((cef_kweb_extension_state_t)4)
#define CEF_KWEB_EXTENSION_STATE_BLOCKLISTED ((cef_kweb_extension_state_t)5)
#define CEF_KWEB_EXTENSION_STATE_BLOCKED ((cef_kweb_extension_state_t)6)

typedef struct cef_kweb_extension_string_view {
  const char *data;
  size_t size;
} cef_kweb_extension_string_view;

typedef struct cef_kweb_extension_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t operation_id;
  cef_kweb_extension_operation_t operation;
  cef_kweb_extension_outcome_t outcome;
  cef_kweb_extension_state_t state;
  uint32_t reserved;
  cef_kweb_extension_string_view extension_id;
  cef_kweb_extension_string_view version;
  cef_kweb_extension_string_view path;
  cef_kweb_extension_string_view error_code;
  cef_kweb_extension_string_view error_message;
} cef_kweb_extension_result;

typedef void(KWEB_CEF_CALLBACK *cef_kweb_extension_result_callback)(
    void *user_data, const cef_kweb_extension_result *result);

typedef struct cef_kweb_extension_request {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t operation_id;
  cef_kweb_extension_operation_t operation;
  uint32_t reserved;
  cef_kweb_extension_string_view profile_path;
  cef_kweb_extension_string_view extension_id;
  cef_kweb_extension_string_view expected_version;
  cef_kweb_extension_string_view extension_path;
  cef_kweb_extension_result_callback callback;
  void *user_data;
} cef_kweb_extension_request;

typedef const char *(*cef_kweb_extension_abi_fingerprint_fn)(void);
typedef cef_kweb_extension_status_t (*cef_kweb_extension_start_fn)(
    const cef_kweb_extension_request *request);
// A successful cancellation keeps the operation alive until exactly one
// terminal callback reports that any dispatched Chromium work has settled.
typedef cef_kweb_extension_status_t (*cef_kweb_extension_cancel_fn)(
    uint64_t operation_id);
typedef uint64_t (*cef_kweb_extension_live_operation_count_fn)(void);

#endif // KWEBSHELL_NATIVE_CEF_EXTENSION_ABI_H_
