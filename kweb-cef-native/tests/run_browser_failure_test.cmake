cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")

foreach(required_variable
        FAILURE_URL
        EXPECTED_ERROR_CODE
        EXPECTED_PROCESS_TYPE
        EXPECTED_PROCESS_LAUNCHES
        FAILURE_NAME)
  if(NOT DEFINED ${required_variable} OR "${${required_variable}}" STREQUAL "")
    message(FATAL_ERROR "${required_variable} is required for the browser failure test.")
  endif()
endforeach()

kweb_require_test_inputs()
kweb_run_host(TIMEOUT 45 ARGUMENTS "--kweb-url=${FAILURE_URL}")

if(NOT KWEB_HOST_RESULT STREQUAL "71")
  message(FATAL_ERROR
    "${FAILURE_NAME} failure test exited with '${KWEB_HOST_RESULT}', expected '71'.\n"
    "stdout:\n${KWEB_HOST_STDOUT}\n"
    "stderr:\n${KWEB_HOST_STDERR}")
endif()

kweb_read_event_lines(event_lines "${KWEB_EVENT_LOG_PATH}")
set(previous_sequence 0)
set(primary_error_count 0)
set(shutdown_timeout_error_count 0)
set(expected_process_launches 0)
set(saw_expected_process FALSE)
set(required_events
  browser_process_start
  cef_context_initialized
  native_window_created
  browser_created
  native_child_attached
  browser_close_accepted
  native_window_close_accepted
  native_window_close_dispatched
  browser_destroyed
  native_window_destroyed
  cef_quit_requested
  cef_quit_returned
  cef_shutdown_started
)

foreach(event_line IN LISTS event_lines)
  kweb_read_json_member(sequence "${event_line}" sequence)
  kweb_read_json_member(event_name "${event_line}" event)

  math(EXPR expected_sequence "${previous_sequence} + 1")
  if(NOT sequence EQUAL expected_sequence)
    message(FATAL_ERROR
      "${FAILURE_NAME} failure event sequence is not contiguous: "
      "expected ${expected_sequence}, got ${sequence}.")
  endif()
  set(previous_sequence "${sequence}")

  if(event_name STREQUAL "child_process_launch")
    kweb_read_json_member(process_type "${event_line}" type)
    if(process_type STREQUAL "${EXPECTED_PROCESS_TYPE}")
      set(saw_expected_process TRUE)
      kweb_read_json_member(launch_count "${event_line}" launch_count)
      if(launch_count GREATER expected_process_launches)
        set(expected_process_launches "${launch_count}")
      endif()
    endif()
  elseif(event_name STREQUAL "browser_created")
    kweb_read_json_member(runtime_style "${event_line}" runtime_style)
    kweb_read_json_member(windowless "${event_line}" windowless)
    kweb_read_json_member(native_window "${event_line}" native_window)
    if(NOT runtime_style STREQUAL "alloy" OR
       NOT windowless STREQUAL "false" OR
       NOT native_window STREQUAL "present")
      message(FATAL_ERROR
        "${FAILURE_NAME} failure browser was not an Alloy native child: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_child_attached")
    kweb_read_json_member(superview "${event_line}" superview)
    if(NOT superview STREQUAL "content-view")
      message(FATAL_ERROR
        "${FAILURE_NAME} failure browser had an unexpected parent: ${event_line}")
    endif()
  elseif(event_name STREQUAL "error")
    kweb_read_json_member(error_code "${event_line}" code)
    if(error_code STREQUAL "${EXPECTED_ERROR_CODE}")
      math(EXPR primary_error_count "${primary_error_count} + 1")
      set(event_sequence_failure "${sequence}")
    elseif(error_code STREQUAL "native.cef.shutdown-timeout")
      math(EXPR shutdown_timeout_error_count
        "${shutdown_timeout_error_count} + 1")
      set(event_sequence_shutdown_timeout "${sequence}")
    else()
      message(FATAL_ERROR
        "Unexpected ${FAILURE_NAME} failure error '${error_code}'.\n${event_line}")
    endif()
  elseif(event_name STREQUAL "cef_shutdown")
    kweb_read_json_member(shutdown_exit_code "${event_line}" exit_code)
    if(NOT shutdown_exit_code STREQUAL "71")
      message(FATAL_ERROR
        "${FAILURE_NAME} failure shutdown used the wrong exit code: ${event_line}")
    endif()
    set(event_sequence_cef_shutdown "${sequence}")
  elseif(event_name STREQUAL "cef_shutdown_forced_exit")
    kweb_read_json_member(shutdown_exit_code "${event_line}" exit_code)
    if(NOT shutdown_exit_code STREQUAL "71")
      message(FATAL_ERROR
        "${FAILURE_NAME} forced shutdown used the wrong exit code: ${event_line}")
    endif()
    set(event_sequence_cef_shutdown_forced_exit "${sequence}")
  endif()

  if(event_name IN_LIST required_events AND
     NOT DEFINED event_sequence_${event_name})
    set(event_sequence_${event_name} "${sequence}")
  endif()
