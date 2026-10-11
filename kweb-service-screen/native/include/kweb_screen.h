#ifndef KWEB_SCREEN_H_
#define KWEB_SCREEN_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_SCREEN_ABI_BUILD)
#define KWEB_SCREEN_EXPORT __declspec(dllexport)
#else
#define KWEB_SCREEN_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_SCREEN_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_SCREEN_CALL __cdecl
#else
#define KWEB_SCREEN_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_SCREEN_ABI_VERSION ((uint32_t)1)
#define KWEB_SCREEN_MAX_ID 128u
#define KWEB_SCREEN_MAX_NAME 128u
#define KWEB_SCREEN_MAX_PROVIDER 64u
#define KWEB_SCREEN_MAX_DISPLAYS 16u
#define KWEB_SCREEN_EVENT_IDS (3u * KWEB_SCREEN_MAX_DISPLAYS)
#define KWEB_SCREEN_EVENT_CAPACITY 64u
#define KWEB_SCREEN_MIN_COORDINATE (-32768)
#define KWEB_SCREEN_MAX_COORDINATE 32767
#define KWEB_SCREEN_MAX_EXTENT 32767
#define KWEB_SCREEN_MIN_SCALE_PERMILLE 1000u
#define KWEB_SCREEN_MAX_SCALE_PERMILLE 8000u
#define KWEB_SCREEN_MAX_REFRESH_HERTZ 1000u
#define KWEB_SCREEN_MAX_COLOR_DEPTH 64u
#define KWEB_SCREEN_MAX_MILLIMETRES 100000u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_screen_status;
#define KWEB_SCREEN_STATUS_OK ((kweb_screen_status)0)
#define KWEB_SCREEN_STATUS_INVALID_ARGUMENT ((kweb_screen_status)1)
#define KWEB_SCREEN_STATUS_ABI_MISMATCH ((kweb_screen_status)2)
#define KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE ((kweb_screen_status)3)
#define KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE ((kweb_screen_status)4)
#define KWEB_SCREEN_STATUS_TOPOLOGY_INVALID ((kweb_screen_status)5)
#define KWEB_SCREEN_STATUS_DISPLAY_UNKNOWN ((kweb_screen_status)6)
#define KWEB_SCREEN_STATUS_GEOMETRY_INVALID ((kweb_screen_status)7)
#define KWEB_SCREEN_STATUS_POINT_INVALID ((kweb_screen_status)8)
#define KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED ((kweb_screen_status)9)
#define KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE ((kweb_screen_status)10)
#define KWEB_SCREEN_STATUS_NATIVE_FAILED ((kweb_screen_status)11)
#define KWEB_SCREEN_STATUS_NO_EVENT ((kweb_screen_status)12)
#define KWEB_SCREEN_STATUS_OWNER_CLOSED ((kweb_screen_status)13)
#define KWEB_SCREEN_STATUS_ALREADY_OPEN ((kweb_screen_status)14)
#define KWEB_SCREEN_STATUS_BUFFER_TOO_SMALL ((kweb_screen_status)15)

/* The session a provider implements. A provider advertises exactly one. */
#define KWEB_SCREEN_SESSION_WINDOWS ((uint32_t)1)
#define KWEB_SCREEN_SESSION_MACOS ((uint32_t)2)
#define KWEB_SCREEN_SESSION_X11 ((uint32_t)3)
#define KWEB_SCREEN_SESSION_WAYLAND ((uint32_t)4)

/* Coordinate spaces. */
#define KWEB_SCREEN_SPACE_LOGICAL ((uint32_t)1)
#define KWEB_SCREEN_SPACE_NATIVE ((uint32_t)2)
#define KWEB_SCREEN_SPACE_PHYSICAL ((uint32_t)3)
#define KWEB_SCREEN_SPACES_REQUIRED ((uint32_t)((1u << 0) | (1u << 1)))

/* Quarter-turn rotations. */
#define KWEB_SCREEN_ROTATION_NONE ((uint32_t)1)
#define KWEB_SCREEN_ROTATION_QUAD_1 ((uint32_t)2)
#define KWEB_SCREEN_ROTATION_QUAD_2 ((uint32_t)3)
#define KWEB_SCREEN_ROTATION_QUAD_3 ((uint32_t)4)

/* Published display facts. A fact whose bit is absent is absent from the model. */
#define KWEB_SCREEN_FACT_WORK_AREA ((uint32_t)1)
#define KWEB_SCREEN_FACT_SCALE ((uint32_t)2)
#define KWEB_SCREEN_FACT_ROTATION ((uint32_t)4)
#define KWEB_SCREEN_FACT_REFRESH_RATE ((uint32_t)8)
#define KWEB_SCREEN_FACT_COLOR_DEPTH ((uint32_t)16)
#define KWEB_SCREEN_FACT_PHYSICAL_SIZE ((uint32_t)32)
#define KWEB_SCREEN_FACT_PRIMARY ((uint32_t)64)
#define KWEB_SCREEN_FACT_ALL ((uint32_t)127)

