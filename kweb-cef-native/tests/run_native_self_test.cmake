cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")
kweb_require_test_inputs()
kweb_run_host(TIMEOUT 75 ARGUMENTS --kweb-self-test)

if(NOT KWEB_HOST_RESULT STREQUAL "0")
  message(FATAL_ERROR
    "Native self-test exited with '${KWEB_HOST_RESULT}'.\n"
    "stdout:\n${KWEB_HOST_STDOUT}\n"
    "stderr:\n${KWEB_HOST_STDERR}")
endif()
kweb_read_event_lines(event_lines "${KWEB_EVENT_LOG_PATH}")

set(previous_sequence 0)
set(saw_gpu_process FALSE)
set(saw_renderer_process FALSE)
set(saw_utility_process FALSE)
set(required_events
  browser_process_start
  cef_context_initialized
  native_window_created
  browser_created
  native_child_attached
  load_end
  native_resize_sent
  native_focus_sent
  native_mouse_input_sent
  native_wheel_input_sent
  native_keyboard_input_sent
  native_input_self_test_passed
  native_input_settled
  native_self_test_passed
  profile_cookie_flush_started
  profile_cookie_flush_completed
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
else()
  list(APPEND required_events macos_process_requirement_metrics_disabled)
endif()

foreach(event_line IN LISTS event_lines)
  kweb_read_json_member(sequence "${event_line}" sequence)
  kweb_read_json_member(event_name "${event_line}" event)

  if(DEFINED event_sequence_native_input_self_test_passed AND
     event_name MATCHES "^native_(focus_sent|mouse_input_sent|wheel_input_sent|keyboard_input_sent)$")
    message(FATAL_ERROR
      "Native input was retried after the page passed: ${event_line}")
  endif()

  math(EXPR expected_sequence "${previous_sequence} + 1")
  if(NOT sequence EQUAL expected_sequence)
    message(FATAL_ERROR
      "Event sequence is not contiguous: expected ${expected_sequence}, got ${sequence}.")
  endif()
  set(previous_sequence "${sequence}")

  if(event_name STREQUAL "error")
    kweb_read_json_member(error_code "${event_line}" code)
    message(FATAL_ERROR "Native host recorded error '${error_code}'.\n${event_line}")
  endif()

  if(event_name STREQUAL "child_process_launch")
    kweb_read_json_member(process_type "${event_line}" type)
    if(process_type STREQUAL "gpu-process")
      set(saw_gpu_process TRUE)
    elseif(process_type STREQUAL "renderer")
      set(saw_renderer_process TRUE)
    elseif(process_type STREQUAL "utility")
      set(saw_utility_process TRUE)
    endif()
  elseif(event_name STREQUAL "native_window_created")
    kweb_read_json_member(device_scale_factor "${event_line}" device_scale_factor)
    if(device_scale_factor LESS_EQUAL 0)
      message(FATAL_ERROR "Native device scale factor is invalid: ${event_line}")
    endif()
  elseif(event_name STREQUAL "root_screen_rect_reported")
    kweb_read_json_member(root_width "${event_line}" width)
    kweb_read_json_member(root_height "${event_line}" height)
    if(root_width LESS_EQUAL 0 OR root_height LESS_EQUAL 0)
      message(FATAL_ERROR "Native root screen rectangle is invalid: ${event_line}")
    endif()
  elseif(event_name STREQUAL "browser_created")
    kweb_read_json_member(runtime_style "${event_line}" runtime_style)
    kweb_read_json_member(windowless "${event_line}" windowless)
    kweb_read_json_member(native_window "${event_line}" native_window)
    if(NOT runtime_style STREQUAL "alloy" OR
       NOT windowless STREQUAL "false" OR
       NOT native_window STREQUAL "present")
      message(FATAL_ERROR "Browser did not use an Alloy native child: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_child_attached")
    kweb_read_json_member(superview "${event_line}" superview)
    if(NOT superview STREQUAL "content-view")
      message(FATAL_ERROR "Native child has an unexpected parent: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_resize_sent")
    kweb_read_json_member(width "${event_line}" width)
    kweb_read_json_member(height "${event_line}" height)
    if(NOT width STREQUAL "960" OR NOT height STREQUAL "720")
      message(FATAL_ERROR "Native resize did not use the expected dimensions: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_dpi_changed")
    kweb_read_json_member(device_scale_factor "${event_line}" device_scale_factor)
    if(device_scale_factor LESS_EQUAL 0)
      message(FATAL_ERROR "Native DPI change reported an invalid scale: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_focus_sent" AND PLATFORM STREQUAL "Darwin")
    kweb_read_json_member(first_responder "${event_line}" first_responder)
    if(NOT first_responder STREQUAL "true")
      message(FATAL_ERROR "macOS native focus was not accepted: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_mouse_input_sent")
    kweb_read_json_member(mouse_transport "${event_line}" transport)
    if(NOT mouse_transport STREQUAL "cef-windowed-host")
      message(FATAL_ERROR "Mouse input did not use the windowed CEF host: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_wheel_input_sent")
    kweb_read_json_member(wheel_transport "${event_line}" transport)
    if(PLATFORM STREQUAL "Darwin")
      if(NOT wheel_transport STREQUAL "cocoa-child-view")
        message(FATAL_ERROR
          "macOS wheel input did not use the Cocoa child view: ${event_line}")
      endif()
    elseif(NOT wheel_transport STREQUAL "cef-windowed-host")
      message(FATAL_ERROR
        "Wheel input did not use the windowed CEF host: ${event_line}")
    endif()
  elseif(event_name STREQUAL "native_input_self_test_passed")
    kweb_read_json_member(self_test_result "${event_line}" result)
    if(NOT self_test_result MATCHES
       "^KWEB_SELF_TEST_PASS\\|([0-9]+)\\|([0-9]+)\\|([^|]+)\\|.+$")
      message(FATAL_ERROR "Page self-test result is invalid: ${self_test_result}")
    endif()
    set(viewport_width "${CMAKE_MATCH_1}")
    set(viewport_height "${CMAKE_MATCH_2}")
    set(device_pixel_ratio "${CMAKE_MATCH_3}")
    if(viewport_width LESS 900 OR viewport_height LESS 650)
      message(FATAL_ERROR
        "Page viewport is below the native resize contract: ${self_test_result}")
    endif()
    if(device_pixel_ratio LESS_EQUAL 0)
      message(FATAL_ERROR
        "Page device pixel ratio is invalid: ${self_test_result}")
    endif()
    string(TOLOWER "${self_test_result}" normalized_result)
    if(normalized_result MATCHES "swiftshader|llvmpipe|software|missing-webgl")
      message(FATAL_ERROR "Page self-test used software rendering: ${self_test_result}")
    endif()
  elseif(event_name STREQUAL "native_window_destroyed" AND PLATFORM STREQUAL "Darwin")
    kweb_read_json_member(child_view_released "${event_line}" child_view_released)
    if(NOT child_view_released STREQUAL "true")
      message(FATAL_ERROR "macOS CEF child view was not released: ${event_line}")
    endif()
  elseif(event_name STREQUAL "cef_shutdown")
    kweb_read_json_member(shutdown_exit_code "${event_line}" exit_code)
    if(NOT shutdown_exit_code STREQUAL "0")
      message(FATAL_ERROR "CEF shutdown reported a failure: ${event_line}")
    endif()
  endif()

  if(event_name IN_LIST required_events AND
     NOT DEFINED event_sequence_${event_name})
    set(event_sequence_${event_name} "${sequence}")
  endif()
endforeach()

if(NOT saw_gpu_process)
  message(FATAL_ERROR "Native self-test did not launch a GPU process.")
endif()
if(NOT saw_renderer_process)
  message(FATAL_ERROR "Native self-test did not launch a renderer process.")
endif()
if(NOT saw_utility_process)
  message(FATAL_ERROR "Native self-test did not launch a utility process.")
endif()

foreach(required_event IN LISTS required_events)
  if(NOT DEFINED event_sequence_${required_event})
    message(FATAL_ERROR "Native self-test did not record '${required_event}'.")
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
if(PLATFORM STREQUAL "Darwin")
  assert_event_before(
    macos_process_requirement_metrics_disabled
    cef_context_initialized
  )
endif()
assert_event_before(cef_context_initialized native_window_created)
assert_event_before(native_window_created browser_created)
assert_event_before(browser_created native_child_attached)
if(NOT PLATFORM STREQUAL "Darwin")
  assert_event_before(native_child_attached root_screen_rect_reported)
  assert_event_before(root_screen_rect_reported native_input_self_test_passed)
endif()
assert_event_before(native_child_attached native_input_self_test_passed)
assert_event_before(native_resize_sent native_input_self_test_passed)
assert_event_before(native_focus_sent native_input_self_test_passed)
assert_event_before(native_mouse_input_sent native_input_self_test_passed)
assert_event_before(native_wheel_input_sent native_input_self_test_passed)
assert_event_before(native_keyboard_input_sent native_input_self_test_passed)
assert_event_before(native_input_self_test_passed native_input_settled)
assert_event_before(native_input_settled native_self_test_passed)
assert_event_before(native_self_test_passed profile_cookie_flush_started)
assert_event_before(profile_cookie_flush_started profile_cookie_flush_completed)
assert_event_before(profile_cookie_flush_completed browser_close_accepted)
assert_event_before(browser_close_accepted native_window_close_accepted)
assert_event_before(native_window_close_accepted native_window_close_dispatched)
assert_event_before(native_window_close_dispatched browser_destroyed)
assert_event_before(native_window_close_dispatched native_window_destroyed)
assert_event_before(browser_destroyed cef_shutdown)
assert_event_before(native_window_destroyed cef_shutdown)
assert_event_before(cef_quit_requested cef_quit_returned)
assert_event_before(cef_quit_returned cef_shutdown_started)
assert_event_before(cef_shutdown_started cef_shutdown)

message(STATUS
  "Native self-test passed on ${PLATFORM} with ${previous_sequence} ordered events.")
