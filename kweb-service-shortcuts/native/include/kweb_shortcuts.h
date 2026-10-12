#ifndef KWEB_SHORTCUTS_H_
#define KWEB_SHORTCUTS_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_SHORTCUTS_ABI_BUILD)
#define KWEB_SHORTCUTS_EXPORT __declspec(dllexport)
#else
#define KWEB_SHORTCUTS_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_SHORTCUTS_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_SHORTCUTS_CALL __cdecl
#else
#define KWEB_SHORTCUTS_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_SHORTCUTS_ABI_VERSION ((uint32_t)1)
#define KWEB_SHORTCUTS_MAX_ID 129u
#define KWEB_SHORTCUTS_MAX_PROVIDER 64u
#define KWEB_SHORTCUTS_MAX_REGISTRATIONS 32u
#define KWEB_SHORTCUTS_EVENT_CAPACITY 64u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_shortcuts_status;
#define KWEB_SHORTCUTS_STATUS_OK ((kweb_shortcuts_status)0)
#define KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT ((kweb_shortcuts_status)1)
#define KWEB_SHORTCUTS_STATUS_ABI_MISMATCH ((kweb_shortcuts_status)2)
#define KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE ((kweb_shortcuts_status)3)
#define KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED ((kweb_shortcuts_status)4)
#define KWEB_SHORTCUTS_STATUS_MODIFIER_UNSUPPORTED ((kweb_shortcuts_status)5)
#define KWEB_SHORTCUTS_STATUS_MEDIA_PERMISSION_DENIED ((kweb_shortcuts_status)6)
#define KWEB_SHORTCUTS_STATUS_CONFLICT ((kweb_shortcuts_status)7)
#define KWEB_SHORTCUTS_STATUS_RESERVED ((kweb_shortcuts_status)8)
#define KWEB_SHORTCUTS_STATUS_DUPLICATE_ID ((kweb_shortcuts_status)9)
#define KWEB_SHORTCUTS_STATUS_LIMIT_EXCEEDED ((kweb_shortcuts_status)10)
#define KWEB_SHORTCUTS_STATUS_REGISTRATION_UNKNOWN ((kweb_shortcuts_status)11)
#define KWEB_SHORTCUTS_STATUS_COMMAND_UNDECLARED ((kweb_shortcuts_status)12)
#define KWEB_SHORTCUTS_STATUS_NATIVE_FAILED ((kweb_shortcuts_status)13)
#define KWEB_SHORTCUTS_STATUS_NO_EVENT ((kweb_shortcuts_status)14)
#define KWEB_SHORTCUTS_STATUS_OWNER_CLOSED ((kweb_shortcuts_status)15)
#define KWEB_SHORTCUTS_STATUS_ALREADY_OPEN ((kweb_shortcuts_status)16)
#define KWEB_SHORTCUTS_STATUS_BUFFER_TOO_SMALL ((kweb_shortcuts_status)17)

/* Modifier bits. A provider advertises the subset it can register. */
#define KWEB_SHORTCUTS_MOD_CONTROL ((uint32_t)1)
#define KWEB_SHORTCUTS_MOD_ALT ((uint32_t)2)
#define KWEB_SHORTCUTS_MOD_SHIFT ((uint32_t)4)
#define KWEB_SHORTCUTS_MOD_META ((uint32_t)8)
#define KWEB_SHORTCUTS_MOD_ALL ((uint32_t)15)

/* Key kinds. */
#define KWEB_SHORTCUTS_KEY_NAMED ((uint32_t)1)
#define KWEB_SHORTCUTS_KEY_MEDIA ((uint32_t)2)