/* Declared provider capabilities. */
#define KWEB_SCREEN_CAP_WORK_AREA ((uint32_t)1)
#define KWEB_SCREEN_CAP_SCALE ((uint32_t)2)
#define KWEB_SCREEN_CAP_ROTATION ((uint32_t)4)
#define KWEB_SCREEN_CAP_REFRESH_RATE ((uint32_t)8)
#define KWEB_SCREEN_CAP_COLOR_DEPTH ((uint32_t)16)
#define KWEB_SCREEN_CAP_PHYSICAL_SIZE ((uint32_t)32)
#define KWEB_SCREEN_CAP_CURSOR ((uint32_t)64)
#define KWEB_SCREEN_CAP_TOPOLOGY_EVENTS ((uint32_t)128)
#define KWEB_SCREEN_CAP_MULTI_DISPLAY ((uint32_t)256)

/* Event kinds. */
#define KWEB_SCREEN_EVENT_TOPOLOGY_CHANGED ((uint32_t)1)
#define KWEB_SCREEN_EVENT_FAILED ((uint32_t)2)

typedef struct kweb_screen_string {
  const uint8_t *data;
  size_t size;
} kweb_screen_string;

typedef struct kweb_screen_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_screen_string application_id;
  kweb_screen_string package_identity;
  uint32_t reserved;
} kweb_screen_configuration;

typedef struct kweb_screen_rectangle {
  int32_t left;
  int32_t top;
  int32_t width;
  int32_t height;
} kweb_screen_rectangle;

typedef struct kweb_screen_display {
  uint32_t struct_size;
  uint32_t abi_version;
  char id[KWEB_SCREEN_MAX_ID];
  char name[KWEB_SCREEN_MAX_NAME];
  uint32_t fact_bits;
  uint32_t session;
  int32_t left;
  int32_t top;
  int32_t width;
  int32_t height;
  int32_t native_left;
  int32_t native_top;
  int32_t native_width;
  int32_t native_height;
  int32_t work_left;
  int32_t work_top;
  int32_t work_width;
  int32_t work_height;
  uint32_t scale_permille;
  uint32_t rotation;
  uint32_t refresh_hertz;
  uint32_t color_depth_bits;
  uint32_t physical_width_mm;
  uint32_t physical_height_mm;
} kweb_screen_display;

typedef struct kweb_screen_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_SCREEN_MAX_PROVIDER];
  uint32_t session;
  uint32_t capability_bits;
  uint32_t space_bits;
} kweb_screen_capabilities_result;

typedef struct kweb_screen_topology_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_SCREEN_MAX_PROVIDER];
  uint32_t session;
  uint32_t display_count;
  uint32_t capacity;
  uint32_t reserved;
  uint64_t sequence;
  kweb_screen_display *displays;
} kweb_screen_topology_result;

typedef struct kweb_screen_id_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t found;
  uint32_t reserved;
  char id[KWEB_SCREEN_MAX_ID];
} kweb_screen_id_result;

typedef struct kweb_screen_point_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t space;
  uint32_t display_known;
  int32_t x;
  int32_t y;
  char display_id[KWEB_SCREEN_MAX_ID];
} kweb_screen_point_result;

typedef struct kweb_screen_conversion_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_screen_string display_id;
  uint32_t from_space;
  uint32_t to_space;
  int32_t x;
  int32_t y;
} kweb_screen_conversion_request;

typedef struct kweb_screen_event {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t kind;
  uint32_t added_count;
  uint32_t removed_count;
  uint32_t changed_count;
  uint64_t sequence;
  char code[96];
  char ids[KWEB_SCREEN_EVENT_IDS][KWEB_SCREEN_MAX_ID];
} kweb_screen_event;

/* The ABI revision this library implements. */
KWEB_SCREEN_EXPORT uint32_t KWEB_SCREEN_CALL kweb_screen_abi_version(void);

/* Opens the process-wide screen service. A second open fails. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_open(
    const kweb_screen_configuration *configuration, uint64_t *handle);

/* Declared provider capabilities for the current target and session. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_capabilities(
    uint64_t handle, kweb_screen_capabilities_result *result);

/* One atomic topology snapshot into the caller's display buffer. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_topology(
    uint64_t handle, kweb_screen_topology_result *result);

/* The published cursor position, or the typed unavailable boundary. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_cursor(
    uint64_t handle, kweb_screen_point_result *result);

/* The display containing a point, or found = 0 when no display does. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_display_at(
    uint64_t handle, int32_t x, int32_t y, uint32_t space, kweb_screen_id_result *result);

/* The closest display to a point. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_nearest(
    uint64_t handle, int32_t x, int32_t y, uint32_t space, kweb_screen_id_result *result);

/* One exact conversion between the declared spaces of one live display. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_convert(
    uint64_t handle, const kweb_screen_conversion_request *request, kweb_screen_point_result *result);

/* Pops one ordered event, or reports KWEB_SCREEN_STATUS_NO_EVENT. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_poll_event(
    uint64_t handle, kweb_screen_event *event);

/* Releases every native observer and the process-wide state. */
KWEB_SCREEN_EXPORT kweb_screen_status KWEB_SCREEN_CALL kweb_screen_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
