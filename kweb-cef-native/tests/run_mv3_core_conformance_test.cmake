cmake_minimum_required(VERSION 3.21)

include("${CMAKE_CURRENT_LIST_DIR}/native_test_support.cmake")
kweb_require_test_inputs()

if(NOT DEFINED EXTENSION_PATH OR EXTENSION_PATH STREQUAL "" OR
   NOT IS_DIRECTORY "${EXTENSION_PATH}")
  message(FATAL_ERROR "EXTENSION_PATH must identify the MV3 fixture directory.")
endif()
if(NOT DEFINED EXPECT_CUSTOM_EXTENSION_RUNTIME OR
   (NOT EXPECT_CUSTOM_EXTENSION_RUNTIME STREQUAL "ON" AND
    NOT EXPECT_CUSTOM_EXTENSION_RUNTIME STREQUAL "OFF"))
  message(FATAL_ERROR
    "EXPECT_CUSTOM_EXTENSION_RUNTIME must be exactly ON or OFF.")
endif()

set(mv3_test_url https://kwebshell.test/mv3-core-self-test)
set(mv3_extension_id dhhnhmffjehhodphofnkingncijnaona)
set(mv3_options_page_url
  chrome-extension://dhhnhmffjehhodphofnkingncijnaona/options.html)
set(mv3_action_popup_url
  chrome-extension://dhhnhmffjehhodphofnkingncijnaona/popup.html)
set(mv3_context_menu_label "KWebShell MV3 context item")
set(mv3_context_menu_x 120)
set(mv3_context_menu_y 120)
set(mv3_context_menu_result
  "KWEB_MV3_CONTEXT_MENU_PASS|id=${mv3_extension_id}|menu=kwebshell-mv3-context-menu|clickCount=1|page=https%3A%2F%2Fkwebshell.test%2Fmv3-core-self-test")
set(mv3_devtools_result
  "KWEB_MV3_DEVTOOLS_PASS|id=${mv3_extension_id}|origin=chrome-extension%3A%2F%2F${mv3_extension_id}|page=%2Fdevtools.html|panel=KWebShell%20MV3%20panel|panelPage=devtools-panel.html|inspected=kwebshell-devtools-inspected|eval=true|created=true")
set(mv3_offscreen_result
  "KWEB_MV3_OFFSCREEN_PASS|id=${mv3_extension_id}|origin=chrome-extension%3A%2F%2F${mv3_extension_id}|page=%2Foffscreen.html|reason=DOM_PARSER|parser=KWebShell%20offscreen%20parser|before=false|during=true|closed=true|after=false|ready=1")

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

function(expected_mv3_extension_page surface_output url_output result_output mode)
  set(surface)
  set(url)
  set(result)
  if(mode STREQUAL "options")
    set(surface "options")
    set(url "${mv3_options_page_url}")
    set(result
      "KWEB_MV3_OPTIONS_PASS|id=${mv3_extension_id}|manifest=KWebShell%20MV3%20core%20conformance|messageCount=2|path=/options.html")
  elseif(mode STREQUAL "action-popup")
    set(surface "action-popup")
    set(url "${mv3_action_popup_url}")
    set(result
      "KWEB_MV3_ACTION_POPUP_PASS|id=${mv3_extension_id}|manifest=KWebShell%20MV3%20core%20conformance|popup=popup.html|defaultTitle=KWebShell%20MV3%20action|badge=2|title=KWebShell%20MV3%20action%20count%3A%202|messageCount=2|path=/popup.html")
  endif()
  set(${surface_output} "${surface}" PARENT_SCOPE)
  set(${url_output} "${url}" PARENT_SCOPE)
  set(${result_output} "${result}" PARENT_SCOPE)
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

function(require_stock_context_menu_rejected)
  kweb_run_host(
    TIMEOUT 180
    PROFILE_NAME stock-context-menu
    EVENT_LOG_NAME mv3-stock-context-menu.jsonl
    ARGUMENTS
      --kweb-mv3-core-self-test=context-menu
      "--kweb-mv3-extension-path=${EXTENSION_PATH}"
  )
  if(NOT KWEB_HOST_RESULT STREQUAL "71")
    message(FATAL_ERROR
      "Stock CEF context-menu gate exited with '${KWEB_HOST_RESULT}', "
      "expected '71'.\nstdout:\n${KWEB_HOST_STDOUT}\n"
      "stderr:\n${KWEB_HOST_STDERR}")
  endif()

  expected_mv3_result(expected_core_result context-menu)
  kweb_read_event_lines(event_lines "${KWEB_EVENT_LOG_PATH}")
  set(previous_sequence 0)
  set(renderer_process_seen FALSE)
  set(required_events
    browser_process_start
    mv3_extension_load_configured
    browser_created
    mv3_core_self_test_passed
    mv3_context_menu_input_requested
    error
    browser_destroyed
    cef_shutdown
  )
  set(forbidden_events
    mv3_context_menu_model_observed
    mv3_context_menu_selection_dispatched
    mv3_context_menu_command_observed
    mv3_context_menu_dismissed
    mv3_context_menu_page_passed
    profile_cookie_flush_started
    profile_cookie_flush_completed
  )

  foreach(event_line IN LISTS event_lines)
    kweb_read_json_member(sequence "${event_line}" sequence)
    kweb_read_json_member(event_name "${event_line}" event)
    math(EXPR expected_sequence "${previous_sequence} + 1")
    if(NOT sequence EQUAL expected_sequence)
      message(FATAL_ERROR
        "Stock CEF context-menu sequence is not contiguous: expected "
        "${expected_sequence}, got ${sequence}.")
    endif()
    set(previous_sequence "${sequence}")

    if(event_name IN_LIST forbidden_events)
      message(FATAL_ERROR
        "Stock CEF fabricated or partially dispatched '${event_name}': "
        "${event_line}")
    elseif(event_name STREQUAL "child_process_launch")
      kweb_read_json_member(process_type "${event_line}" type)
      if(process_type STREQUAL "renderer")
        set(renderer_process_seen TRUE)
      endif()
    elseif(event_name STREQUAL "mv3_extension_load_configured")
      kweb_read_json_member(mode "${event_line}" mode)
      kweb_read_json_member(context_menu_backend "${event_line}"
        context_menu_backend)
      if(NOT mode STREQUAL "context-menu" OR
         NOT context_menu_backend STREQUAL "chrome-render-view")
        message(FATAL_ERROR
          "Stock CEF gate did not request the exact patched backend: "
          "${event_line}")
      endif()
    elseif(event_name STREQUAL "browser_created")
      kweb_read_json_member(runtime_style "${event_line}" runtime_style)
      kweb_read_json_member(windowless "${event_line}" windowless)
      kweb_read_json_member(native_window "${event_line}" native_window)
      if(NOT runtime_style STREQUAL "alloy" OR
         NOT windowless STREQUAL "false" OR
         NOT native_window STREQUAL "present")
        message(FATAL_ERROR
          "Stock CEF gate did not use the Alloy native child: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_core_self_test_passed")
      kweb_read_json_member(mode "${event_line}" mode)
      kweb_read_json_member(result "${event_line}" result)
      if(NOT mode STREQUAL "context-menu" OR
         NOT result STREQUAL "${expected_core_result}")
        message(FATAL_ERROR
          "Stock CEF gate did not complete MV3 core first: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_input_requested")
      kweb_read_json_member(context_menu_x "${event_line}" x)
      kweb_read_json_member(context_menu_y "${event_line}" y)
      kweb_read_json_member(context_menu_url "${event_line}" url)
      if(NOT context_menu_x STREQUAL "${mv3_context_menu_x}" OR
         NOT context_menu_y STREQUAL "${mv3_context_menu_y}" OR
         NOT context_menu_url STREQUAL "${mv3_test_url}")
        message(FATAL_ERROR
          "Stock CEF gate did not send the exact right-click input: "
          "${event_line}")
      endif()
    elseif(event_name STREQUAL "error")
      kweb_read_json_member(error_code "${event_line}" code)
      kweb_read_json_member(expected_count "${event_line}" expected)
      kweb_read_json_member(actual_count "${event_line}" actual)
      if(NOT error_code STREQUAL
             "native.mv3.context-menu-item-count-invalid" OR
         NOT expected_count STREQUAL "1" OR NOT actual_count STREQUAL "0")
        message(FATAL_ERROR
          "Stock CEF gate did not fail with the exact missing-item error: "
          "${event_line}")
      endif()
    elseif(event_name STREQUAL "cef_shutdown")
      kweb_read_json_member(exit_code "${event_line}" exit_code)
      if(NOT exit_code STREQUAL "71")
        message(FATAL_ERROR
          "Stock CEF context-menu shutdown was not terminal: ${event_line}")
      endif()
    endif()

    if(event_name IN_LIST required_events AND
       NOT DEFINED event_sequence_${event_name})
      set(event_sequence_${event_name} "${sequence}")
    endif()
  endforeach()

  if(NOT renderer_process_seen)
    message(FATAL_ERROR
      "Stock CEF context-menu gate did not launch a real renderer process.")
  endif()
  foreach(required_event IN LISTS required_events)
    if(NOT DEFINED event_sequence_${required_event})
      message(FATAL_ERROR
        "Stock CEF context-menu gate did not record '${required_event}'.")
    endif()
  endforeach()
  if(event_sequence_mv3_core_self_test_passed GREATER_EQUAL
       event_sequence_mv3_context_menu_input_requested OR
     event_sequence_mv3_context_menu_input_requested GREATER_EQUAL
       event_sequence_error OR
     event_sequence_error GREATER_EQUAL event_sequence_browser_destroyed OR
     event_sequence_browser_destroyed GREATER_EQUAL event_sequence_cef_shutdown)
    message(FATAL_ERROR
      "Stock CEF context-menu gate recorded an invalid terminal order.")
  endif()

  message(STATUS
    "Stock CEF correctly rejected Chromium-backed Alloy context menus without "
    "fabricating an extension item (${previous_sequence} ordered events).")
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
  expected_mv3_extension_page(expected_extension_page_surface
    expected_extension_page_url expected_extension_page_result
    "${expected_mode}")
  kweb_read_event_lines(event_lines "${event_log}")
  set(previous_sequence 0)
  set(renderer_process_seen FALSE)
  set(core_navigation_seen FALSE)
  set(core_load_seen FALSE)
  set(extension_page_navigation_seen FALSE)
  set(extension_page_load_seen FALSE)
  set(context_menu_mode FALSE)
  set(devtools_mode FALSE)
  set(offscreen_mode FALSE)
  if(expected_mode STREQUAL "context-menu")
    set(context_menu_mode TRUE)
  endif()
  if(expected_mode STREQUAL "devtools")
    set(devtools_mode TRUE)
  endif()
  if(expected_mode STREQUAL "offscreen")
    set(offscreen_mode TRUE)
  endif()
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
  if(NOT expected_extension_page_surface STREQUAL "")
    list(APPEND required_events
      mv3_extension_page_navigation_requested
      mv3_extension_page_loaded
      mv3_extension_page_passed
    )
  endif()
  if(context_menu_mode)
    list(APPEND required_events
      mv3_context_menu_input_requested
      mv3_context_menu_model_observed
      mv3_context_menu_selection_dispatched
      mv3_context_menu_command_observed
      mv3_context_menu_dismissed
      mv3_context_menu_page_passed
    )
  endif()
  if(devtools_mode)
    list(APPEND required_events
      mv3_devtools_open_requested
      mv3_devtools_opened
      mv3_devtools_frontend_loaded
      mv3_devtools_page_passed
      mv3_devtools_close_requested
      mv3_devtools_closed
    )
  endif()
  if(offscreen_mode)
    list(APPEND required_events mv3_offscreen_page_passed)
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
      kweb_read_json_member(context_menu_backend "${event_line}"
        context_menu_backend)
      cmake_path(CONVERT "${extension_path}" TO_CMAKE_PATH_LIST
        normalized_extension_path NORMALIZE)
      cmake_path(CONVERT "${EXTENSION_PATH}" TO_CMAKE_PATH_LIST
        normalized_expected_extension_path NORMALIZE)
      if(NOT mode STREQUAL "${expected_mode}" OR
         NOT background_networking STREQUAL "disabled" OR
         NOT component_updates STREQUAL "disabled" OR
         NOT proxy STREQUAL "disabled" OR
         (expected_mode STREQUAL "context-menu" AND
          NOT context_menu_backend STREQUAL "chrome-render-view") OR
         (NOT expected_mode STREQUAL "context-menu" AND
          NOT context_menu_backend STREQUAL "alloy") OR
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
      elseif(NOT expected_extension_page_surface STREQUAL "" AND
             navigated_url STREQUAL "${expected_extension_page_url}")
        set(extension_page_navigation_seen TRUE)
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
      elseif(NOT expected_extension_page_surface STREQUAL "" AND
             loaded_url STREQUAL "${expected_extension_page_url}")
        if(NOT http_status STREQUAL "200")
          message(FATAL_ERROR
            "MV3 ${expected_extension_page_surface} page did not report "
            "HTTP 200: ${event_line}")
        endif()
        set(extension_page_load_seen TRUE)
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
    elseif(event_name STREQUAL "mv3_extension_page_navigation_requested")
      kweb_read_json_member(extension_page_surface "${event_line}" surface)
      kweb_read_json_member(extension_page_url "${event_line}" url)
      if(expected_extension_page_surface STREQUAL "" OR
         NOT extension_page_surface STREQUAL
             "${expected_extension_page_surface}" OR
         NOT extension_page_url STREQUAL "${expected_extension_page_url}")
        message(FATAL_ERROR
          "MV3 extension-page navigation request was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_extension_page_loaded")
      kweb_read_json_member(extension_page_surface "${event_line}" surface)
      kweb_read_json_member(extension_page_url "${event_line}" url)
      if(expected_extension_page_surface STREQUAL "" OR
         NOT extension_page_surface STREQUAL
             "${expected_extension_page_surface}" OR
         NOT extension_page_url STREQUAL "${expected_extension_page_url}")
        message(FATAL_ERROR
          "MV3 extension-page load was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_extension_page_passed")
      kweb_read_json_member(extension_page_surface "${event_line}" surface)
      kweb_read_json_member(extension_page_result "${event_line}" result)
      if(expected_extension_page_surface STREQUAL "" OR
         NOT extension_page_surface STREQUAL
             "${expected_extension_page_surface}" OR
         NOT extension_page_result STREQUAL "${expected_extension_page_result}")
        message(FATAL_ERROR
          "MV3 extension-page result was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_input_requested")
      kweb_read_json_member(context_menu_x "${event_line}" x)
      kweb_read_json_member(context_menu_y "${event_line}" y)
      kweb_read_json_member(context_menu_url "${event_line}" url)
      if(NOT context_menu_mode OR
         NOT context_menu_x STREQUAL "${mv3_context_menu_x}" OR
         NOT context_menu_y STREQUAL "${mv3_context_menu_y}" OR
         NOT context_menu_url STREQUAL "${mv3_test_url}")
        message(FATAL_ERROR
          "MV3 context-menu input was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_model_observed")
      kweb_read_json_member(context_menu_command_id "${event_line}"
        command_id)
      kweb_read_json_member(context_menu_top_level_item_count "${event_line}"
        top_level_item_count)
      kweb_read_json_member(context_menu_x "${event_line}" x)
      kweb_read_json_member(context_menu_y "${event_line}" y)
      kweb_read_json_member(context_menu_url "${event_line}" url)
      kweb_read_json_member(context_menu_label "${event_line}" label)
      if(NOT context_menu_mode OR
         NOT context_menu_command_id MATCHES "^[1-9][0-9]*$" OR
         NOT context_menu_top_level_item_count MATCHES "^[1-9][0-9]*$" OR
         NOT context_menu_x STREQUAL "${mv3_context_menu_x}" OR
         NOT context_menu_y STREQUAL "${mv3_context_menu_y}" OR
         NOT context_menu_url STREQUAL "${mv3_test_url}" OR
         NOT context_menu_label STREQUAL "${mv3_context_menu_label}")
        message(FATAL_ERROR
          "MV3 context-menu model was not exact: ${event_line}")
      endif()
      set(observed_context_menu_command_id "${context_menu_command_id}")
    elseif(event_name STREQUAL "mv3_context_menu_selection_dispatched")
      kweb_read_json_member(context_menu_command_id "${event_line}"
        command_id)
      if(NOT context_menu_mode OR
         NOT DEFINED observed_context_menu_command_id OR
         NOT context_menu_command_id STREQUAL
             "${observed_context_menu_command_id}")
        message(FATAL_ERROR
          "MV3 context-menu selected the wrong command: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_command_observed")
      kweb_read_json_member(context_menu_command_id "${event_line}"
        command_id)
      kweb_read_json_member(context_menu_client_handled "${event_line}"
        client_handled)
      kweb_read_json_member(context_menu_default_dispatch "${event_line}"
        default_dispatch)
      if(NOT context_menu_mode OR
         NOT DEFINED observed_context_menu_command_id OR
         NOT context_menu_command_id STREQUAL
             "${observed_context_menu_command_id}" OR
         NOT context_menu_client_handled STREQUAL "false" OR
         NOT context_menu_default_dispatch STREQUAL "requested")
        message(FATAL_ERROR
          "MV3 context-menu command dispatch was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_dismissed")
      if(NOT context_menu_mode)
        message(FATAL_ERROR
          "MV3 context-menu dismissal was unexpected: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_context_menu_page_passed")
      kweb_read_json_member(context_menu_page_result "${event_line}" result)
      if(NOT context_menu_mode OR
         NOT context_menu_page_result STREQUAL "${mv3_context_menu_result}")
        message(FATAL_ERROR
          "MV3 context-menu extension result was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_devtools_open_requested")
      if(NOT devtools_mode)
        message(FATAL_ERROR
          "MV3 DevTools open request was unexpected: ${event_line}")
      endif()
      kweb_read_json_member(devtools_inspected_url "${event_line}" inspected_url)
      if(NOT devtools_inspected_url STREQUAL "${mv3_test_url}")
        message(FATAL_ERROR
          "MV3 DevTools opened for the wrong inspected URL: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_devtools_opened")
      if(NOT devtools_mode)
        message(FATAL_ERROR
          "MV3 DevTools open event was unexpected: ${event_line}")
      endif()
      kweb_read_json_member(devtools_popup "${event_line}" popup)
      kweb_read_json_member(devtools_runtime_style "${event_line}" runtime_style)
      kweb_read_json_member(devtools_windowless "${event_line}" windowless)
      kweb_read_json_member(devtools_native_window "${event_line}" native_window)
      kweb_read_json_member(devtools_profile_match "${event_line}" profile_match)
      kweb_read_json_member(devtools_inspected_url "${event_line}" inspected_url)
      if(NOT devtools_popup STREQUAL "true" OR
         NOT devtools_runtime_style STREQUAL "chrome" OR
         NOT devtools_windowless STREQUAL "false" OR
         NOT devtools_native_window STREQUAL "present" OR
         NOT devtools_profile_match STREQUAL "true" OR
         NOT devtools_inspected_url STREQUAL "${mv3_test_url}")
        message(FATAL_ERROR
          "MV3 DevTools window contract was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_devtools_frontend_loaded")
      if(NOT devtools_mode)
        message(FATAL_ERROR
          "MV3 DevTools frontend load was unexpected: ${event_line}")
      endif()
      kweb_read_json_member(devtools_frontend_url "${event_line}" url)
      kweb_read_json_member(devtools_frontend_status "${event_line}" http_status)
      if(NOT devtools_frontend_url MATCHES "^devtools://" OR
         NOT devtools_frontend_status STREQUAL "200")
        message(FATAL_ERROR
          "MV3 DevTools frontend load was not real: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_devtools_page_passed")
      if(NOT devtools_mode)
        message(FATAL_ERROR
          "MV3 DevTools page result was unexpected: ${event_line}")
      endif()
      kweb_read_json_member(devtools_result "${event_line}" result)
      if(NOT devtools_result STREQUAL "${mv3_devtools_result}")
        message(FATAL_ERROR
          "MV3 DevTools extension result was not exact: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_devtools_close_requested" OR
           event_name STREQUAL "mv3_devtools_closed")
      if(NOT devtools_mode)
        message(FATAL_ERROR
          "MV3 DevTools close event was unexpected: ${event_line}")
      endif()
    elseif(event_name STREQUAL "mv3_offscreen_page_passed")
      if(NOT offscreen_mode)
        message(FATAL_ERROR
          "MV3 offscreen result was unexpected: ${event_line}")
      endif()
      kweb_read_json_member(offscreen_result "${event_line}" result)
      if(NOT offscreen_result STREQUAL "${mv3_offscreen_result}")
        message(FATAL_ERROR
          "MV3 offscreen extension result was not exact: ${event_line}")
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
  if(NOT expected_extension_page_surface STREQUAL "" AND
     (NOT extension_page_navigation_seen OR NOT extension_page_load_seen))
    message(FATAL_ERROR
      "MV3 ${expected_extension_page_surface} mode did not navigate and "
      "load the exact extension page.")
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
  if(NOT expected_extension_page_surface STREQUAL "")
    assert_event_before(mv3_core_self_test_passed
      mv3_extension_page_navigation_requested)
    assert_event_before(mv3_extension_page_navigation_requested
      mv3_extension_page_loaded)
    assert_event_before(mv3_extension_page_navigation_requested
      mv3_extension_page_passed)
    assert_event_before(mv3_extension_page_loaded profile_cookie_flush_started)
    assert_event_before(mv3_extension_page_passed profile_cookie_flush_started)
  endif()
  if(context_menu_mode)
    assert_event_before(mv3_core_self_test_passed
      mv3_context_menu_input_requested)
    assert_event_before(mv3_context_menu_input_requested
      mv3_context_menu_model_observed)
    assert_event_before(mv3_context_menu_model_observed
      mv3_context_menu_selection_dispatched)
    assert_event_before(mv3_context_menu_selection_dispatched
      mv3_context_menu_command_observed)
    assert_event_before(mv3_context_menu_command_observed
      mv3_context_menu_dismissed)
    assert_event_before(mv3_context_menu_dismissed
      mv3_context_menu_page_passed)
    assert_event_before(mv3_context_menu_page_passed
      profile_cookie_flush_started)
  endif()
  if(devtools_mode)
    assert_event_before(mv3_core_self_test_passed
      mv3_devtools_open_requested)
    assert_event_before(mv3_devtools_open_requested mv3_devtools_opened)
    assert_event_before(mv3_devtools_opened mv3_devtools_frontend_loaded)
    assert_event_before(mv3_devtools_frontend_loaded mv3_devtools_page_passed)
    assert_event_before(mv3_devtools_page_passed mv3_devtools_close_requested)
    assert_event_before(mv3_devtools_close_requested mv3_devtools_closed)
    assert_event_before(mv3_devtools_closed profile_cookie_flush_started)
  endif()
  if(offscreen_mode)
    assert_event_before(mv3_core_self_test_passed mv3_offscreen_page_passed)
    assert_event_before(mv3_offscreen_page_passed
      profile_cookie_flush_started)
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
run_mv3(action-popup action-popup mv3-action-popup.jsonl FALSE)
run_mv3(devtools devtools mv3-devtools.jsonl FALSE)
run_mv3(offscreen offscreen mv3-offscreen.jsonl FALSE)
if(EXPECT_CUSTOM_EXTENSION_RUNTIME)
  run_mv3(context-menu context-menu mv3-context-menu.jsonl FALSE)
else()
  require_stock_context_menu_rejected()
endif()

if(EXPECT_CUSTOM_EXTENSION_RUNTIME)
  message(STATUS
    "Custom Alloy MV3 conformance passed on ${PLATFORM}: content script, runtime messaging, Service Worker idle restart, storage persistence, Profile isolation, options-page navigation, action-popup navigation, DevTools extension page/panel dispatch, offscreen-document lifecycle, and Chromium context-menu command dispatch.")
else()
  message(STATUS
    "Stock Alloy MV3 conformance passed on ${PLATFORM}: core, options, action-popup, DevTools extension, and the fixed offscreen-document gate plus the strict unpublished context-menu capability gate.")
endif()