/* Named key codes. Letters and digits use their uppercase ASCII value. */
#define KWEB_SHORTCUTS_NAMED_F1 ((uint32_t)0x100)
#define KWEB_SHORTCUTS_NAMED_F13 ((uint32_t)0x10c)
#define KWEB_SHORTCUTS_NAMED_F14 ((uint32_t)0x10d)
#define KWEB_SHORTCUTS_NAMED_F15 ((uint32_t)0x10e)
#define KWEB_SHORTCUTS_NAMED_F16 ((uint32_t)0x10f)
#define KWEB_SHORTCUTS_NAMED_F17 ((uint32_t)0x110)
#define KWEB_SHORTCUTS_NAMED_F18 ((uint32_t)0x111)
#define KWEB_SHORTCUTS_NAMED_F19 ((uint32_t)0x112)
#define KWEB_SHORTCUTS_NAMED_F20 ((uint32_t)0x113)
#define KWEB_SHORTCUTS_NAMED_F21 ((uint32_t)0x114)
#define KWEB_SHORTCUTS_NAMED_F22 ((uint32_t)0x115)
#define KWEB_SHORTCUTS_NAMED_F23 ((uint32_t)0x116)
#define KWEB_SHORTCUTS_NAMED_F24 ((uint32_t)0x117)
#define KWEB_SHORTCUTS_NAMED_SPACE ((uint32_t)0x200)
#define KWEB_SHORTCUTS_NAMED_TAB ((uint32_t)0x201)
#define KWEB_SHORTCUTS_NAMED_ENTER ((uint32_t)0x202)
#define KWEB_SHORTCUTS_NAMED_ESCAPE ((uint32_t)0x203)
#define KWEB_SHORTCUTS_NAMED_BACKSPACE ((uint32_t)0x204)
#define KWEB_SHORTCUTS_NAMED_DELETE ((uint32_t)0x205)
#define KWEB_SHORTCUTS_NAMED_INSERT ((uint32_t)0x206)
#define KWEB_SHORTCUTS_NAMED_HOME ((uint32_t)0x207)
#define KWEB_SHORTCUTS_NAMED_END ((uint32_t)0x208)
#define KWEB_SHORTCUTS_NAMED_PAGE_UP ((uint32_t)0x209)
#define KWEB_SHORTCUTS_NAMED_PAGE_DOWN ((uint32_t)0x20a)
#define KWEB_SHORTCUTS_NAMED_ARROW_LEFT ((uint32_t)0x20b)
#define KWEB_SHORTCUTS_NAMED_ARROW_RIGHT ((uint32_t)0x20c)
#define KWEB_SHORTCUTS_NAMED_ARROW_UP ((uint32_t)0x20d)
#define KWEB_SHORTCUTS_NAMED_ARROW_DOWN ((uint32_t)0x20e)
#define KWEB_SHORTCUTS_NAMED_MINUS ((uint32_t)0x20f)
#define KWEB_SHORTCUTS_NAMED_EQUALS ((uint32_t)0x210)
#define KWEB_SHORTCUTS_NAMED_BRACKET_LEFT ((uint32_t)0x211)
#define KWEB_SHORTCUTS_NAMED_BRACKET_RIGHT ((uint32_t)0x212)
#define KWEB_SHORTCUTS_NAMED_BACKSLASH ((uint32_t)0x213)
#define KWEB_SHORTCUTS_NAMED_SEMICOLON ((uint32_t)0x214)
#define KWEB_SHORTCUTS_NAMED_QUOTE ((uint32_t)0x215)
#define KWEB_SHORTCUTS_NAMED_COMMA ((uint32_t)0x216)
#define KWEB_SHORTCUTS_NAMED_PERIOD ((uint32_t)0x217)
#define KWEB_SHORTCUTS_NAMED_SLASH ((uint32_t)0x218)
#define KWEB_SHORTCUTS_NAMED_BACKQUOTE ((uint32_t)0x219)
#define KWEB_SHORTCUTS_NAMED_MAX ((uint32_t)0x219)

/* Media key codes. */
#define KWEB_SHORTCUTS_MEDIA_PLAY_PAUSE ((uint32_t)1)
#define KWEB_SHORTCUTS_MEDIA_NEXT_TRACK ((uint32_t)2)
#define KWEB_SHORTCUTS_MEDIA_PREVIOUS_TRACK ((uint32_t)3)
#define KWEB_SHORTCUTS_MEDIA_STOP ((uint32_t)4)
#define KWEB_SHORTCUTS_MEDIA_VOLUME_UP ((uint32_t)5)
#define KWEB_SHORTCUTS_MEDIA_VOLUME_DOWN ((uint32_t)6)
#define KWEB_SHORTCUTS_MEDIA_MUTE ((uint32_t)7)
#define KWEB_SHORTCUTS_MEDIA_ALL ((uint32_t)127)

/* Declared provider capabilities. */
#define KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION ((uint32_t)1)
#define KWEB_SHORTCUTS_CAP_MEDIA_KEYS ((uint32_t)2)
#define KWEB_SHORTCUTS_CAP_ATOMIC_REPLACEMENT ((uint32_t)4)
#define KWEB_SHORTCUTS_CAP_INVOCATION_REPORTING ((uint32_t)8)
#define KWEB_SHORTCUTS_CAP_SESSION_LOCK_RELEASE ((uint32_t)16)
#define KWEB_SHORTCUTS_CAP_MEDIA_KEY_PERMISSION ((uint32_t)32)

/* Event kinds. */
#define KWEB_SHORTCUTS_EVENT_INVOKED ((uint32_t)1)
#define KWEB_SHORTCUTS_EVENT_LOST ((uint32_t)2)
#define KWEB_SHORTCUTS_EVENT_REMOVED ((uint32_t)3)
#define KWEB_SHORTCUTS_EVENT_FAILED ((uint32_t)4)

/* Invocation source. */
#define KWEB_SHORTCUTS_SOURCE_PLATFORM ((uint32_t)1)
#define KWEB_SHORTCUTS_SOURCE_RENDERER ((uint32_t)2)

/* Loss reasons. */
#define KWEB_SHORTCUTS_LOSS_CONFLICT ((uint32_t)1)
#define KWEB_SHORTCUTS_LOSS_SESSION_LOCK ((uint32_t)2)
#define KWEB_SHORTCUTS_LOSS_PERMISSION_REVOKED ((uint32_t)3)
#define KWEB_SHORTCUTS_LOSS_PLATFORM_REMOVED ((uint32_t)4)

