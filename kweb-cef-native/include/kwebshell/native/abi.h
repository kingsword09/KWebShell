#ifndef KWEBSHELL_NATIVE_ABI_H_
#define KWEBSHELL_NATIVE_ABI_H_

#include <stddef.h>
#include <stdint.h>

#include "kwebshell/native/base_abi.h"

#if defined(_WIN32)
#if defined(KWEB_ABI_BUILD)
#define KWEB_ABI_EXPORT __declspec(dllexport)
#elif defined(KWEB_ABI_STATIC)
#define KWEB_ABI_EXPORT
#else
#define KWEB_ABI_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_ABI_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_ABI_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_INVALID_SESSION_HANDLE ((uint64_t)0)

typedef uint64_t kweb_session_handle;
typedef uint32_t kweb_event_type;

#define KWEB_EVENT_SESSION_OPENED ((kweb_event_type)1)
#define KWEB_EVENT_NAVIGATION_REQUESTED ((kweb_event_type)2)
#define KWEB_EVENT_VIEWPORT_CHANGED ((kweb_event_type)3)
#define KWEB_EVENT_SESSION_CLOSED ((kweb_event_type)4)

typedef struct kweb_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_event_type type;
  uint32_t reserved;
  kweb_session_handle session;
  uint64_t sequence;
  const char *text;
  size_t text_size;
  int32_t width;
  int32_t height;
} kweb_event;

typedef void(KWEB_ABI_CALL *kweb_event_callback)(void *user_data,
                                                 const kweb_event *event);

typedef struct kweb_session_config {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_event_callback callback;
  void *user_data;
} kweb_session_config;

KWEB_ABI_EXPORT uint32_t KWEB_ABI_CALL kweb_abi_version(void);

KWEB_ABI_EXPORT const char *KWEB_ABI_CALL kweb_status_name(kweb_status status);

KWEB_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_session_create(
    const kweb_session_config *config, kweb_session_handle *session_out);

KWEB_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_session_request_navigation(
    kweb_session_handle session, const char *url_utf8, size_t url_size);

KWEB_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_session_resize(kweb_session_handle session, int32_t width, int32_t height);

KWEB_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_session_close(kweb_session_handle session);

KWEB_ABI_EXPORT uint64_t KWEB_ABI_CALL kweb_live_session_count(void);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_NATIVE_ABI_H_
