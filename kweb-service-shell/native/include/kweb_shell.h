#ifndef KWEB_SHELL_H_
#define KWEB_SHELL_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define KWEB_SHELL_EXPORT __declspec(dllexport)
#else
#define KWEB_SHELL_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_SHELL_CALL __cdecl
#else
#define KWEB_SHELL_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_SHELL_ABI_VERSION 1u

typedef uint32_t kweb_shell_status;
#define KWEB_SHELL_STATUS_OK 0u
#define KWEB_SHELL_STATUS_INVALID_ARGUMENT 1u
#define KWEB_SHELL_STATUS_ABI_MISMATCH 2u
#define KWEB_SHELL_STATUS_NATIVE_UNAVAILABLE 3u
#define KWEB_SHELL_STATUS_HANDLER_REJECTED 4u
#define KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE 5u
#define KWEB_SHELL_STATUS_TRASH_FAILED 6u
#define KWEB_SHELL_STATUS_TRASH_VERIFICATION_FAILED 7u
#define KWEB_SHELL_STATUS_NATIVE_FAILED 8u

typedef uint32_t kweb_shell_action;
#define KWEB_SHELL_ACTION_OPEN_EXTERNAL 1u
#define KWEB_SHELL_ACTION_OPEN_RESOURCE 2u
#define KWEB_SHELL_ACTION_REVEAL_RESOURCE 3u
#define KWEB_SHELL_ACTION_TRASH_RESOURCE 4u

typedef uint32_t kweb_shell_resource_kind;
#define KWEB_SHELL_RESOURCE_NONE 0u
#define KWEB_SHELL_RESOURCE_FILE 1u
#define KWEB_SHELL_RESOURCE_DIRECTORY 2u

typedef uint32_t kweb_shell_outcome;
#define KWEB_SHELL_OUTCOME_NONE 0u
#define KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED 1u
#define KWEB_SHELL_OUTCOME_MOVED_TO_TRASH 2u

typedef struct kweb_shell_string_view {
  const uint8_t *data;
  size_t size;
} kweb_shell_string_view;

typedef struct kweb_shell_request {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t action;
  uint32_t resource_kind;
  uint32_t reserved;
  kweb_shell_string_view value;
} kweb_shell_request;

typedef struct kweb_shell_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t outcome;
  uint32_t resource_kind;
  uint32_t reserved;
} kweb_shell_result;

KWEB_SHELL_EXPORT uint32_t KWEB_SHELL_CALL kweb_shell_abi_version(void);
KWEB_SHELL_EXPORT const char * KWEB_SHELL_CALL kweb_shell_status_name(kweb_shell_status status);
KWEB_SHELL_EXPORT const char * KWEB_SHELL_CALL kweb_shell_provider_id(void);
KWEB_SHELL_EXPORT kweb_shell_status KWEB_SHELL_CALL
kweb_shell_execute(const kweb_shell_request *request, kweb_shell_result *result);
KWEB_SHELL_EXPORT uint32_t KWEB_SHELL_CALL kweb_shell_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
