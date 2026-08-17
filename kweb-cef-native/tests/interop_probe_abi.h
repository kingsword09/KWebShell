#ifndef KWEBSHELL_TESTS_INTEROP_PROBE_ABI_H_
#define KWEBSHELL_TESTS_INTEROP_PROBE_ABI_H_

#include <stddef.h>
#include <stdint.h>

#include "kwebshell/native/base_abi.h"

#if defined(_WIN32)
#define KWEB_PROBE_EXPORT __declspec(dllexport)
#elif defined(__GNUC__)
#define KWEB_PROBE_EXPORT __attribute__((visibility("default")))
#else
#define KWEB_PROBE_EXPORT
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_INTEROP_PROBE_ABI_VERSION ((uint32_t)1)
#define KWEB_PROBE_INVALID_LAYOUT_VALUE UINT64_MAX
#define KWEB_PROBE_MAXIMUM_UTF8_SIZE ((size_t)1048576)

typedef uint32_t kweb_probe_layout_type;

#define KWEB_PROBE_LAYOUT_STRING_VIEW ((kweb_probe_layout_type)1)
#define KWEB_PROBE_LAYOUT_ENGINE_EVENT ((kweb_probe_layout_type)2)
#define KWEB_PROBE_LAYOUT_ENGINE_CONFIG ((kweb_probe_layout_type)3)
#define KWEB_PROBE_LAYOUT_BROWSER_EVENT ((kweb_probe_layout_type)4)
#define KWEB_PROBE_LAYOUT_BRIDGE_EVENT ((kweb_probe_layout_type)5)
#define KWEB_PROBE_LAYOUT_EXTENSION_RESULT ((kweb_probe_layout_type)6)
#define KWEB_PROBE_LAYOUT_EXTENSION_CONFIG ((kweb_probe_layout_type)7)
#define KWEB_PROBE_LAYOUT_BROWSER_CONFIG ((kweb_probe_layout_type)8)

typedef uint64_t(KWEB_ABI_CALL *kweb_probe_fixed_callback)(
    void *user_data, uint64_t sequence);

typedef uint64_t(KWEB_ABI_CALL *kweb_probe_utf8_callback)(
    void *user_data, const char *text_utf8, size_t text_size,
    uint64_t sequence);

KWEB_PROBE_EXPORT uint32_t KWEB_ABI_CALL kweb_probe_abi_version(void);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL
kweb_probe_layout_size(kweb_probe_layout_type type);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL
kweb_probe_layout_alignment(kweb_probe_layout_type type);

KWEB_PROBE_EXPORT uint32_t KWEB_ABI_CALL
kweb_probe_layout_field_count(kweb_probe_layout_type type);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL
kweb_probe_layout_field_offset(kweb_probe_layout_type type,
                               uint32_t field_index);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_integer_call(
    uint64_t handle, int32_t width, int32_t height);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL
kweb_probe_utf8_call(const char *text_utf8, size_t text_size);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_fixed_upcall(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_threaded_fixed_upcall(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_utf8_upcall(
    kweb_probe_utf8_callback callback, void *user_data, const char *text_utf8,
    size_t text_size, uint32_t count);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_malformed_utf8_upcall(
    kweb_probe_utf8_callback callback, void *user_data);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL kweb_probe_owner_cycles(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count);

KWEB_PROBE_EXPORT uint64_t KWEB_ABI_CALL
kweb_probe_live_native_bytes(void);

KWEB_PROBE_EXPORT kweb_status KWEB_ABI_CALL
kweb_probe_validate_native_parent(uintptr_t native_parent);

#ifdef __cplusplus
}
#endif

#endif // KWEBSHELL_TESTS_INTEROP_PROBE_ABI_H_
