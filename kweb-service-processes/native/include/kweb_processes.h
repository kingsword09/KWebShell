#ifndef KWEB_PROCESSES_H_
#define KWEB_PROCESSES_H_

#include <stddef.h>
#include <stdint.h>

#if defined(_WIN32)
#if defined(KWEB_PROCESSES_ABI_BUILD)
#define KWEB_PROCESSES_EXPORT __declspec(dllexport)
#else
#define KWEB_PROCESSES_EXPORT __declspec(dllimport)
#endif
#else
#define KWEB_PROCESSES_EXPORT __attribute__((visibility("default")))
#endif

#if defined(_WIN32)
#define KWEB_PROCESSES_CALL __cdecl
#else
#define KWEB_PROCESSES_CALL
#endif

#ifdef __cplusplus
extern "C" {
#endif

#define KWEB_PROCESSES_ABI_VERSION ((uint32_t)1)
#define KWEB_PROCESSES_MAX_ID 129u
#define KWEB_PROCESSES_MAX_PROVIDER 64u
#define KWEB_PROCESSES_MAX_LIVE 8u
#define KWEB_PROCESSES_MAX_ARGUMENTS 64u
#define KWEB_PROCESSES_MAX_ENVIRONMENT 64u
#define KWEB_PROCESSES_MAX_ARGUMENT_BYTES 4096u
#define KWEB_PROCESSES_MAX_ENVIRONMENT_VALUE_BYTES 4096u
#define KWEB_PROCESSES_MIN_TIMEOUT_SECONDS 1u
#define KWEB_PROCESSES_MAX_TIMEOUT_SECONDS 3600u
#define KWEB_PROCESSES_MIN_CPU_SECONDS 1u
#define KWEB_PROCESSES_MAX_CPU_SECONDS 3600u
#define KWEB_PROCESSES_MIN_MEMORY_BYTES ((uint64_t)16777216)
#define KWEB_PROCESSES_MAX_MEMORY_BYTES ((uint64_t)17179869184)
#define KWEB_PROCESSES_MIN_OUTPUT_QUOTA_BYTES ((uint64_t)262144)
#define KWEB_PROCESSES_MAX_OUTPUT_QUOTA_BYTES ((uint64_t)67108864)
#define KWEB_PROCESSES_MIN_OUTPUT_WINDOW_BYTES 4096u
#define KWEB_PROCESSES_MAX_OUTPUT_WINDOW_BYTES 1048576u
#define KWEB_PROCESSES_MAX_STDIN_CHUNK 65536u
#define KWEB_PROCESSES_MAX_OUTPUT_CHUNK 65536u
#define KWEB_PROCESSES_MAX_MONITOR_PERIOD_MILLIS 100u

/* Status codes. Every failure is typed and observable. */
typedef uint32_t kweb_processes_status;
#define KWEB_PROCESSES_STATUS_OK ((kweb_processes_status)0)
#define KWEB_PROCESSES_STATUS_INVALID_ARGUMENT ((kweb_processes_status)1)
#define KWEB_PROCESSES_STATUS_ABI_MISMATCH ((kweb_processes_status)2)
#define KWEB_PROCESSES_STATUS_PLATFORM_UNAVAILABLE ((kweb_processes_status)3)
#define KWEB_PROCESSES_STATUS_ISOLATION_UNSUPPORTED ((kweb_processes_status)4)
#define KWEB_PROCESSES_STATUS_EXECUTABLE_UNVERIFIED ((kweb_processes_status)5)
#define KWEB_PROCESSES_STATUS_EXECUTABLE_MISSING ((kweb_processes_status)6)
#define KWEB_PROCESSES_STATUS_WORKING_DIRECTORY_INVALID ((kweb_processes_status)7)
#define KWEB_PROCESSES_STATUS_LIMIT_EXCEEDED ((kweb_processes_status)8)
#define KWEB_PROCESSES_STATUS_PROCESS_UNKNOWN ((kweb_processes_status)9)
#define KWEB_PROCESSES_STATUS_OWNER_CLOSED ((kweb_processes_status)10)
#define KWEB_PROCESSES_STATUS_NATIVE_FAILED ((kweb_processes_status)11)
#define KWEB_PROCESSES_STATUS_NO_OUTPUT ((kweb_processes_status)12)
#define KWEB_PROCESSES_STATUS_STILL_RUNNING ((kweb_processes_status)13)
#define KWEB_PROCESSES_STATUS_BUFFER_TOO_SMALL ((kweb_processes_status)14)
#define KWEB_PROCESSES_STATUS_STDIN_CLOSED ((kweb_processes_status)15)
#define KWEB_PROCESSES_STATUS_STDIN_TIMEOUT ((kweb_processes_status)16)
#define KWEB_PROCESSES_STATUS_ALREADY_OPEN ((kweb_processes_status)17)
#define KWEB_PROCESSES_STATUS_LAUNCH_FAILED ((kweb_processes_status)18)
#define KWEB_PROCESSES_STATUS_OUTPUT_ENDED ((kweb_processes_status)19)

/* Requested and declared isolation profiles. */
#define KWEB_PROCESSES_ISOLATION_PROCESS_TREE ((uint32_t)1)
#define KWEB_PROCESSES_ISOLATION_RESOURCE_LIMITS ((uint32_t)2)
#define KWEB_PROCESSES_ISOLATION_RESTRICTED_TOKEN ((uint32_t)4)
#define KWEB_PROCESSES_ISOLATION_SYSTEMD_SCOPE ((uint32_t)8)
#define KWEB_PROCESSES_ISOLATION_SECCOMP ((uint32_t)16)
#define KWEB_PROCESSES_ISOLATION_NAMESPACES ((uint32_t)32)
#define KWEB_PROCESSES_ISOLATION_SEATBELT ((uint32_t)64)
#define KWEB_PROCESSES_ISOLATION_ALL ((uint32_t)127)

/* Output streams. */
#define KWEB_PROCESSES_STREAM_STDOUT ((uint32_t)1)
#define KWEB_PROCESSES_STREAM_STDERR ((uint32_t)2)

/* Process states. */
#define KWEB_PROCESSES_STATE_RUNNING ((uint32_t)1)
#define KWEB_PROCESSES_STATE_TERMINATED ((uint32_t)2)

/* Terminal reasons. */
#define KWEB_PROCESSES_REASON_EXITED ((uint32_t)1)
#define KWEB_PROCESSES_REASON_CRASHED ((uint32_t)2)
#define KWEB_PROCESSES_REASON_TIMEOUT ((uint32_t)3)
#define KWEB_PROCESSES_REASON_CPU_LIMIT ((uint32_t)4)
#define KWEB_PROCESSES_REASON_MEMORY_LIMIT ((uint32_t)5)
#define KWEB_PROCESSES_REASON_OUTPUT_LIMIT ((uint32_t)6)
#define KWEB_PROCESSES_REASON_KILLED ((uint32_t)7)
#define KWEB_PROCESSES_REASON_OWNER_CLOSED ((uint32_t)8)

/* The memory measure the provider enforces against. */
#define KWEB_PROCESSES_MEMORY_RESIDENT ((uint32_t)1)

typedef struct kweb_processes_string {
  const uint8_t *data;
  size_t size;
} kweb_processes_string;

typedef struct kweb_processes_string_list {
  const kweb_processes_string *items;
  size_t count;
} kweb_processes_string_list;

typedef struct kweb_processes_configuration {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_processes_string application_id;
  uint32_t reserved;
} kweb_processes_configuration;

typedef struct kweb_processes_capabilities_result {
  uint32_t struct_size;
  uint32_t abi_version;
  char provider_id[KWEB_PROCESSES_MAX_PROVIDER];
  uint32_t isolation_bits;
  uint32_t memory_measure;
  uint32_t max_live_processes;
  uint32_t min_timeout_seconds;
  uint32_t max_timeout_seconds;
  uint32_t min_cpu_seconds;
  uint32_t max_cpu_seconds;
  uint32_t min_output_window_bytes;
  uint32_t max_output_window_bytes;
  uint32_t max_stdin_chunk_bytes;
  uint32_t max_arguments;
  uint32_t max_environment;
  uint32_t live_processes;
  uint32_t reserved;
  uint64_t min_memory_bytes;
  uint64_t max_memory_bytes;
  uint64_t min_output_quota_bytes;
  uint64_t max_output_quota_bytes;
} kweb_processes_capabilities_result;

typedef struct kweb_processes_start_request {
  uint32_t struct_size;
  uint32_t abi_version;
  kweb_processes_string process_id;
  kweb_processes_string executable_path;
  kweb_processes_string executable_sha256;
  kweb_processes_string working_directory;
  kweb_processes_string_list arguments;
  kweb_processes_string_list environment;
  uint32_t requested_isolation_bits;
  uint32_t timeout_seconds;
  uint32_t cpu_seconds;
  uint32_t stdout_window_bytes;
  uint32_t stderr_window_bytes;
  uint32_t reserved;
  uint64_t memory_bytes;
  uint64_t stdout_quota_bytes;
  uint64_t stderr_quota_bytes;
} kweb_processes_start_request;

typedef struct kweb_processes_start_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t isolation_bits;
  uint32_t reserved;
  uint64_t process_handle;
} kweb_processes_start_result;

