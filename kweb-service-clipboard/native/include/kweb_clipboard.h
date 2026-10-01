#ifndef KWEB_CLIPBOARD_H_
#define KWEB_CLIPBOARD_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_CLIPBOARD_ABI_BUILD)
#define KWEB_CLIPBOARD_EXPORT __declspec(dllexport)
#else
#define KWEB_CLIPBOARD_EXPORT __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define KWEB_CLIPBOARD_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_CLIPBOARD_EXPORT
#endif

#if defined(_WIN32)
#define KWEB_CLIPBOARD_CALL __cdecl
#else
#define KWEB_CLIPBOARD_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_CLIPBOARD_ABI_VERSION ((uint32_t)1)

typedef uint32_t kweb_clipboard_status;
#define KWEB_CLIPBOARD_STATUS_OK ((kweb_clipboard_status)0)
#define KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT ((kweb_clipboard_status)1)
#define KWEB_CLIPBOARD_STATUS_ABI_MISMATCH ((kweb_clipboard_status)2)
#define KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE ((kweb_clipboard_status)3)
#define KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE ((kweb_clipboard_status)4)
#define KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE ((kweb_clipboard_status)5)
#define KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN ((kweb_clipboard_status)6)
#define KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED ((kweb_clipboard_status)7)
#define KWEB_CLIPBOARD_STATUS_BUFFER_SMALL ((kweb_clipboard_status)8)
#define KWEB_CLIPBOARD_STATUS_NATIVE_FAILED ((kweb_clipboard_status)9)

typedef uint32_t kweb_clipboard_format;
#define KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN ((kweb_clipboard_format)1)
#define KWEB_CLIPBOARD_FORMAT_TEXT_HTML ((kweb_clipboard_format)2)
#define KWEB_CLIPBOARD_FORMAT_TEXT_RTF ((kweb_clipboard_format)3)
#define KWEB_CLIPBOARD_FORMAT_URI_LIST ((kweb_clipboard_format)4)

typedef uint32_t kweb_clipboard_ownership;
#define KWEB_CLIPBOARD_OWNERSHIP_OWNED ((kweb_clipboard_ownership)1)
#define KWEB_CLIPBOARD_OWNERSHIP_FOREIGN ((kweb_clipboard_ownership)2)
#define KWEB_CLIPBOARD_OWNERSHIP_EMPTY ((kweb_clipboard_ownership)3)
#define KWEB_CLIPBOARD_OWNERSHIP_UNAVAILABLE ((kweb_clipboard_ownership)4)

typedef struct kweb_clipboard_item {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_clipboard_format format;
  uint32_t reserved;
  const uint8_t *data;
  size_t size;
} kweb_clipboard_item;

typedef struct kweb_clipboard_snapshot_record {
  uint32_t struct_size;
  uint32_t abi_version;
  uint64_t sequence;
  uint32_t format_mask;
  kweb_clipboard_ownership ownership;
  uint32_t reserved;
} kweb_clipboard_snapshot_record;

KWEB_CLIPBOARD_EXPORT uint32_t KWEB_CLIPBOARD_CALL
kweb_clipboard_abi_version(void);

KWEB_CLIPBOARD_EXPORT const char * KWEB_CLIPBOARD_CALL
kweb_clipboard_status_name(kweb_clipboard_status status);

KWEB_CLIPBOARD_EXPORT const char * KWEB_CLIPBOARD_CALL
kweb_clipboard_provider_id(void);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_open(uint64_t *handle);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_snapshot(uint64_t handle, kweb_clipboard_snapshot_record *snapshot);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_read(uint64_t handle, kweb_clipboard_format format,
                    uint8_t *buffer, size_t capacity, size_t *size);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_write(uint64_t handle, const kweb_clipboard_item *items,
                     size_t count);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_clear(uint64_t handle);

KWEB_CLIPBOARD_EXPORT kweb_clipboard_status KWEB_CLIPBOARD_CALL
kweb_clipboard_close(uint64_t handle);

KWEB_CLIPBOARD_EXPORT uint32_t KWEB_CLIPBOARD_CALL
kweb_clipboard_live_count(void);

#ifdef __cplusplus
}
#endif

#endif
