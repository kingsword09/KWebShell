#ifndef KWEB_POWER_H_
#define KWEB_POWER_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_POWER_ABI_BUILD)
#define KWEB_POWER_EXPORT __declspec(dllexport)
#else
#define KWEB_POWER_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_POWER_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_POWER_CALL __cdecl
#else
#define KWEB_POWER_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_POWER_ABI_VERSION ((uint32_t)1)
#define KWEB_POWER_MAX_ID 129u
#define KWEB_POWER_MAX_PROVIDER 64u
#define KWEB_POWER_MAX_LEASES 8u
#define KWEB_POWER_EVENT_CAPACITY 64u
#define KWEB_POWER_MAX_REASON 96u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_power_status;
#define KWEB_POWER_STATUS_OK ((kweb_power_status)0)
#define KWEB_POWER_STATUS_INVALID_ARGUMENT ((kweb_power_status)1)
#define KWEB_POWER_STATUS_ABI_MISMATCH ((kweb_power_status)2)
#define KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE ((kweb_power_status)3)
#define KWEB_POWER_STATUS_SERVICE_UNAVAILABLE ((kweb_power_status)4)
#define KWEB_POWER_STATUS_TYPE_UNSUPPORTED ((kweb_power_status)5)
#define KWEB_POWER_STATUS_DURATION_EXCEEDED ((kweb_power_status)6)
#define KWEB_POWER_STATUS_LIMIT_EXCEEDED ((kweb_power_status)7)
#define KWEB_POWER_STATUS_LEASE_UNKNOWN ((kweb_power_status)8)
#define KWEB_POWER_STATUS_RENEWAL_EXCEEDED ((kweb_power_status)9)
#define KWEB_POWER_STATUS_NATIVE_FAILED ((kweb_power_status)10)
#define KWEB_POWER_STATUS_NO_EVENT ((kweb_power_status)11)
#define KWEB_POWER_STATUS_OWNER_CLOSED ((kweb_power_status)12)
#define KWEB_POWER_STATUS_ALREADY_OPEN ((kweb_power_status)13)
#define KWEB_POWER_STATUS_ALREADY_EXISTS ((kweb_power_status)14)
#define KWEB_POWER_STATUS_BUFFER_TOO_SMALL ((kweb_power_status)15)

/* Blocker types. */
#define KWEB_POWER_TYPE_SYSTEM_SLEEP ((uint32_t)1)
#define KWEB_POWER_TYPE_DISPLAY_SLEEP ((uint32_t)2)

/* Thermal levels. */
#define KWEB_POWER_THERMAL_NOMINAL ((uint32_t)1)
#define KWEB_POWER_THERMAL_FAIR ((uint32_t)2)
#define KWEB_POWER_THERMAL_SERIOUS ((uint32_t)3)
#define KWEB_POWER_THERMAL_CRITICAL ((uint32_t)4)

/* Declared provider capabilities. */
#define KWEB_POWER_CAP_SUSPEND_EVENTS ((uint32_t)1)
#define KWEB_POWER_CAP_LOCK_EVENTS ((uint32_t)2)
#define KWEB_POWER_CAP_POWER_SOURCE_STATE ((uint32_t)4)
#define KWEB_POWER_CAP_THERMAL_STATE ((uint32_t)8)
#define KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER ((uint32_t)16)
#define KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER ((uint32_t)32)
#define KWEB_POWER_CAP_LEASE_RENEWAL ((uint32_t)64)

/* Event kinds. */
#define KWEB_POWER_EVENT_SUSPENDED ((uint32_t)1)
#define KWEB_POWER_EVENT_RESUMED ((uint32_t)2)
#define KWEB_POWER_EVENT_LOCKED ((uint32_t)3)
#define KWEB_POWER_EVENT_UNLOCKED ((uint32_t)4)
#define KWEB_POWER_EVENT_POWER_SOURCE_CHANGED ((uint32_t)5)
#define KWEB_POWER_EVENT_THERMAL_CHANGED ((uint32_t)6)
#define KWEB_POWER_EVENT_LEASE_ENDED ((uint32_t)7)
#define KWEB_POWER_EVENT_FAILED ((uint32_t)8)

