#ifndef KWEB_NOTIFICATIONS_H_
#define KWEB_NOTIFICATIONS_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_NOTIFICATIONS_ABI_BUILD)
#define KWEB_NOTIFICATIONS_EXPORT __declspec(dllexport)
#else
#define KWEB_NOTIFICATIONS_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_NOTIFICATIONS_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_NOTIFICATIONS_EXPORT
#endif

#if defined(_WIN32)
#define KWEB_NOTIFICATIONS_CALL __cdecl
#else
#define KWEB_NOTIFICATIONS_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_NOTIFICATIONS_ABI_VERSION ((uint32_t)1)
#define KWEB_NOTIFICATIONS_MAX_ID 128u
#define KWEB_NOTIFICATIONS_MAX_TAG 128u
#define KWEB_NOTIFICATIONS_MAX_TITLE 256u
#define KWEB_NOTIFICATIONS_MAX_BODY 4096u
#define KWEB_NOTIFICATIONS_MAX_ACTIONS 3u
#define KWEB_NOTIFICATIONS_MAX_ACTION_ID 64u
#define KWEB_NOTIFICATIONS_MAX_ACTION_TITLE 128u
#define KWEB_NOTIFICATIONS_MAX_REPLY 1024u
#define KWEB_NOTIFICATIONS_MAX_PROVIDER 128u
#define KWEB_NOTIFICATIONS_MAX_ERROR 96u
#define KWEB_NOTIFICATIONS_EVENT_CAPACITY 64u

typedef uint32_t kweb_notifications_status;
#define KWEB_NOTIFICATIONS_STATUS_OK ((kweb_notifications_status)0)
#define KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT ((kweb_notifications_status)1)
#define KWEB_NOTIFICATIONS_STATUS_ABI_MISMATCH ((kweb_notifications_status)2)
#define KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE ((kweb_notifications_status)3)
#define KWEB_NOTIFICATIONS_STATUS_PERMISSION_DENIED ((kweb_notifications_status)4)
#define KWEB_NOTIFICATIONS_STATUS_PERMISSION_UNDETERMINED ((kweb_notifications_status)5)
#define KWEB_NOTIFICATIONS_STATUS_ACTIONS_UNSUPPORTED ((kweb_notifications_status)6)
#define KWEB_NOTIFICATIONS_STATUS_REPLY_UNSUPPORTED ((kweb_notifications_status)7)
#define KWEB_NOTIFICATIONS_STATUS_TIMEOUT_UNSUPPORTED ((kweb_notifications_status)8)
#define KWEB_NOTIFICATIONS_STATUS_NOT_FOUND ((kweb_notifications_status)9)
#define KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED ((kweb_notifications_status)10)
#define KWEB_NOTIFICATIONS_STATUS_NO_EVENT ((kweb_notifications_status)11)

typedef uint32_t kweb_notifications_permission_status;
#define KWEB_NOTIFICATIONS_PERMISSION_NOT_DETERMINED ((kweb_notifications_permission_status)1)
#define KWEB_NOTIFICATIONS_PERMISSION_GRANTED ((kweb_notifications_permission_status)2)
#define KWEB_NOTIFICATIONS_PERMISSION_DENIED ((kweb_notifications_permission_status)3)
#define KWEB_NOTIFICATIONS_PERMISSION_NOT_APPLICABLE ((kweb_notifications_permission_status)4)
#define KWEB_NOTIFICATIONS_PERMISSION_UNAVAILABLE ((kweb_notifications_permission_status)5)

#define KWEB_NOTIFICATIONS_CAP_ACTIONS ((uint32_t)1u << 0)
#define KWEB_NOTIFICATIONS_CAP_REPLIES ((uint32_t)1u << 1)
#define KWEB_NOTIFICATIONS_CAP_REPLACEMENT ((uint32_t)1u << 2)
#define KWEB_NOTIFICATIONS_CAP_TIMEOUT ((uint32_t)1u << 3)
#define KWEB_NOTIFICATIONS_CAP_ACTIVATION ((uint32_t)1u << 4)

typedef uint32_t kweb_notifications_action_kind;
#define KWEB_NOTIFICATIONS_ACTION_BUTTON ((kweb_notifications_action_kind)1)
#define KWEB_NOTIFICATIONS_ACTION_REPLY ((kweb_notifications_action_kind)2)

typedef uint32_t kweb_notifications_icon;
#define KWEB_NOTIFICATIONS_ICON_APPLICATION ((kweb_notifications_icon)1)

typedef uint32_t kweb_notifications_urgency;
#define KWEB_NOTIFICATIONS_URGENCY_LOW ((kweb_notifications_urgency)1)
#define KWEB_NOTIFICATIONS_URGENCY_NORMAL ((kweb_notifications_urgency)2)
#define KWEB_NOTIFICATIONS_URGENCY_HIGH ((kweb_notifications_urgency)3)

