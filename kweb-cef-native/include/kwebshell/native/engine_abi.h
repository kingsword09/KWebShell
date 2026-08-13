#ifndef KWEBSHELL_NATIVE_ENGINE_ABI_H_
#define KWEBSHELL_NATIVE_ENGINE_ABI_H_

#include <stddef.h>
#include <stdint.h>

#include "kwebshell/native/base_abi.h"

#if defined(_WIN32)
#if defined(KWEB_ENGINE_ABI_BUILD)
#define KWEB_ENGINE_ABI_EXPORT __declspec(dllexport)
#else
#define KWEB_ENGINE_ABI_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_ENGINE_ABI_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_ENGINE_ABI_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_INVALID_ENGINE_HANDLE ((uint64_t)0)
#define KWEB_INVALID_BROWSER_HANDLE ((uint64_t)0)

typedef uint64_t kweb_engine_handle;
typedef uint64_t kweb_browser_handle;
typedef uint32_t kweb_engine_event_type;
typedef uint32_t kweb_browser_event_type;

#define KWEB_ENGINE_EVENT_OPENED ((kweb_engine_event_type)1)
#define KWEB_ENGINE_EVENT_CLOSED ((kweb_engine_event_type)2)

#define KWEB_BROWSER_EVENT_CREATED ((kweb_browser_event_type)1)
#define KWEB_BROWSER_EVENT_NAVIGATION_STARTED ((kweb_browser_event_type)2)
#define KWEB_BROWSER_EVENT_ADDRESS_CHANGED ((kweb_browser_event_type)3)
#define KWEB_BROWSER_EVENT_LOADING_STATE_CHANGED ((kweb_browser_event_type)4)
#define KWEB_BROWSER_EVENT_LOAD_ENDED ((kweb_browser_event_type)5)
#define KWEB_BROWSER_EVENT_LOAD_FAILED ((kweb_browser_event_type)6)
#define KWEB_BROWSER_EVENT_RESIZED ((kweb_browser_event_type)7)
#define KWEB_BROWSER_EVENT_FATAL_ERROR ((kweb_browser_event_type)8)
#define KWEB_BROWSER_EVENT_TITLE_CHANGED ((kweb_browser_event_type)9)
#define KWEB_BROWSER_EVENT_CLOSED ((kweb_browser_event_type)10)
#define KWEB_BROWSER_EVENT_DEVTOOLS_OPENED ((kweb_browser_event_type)11)
#define KWEB_BROWSER_EVENT_DEVTOOLS_CLOSED ((kweb_browser_event_type)12)
#define KWEB_BROWSER_EVENT_DEVTOOLS_FAILED ((kweb_browser_event_type)13)

#define KWEB_BROWSER_FLAG_LOADING ((uint32_t)1)
#define KWEB_BROWSER_FLAG_CAN_GO_BACK ((uint32_t)2)
#define KWEB_BROWSER_FLAG_CAN_GO_FORWARD ((uint32_t)4)
#define KWEB_BROWSER_FLAG_USER_GESTURE ((uint32_t)8)
#define KWEB_BROWSER_FLAG_REDIRECT ((uint32_t)16)

typedef struct kweb_string_view {
  const char *data;
  size_t size;
} kweb_string_view;

typedef struct kweb_engine_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_engine_event_type type;
  uint32_t reserved;
  kweb_engine_handle engine;
  uint64_t sequence;
} kweb_engine_event;

typedef void(KWEB_ABI_CALL *kweb_engine_event_callback)(
    void *user_data, const kweb_engine_event *event);

typedef struct kweb_engine_config {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_engine_event_callback callback;
  void *user_data;
  kweb_string_view cef_runtime_path;
  kweb_string_view browser_subprocess_path;
  kweb_string_view resources_path;
  kweb_string_view locales_path;
  kweb_string_view root_cache_path;
  kweb_string_view log_path;
  int32_t remote_debugging_port;
  uint32_t reserved;
} kweb_engine_config;

typedef struct kweb_browser_event {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_browser_event_type type;
  uint32_t flags;
  kweb_engine_handle engine;
  kweb_browser_handle browser;
  uint64_t sequence;
  kweb_string_view text;
  int32_t status_code;
  int32_t width;
  int32_t height;
  uint32_t reserved;
} kweb_browser_event;

typedef void(KWEB_ABI_CALL *kweb_browser_event_callback)(
    void *user_data, const kweb_browser_event *event);

typedef struct kweb_browser_config {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_engine_handle engine;
  uint32_t reserved;
  uintptr_t native_parent;
  int32_t x;
  int32_t y;
  int32_t width;
  int32_t height;
  kweb_string_view profile_path;
  kweb_string_view initial_url;
  kweb_browser_event_callback callback;
  void *user_data;
} kweb_browser_config;

KWEB_ENGINE_ABI_EXPORT uint32_t KWEB_ABI_CALL kweb_engine_abi_version(void);

KWEB_ENGINE_ABI_EXPORT const char *KWEB_ABI_CALL
kweb_status_name(kweb_status status);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_engine_platform_startup(
    const char *cef_runtime_path_utf8, size_t cef_runtime_path_size);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_engine_create(
    const kweb_engine_config *config, kweb_engine_handle *engine_out);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_engine_close(kweb_engine_handle engine);

KWEB_ENGINE_ABI_EXPORT uint64_t KWEB_ABI_CALL kweb_live_engine_count(void);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_browser_create(
    const kweb_browser_config *config, kweb_browser_handle *browser_out);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_browser_navigate(
    kweb_browser_handle browser, const char *url_utf8, size_t url_size);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_browser_resize(kweb_browser_handle browser, int32_t width, int32_t height);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_browser_close(kweb_browser_handle browser);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_browser_open_devtools(kweb_browser_handle browser);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_browser_close_devtools(kweb_browser_handle browser);

KWEB_ENGINE_ABI_EXPORT uint64_t KWEB_ABI_CALL kweb_live_browser_count(void);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_NATIVE_ENGINE_ABI_H_
