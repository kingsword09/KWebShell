cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")
kweb_require_test_inputs()

if(NOT DEFINED EXTENSION_PATH OR EXTENSION_PATH STREQUAL "" OR
   NOT IS_DIRECTORY "${EXTENSION_PATH}")
  message(FATAL_ERROR "EXTENSION_PATH must identify the MV3 fixture directory.")
endif()

set(mv3_test_url https://kwebshell.test/mv3-core-self-test)
set(mv3_extension_id dhhnhmffjehhodphofnkingncijnaona)
set(mv3_options_page_url
  chrome-extension://dhhnhmffjehhodphofnkingncijnaona/options.html)

function(expected_mv3_result output mode)
  if(mode STREQUAL "restart")
    set(first_count 3)
  else()
    set(first_count 1)
  endif()
  math(EXPR second_count "${first_count} + 1")
  set(${output}
    "KWEB_MV3_CORE_PASS|${mode}|first=${first_count}|second=${second_count}|suspended=true|isolated=true|id=${mv3_extension_id}"
    PARENT_SCOPE)
endfunction()

function(expected_mv3_options_result output)
  set(${output}
    "KWEB_MV3_OPTIONS_PASS|id=${mv3_extension_id}|manifest=KWebShell%20MV3%20core%20conformance|messageCount=2|path=/options.html"
    PARENT_SCOPE)
endfunction()

function(require_host_success mode result stdout stderr)
  if(NOT result STREQUAL "0")
    message(FATAL_ERROR
      "MV3 ${mode} process exited with '${result}'.\n"
      "stdout:\n${stdout}\n"
      "stderr:\n${stderr}")
  endif()
endfunction()

function(require_invalid_fixture_rejected)
  set(missing_fixture_path "${TEST_ROOT}/missing-extension")
  kweb_run_host(
    TIMEOUT 10
    PROFILE_NAME invalid-fixture
    EVENT_LOG_NAME mv3-invalid-fixture.jsonl
    ARGUMENTS
      --kweb-mv3-core-self-test=initial
      "--kweb-mv3-extension-path=${missing_fixture_path}"
  )
  if(NOT KWEB_HOST_RESULT STREQUAL "64")
    message(FATAL_ERROR
      "Invalid MV3 fixture exited with '${KWEB_HOST_RESULT}', expected '64'.\n"
      "stdout:\n${KWEB_HOST_STDOUT}\n"
      "stderr:\n${KWEB_HOST_STDERR}")
  endif()

  kweb_read_event_lines(event_lines "${KWEB_EVENT_LOG_PATH}")
  set(browser_process_start_seen FALSE)
  set(typed_error_seen FALSE)
  foreach(event_line IN LISTS event_lines)
    kweb_read_json_member(event_name "${event_line}" event)
    if(event_name STREQUAL "browser_process_start")
      set(browser_process_start_seen TRUE)
    elseif(event_name STREQUAL "error")
      kweb_read_json_member(error_code "${event_line}" code)
      kweb_read_json_member(error_path "${event_line}" path)
      kweb_read_json_member(error_message "${event_line}" message)
      cmake_path(CONVERT "${error_path}" TO_CMAKE_PATH_LIST
        normalized_error_path NORMALIZE)
      cmake_path(CONVERT "${missing_fixture_path}" TO_CMAKE_PATH_LIST
        normalized_missing_fixture_path NORMALIZE)
      if(NOT error_code STREQUAL "native.mv3.test-extension-path-invalid" OR
         NOT normalized_error_path STREQUAL
             "${normalized_missing_fixture_path}" OR
         NOT error_message MATCHES
             "^MV3 core fixture path cannot be canonicalized:")
        message(FATAL_ERROR
          "Invalid MV3 fixture failure was not actionable: ${event_line}")
      endif()
      set(typed_error_seen TRUE)
    else()
      message(FATAL_ERROR
        "Invalid MV3 fixture started Chromium unexpectedly: ${event_line}")
    endif()
  endforeach()
  if(NOT browser_process_start_seen OR NOT typed_error_seen)
    message(FATAL_ERROR
      "Invalid MV3 fixture did not record startup and typed failure events.")
  endif()
endfunction()

function(require_mv3_disk_state profile_name)
  set(profile_path "${TEST_ROOT}/root/${profile_name}")
  set(required_files
    "${profile_path}/Preferences"
    "${profile_path}/Extension State/CURRENT"
    "${profile_path}/Extension Scripts/CURRENT"
    "${profile_path}/Local Extension Settings/${mv3_extension_id}/CURRENT"
    "${profile_path}/Service Worker/Database/CURRENT"
  )
  foreach(required_file IN LISTS required_files)
    if(NOT EXISTS "${required_file}" OR IS_DIRECTORY "${required_file}")
      message(FATAL_ERROR
        "MV3 Profile '${profile_name}' did not persist '${required_file}'.")
    endif()
    file(SIZE "${required_file}" required_file_size)
    if(required_file_size LESS_EQUAL 0)
      message(FATAL_ERROR
        "MV3 Profile '${profile_name}' persisted an empty file at "
        "'${required_file}'.")
    endif()
  endforeach()
  message(STATUS
    "MV3 Profile ${profile_name} persisted extension and Service Worker state.")
endfunction()

function(validate_mv3_run event_log expected_mode)
  expected_mv3_result(expected_result "${expected_mode}")
  if(expected_mode STREQUAL "options")
    expected_mv3_options_result(expected_options_result)
  endif()
  kweb_read_event_lines(event_lines "${event_log}")
  set(previous_sequence 0)
  set(renderer_process_seen FALSE)
  set(core_navigation_seen FALSE)
  set(core_load_seen FALSE)
  set(options_navigation_seen FALSE)
  set(options_load_seen FALSE)
  set(required_events
    browser_process_start
    mv3_extension_load_configured
    cef_context_initialized
    profile_open_requested
    profile_opened
    native_window_created
    browser_created
    native_child_attached
    mv3_test_request_intercepted
    navigation_started
    load_end
    mv3_core_self_test_passed
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
  if(expected_mode STREQUAL "options")
    list(APPEND required_events
      mv3_options_page_navigation_requested
      mv3_options_page_loaded
      mv3_options_page_passed
    )
  endif()

  foreach(event_line IN LISTS event_lines)
    kweb_read_json_member(sequence "${event_line}" sequence)
    kweb_read_json_member(event_name "${event_line}" event)
    math(EXPR expected_sequence "${previous_sequence} + 1")
    if(NOT sequence EQUAL expected_sequence)
      message(FATAL_ERROR
        "MV3 ${expected_mode} sequence is not contiguous: expected "
        "${expected_sequence}, got ${sequence}.")
    endif()
    set(previous_sequence "${sequence}")

    if(event_name STREQUAL "error")
      kweb_read_json_member(error_code "${event_line}" code)
      message(FATAL_ERROR
        "MV3 ${expected_mode} recorded '${error_code}'.\n${event_line}")
    elseif(event_name STREQUAL "child_process_launch")
      kweb_read_json_member(process_type "${event_line}" type)
      if(process_type STREQUAL "renderer")
        set(renderer_process_seen TRUE)
      endif()
    elseif(event_name STREQUAL "mv3_extension_load_configured")
      kweb_read_json_member(mode "${event_line}" mode)
      kweb_read_json_member(extension_path "${event_line}" path)
      kweb_read_json_member(background_networking "${event_line}"
        background_networking)
      kweb_read_json_member(component_updates "${event_line}"
        component_updates)
      kweb_read_json_member(proxy "${event_line}" proxy)
      cmake_path(CONVERT "${extension_path}" TO_CMAKE_PATH_LIST
        normalized_extension_path NORMALIZE)
      cmake_path(CONVERT "${EXTENSION_PATH}" TO_CMAKE_PATH_LIST
        normalized_expected_extension_path NORMALIZE)
      if(NOT mode STREQUAL "${expected_mode}" OR
         NOT background_networking STREQUAL "disabled" OR
         NOT component_updates STREQUAL "disabled" OR
         NOT proxy STREQUAL "disabled" OR
         NOT normalized_extension_path STREQUAL
             "${normalized_expected_extension_path}")
        message(FATAL_ERROR
          "MV3 extension switch was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "profile_opened")
      kweb_read_json_member(persistent "${event_line}" persistent)
      kweb_read_json_member(global_context "${event_line}" global)
      kweb_read_json_member(cache_path_match "${event_line}" cache_path_match)
      if(NOT persistent STREQUAL "true" OR
         NOT global_context STREQUAL "false" OR
         NOT cache_path_match STREQUAL "true")
        message(FATAL_ERROR
          "MV3 Profile context was not persistent and isolated: ${event_line}")
      endif()
    elseif(event_name STREQUAL "browser_created")
      kweb_read_json_member(runtime_style "${event_line}" runtime_style)
      kweb_read_json_member(windowless "${event_line}" windowless)
      kweb_read_json_member(native_window "${event_line}" native_window)
      if(NOT runtime_style STREQUAL "alloy" OR
         NOT windowless STREQUAL "false" OR
         NOT native_window STREQUAL "present")
        message(FATAL_ERROR
          "MV3 browser did not use the Alloy native-child path: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_test_request_intercepted")
      kweb_read_json_member(intercepted_url "${event_line}" url)
      if(NOT intercepted_url STREQUAL "${mv3_test_url}")
        message(FATAL_ERROR
          "MV3 test intercepted an unexpected URL: ${event_line}")
      endif()
    elseif(event_name STREQUAL "navigation_started")
      kweb_read_json_member(navigated_url "${event_line}" url)
      if(navigated_url STREQUAL "${mv3_test_url}")
        set(core_navigation_seen TRUE)
      elseif(expected_mode STREQUAL "options" AND
             navigated_url STREQUAL "${mv3_options_page_url}")
        set(options_navigation_seen TRUE)
      else()
        message(FATAL_ERROR
          "MV3 ${expected_mode} navigated to an unexpected URL: ${event_line}")
      endif()
    elseif(event_name STREQUAL "load_end")
      kweb_read_json_member(http_status "${event_line}" http_status)
      kweb_read_json_member(loaded_url "${event_line}" url)
      if(loaded_url STREQUAL "${mv3_test_url}")
        if(NOT http_status STREQUAL "200")
          message(FATAL_ERROR
            "MV3 core test page did not report HTTP 200: ${event_line}")
        endif()
        set(core_load_seen TRUE)
      elseif(expected_mode STREQUAL "options" AND
             loaded_url STREQUAL "${mv3_options_page_url}")
        if(NOT http_status STREQUAL "200")
          message(FATAL_ERROR
            "MV3 options page did not report HTTP 200: ${event_line}")
        endif()
        set(options_load_seen TRUE)
      else()
        message(FATAL_ERROR
          "MV3 ${expected_mode} loaded an unexpected page: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_core_self_test_passed")
      kweb_read_json_member(mode "${event_line}" mode)
      kweb_read_json_member(result "${event_line}" result)
      if(NOT mode STREQUAL "${expected_mode}" OR
         NOT result STREQUAL "${expected_result}")
        message(FATAL_ERROR
          "MV3 core result was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_options_page_navigation_requested")
      kweb_read_json_member(options_url "${event_line}" url)
      if(NOT expected_mode STREQUAL "options" OR
         NOT options_url STREQUAL "${mv3_options_page_url}")
        message(FATAL_ERROR
          "MV3 options navigation request was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_options_page_loaded")
      kweb_read_json_member(options_url "${event_line}" url)
      if(NOT expected_mode STREQUAL "options" OR
         NOT options_url STREQUAL "${mv3_options_page_url}")
        message(FATAL_ERROR
          "MV3 options load was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_options_page_passed")
      kweb_read_json_member(options_result "${event_line}" result)
      if(NOT expected_mode STREQUAL "options" OR
         NOT options_result STREQUAL "${expected_options_result}")
        message(FATAL_ERROR
          "MV3 options result was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "cef_shutdown")
      kweb_read_json_member(exit_code "${event_line}" exit_code)
      if(NOT exit_code STREQUAL "0")
        message(FATAL_ERROR
          "MV3 ${expected_mode} shutdown failed: ${event_line}")
      endif()
    endif()

    if(event_name IN_LIST required_events AND
       NOT DEFINED event_sequence_${event_name})
      set(event_sequence_${event_name} "${sequence}")
    endif()
  endforeach()

  if(NOT renderer_process_seen)
    message(FATAL_ERROR
      "MV3 ${expected_mode} did not launch a real renderer process.")
  endif()
  foreach(required_event IN LISTS required_events)
    if(NOT DEFINED event_sequence_${required_event})
      message(FATAL_ERROR
        "MV3 ${expected_mode} did not record '${required_event}'.")
    endif()
  endforeach()
  if(NOT core_navigation_seen OR NOT core_load_seen)
    message(FATAL_ERROR
      "MV3 ${expected_mode} did not navigate and load the controlled core page.")
  endif()
  if(expected_mode STREQUAL "options" AND
     (NOT options_navigation_seen OR NOT options_load_seen))
    message(FATAL_ERROR
      "MV3 options mode did not navigate and load the exact extension page.")
  endif()

  macro(assert_event_before first_event second_event)
    if(event_sequence_${first_event} GREATER_EQUAL
       event_sequence_${second_event})
      message(FATAL_ERROR
        "MV3 event '${first_event}' must precede '${second_event}'.")
    endif()
  endmacro()
  assert_event_before(browser_process_start mv3_extension_load_configured)
  assert_event_before(mv3_extension_load_configured cef_context_initialized)
  assert_event_before(profile_opened browser_created)
  assert_event_before(browser_created native_child_attached)
  assert_event_before(mv3_test_request_intercepted load_end)
  assert_event_before(load_end mv3_core_self_test_passed)
  if(expected_mode STREQUAL "options")
    assert_event_before(mv3_core_self_test_passed
      mv3_options_page_navigation_requested)
    assert_event_before(mv3_options_page_navigation_requested
      mv3_options_page_loaded)
    assert_event_before(mv3_options_page_navigation_requested
      mv3_options_page_passed)
    assert_event_before(mv3_options_page_loaded profile_cookie_flush_started)
    assert_event_before(mv3_options_page_passed profile_cookie_flush_started)
  endif()
  assert_event_before(mv3_core_self_test_passed profile_cookie_flush_started)
  assert_event_before(profile_cookie_flush_completed browser_close_accepted)
  assert_event_before(browser_destroyed cef_quit_requested)
  assert_event_before(cef_quit_returned cef_shutdown_started)
  assert_event_before(cef_shutdown_started cef_shutdown)

  message(STATUS
    "MV3 ${expected_mode} passed with ${previous_sequence} ordered events.")
endfunction()

function(run_mv3 mode profile_name event_log_name preserve_root)
  set(preserve_argument)
  if(preserve_root)
    set(preserve_argument PRESERVE_ROOT)
  endif()
  kweb_run_host(
    ${preserve_argument}
    TIMEOUT 180
    PROFILE_NAME "${profile_name}"
    EVENT_LOG_NAME "${event_log_name}"
    ARGUMENTS
      "--kweb-mv3-core-self-test=${mode}"
      "--kweb-mv3-extension-path=${EXTENSION_PATH}"
  )
  require_host_success("${mode}" "${KWEB_HOST_RESULT}"
    "${KWEB_HOST_STDOUT}" "${KWEB_HOST_STDERR}")
  validate_mv3_run("${KWEB_EVENT_LOG_PATH}" "${mode}")
  require_mv3_disk_state("${profile_name}")
endfunction()

require_invalid_fixture_rejected()
run_mv3(initial alpha mv3-initial.jsonl FALSE)
run_mv3(restart alpha mv3-restart.jsonl TRUE)
run_mv3(isolated beta mv3-isolated.jsonl TRUE)
run_mv3(options options mv3-options.jsonl FALSE)

message(STATUS
  "Alloy MV3 core conformance passed on ${PLATFORM}: content script, runtime messaging, Service Worker idle restart, storage persistence, Profile isolation, and options-page native-child navigation.")
