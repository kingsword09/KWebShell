#ifndef KWEB_SECURE_STORAGE_H_
#define KWEB_SECURE_STORAGE_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_SECURE_STORAGE_ABI_BUILD)
#define KWEB_SECURE_STORAGE_EXPORT __declspec(dllexport)
#else
#define KWEB_SECURE_STORAGE_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_SECURE_STORAGE_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_SECURE_STORAGE_CALL __cdecl
#else
#define KWEB_SECURE_STORAGE_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_SECURE_STORAGE_ABI_VERSION ((uint32_t)1)
#define KWEB_SECURE_STORAGE_MAX_ID 129u
#define KWEB_SECURE_STORAGE_MAX_OWNER 129u
#define KWEB_SECURE_STORAGE_MAX_PROVIDER 64u
#define KWEB_SECURE_STORAGE_MAX_VALUE 4096u
#define KWEB_SECURE_STORAGE_MAX_METADATA 32u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_secure_storage_status;
#define KWEB_SECURE_STORAGE_STATUS_OK ((kweb_secure_storage_status)0)
#define KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT ((kweb_secure_storage_status)1)
#define KWEB_SECURE_STORAGE_STATUS_ABI_MISMATCH ((kweb_secure_storage_status)2)
#define KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE ((kweb_secure_storage_status)3)
#define KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE ((kweb_secure_storage_status)4)
#define KWEB_SECURE_STORAGE_STATUS_ACCESSIBILITY_UNSUPPORTED ((kweb_secure_storage_status)5)
#define KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_UNSUPPORTED ((kweb_secure_storage_status)6)
#define KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_DENIED ((kweb_secure_storage_status)7)
#define KWEB_SECURE_STORAGE_STATUS_LOCKED ((kweb_secure_storage_status)8)
#define KWEB_SECURE_STORAGE_STATUS_CANCELLED ((kweb_secure_storage_status)9)
#define KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND ((kweb_secure_storage_status)10)
#define KWEB_SECURE_STORAGE_STATUS_CORRUPTED ((kweb_secure_storage_status)11)
#define KWEB_SECURE_STORAGE_STATUS_ROTATION_UNSUPPORTED ((kweb_secure_storage_status)12)
#define KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED ((kweb_secure_storage_status)13)
#define KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED ((kweb_secure_storage_status)14)
#define KWEB_SECURE_STORAGE_STATUS_ALREADY_OPEN ((kweb_secure_storage_status)15)
#define KWEB_SECURE_STORAGE_STATUS_BUFFER_TOO_SMALL ((kweb_secure_storage_status)16)

/* Ownership scope. */
#define KWEB_SECRET_SCOPE_APPLICATION ((uint32_t)1)
#define KWEB_SECRET_SCOPE_PROFILE ((uint32_t)2)

/* Declared accessibility. A backend that cannot enforce one reports it absent. */
#define KWEB_SECRET_ACCESS_WHEN_UNLOCKED ((uint32_t)1)
#define KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY ((uint32_t)2)
#define KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK ((uint32_t)3)
#define KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY ((uint32_t)4)

/* The declared credential backend. */
#define KWEB_SECRET_BACKEND_MACOS_KEYCHAIN ((uint32_t)1)
#define KWEB_SECRET_BACKEND_WINDOWS_CREDENTIAL_MANAGER ((uint32_t)2)
#define KWEB_SECRET_BACKEND_LINUX_SECRET_SERVICE ((uint32_t)3)

/* Declared provider capabilities. */
#define KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY ((uint32_t)1)
#define KWEB_SECURE_STORAGE_CAP_USER_PRESENCE ((uint32_t)2)
#define KWEB_SECURE_STORAGE_CAP_ROTATION ((uint32_t)4)
#define KWEB_SECURE_STORAGE_CAP_PROFILE_SCOPE ((uint32_t)8)