typedef struct kweb_processes_output_chunk {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t stream;
  uint32_t size;
  uint32_t reserved;
  uint64_t sequence;
  uint64_t total_bytes;
} kweb_processes_output_chunk;

typedef struct kweb_processes_termination {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t reason;
  int32_t exit_code;
  int32_t signal;
  uint32_t reserved;
  uint64_t sequence;
  uint64_t stdout_bytes;
  uint64_t stderr_bytes;
  uint64_t cpu_millis;
  uint64_t peak_memory_bytes;
  uint64_t duration_millis;
} kweb_processes_termination;

typedef struct kweb_processes_status_result {
  uint32_t struct_size;
  uint32_t abi_version;
  uint32_t state;
  uint32_t reserved;
  uint64_t sequence;
  uint64_t stdout_bytes;
  uint64_t stderr_bytes;
  uint64_t pending_output_bytes;
  uint64_t cpu_millis;
  uint64_t peak_memory_bytes;
  uint64_t duration_millis;
  kweb_processes_termination termination;
} kweb_processes_status_result;

/* The ABI revision this library implements. */
KWEB_PROCESSES_EXPORT uint32_t KWEB_PROCESSES_CALL kweb_processes_abi_version(void);

/*
    Opens the process-wide process service. A second open fails.

    POSIX targets ignore SIGPIPE for the service so a child that closes its stdin
    reports a typed failure instead of raising a process-killing signal. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_open(
    const kweb_processes_configuration *configuration, uint64_t *handle);

/* Declared provider isolation, limits, and live-process count. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_capabilities(
    uint64_t handle, kweb_processes_capabilities_result *result);

/*
    Verifies identity, isolation, limits, and the working directory, then starts
    the process tree. Nothing is started when any check fails. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_start(
    uint64_t handle, const kweb_processes_start_request *request, kweb_processes_start_result *result);

/* Writes one bounded chunk to the child's stdin; a full pipe reports zero. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_write_stdin(
    uint64_t process, const uint8_t *bytes, size_t size, uint32_t timeout_millis, uint64_t *accepted);

/* Closes the child's stdin exactly once; a closed stdin reports STDIN_CLOSED. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_close_stdin(uint64_t process);

/* Copies the next ordered chunk, or reports NO_OUTPUT after the wait budget. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_read_output(
    uint64_t process, uint8_t *buffer, size_t capacity, uint32_t timeout_millis,
    kweb_processes_output_chunk *chunk);

/* Reports the state and counters; the termination block is filled when ended. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_state(
    uint64_t process, kweb_processes_status_result *result);

/* Waits up to the budget for the terminal result, or reports STILL_RUNNING. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_wait(
    uint64_t process, uint32_t timeout_millis, kweb_processes_termination *termination);

/* Terminates the whole tree and reports the single terminal result. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_terminate(
    uint64_t process, uint32_t owner_closed, kweb_processes_termination *termination);

/* Releases one terminated process record and its descriptors. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_release(uint64_t process);

/* Terminates every live tree, joins every worker, and releases the service. */
KWEB_PROCESSES_EXPORT kweb_processes_status KWEB_PROCESSES_CALL kweb_processes_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
