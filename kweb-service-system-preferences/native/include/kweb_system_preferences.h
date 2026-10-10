#ifndef KWEB_SYSTEM_PREFERENCES_H_
#define KWEB_SYSTEM_PREFERENCES_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_PREFERENCES_ABI_BUILD)
#define KWEB_PREFERENCES_EXPORT __declspec(dllexport)
#else
#define KWEB_PREFERENCES_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_PREFERENCES_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_PREFERENCES_CALL __cdecl
#else
#define KWEB_PREFERENCES_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_PREFERENCES_ABI_VERSION ((uint32_t)1)
#define KWEB_PREFERENCES_MAX_PROVIDER 64u
#define KWEB_PREFERENCES_MAX_ERROR 96u
#define KWEB_PREFERENCES_EVENT_CAPACITY 64u
#define KWEB_PREFERENCES_MIN_TEXT_SCALE 50u
#define KWEB_PREFERENCES_MAX_TEXT_SCALE 500u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_preferences_status;
#define KWEB_PREFERENCES_STATUS_OK ((kweb_preferences_status)0)
#define KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT ((kweb_preferences_status)1)
#define KWEB_PREFERENCES_STATUS_ABI_MISMATCH ((kweb_preferences_status)2)
#define KWEB_PREFERENCES_STATUS_PLATFORM_UNAVAILABLE ((kweb_preferences_status)3)
#define KWEB_PREFERENCES_STATUS_FACILITY_UNSUPPORTED ((kweb_preferences_status)4)
#define KWEB_PREFERENCES_STATUS_SENSITIVE_KEY_UNKNOWN ((kweb_preferences_status)5)
#define KWEB_PREFERENCES_STATUS_APPEARANCE_INVALID ((kweb_preferences_status)6)
#define KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED ((kweb_preferences_status)7)
#define KWEB_PREFERENCES_STATUS_NATIVE_FAILED ((kweb_preferences_status)8)
#define KWEB_PREFERENCES_STATUS_NO_EVENT ((kweb_preferences_status)9)
#define KWEB_PREFERENCES_STATUS_OWNER_CLOSED ((kweb_preferences_status)10)
#define KWEB_PREFERENCES_STATUS_ALREADY_OPEN ((kweb_preferences_status)11)

/* Published facts. A fact whose bit is absent is absent from the snapshot. */
#define KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE ((uint32_t)1)
#define KWEB_PREFERENCE_FACT_COLOR_SCHEME ((uint32_t)2)
#define KWEB_PREFERENCE_FACT_CONTRAST ((uint32_t)4)
#define KWEB_PREFERENCE_FACT_REDUCED_MOTION ((uint32_t)8)
#define KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY ((uint32_t)16)
#define KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR ((uint32_t)32)
#define KWEB_PREFERENCE_FACT_INVERT_COLORS ((uint32_t)64)
#define KWEB_PREFERENCE_FACT_ACCENT_COLOR ((uint32_t)128)
#define KWEB_PREFERENCE_FACT_TEXT_SCALE ((uint32_t)256)
#define KWEB_PREFERENCE_FACT_SCREEN_READER ((uint32_t)512)
#define KWEB_PREFERENCE_FACT_ALL ((uint32_t)1023)

/* Declared sensitive keys. One key applies per target. */
#define KWEB_PREFERENCES_KEY_MACOS_VOICEOVER ((uint32_t)1)
#define KWEB_PREFERENCES_KEY_WINDOWS_SCREEN_READER ((uint32_t)2)
#define KWEB_PREFERENCES_KEY_LINUX_A11Y ((uint32_t)4)
#define KWEB_PREFERENCES_KEY_ALL ((uint32_t)7)

/* Color scheme. Zero means the platform publishes no preference. */
#define KWEB_COLOR_SCHEME_LIGHT ((uint32_t)1)
#define KWEB_COLOR_SCHEME_DARK ((uint32_t)2)

