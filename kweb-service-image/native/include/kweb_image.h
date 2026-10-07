#ifndef KWEB_IMAGE_H_
#define KWEB_IMAGE_H_

#include <stddef.h>
#include <stdint.h>

#ifdef _WIN32
#define KWEB_IMAGE_EXPORT __declspec(dllexport)
#else
#define KWEB_IMAGE_EXPORT __attribute__((visibility("default")))
#endif

typedef enum kweb_image_status {
  KWEB_IMAGE_STATUS_OK = 0,
  KWEB_IMAGE_STATUS_INVALID_ARGUMENT = 1,
  KWEB_IMAGE_STATUS_ABI_MISMATCH = 2,
  KWEB_IMAGE_STATUS_NATIVE_UNAVAILABLE = 3,
  KWEB_IMAGE_STATUS_NATIVE_FAILED = 4,
  KWEB_IMAGE_STATUS_HANDLE_LIMIT = 5,
  KWEB_IMAGE_STATUS_OUTCOME_UNKNOWN = 6,
} kweb_image_status;

#define KWEB_IMAGE_MAX_NATIVE_HANDLES 256U

typedef struct kweb_image_rgba {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t width;
  uint32_t height;
  const uint8_t *bytes;
  uint64_t size;
} kweb_image_rgba;

#ifdef __cplusplus
extern "C" {
#endif

KWEB_IMAGE_EXPORT uint32_t kweb_image_abi_version(void);
KWEB_IMAGE_EXPORT const char *kweb_image_status_name(uint32_t status);
KWEB_IMAGE_EXPORT const char *kweb_image_provider_id(void);
KWEB_IMAGE_EXPORT uint32_t kweb_image_create(const kweb_image_rgba *input, uint64_t *handle);
KWEB_IMAGE_EXPORT uint32_t kweb_image_release(uint64_t handle);
KWEB_IMAGE_EXPORT uint32_t kweb_image_live_count(uint32_t *count);

#ifdef __cplusplus
}
#endif

#endif