/* Rotation status. */
#define KWEB_SECRET_ROTATION_CURRENT ((uint32_t)1)
#define KWEB_SECRET_ROTATION_STALE_ENVELOPE ((uint32_t)2)
#define KWEB_SECRET_ROTATION_STALE_POLICY ((uint32_t)3)

typedef struct kweb_secure_storage_string {
  const uint8_t *data;
  size_t size;
} kweb_secure_storage_string;

typedef struct kweb_secure_storage_bytes {
  const uint8_t *data;
  size_t size;
} kweb_secure_storage_bytes;

typedef struct kweb_secure_storage_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_secure_storage_string application_id;
  kweb_secure_storage_string package_identity;
  uint32_t reserved;
} kweb_secure_storage_configuration;

typedef struct kweb_secure_storage_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_SECURE_STORAGE_MAX_PROVIDER];
  uint32_t backend;
  uint32_t capability_bits;
  uint32_t accessibility_bits;
  uint32_t max_value_bytes;
  uint32_t secret_count;
} kweb_secure_storage_capabilities_result;

typedef struct kweb_secure_storage_reference {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_secure_storage_string secret_id;
  kweb_secure_storage_string owner_id;
  uint32_t scope;
  uint32_t reserved;
} kweb_secure_storage_reference;

typedef struct kweb_secure_storage_put_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_secure_storage_reference reference;
  kweb_secure_storage_bytes value;
  uint32_t accessibility;
  uint32_t require_user_presence;
} kweb_secure_storage_put_request;

typedef struct kweb_secure_storage_metadata {
  char secret_id[KWEB_SECURE_STORAGE_MAX_ID];
  uint32_t scope;
  uint32_t backend;
  uint32_t accessibility;
  uint32_t envelope_version;
  uint32_t generation;
  uint32_t size_bytes;
  uint32_t reserved;
} kweb_secure_storage_metadata;

typedef struct kweb_secure_storage_value_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t size;
  uint32_t capacity;
  uint8_t *data;
} kweb_secure_storage_value_result;

typedef struct kweb_secure_storage_presence_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t present;
  uint32_t reserved;
} kweb_secure_storage_presence_result;

typedef struct kweb_secure_storage_rotation_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t status;
  uint32_t generation;
} kweb_secure_storage_rotation_result;

typedef struct kweb_secure_storage_list_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t count;
  uint32_t capacity;
  kweb_secure_storage_metadata *entries;
} kweb_secure_storage_list_result;

/* The ABI revision this library implements. */
KWEB_SECURE_STORAGE_EXPORT uint32_t KWEB_SECURE_STORAGE_CALL kweb_secure_storage_abi_version(void);

/* Opens the process-wide secret store. A second open fails. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_open(
    const kweb_secure_storage_configuration *configuration, uint64_t *handle);

/* Declared provider capabilities for the current target. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_capabilities(
    uint64_t handle, kweb_secure_storage_capabilities_result *result);

/* Stores one secret, or fails typed and stores nothing. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_put(
    uint64_t handle, const kweb_secure_storage_put_request *request,
    kweb_secure_storage_metadata *result);

/* Reads one secret into the caller's buffer; the buffer is zeroed on release. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_get(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_value_result *result);

/* Releases a value buffer this library filled and zeroes it. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_release(
    uint64_t handle, kweb_secure_storage_value_result *value);

/* Deletes one secret; an unknown identifier reports present = 0. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_delete(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_presence_result *result);

/* Reports whether one secret is stored. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_exists(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_presence_result *result);

/* Re-writes one stored secret through the current backend and policy. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_rotate(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_metadata *result);

/* Reports whether a stored record matches the current envelope and policy. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_rotation_status(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_rotation_result *result);

/* Lists the metadata of the stored records into the caller's buffer. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_list(
    uint64_t handle, kweb_secure_storage_list_result *result);

/* Releases the platform session and zeroes every staging buffer. */
KWEB_SECURE_STORAGE_EXPORT kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_close(
    uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
