#ifndef KWEBSHELL_NATIVE_CEF_NETWORK_ABI_H_
#define KWEBSHELL_NATIVE_CEF_NETWORK_ABI_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define KWEB_CEF_NETWORK_CALLBACK __stdcall
#else
#define KWEB_CEF_NETWORK_CALLBACK
#endif

// Recomputed whenever this private ABI header or its semantics change.
#define CEF_KWEB_NETWORK_ABI_VERSION ((uint32_t)1)
#define CEF_KWEB_NETWORK_ABI_FINGERPRINT \
  "4c0d5bdbc917002a5ffa3c2a09be4fb51d12277c8f2cdd8ae28b41b995d43cdb"

typedef uint32_t cef_kweb_network_status_t;
typedef uint32_t cef_kweb_network_proxy_mode_t;

#define CEF_KWEB_NETWORK_STATUS_OK ((cef_kweb_network_status_t)0)
#define CEF_KWEB_NETWORK_STATUS_INVALID_ARGUMENT ((cef_kweb_network_status_t)1)
#define CEF_KWEB_NETWORK_STATUS_ABI_MISMATCH ((cef_kweb_network_status_t)2)
#define CEF_KWEB_NETWORK_STATUS_WRONG_THREAD ((cef_kweb_network_status_t)3)
#define CEF_KWEB_NETWORK_STATUS_PROFILE_NOT_FOUND ((cef_kweb_network_status_t)4)
#define CEF_KWEB_NETWORK_STATUS_PROXY_INVALID ((cef_kweb_network_status_t)5)
#define CEF_KWEB_NETWORK_STATUS_PROXY_RESOLVE_FAILED ((cef_kweb_network_status_t)6)
#define CEF_KWEB_NETWORK_STATUS_USER_AGENT_LOCKED ((cef_kweb_network_status_t)7)
#define CEF_KWEB_NETWORK_STATUS_INTERNAL_ERROR ((cef_kweb_network_status_t)8)

#define CEF_KWEB_NETWORK_PROXY_DIRECT ((cef_kweb_network_proxy_mode_t)1)
#define CEF_KWEB_NETWORK_PROXY_FIXED ((cef_kweb_network_proxy_mode_t)2)
#define CEF_KWEB_NETWORK_PROXY_PAC ((cef_kweb_network_proxy_mode_t)3)

typedef struct cef_kweb_network_string_view {
  const char *data;
  size_t size;
} cef_kweb_network_string_view;

typedef struct cef_kweb_network_proxy_config {
  uint32_t struct_size;
  uint32_t abi_version;
  cef_kweb_network_proxy_mode_t mode;
  uint32_t pac_mandatory;
  cef_kweb_network_string_view profile_path;
  cef_kweb_network_string_view proxy_rules;
  cef_kweb_network_string_view pac_url;
  cef_kweb_network_string_view bypass_list;
  cef_kweb_network_string_view user_agent;
  cef_kweb_network_string_view accept_language;
} cef_kweb_network_proxy_config;

typedef void(KWEB_CEF_NETWORK_CALLBACK *cef_kweb_network_resolve_callback)(
    void *user_data, cef_kweb_network_status_t status,
    cef_kweb_network_string_view result);

typedef const char *(*cef_kweb_network_abi_fingerprint_fn)(void);
typedef cef_kweb_network_status_t (*cef_kweb_network_set_config_fn)(
    const cef_kweb_network_proxy_config *config);
typedef cef_kweb_network_status_t (*cef_kweb_network_resolve_proxy_fn)(
    cef_kweb_network_string_view profile_path,
    cef_kweb_network_string_view url,
    cef_kweb_network_resolve_callback callback, void *user_data);
typedef cef_kweb_network_status_t (*cef_kweb_network_clear_config_fn)(
    cef_kweb_network_string_view profile_path);

#endif  // KWEBSHELL_NATIVE_CEF_NETWORK_ABI_H_
