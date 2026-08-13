if(NOT DEFINED ENGINE_LIBRARY OR NOT EXISTS "${ENGINE_LIBRARY}")
  message(FATAL_ERROR "ENGINE_LIBRARY must identify the built engine library.")
endif()

set(expected
  kweb_browser_close
  kweb_browser_create
  kweb_browser_navigate
  kweb_browser_resize
  kweb_browser_open_devtools
  kweb_browser_close_devtools
  kweb_engine_abi_version
  kweb_engine_close
  kweb_engine_create
  kweb_engine_platform_startup
  kweb_live_browser_count
  kweb_live_engine_count
  kweb_status_name
)
list(SORT expected)

if(WIN32)
  if(NOT DEFINED DUMPBIN OR DUMPBIN STREQUAL "")
    message(FATAL_ERROR "DUMPBIN is required on Windows.")
  endif()
  execute_process(
    COMMAND "${DUMPBIN}" /exports "${ENGINE_LIBRARY}"
    RESULT_VARIABLE result
    OUTPUT_VARIABLE output
    ERROR_VARIABLE error
  )
elseif(APPLE)
  execute_process(
    COMMAND nm -gU "${ENGINE_LIBRARY}"
    RESULT_VARIABLE result
    OUTPUT_VARIABLE output
    ERROR_VARIABLE error
  )
else()
  execute_process(
    COMMAND nm -D --defined-only "${ENGINE_LIBRARY}"
    RESULT_VARIABLE result
    OUTPUT_VARIABLE output
    ERROR_VARIABLE error
  )
endif()
if(NOT result EQUAL 0)
  message(FATAL_ERROR "Could not inspect engine exports: ${error}")
endif()

string(REGEX MATCHALL "_?kweb_[A-Za-z0-9_]+" matches "${output}")
set(actual)
foreach(match IN LISTS matches)
  string(REGEX REPLACE "^_" "" symbol "${match}")
  list(APPEND actual "${symbol}")
endforeach()
list(REMOVE_DUPLICATES actual)
list(SORT actual)
if(NOT actual STREQUAL expected)
  message(FATAL_ERROR "Engine exports differ. Expected '${expected}', got '${actual}'.")
endif()