/* Contrast preference. Zero means the platform publishes no preference. */
#define KWEB_CONTRAST_NONE ((uint32_t)1)
#define KWEB_CONTRAST_MORE ((uint32_t)2)
#define KWEB_CONTRAST_FORCED_COLORS ((uint32_t)3)

/* Tri-state facts: unknown means the platform does not publish the fact. */
#define KWEB_PREFERENCE_UNKNOWN ((uint32_t)0)
#define KWEB_PREFERENCE_FALSE ((uint32_t)1)
#define KWEB_PREFERENCE_TRUE ((uint32_t)2)

/* Accent color origin. */
#define KWEB_ACCENT_SOURCE_SYSTEM_COLORIZATION ((uint32_t)1)
#define KWEB_ACCENT_SOURCE_CONTROL_ACCENT ((uint32_t)2)
#define KWEB_ACCENT_SOURCE_DESKTOP_PORTAL ((uint32_t)3)

/* Application appearance. */
#define KWEB_APPEARANCE_SYSTEM ((uint32_t)1)
#define KWEB_APPEARANCE_LIGHT ((uint32_t)2)
#define KWEB_APPEARANCE_DARK ((uint32_t)3)

/* Event kinds. */
#define KWEB_PREFERENCES_EVENT_CHANGED ((uint32_t)1)
#define KWEB_PREFERENCES_EVENT_FAILED ((uint32_t)2)

typedef struct kweb_preferences_string {
  const uint8_t *data;
  size_t size;
} kweb_preferences_string;

typedef struct kweb_preferences_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_preferences_string application_id;
  kweb_preferences_string package_identity;
  /* Declared sensitive keys. A key that does not belong to the current target
   * is rejected instead of being ignored. */
  uint32_t sensitive_key_bits;
  uint32_t reserved;
} kweb_preferences_configuration;

typedef struct kweb_preferences_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_PREFERENCES_MAX_PROVIDER];
  uint32_t published_fact_bits;
  uint32_t live_fact_bits;
  uint32_t sensitive_key_bits;
  uint32_t reserved;
} kweb_preferences_capabilities_result;

typedef struct kweb_preferences_snapshot_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t fact_bits;
  uint32_t color_scheme;
  uint32_t contrast;
  uint32_t appearance_source;
  uint32_t reduced_motion;
  uint32_t reduced_transparency;
  uint32_t differentiate_without_color;
  uint32_t invert_colors;
  uint32_t screen_reader;
  uint32_t accent_source;
  uint32_t accent_red;
  uint32_t accent_green;
  uint32_t accent_blue;
  uint32_t text_scale_percent;
  uint64_t sequence;
} kweb_preferences_snapshot_result;

typedef struct kweb_preferences_appearance_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t requested;
  uint32_t effective;
  uint64_t sequence;
} kweb_preferences_appearance_result;

typedef struct kweb_preferences_event {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t kind;
  uint32_t changed_fact_bits;
  uint64_t sequence;
  char code[KWEB_PREFERENCES_MAX_ERROR];
} kweb_preferences_event;

/* The ABI revision this library implements. */
KWEB_PREFERENCES_EXPORT uint32_t KWEB_PREFERENCES_CALL kweb_preferences_abi_version(void);

/* Opens the process-wide preferences service. A second open fails. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_open(
    const kweb_preferences_configuration *configuration, uint64_t *handle);

/* Declared provider capabilities for the current target. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_capabilities(
    uint64_t handle, kweb_preferences_capabilities_result *result);

/* One atomic snapshot of the published facts. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_snapshot(
    uint64_t handle, kweb_preferences_snapshot_result *result);

/* Records the application appearance and reports the effective source. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_request_appearance(
    uint64_t handle, uint32_t source, kweb_preferences_appearance_result *result);

/* Pops one ordered event, or reports KWEB_PREFERENCES_STATUS_NO_EVENT. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_poll_event(
    uint64_t handle, kweb_preferences_event *event);

/* Releases every OS observer and the process-wide state. */
KWEB_PREFERENCES_EXPORT kweb_preferences_status KWEB_PREFERENCES_CALL kweb_preferences_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
