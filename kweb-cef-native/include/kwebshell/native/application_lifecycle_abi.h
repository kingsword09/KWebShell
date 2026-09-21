#ifndef KWEBSHELL_NATIVE_APPLICATION_LIFECYCLE_ABI_H_
#define KWEBSHELL_NATIVE_APPLICATION_LIFECYCLE_ABI_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define KWEB_APPLICATION_LIFECYCLE_EXPORT __declspec(dllexport)
#define KWEB_APPLICATION_LIFECYCLE_CALL __cdecl
#else
#define KWEB_APPLICATION_LIFECYCLE_EXPORT __attribute__((visibility("default")))
#define KWEB_APPLICATION_LIFECYCLE_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

enum {
  KWEB_APPLICATION_LIFECYCLE_ABI_VERSION = 1,
  KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY = 0,
  KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY = 1,
  KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT = 2,
  KWEB_APPLICATION_LIFECYCLE_ALREADY_OWNED = 3,
  KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED = 4,
  KWEB_APPLICATION_LIFECYCLE_NOT_SUPPORTED = 5,
  KWEB_APPLICATION_LIFECYCLE_AUTH_FAILED = 6,
  KWEB_APPLICATION_LIFECYCLE_CLOSED = 7,
  KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED = 8,
};

typedef uint64_t kweb_application_lifecycle_handle;
typedef void(KWEB_APPLICATION_LIFECYCLE_CALL *
             kweb_application_lifecycle_activation_callback)(
    void *user_data, const uint8_t *payload, size_t payload_size);

KWEB_APPLICATION_LIFECYCLE_EXPORT uint32_t
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_abi_version(void);

KWEB_APPLICATION_LIFECYCLE_EXPORT const char *
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_provider_id(void);

KWEB_APPLICATION_LIFECYCLE_EXPORT int32_t
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_acquire(
    const char *application_id, size_t application_id_size,
    const char *transport_root, size_t transport_root_size,
    const uint8_t *initial_payload, size_t initial_payload_size,
    kweb_application_lifecycle_activation_callback callback, void *user_data,
    kweb_application_lifecycle_handle *handle_out);

KWEB_APPLICATION_LIFECYCLE_EXPORT int32_t
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_release(
    kweb_application_lifecycle_handle handle);

KWEB_APPLICATION_LIFECYCLE_EXPORT int32_t
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_register_associations(
    const char *application_id, size_t application_id_size,
    const char *package_root, size_t package_root_size,
    const char *executable, size_t executable_size,
    const char *schemes_csv, size_t schemes_csv_size,
    const char *extensions_csv, size_t extensions_csv_size,
    uint8_t remove, char *digest_out, size_t digest_out_size);

KWEB_APPLICATION_LIFECYCLE_EXPORT uint64_t
KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