endforeach()

if(NOT saw_expected_process)
  message(FATAL_ERROR
    "${FAILURE_NAME} failure test did not launch a '${EXPECTED_PROCESS_TYPE}' process.")
endif()
if(NOT expected_process_launches STREQUAL "${EXPECTED_PROCESS_LAUNCHES}")
  message(FATAL_ERROR
    "${FAILURE_NAME} failure test observed ${expected_process_launches} "
    "'${EXPECTED_PROCESS_TYPE}' launches, expected ${EXPECTED_PROCESS_LAUNCHES}.")
endif()
if(NOT primary_error_count EQUAL 1)
  message(FATAL_ERROR
    "${FAILURE_NAME} failure test recorded ${primary_error_count} primary errors; expected one.")
endif()
if(NOT DEFINED event_sequence_failure)
  message(FATAL_ERROR "${FAILURE_NAME} failure test did not record the expected error.")
endif()
foreach(required_event IN LISTS required_events)
  if(NOT DEFINED event_sequence_${required_event})
    message(FATAL_ERROR
      "${FAILURE_NAME} failure test did not record '${required_event}'.")
  endif()
endforeach()

set(shutdown_terminal_count 0)
if(DEFINED event_sequence_cef_shutdown)
  math(EXPR shutdown_terminal_count "${shutdown_terminal_count} + 1")
endif()
if(DEFINED event_sequence_cef_shutdown_forced_exit)
  math(EXPR shutdown_terminal_count "${shutdown_terminal_count} + 1")
endif()
if(NOT shutdown_terminal_count EQUAL 1)
  message(FATAL_ERROR
    "${FAILURE_NAME} failure test recorded ${shutdown_terminal_count} shutdown terminals; expected one.")
endif()

if(DEFINED event_sequence_cef_shutdown)
  if(NOT shutdown_timeout_error_count EQUAL 0)
    message(FATAL_ERROR
      "${FAILURE_NAME} graceful shutdown also recorded a timeout error.")
  endif()
  set(shutdown_mode "graceful")
else()
  if(NOT shutdown_timeout_error_count EQUAL 1)
    message(FATAL_ERROR
      "${FAILURE_NAME} forced shutdown recorded ${shutdown_timeout_error_count} "
      "timeout errors; expected one.")
  endif()
  set(shutdown_mode "forced-after-timeout")
endif()

function(assert_event_before first_event second_event)
  set(first_sequence "${event_sequence_${first_event}}")
  set(second_sequence "${event_sequence_${second_event}}")
  if(first_sequence GREATER_EQUAL second_sequence)
    message(FATAL_ERROR
      "Native event '${first_event}' at ${first_sequence} must precede "
      "'${second_event}' at ${second_sequence}.")
  endif()
endfunction()

assert_event_before(browser_process_start cef_context_initialized)
assert_event_before(cef_context_initialized native_window_created)
assert_event_before(native_window_created browser_created)
assert_event_before(browser_created native_child_attached)
assert_event_before(native_child_attached failure)
assert_event_before(failure browser_close_accepted)
assert_event_before(browser_close_accepted native_window_close_accepted)
assert_event_before(native_window_close_accepted native_window_close_dispatched)
assert_event_before(native_window_close_dispatched browser_destroyed)
assert_event_before(native_window_close_dispatched native_window_destroyed)
assert_event_before(browser_destroyed cef_quit_requested)
assert_event_before(cef_quit_requested cef_quit_returned)
assert_event_before(cef_quit_returned cef_shutdown_started)
assert_event_before(native_window_destroyed cef_shutdown_started)
if(shutdown_mode STREQUAL "graceful")
  assert_event_before(cef_shutdown_started cef_shutdown)
else()
  assert_event_before(cef_shutdown_started shutdown_timeout)
  assert_event_before(shutdown_timeout cef_shutdown_forced_exit)
endif()

message(STATUS
  "${FAILURE_NAME} failure test passed on ${PLATFORM} with "
  "${previous_sequence} ordered events and ${shutdown_mode} shutdown.")