/* Lease end reasons. */
#define KWEB_POWER_END_EXPIRED ((uint32_t)1)
#define KWEB_POWER_END_RELEASED ((uint32_t)2)
#define KWEB_POWER_END_OWNER_CLOSED ((uint32_t)3)

/* Fact availability bits for the state result. */
#define KWEB_POWER_FACT_ON_BATTERY ((uint32_t)1)
#define KWEB_POWER_FACT_BATTERY_PERCENT ((uint32_t)2)
#define KWEB_POWER_FACT_THERMAL ((uint32_t)4)
#define KWEB_POWER_FACT_LOCKED ((uint32_t)8)

typedef struct kweb_power_string {
  const uint8_t *data;
  size_t size;
} kweb_power_string;

typedef struct kweb_power_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_power_string application_id;
  kweb_power_string package_identity;
  uint32_t reserved;
} kweb_power_configuration;

typedef struct kweb_power_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_POWER_MAX_PROVIDER];
  uint32_t capability_bits;
  uint32_t max_leases;
  uint32_t max_leases_per_type;
  uint32_t min_lease_seconds;
  uint32_t max_lease_seconds;
  uint32_t lease_count;
} kweb_power_capabilities_result;

typedef struct kweb_power_state_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t fact_bits;
  uint32_t on_battery;
  uint32_t battery_percent;
  uint32_t thermal;
  uint32_t locked;
} kweb_power_state_result;

typedef struct kweb_power_lease_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_power_string lease_id;
  kweb_power_string reason;
  uint32_t type;
  uint32_t max_duration_seconds;
} kweb_power_lease_request;

typedef struct kweb_power_lease_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char lease_id[KWEB_POWER_MAX_ID];
  uint32_t type;
  uint32_t granted_seconds;
  uint32_t remaining_seconds;
  uint32_t renew_count;
  uint32_t reserved;
} kweb_power_lease_result;

typedef struct kweb_power_lease_query {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_power_string lease_id;
  uint32_t reserved;
} kweb_power_lease_query;

typedef struct kweb_power_release_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t released;
  uint32_t reserved;
} kweb_power_release_result;

typedef struct kweb_power_lease {
  char lease_id[KWEB_POWER_MAX_ID];
  char reason[KWEB_POWER_MAX_REASON];
  uint32_t type;
  uint32_t granted_seconds;
  uint32_t remaining_seconds;
  uint32_t renew_count;
  uint32_t reserved;
} kweb_power_lease;

typedef struct kweb_power_lease_list_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t count;
  uint32_t capacity;
  kweb_power_lease *leases;
} kweb_power_lease_list_result;

typedef struct kweb_power_event {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t kind;
  uint32_t value;
  uint32_t reason;
  uint32_t reserved;
  uint64_t sequence;
  char lease_id[KWEB_POWER_MAX_ID];
  char code[KWEB_POWER_MAX_REASON];
} kweb_power_event;

/* The ABI revision this library implements. */
KWEB_POWER_EXPORT uint32_t KWEB_POWER_CALL kweb_power_abi_version(void);

/* Opens the process-wide power service. A second open fails. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_open(
    const kweb_power_configuration *configuration, uint64_t *handle);

/* Declared provider capabilities for the current target and session. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_capabilities(
    uint64_t handle, kweb_power_capabilities_result *result);

/* The facts the session publishes; an unpublished fact stays absent. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_state(
    uint64_t handle, kweb_power_state_result *result);

/* Installs one bounded lease, or fails typed and installs nothing. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_acquire(
    uint64_t handle, const kweb_power_lease_request *request, kweb_power_lease_result *result);

/* Extends one live lease to its declared window from now. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_renew(
    uint64_t handle, const kweb_power_lease_query *query, kweb_power_lease_result *result);

/* Releases one lease; an unknown identifier reports released = 0. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_release(
    uint64_t handle, const kweb_power_lease_query *query, kweb_power_release_result *result);

/* Lists the live leases into the caller's buffer. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_leases(
    uint64_t handle, kweb_power_lease_list_result *result);

/* Pops one ordered event, or reports KWEB_POWER_STATUS_NO_EVENT. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_poll_event(
    uint64_t handle, kweb_power_event *event);

/* Releases every live lease and the process-wide state. */
KWEB_POWER_EXPORT kweb_power_status KWEB_POWER_CALL kweb_power_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
