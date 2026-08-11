cmake_minimum_required(VERSION 3.21)

function(kweb_require_test_inputs)
  foreach(required_variable HOST_EXECUTABLE TEST_ROOT PLATFORM)
    if(NOT DEFINED ${required_variable} OR "${${required_variable}}" STREQUAL "")
      message(FATAL_ERROR "${required_variable} is required for the native test.")
    endif()
  endforeach()
  if(NOT EXISTS "${HOST_EXECUTABLE}")
    message(FATAL_ERROR "Native host executable does not exist: ${HOST_EXECUTABLE}")
  endif()
endfunction()

function(kweb_run_host)
  cmake_parse_arguments(PARSE_ARGV 0 KWEB "" "TIMEOUT" "ARGUMENTS")
  if(NOT DEFINED KWEB_TIMEOUT OR KWEB_TIMEOUT STREQUAL "")
    set(KWEB_TIMEOUT 75)
  endif()

  file(REMOVE_RECURSE "${TEST_ROOT}")
  set(profile_path "${TEST_ROOT}/profile")
  set(event_log_path "${TEST_ROOT}/events.jsonl")
  set(xauthority_path "${TEST_ROOT}/xauthority")
  file(MAKE_DIRECTORY "${profile_path}")

  set(host_command)
  if(DEFINED HOST_LAUNCHER AND NOT "${HOST_LAUNCHER}" STREQUAL "")
    list(APPEND host_command "${HOST_LAUNCHER}" --auto-servernum
         "--auth-file=${xauthority_path}"
         "--server-args=-screen 0 1280x1024x24")
  endif()
  list(APPEND host_command "${HOST_EXECUTABLE}"
       "--kweb-root-cache-path=${profile_path}"
       "--kweb-event-log-path=${event_log_path}"
       ${KWEB_ARGUMENTS})

  execute_process(
    COMMAND ${host_command}
    RESULT_VARIABLE host_result
    OUTPUT_VARIABLE host_stdout
    ERROR_VARIABLE host_stderr
    TIMEOUT ${KWEB_TIMEOUT}
  )
  file(REMOVE "${xauthority_path}")

  set(KWEB_EVENT_LOG_PATH "${event_log_path}" PARENT_SCOPE)
  set(KWEB_HOST_RESULT "${host_result}" PARENT_SCOPE)
  set(KWEB_HOST_STDOUT "${host_stdout}" PARENT_SCOPE)
  set(KWEB_HOST_STDERR "${host_stderr}" PARENT_SCOPE)
endfunction()

function(kweb_read_json_member output json member)
  string(JSON member_value ERROR_VARIABLE json_error GET "${json}" "${member}")
  if(NOT json_error STREQUAL "NOTFOUND")
    message(FATAL_ERROR
      "Invalid event JSON or missing '${member}': ${json_error}\nLine: ${json}")
  endif()
  set(${output} "${member_value}" PARENT_SCOPE)
endfunction()

function(kweb_read_event_lines output event_log_path)
  if(NOT EXISTS "${event_log_path}")
    message(FATAL_ERROR "Native host did not create the event log: ${event_log_path}")
  endif()
  file(STRINGS "${event_log_path}" event_lines ENCODING UTF-8)
  if(NOT event_lines)
    message(FATAL_ERROR "Native event log is empty: ${event_log_path}")
  endif()
  set(${output} "${event_lines}" PARENT_SCOPE)
endfunction()
