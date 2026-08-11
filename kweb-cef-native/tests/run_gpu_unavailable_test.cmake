cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")
kweb_require_test_inputs()
kweb_run_host(TIMEOUT 75 ARGUMENTS --kweb-self-test)

if(NOT KWEB_HOST_RESULT STREQUAL "71")
  message(FATAL_ERROR
    "No-GPU contract test exited with '${KWEB_HOST_RESULT}', expected '71'.\n"
    "stdout:\n${KWEB_HOST_STDOUT}\n"
    "stderr:\n${KWEB_HOST_STDERR}")
endif()

kweb_read_event_lines(event_lines "${KWEB_EVENT_LOG_PATH}")
set(previous_sequence 0)
set(error_count 0)
set(saw_gpu_process FALSE)
set(saw_renderer_process FALSE)
set(saw_utility_process FALSE)
set(required_events
  browser_process_start
  cef_context_initialized
  native_window_created
  browser_created
  native_child_attached
  gpu_unavailable
  browser_close_accepted
  native_window_close_accepted
  native_window_close_dispatched
  browser_destroyed
  native_window_destroyed
  cef_quit_requested
  cef_quit_returned
  cef_shutdown_started
  cef_shutdown
)
if(NOT PLATFORM STREQUAL "Darwin")
  list(APPEND required_events root_screen_rect_reported)
endif()

foreach(event_line IN LISTS event_lines)
  kweb_read_json_member(sequence "${event_line}" sequence)
  kweb_read_json_member(event_name "${event_line}" event)

  math(EXPR expected_sequence "${previous_sequence} + 1")
  if(NOT sequence EQUAL expected_sequence)
    message(FATAL_ERROR
      "No-GPU event sequence is not contiguous: expected ${expected_sequence}, got ${sequence}.")
  endif()
  set(previous_sequence "${sequence}")

  if(event_name STREQUAL "child_process_launch")
    kweb_read_json_member(process_type "${event_line}" type)
    if(process_type STREQUAL "gpu-process")
      set(saw_gpu_process TRUE)
    elseif(process_type STREQUAL "renderer")
      set(saw_renderer_process TRUE)
    elseif(process_type STREQUAL "utility")
      set(saw_utility_process TRUE)
    endif()
  elseif(event_name STREQUAL "browser_created")
    kweb_read_json_member(runtime_style "${event_line}" runtime_style)
    kweb_read_json_member(windowless "${event_line}" windowless)
    kweb_read_json_member(native_window "${event_line}" native_window)
    if(NOT runtime_style STREQUAL "alloy" OR
       NOT windowless STREQUAL "false" OR
       NOT native_window STREQUAL "present")
      message(FATAL_ERROR "No-GPU browser was not an Alloy native child: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_child_attached")
    kweb_read_json_member(superview "${event_line}" superview)
    if(NOT superview STREQUAL "content-view")
      message(FATAL_ERROR "No-GPU browser had an unexpected parent: ${event_line}")
    endif()
  elseif(event_name STREQUAL "root_screen_rect_reported")
    kweb_read_json_member(root_width "${event_line}" width)
    kweb_read_json_member(root_height "${event_line}" height)
    if(root_width LESS_EQUAL 0 OR root_height LESS_EQUAL 0)
      message(FATAL_ERROR "No-GPU root screen rectangle is invalid: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_input_self_test_passed" OR
         event_name STREQUAL "native_self_test_passed")
    message(FATAL_ERROR
      "No-GPU contract reported a successful hardware self-test: ${event_line}")
  elseif(event_name STREQUAL "error")
    math(EXPR error_count "${error_count} + 1")
    kweb_read_json_member(error_code "${event_line}" code)
    if(NOT error_code STREQUAL "native.gpu.hardware-acceleration-unavailable")
      message(FATAL_ERROR "Unexpected no-GPU error '${error_code}'.\n${event_line}")
    endif()
    kweb_read_json_member(renderer "${event_line}" renderer)
    string(TOLOWER "${renderer}" normalized_renderer)
    if(NOT normalized_renderer MATCHES
       "missing-webgl|swiftshader|llvmpipe|lavapipe|software|basic render driver|warp")
      message(FATAL_ERROR
        "No-GPU error did not identify a software or missing renderer: ${event_line}")
    endif()
    set(event_sequence_gpu_unavailable "${sequence}")
  elseif(event_name STREQUAL "cef_shutdown")
    kweb_read_json_member(shutdown_exit_code "${event_line}" exit_code)
    if(NOT shutdown_exit_code STREQUAL "71")
      message(FATAL_ERROR "No-GPU shutdown used the wrong exit code: ${event_line}")
    endif()
  endif()

  if(event_name IN_LIST required_events AND
     NOT DEFINED event_sequence_${event_name})
    set(event_sequence_${event_name} "${sequence}")
  endif()
endforeach()

foreach(process_name gpu renderer utility)
  if(NOT saw_${process_name}_process)
    message(FATAL_ERROR "No-GPU test did not launch a ${process_name} process.")
  endif()
endforeach()
if(NOT error_count EQUAL 1)
  message(FATAL_ERROR "No-GPU test recorded ${error_count} errors; expected one.")
endif()
foreach(required_event IN LISTS required_events)
  if(NOT DEFINED event_sequence_${required_event})
    message(FATAL_ERROR "No-GPU test did not record '${required_event}'.")
  endif()
endforeach()

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
if(NOT PLATFORM STREQUAL "Darwin")
  assert_event_before(native_child_attached root_screen_rect_reported)
  assert_event_before(root_screen_rect_reported gpu_unavailable)
endif()
assert_event_before(native_child_attached gpu_unavailable)
assert_event_before(gpu_unavailable browser_close_accepted)
assert_event_before(browser_close_accepted native_window_close_accepted)
assert_event_before(native_window_close_accepted native_window_close_dispatched)
assert_event_before(native_window_close_dispatched browser_destroyed)
assert_event_before(native_window_close_dispatched native_window_destroyed)
assert_event_before(browser_destroyed cef_quit_requested)
assert_event_before(cef_quit_requested cef_quit_returned)
assert_event_before(cef_quit_returned cef_shutdown_started)
assert_event_before(native_window_destroyed cef_shutdown_started)
assert_event_before(cef_shutdown_started cef_shutdown)

message(STATUS
  "No-GPU contract passed on ${PLATFORM} with ${previous_sequence} ordered events.")