typedef uint32_t kweb_notifications_timeout;
#define KWEB_NOTIFICATIONS_TIMEOUT_SYSTEM ((kweb_notifications_timeout)1)
#define KWEB_NOTIFICATIONS_TIMEOUT_SHORT ((kweb_notifications_timeout)2)
#define KWEB_NOTIFICATIONS_TIMEOUT_LONG ((kweb_notifications_timeout)3)
#define KWEB_NOTIFICATIONS_TIMEOUT_PERSISTENT ((kweb_notifications_timeout)4)

typedef uint32_t kweb_notifications_event_kind;
#define KWEB_NOTIFICATIONS_EVENT_ACTION ((kweb_notifications_event_kind)1)
#define KWEB_NOTIFICATIONS_EVENT_CLOSED ((kweb_notifications_event_kind)2)
#define KWEB_NOTIFICATIONS_EVENT_FAILED ((kweb_notifications_event_kind)3)

typedef uint32_t kweb_notifications_close_reason;
#define KWEB_NOTIFICATIONS_CLOSE_PROGRAMMATIC ((kweb_notifications_close_reason)1)
#define KWEB_NOTIFICATIONS_CLOSE_REPLACED ((kweb_notifications_close_reason)2)
#define KWEB_NOTIFICATIONS_CLOSE_EXPIRED ((kweb_notifications_close_reason)3)
#define KWEB_NOTIFICATIONS_CLOSE_USER_DISMISSED ((kweb_notifications_close_reason)4)
#define KWEB_NOTIFICATIONS_CLOSE_OWNER_CLOSED ((kweb_notifications_close_reason)5)
#define KWEB_NOTIFICATIONS_CLOSE_NATIVE ((kweb_notifications_close_reason)6)

typedef struct kweb_notifications_string {
  const uint8_t *data;
  size_t size;
} kweb_notifications_string;

typedef struct kweb_notifications_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t reserved;
  kweb_notifications_string application_id;
  kweb_notifications_string package_identity;
} kweb_notifications_configuration;

typedef struct kweb_notifications_action {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_notifications_action_kind kind;
  uint32_t reserved;
  kweb_notifications_string id;
  kweb_notifications_string title;
  kweb_notifications_string reply_placeholder;
} kweb_notifications_action;

typedef struct kweb_notifications_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_notifications_icon icon;
  kweb_notifications_urgency urgency;
  kweb_notifications_timeout timeout;
  uint32_t action_count;
  uint32_t reserved;
  kweb_notifications_string id;
  kweb_notifications_string tag;
  kweb_notifications_string title;
  kweb_notifications_string body;
  kweb_notifications_action actions[KWEB_NOTIFICATIONS_MAX_ACTIONS];
} kweb_notifications_request;

typedef struct kweb_notifications_permission_result {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_notifications_permission_status status;
  uint32_t reserved;
  char provider[KWEB_NOTIFICATIONS_MAX_PROVIDER];
} kweb_notifications_permission_result;

typedef struct kweb_notifications_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t flags;
  uint32_t reserved;
} kweb_notifications_capabilities_result;

typedef struct kweb_notifications_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_notifications_event_kind kind;
  kweb_notifications_close_reason close_reason;
  uint64_t sequence;
  char id[KWEB_NOTIFICATIONS_MAX_ID + 1u];
  char action_id[KWEB_NOTIFICATIONS_MAX_ACTION_ID + 1u];
  char reply[KWEB_NOTIFICATIONS_MAX_REPLY + 1u];
  char code[KWEB_NOTIFICATIONS_MAX_ERROR];
} kweb_notifications_event;

KWEB_NOTIFICATIONS_EXPORT uint32_t KWEB_NOTIFICATIONS_CALL
kweb_notifications_abi_version(void);

KWEB_NOTIFICATIONS_EXPORT const char * KWEB_NOTIFICATIONS_CALL
kweb_notifications_status_name(kweb_notifications_status status);

KWEB_NOTIFICATIONS_EXPORT const char * KWEB_NOTIFICATIONS_CALL
kweb_notifications_provider_id(void);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_open(const kweb_notifications_configuration *configuration, uint64_t *handle);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_permission(uint64_t handle, kweb_notifications_permission_result *result);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_request_permission(uint64_t handle, kweb_notifications_permission_result *result);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_capabilities(uint64_t handle, kweb_notifications_capabilities_result *result);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_show(uint64_t handle, const kweb_notifications_request *request);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_close_notification(uint64_t handle, kweb_notifications_string id);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_poll_event(uint64_t handle, kweb_notifications_event *event);

KWEB_NOTIFICATIONS_EXPORT kweb_notifications_status KWEB_NOTIFICATIONS_CALL
kweb_notifications_close(uint64_t handle);

KWEB_NOTIFICATIONS_EXPORT uint32_t KWEB_NOTIFICATIONS_CALL
kweb_notifications_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
