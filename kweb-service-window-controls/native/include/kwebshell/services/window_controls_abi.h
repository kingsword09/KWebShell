#ifndef KWEBSHELL_SERVICES_WINDOW_CONTROLS_ABI_H_
#define KWEBSHELL_SERVICES_WINDOW_CONTROLS_ABI_H_

#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_WINDOW_CONTROLS_ABI_BUILD)
#define KWEB_WINDOW_CONTROLS_ABI_EXPORT __declspec(dllexport)
#else
#define KWEB_WINDOW_CONTROLS_ABI_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_WINDOW_CONTROLS_ABI_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_WINDOW_CONTROLS_ABI_EXPORT
#endif

#if defined(_WIN32)
#define KWEB_WINDOW_CONTROLS_ABI_CALL __cdecl
#else
#define KWEB_WINDOW_CONTROLS_ABI_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_WINDOW_CONTROLS_ABI_VERSION ((uint32_t)1)

typedef uint32_t kweb_window_controls_status;

#define KWEB_WINDOW_CONTROLS_STATUS_OK ((kweb_window_controls_status)0)
#define KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT ((kweb_window_controls_status)1)
#define KWEB_WINDOW_CONTROLS_STATUS_ABI_MISMATCH ((kweb_window_controls_status)2)
#define KWEB_WINDOW_CONTROLS_STATUS_NATIVE_UNAVAILABLE ((kweb_window_controls_status)3)
#define KWEB_WINDOW_CONTROLS_STATUS_STALE_HANDLE ((kweb_window_controls_status)4)
#define KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED ((kweb_window_controls_status)5)
#define KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED ((kweb_window_controls_status)6)
#define KWEB_WINDOW_CONTROLS_STATUS_WRONG_THREAD ((kweb_window_controls_status)7)
#define KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED ((kweb_window_controls_status)8)

typedef uint64_t kweb_window_handle;
typedef uint32_t kweb_window_modality;

#define KWEB_WINDOW_MODALITY_NONE ((kweb_window_modality)0)
#define KWEB_WINDOW_MODALITY_WINDOW ((kweb_window_modality)1)
#define KWEB_WINDOW_MODALITY_APPLICATION ((kweb_window_modality)2)

KWEB_WINDOW_CONTROLS_ABI_EXPORT uint32_t KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_abi_version(void);

KWEB_WINDOW_CONTROLS_ABI_EXPORT const char *KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_status_name(kweb_window_controls_status status);

KWEB_WINDOW_CONTROLS_ABI_EXPORT const char *KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_provider_id(void);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_probe(kweb_window_handle handle);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_attach(kweb_window_handle child,
                             kweb_window_handle parent,
                             kweb_window_modality modality);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_detach(kweb_window_handle child,
                             kweb_window_handle parent,
                             kweb_window_modality modality);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_set_modal_enabled(kweb_window_handle handle,
                                        uint32_t enabled);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_set_always_on_top(kweb_window_handle handle,
                                       uint32_t enabled);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_request_attention(kweb_window_handle handle);

KWEB_WINDOW_CONTROLS_ABI_EXPORT kweb_window_controls_status
KWEB_WINDOW_CONTROLS_ABI_CALL
kweb_window_controls_verify_parent(kweb_window_handle child,
                                    kweb_window_handle parent);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_SERVICES_WINDOW_CONTROLS_ABI_H_
