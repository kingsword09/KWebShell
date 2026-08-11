cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")
kweb_require_test_inputs()

set(profile_test_value profile-persistence-token-01)
set(profile_test_url https://kwebshell.test/profile-self-test)

function(require_host_success mode result stdout stderr)
  if(NOT result STREQUAL "0")
    message(FATAL_ERROR
      "Profile ${mode} process exited with '${result}'.\n"
      "stdout:\n${stdout}\n"
      "stderr:\n${stderr}")
  endif()
endfunction()

function(require_profile_disk_state profile_name)
  set(profile_path "${TEST_ROOT}/root/${profile_name}")
  if(PLATFORM STREQUAL "Windows")
    set(cookie_store_path "${profile_path}/Network/Cookies")
  else()
    set(cookie_store_path "${profile_path}/Cookies")
  endif()
  set(required_files
    "${profile_path}/Preferences"
    "${cookie_store_path}"
    "${profile_path}/Local Storage/leveldb/CURRENT"
  )
  foreach(required_file IN LISTS required_files)
    if(NOT EXISTS "${required_file}" OR IS_DIRECTORY "${required_file}")
      message(FATAL_ERROR
        "Profile '${profile_name}' did not persist Chromium state at "
        "'${required_file}'.")
    endif()
    file(SIZE "${required_file}" required_file_size)
    if(required_file_size LESS_EQUAL 0)
      message(FATAL_ERROR
        "Profile '${profile_name}' persisted an empty Chromium state file at "
        "'${required_file}'.")
    endif()
  endforeach()
  message(STATUS
    "Profile ${profile_name} persisted Preferences, Cookies, and Local Storage after shutdown.")
endfunction()

function(validate_profile_run event_log expected_mode)
  kweb_read_event_lines(event_lines "${event_log}")
  set(previous_sequence 0)
  set(renderer_process_seen FALSE)
  set(required_events
    browser_process_start
    cef_context_initialized
    profile_open_requested
    profile_opened
    native_window_created
    browser_created
    native_child_attached
    profile_test_request_intercepted
    navigation_started
    load_end
    profile_self_test_passed
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

  foreach(event_line IN LISTS event_lines)
    kweb_read_json_member(sequence "${event_line}" sequence)
    kweb_read_json_member(event_name "${event_line}" event)
    math(EXPR expected_sequence "${previous_sequence} + 1")
    if(NOT sequence EQUAL expected_sequence)
      message(FATAL_ERROR
        "Profile ${expected_mode} sequence is not contiguous: expected "
        "${expected_sequence}, got ${sequence}.")
    endif()
    set(previous_sequence "${sequence}")

    if(event_name STREQUAL "error")
      kweb_read_json_member(error_code "${event_line}" code)
      message(FATAL_ERROR
        "Profile ${expected_mode} recorded '${error_code}'.\n${event_line}")
    elseif(event_name STREQUAL "child_process_launch")
      kweb_read_json_member(process_type "${event_line}" type)
      if(process_type STREQUAL "renderer")
        set(renderer_process_seen TRUE)
      endif()
    elseif(event_name STREQUAL "profile_open_requested")
      kweb_read_json_member(persistent "${event_line}" persistent)
      kweb_read_json_member(mode "${event_line}" mode)
      if(NOT persistent STREQUAL "true" OR
         NOT mode STREQUAL "${expected_mode}")
        message(FATAL_ERROR
          "Profile open request did not preserve mode/persistence: ${event_line}")
      endif()
    elseif(event_name STREQUAL "profile_opened")
      kweb_read_json_member(persistent "${event_line}" persistent)
      kweb_read_json_member(global_context "${event_line}" global)
      kweb_read_json_member(cache_path_match "${event_line}" cache_path_match)
      if(NOT persistent STREQUAL "true" OR
         NOT global_context STREQUAL "false" OR
         NOT cache_path_match STREQUAL "true")
        message(FATAL_ERROR
          "Profile context was not persistent and isolated: ${event_line}")
      endif()
    elseif(event_name STREQUAL "profile_test_request_intercepted")
      kweb_read_json_member(intercepted_url "${event_line}" url)
      if(NOT intercepted_url STREQUAL "${profile_test_url}")
        message(FATAL_ERROR
          "Profile test intercepted an unexpected URL: ${event_line}")
      endif()
    elseif(event_name STREQUAL "navigation_started")
      kweb_read_json_member(navigation_url "${event_line}" url)
      if(NOT navigation_url STREQUAL "${profile_test_url}")
        message(FATAL_ERROR
          "Profile navigation used an unexpected URL: ${event_line}")
      endif()
    elseif(event_name STREQUAL "load_end")
      kweb_read_json_member(http_status "${event_line}" http_status)
      kweb_read_json_member(loaded_url "${event_line}" url)
      if(NOT http_status STREQUAL "200" OR
         NOT loaded_url STREQUAL "${profile_test_url}")
        message(FATAL_ERROR
          "Profile page did not load from the controlled origin: ${event_line}")
      endif()
    elseif(event_name STREQUAL "profile_self_test_passed")
      kweb_read_json_member(mode "${event_line}" mode)
      kweb_read_json_member(value "${event_line}" value)
      kweb_read_json_member(result "${event_line}" result)
      if(expected_mode STREQUAL "expect-absent")
        set(expected_result
          "KWEB_PROFILE_SELF_TEST_PASS|expect-absent|missing|missing")
      else()
        set(expected_result
          "KWEB_PROFILE_SELF_TEST_PASS|${expected_mode}|${profile_test_value}|${profile_test_value}")
      endif()
      if(NOT mode STREQUAL "${expected_mode}" OR
         NOT value STREQUAL "${profile_test_value}" OR
         NOT result STREQUAL "${expected_result}")
        message(FATAL_ERROR
          "Profile page reported an unexpected result: ${event_line}")
      endif()
    elseif(event_name MATCHES "^native_(resize|focus|mouse|wheel|keyboard)_input")
      message(FATAL_ERROR
        "Profile test unexpectedly invoked native input self-test: ${event_line}")
    elseif(event_name STREQUAL "cef_shutdown")
      kweb_read_json_member(exit_code "${event_line}" exit_code)
      if(NOT exit_code STREQUAL "0")
        message(FATAL_ERROR
          "Profile ${expected_mode} shutdown was not successful: ${event_line}")
      endif()
    endif()

    if(event_name IN_LIST required_events AND
       NOT DEFINED event_sequence_${event_name})
      set(event_sequence_${event_name} "${sequence}")
    endif()
  endforeach()

  if(NOT renderer_process_seen)
    message(FATAL_ERROR
      "Profile ${expected_mode} did not launch a real renderer process.")
  endif()
  foreach(required_event IN LISTS required_events)
    if(NOT DEFINED event_sequence_${required_event})
      message(FATAL_ERROR
        "Profile ${expected_mode} did not record '${required_event}'.")
    endif()
  endforeach()

  macro(assert_event_before first_event second_event)
    if(event_sequence_${first_event} GREATER_EQUAL
       event_sequence_${second_event})
      message(FATAL_ERROR
        "Profile event '${first_event}' must precede '${second_event}'.")
    endif()
  endmacro()
  assert_event_before(cef_context_initialized profile_open_requested)
  assert_event_before(profile_open_requested profile_opened)
  assert_event_before(profile_opened native_window_created)
  assert_event_before(native_window_created browser_created)
  assert_event_before(browser_created native_child_attached)
  assert_event_before(profile_test_request_intercepted load_end)
  assert_event_before(profile_self_test_passed profile_cookie_flush_started)
  assert_event_before(load_end profile_cookie_flush_started)
  assert_event_before(profile_cookie_flush_started profile_cookie_flush_completed)
  assert_event_before(profile_cookie_flush_completed browser_close_accepted)
  assert_event_before(browser_destroyed cef_quit_requested)
  assert_event_before(cef_quit_returned cef_shutdown_started)
  assert_event_before(cef_shutdown_started cef_shutdown)

  message(STATUS
    "Profile ${expected_mode} passed with ${previous_sequence} ordered events.")
endfunction()

kweb_run_host(
  TIMEOUT 75
  PROFILE_NAME alpha
  EVENT_LOG_NAME profile-write.jsonl
  ARGUMENTS
    --kweb-profile-self-test=write
    "--kweb-profile-test-value=${profile_test_value}"
)
require_host_success(write "${KWEB_HOST_RESULT}"
  "${KWEB_HOST_STDOUT}" "${KWEB_HOST_STDERR}")
validate_profile_run("${KWEB_EVENT_LOG_PATH}" write)
require_profile_disk_state(alpha)

kweb_run_host(
  PRESERVE_ROOT
  TIMEOUT 75
  PROFILE_NAME beta
  EVENT_LOG_NAME profile-isolation.jsonl
  ARGUMENTS
    --kweb-profile-self-test=expect-absent
    "--kweb-profile-test-value=${profile_test_value}"
)
require_host_success(expect-absent "${KWEB_HOST_RESULT}"
  "${KWEB_HOST_STDOUT}" "${KWEB_HOST_STDERR}")
validate_profile_run("${KWEB_EVENT_LOG_PATH}" expect-absent)
require_profile_disk_state(beta)

kweb_run_host(
  PRESERVE_ROOT
  TIMEOUT 75
  PROFILE_NAME alpha
  EVENT_LOG_NAME profile-read.jsonl
  ARGUMENTS
    --kweb-profile-self-test=read
    "--kweb-profile-test-value=${profile_test_value}"
)
require_host_success(read "${KWEB_HOST_RESULT}"
  "${KWEB_HOST_STDOUT}" "${KWEB_HOST_STDERR}")
validate_profile_run("${KWEB_EVENT_LOG_PATH}" read)
require_profile_disk_state(alpha)

message(STATUS
  "Profile persistence and isolation passed on ${PLATFORM} across three real CEF processes.")
