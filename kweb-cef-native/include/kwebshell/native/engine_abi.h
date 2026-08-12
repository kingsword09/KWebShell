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

typedef uint64_t kweb_engine_handle;
typedef uint32_t kweb_engine_event_type;

#define KWEB_ENGINE_EVENT_OPENED ((kweb_engine_event_type)1)
#define KWEB_ENGINE_EVENT_CLOSED ((kweb_engine_event_type)2)

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
} kweb_engine_config;

KWEB_ENGINE_ABI_EXPORT uint32_t KWEB_ABI_CALL kweb_engine_abi_version(void);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_engine_platform_startup(
    const char *cef_runtime_path_utf8, size_t cef_runtime_path_size);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL kweb_engine_create(
    const kweb_engine_config *config, kweb_engine_handle *engine_out);

KWEB_ENGINE_ABI_EXPORT kweb_status KWEB_ABI_CALL
kweb_engine_close(kweb_engine_handle engine);

KWEB_ENGINE_ABI_EXPORT uint64_t KWEB_ABI_CALL kweb_live_engine_count(void);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_NATIVE_ENGINE_ABI_H_