/* Removal reasons. */
#define KWEB_SHORTCUTS_REMOVAL_UNREGISTERED ((uint32_t)1)
#define KWEB_SHORTCUTS_REMOVAL_REPLACED ((uint32_t)2)
#define KWEB_SHORTCUTS_REMOVAL_OWNER_CLOSED ((uint32_t)3)
#define KWEB_SHORTCUTS_REMOVAL_PLATFORM_REMOVED ((uint32_t)4)

typedef struct kweb_shortcuts_string {
  const uint8_t *data;
  size_t size;
} kweb_shortcuts_string;

typedef struct kweb_shortcuts_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_shortcuts_string application_id;
  kweb_shortcuts_string package_identity;
  uint32_t reserved;
} kweb_shortcuts_configuration;

typedef struct kweb_shortcuts_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_SHORTCUTS_MAX_PROVIDER];
  uint32_t capability_bits;
  uint32_t modifier_bits;
  uint32_t media_key_bits;
  uint32_t max_registrations;
  uint32_t registration_count;
} kweb_shortcuts_capabilities_result;

typedef struct kweb_shortcuts_registration_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_shortcuts_string registration_id;
  kweb_shortcuts_string command_id;
  uint32_t modifier_bits;
  uint32_t key_kind;
  uint32_t key_code;
  uint32_t reserved;
} kweb_shortcuts_registration_request;

typedef struct kweb_shortcuts_registration {
  char registration_id[KWEB_SHORTCUTS_MAX_ID];
  char command_id[KWEB_SHORTCUTS_MAX_ID];
  uint32_t modifier_bits;
  uint32_t key_kind;
  uint32_t key_code;
  uint32_t reserved;
} kweb_shortcuts_registration;

typedef struct kweb_shortcuts_registration_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char registration_id[KWEB_SHORTCUTS_MAX_ID];
  uint64_t sequence;
} kweb_shortcuts_registration_result;

typedef struct kweb_shortcuts_replacement_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t applied_count;
  uint32_t removed_count;
  uint64_t sequence;
} kweb_shortcuts_replacement_result;

typedef struct kweb_shortcuts_unregistration_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t was_registered;
  uint32_t reserved;
} kweb_shortcuts_unregistration_result;

typedef struct kweb_shortcuts_presence_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t registered;
  uint32_t reserved;
} kweb_shortcuts_presence_result;

typedef struct kweb_shortcuts_query {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_shortcuts_string value;
  uint32_t reserved;
} kweb_shortcuts_query;

typedef struct kweb_shortcuts_list_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t count;
  uint32_t capacity;
  kweb_shortcuts_registration *registrations;
} kweb_shortcuts_list_result;

typedef struct kweb_shortcuts_event {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t kind;
  uint32_t source;
  uint32_t reason;
  uint64_t sequence;
  char registration_id[KWEB_SHORTCUTS_MAX_ID];
  char command_id[KWEB_SHORTCUTS_MAX_ID];
  char code[96];
} kweb_shortcuts_event;

/* The ABI revision this library implements. */
KWEB_SHORTCUTS_EXPORT uint32_t KWEB_SHORTCUTS_CALL kweb_shortcuts_abi_version(void);

/* Opens the process-wide shortcut service. A second open fails. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_open(
    const kweb_shortcuts_configuration *configuration, uint64_t *handle);

/* Declared provider capabilities for the current target and session. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_capabilities(
    uint64_t handle, kweb_shortcuts_capabilities_result *result);

/* Installs one registration, or fails typed and installs nothing. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_register(
    uint64_t handle, const kweb_shortcuts_registration_request *request,
    kweb_shortcuts_registration_result *result);

/* Atomically replaces the whole live set; a failure leaves the previous set live. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_replace_all(
    uint64_t handle, const kweb_shortcuts_registration_request *requests, uint32_t count,
    kweb_shortcuts_replacement_result *result);

/* Unregisters one identifier idempotently. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_unregister(
    uint64_t handle, const kweb_shortcuts_query *query, kweb_shortcuts_unregistration_result *result);

/* Reports whether one identifier is live. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_is_registered(
    uint64_t handle, const kweb_shortcuts_query *query, kweb_shortcuts_presence_result *result);

/* Lists the live registrations into the caller's buffer. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_registrations(
    uint64_t handle, kweb_shortcuts_list_result *result);

/* Dispatches one application-declared command, emitting a renderer invocation. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_dispatch(
    uint64_t handle, const kweb_shortcuts_query *command, kweb_shortcuts_registration_result *result);

/* Pops one ordered event, or reports KWEB_SHORTCUTS_STATUS_NO_EVENT. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_poll_event(
    uint64_t handle, kweb_shortcuts_event *event);

/* Releases every active hotkey and the process-wide state. */
KWEB_SHORTCUTS_EXPORT kweb_shortcuts_status KWEB_SHORTCUTS_CALL kweb_shortcuts_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
