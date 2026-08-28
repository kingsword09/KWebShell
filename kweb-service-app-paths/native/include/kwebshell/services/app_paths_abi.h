#ifndef KWEBSHELL_SERVICES_APP_PATHS_ABI_H_
#define KWEBSHELL_SERVICES_APP_PATHS_ABI_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_SERVICES_ABI_BUILD)
#define KWEB_SERVICES_ABI_EXPORT __declspec(dllexport)
#else
#define KWEB_SERVICES_ABI_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_SERVICES_ABI_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_SERVICES_ABI_EXPORT
#endif

#if defined(_WIN32)
#define KWEB_SERVICES_ABI_CALL __cdecl
#else
#define KWEB_SERVICES_ABI_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_SERVICES_ABI_VERSION ((uint32_t)1)

typedef uint32_t kweb_services_status;

#define KWEB_SERVICES_STATUS_OK ((kweb_services_status)0)
#define KWEB_SERVICES_STATUS_INVALID_ARGUMENT ((kweb_services_status)1)
#define KWEB_SERVICES_STATUS_ABI_MISMATCH ((kweb_services_status)2)
#define KWEB_SERVICES_STATUS_ALLOCATION_FAILED ((kweb_services_status)3)
#define KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN ((kweb_services_status)4)
#define KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE ((kweb_services_status)5)
#define KWEB_SERVICES_STATUS_PATH_INVALID ((kweb_services_status)6)
#define KWEB_SERVICES_STATUS_NATIVE_FAILED ((kweb_services_status)7)

typedef uint32_t kweb_app_path_kind;

#define KWEB_APP_PATH_HOME ((kweb_app_path_kind)1)
#define KWEB_APP_PATH_APP_DATA ((kweb_app_path_kind)2)
#define KWEB_APP_PATH_APP_CACHE ((kweb_app_path_kind)3)
#define KWEB_APP_PATH_USER_DATA ((kweb_app_path_kind)4)
#define KWEB_APP_PATH_SESSION_DATA ((kweb_app_path_kind)5)
#define KWEB_APP_PATH_TEMP ((kweb_app_path_kind)6)
#define KWEB_APP_PATH_DESKTOP ((kweb_app_path_kind)7)
#define KWEB_APP_PATH_DOCUMENTS ((kweb_app_path_kind)8)
#define KWEB_APP_PATH_DOWNLOADS ((kweb_app_path_kind)9)
#define KWEB_APP_PATH_MUSIC ((kweb_app_path_kind)10)
#define KWEB_APP_PATH_PICTURES ((kweb_app_path_kind)11)
#define KWEB_APP_PATH_VIDEOS ((kweb_app_path_kind)12)

typedef struct kweb_services_string_view {
  const char *data;
  size_t size;
} kweb_services_string_view;

typedef struct kweb_app_paths_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_app_path_kind kind;
  uint32_t reserved;
  kweb_services_string_view application_id;
  kweb_services_string_view application_data_root;
  kweb_services_string_view session_data_root;
} kweb_app_paths_request;

typedef struct kweb_app_paths_result {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_app_path_kind kind;
  uint32_t reserved;
  kweb_services_string_view path;
  kweb_services_string_view source;
} kweb_app_paths_result;

KWEB_SERVICES_ABI_EXPORT uint32_t KWEB_SERVICES_ABI_CALL
kweb_services_abi_version(void);

KWEB_SERVICES_ABI_EXPORT const char *KWEB_SERVICES_ABI_CALL
kweb_services_status_name(kweb_services_status status);

KWEB_SERVICES_ABI_EXPORT kweb_services_status KWEB_SERVICES_ABI_CALL
kweb_app_paths_resolve(const kweb_app_paths_request *request,
                       kweb_app_paths_result *result);

KWEB_SERVICES_ABI_EXPORT void KWEB_SERVICES_ABI_CALL
kweb_app_paths_result_free(kweb_app_paths_result *result);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_SERVICES_APP_PATHS_ABI_H_
