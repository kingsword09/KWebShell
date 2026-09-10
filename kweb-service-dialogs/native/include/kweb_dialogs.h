#ifndef KWEB_DIALOGS_H
#define KWEB_DIALOGS_H

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#define KWEB_DIALOG_EXPORT __declspec(dllexport)
#else
#define KWEB_DIALOG_EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_DIALOG_ABI_VERSION 1u
#define KWEB_DIALOG_OK 0
#define KWEB_DIALOG_INVALID_REQUEST 1
#define KWEB_DIALOG_INVALID_HANDLE 2
#define KWEB_DIALOG_BUSY 3
#define KWEB_DIALOG_BUFFER_SMALL 4
#define KWEB_DIALOG_UNAVAILABLE 5
#define KWEB_DIALOG_NATIVE_FAILED 6

#define KWEB_DIALOG_PENDING 0u
#define KWEB_DIALOG_VISIBLE 1u
#define KWEB_DIALOG_SELECTED 2u
#define KWEB_DIALOG_CANCELLED 3u
#define KWEB_DIALOG_FAILED 4u

typedef struct kweb_dialog_text {
  const char *data;
  size_t size;
} kweb_dialog_text;

typedef struct kweb_dialog_request {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t mode; /* 0 = open, 1 = save */
  uint32_t extension_count;
  uint64_t owner;
  kweb_dialog_text title;
  kweb_dialog_text directory;
  kweb_dialog_text name;
  const kweb_dialog_text *extensions;
} kweb_dialog_request;

typedef struct kweb_dialog_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t state;
  uint32_t failure;
  size_t path_size;
} kweb_dialog_result;

KWEB_DIALOG_EXPORT uint32_t kweb_dialog_abi_version(void);
/* Copies request data; the caller retains all input memory. */
KWEB_DIALOG_EXPORT int32_t kweb_dialog_start(const kweb_dialog_request *, uint64_t *id);
/* Terminal states are published only after platform resources have closed. */
KWEB_DIALOG_EXPORT int32_t kweb_dialog_poll(uint64_t id, kweb_dialog_result *,
                                          char *path, size_t capacity);
KWEB_DIALOG_EXPORT int32_t kweb_dialog_cancel(uint64_t id);
/* Release is rejected while native work is still pending. */
KWEB_DIALOG_EXPORT int32_t kweb_dialog_release(uint64_t id);
KWEB_DIALOG_EXPORT uint32_t kweb_dialog_live_count(void);

#ifdef __cplusplus
}
#endif
#endif
