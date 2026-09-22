cmake_minimum_required(VERSION 3.21)

project(kweb_service_window_controls_native VERSION 2.0.0 LANGUAGES C CXX)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_WINDOW_CONTROLS_PLATFORM_SOURCE src/window_controls_platform_mac.mm)
elseif(WIN32)
  set(KWEB_WINDOW_CONTROLS_PLATFORM_SOURCE src/window_controls_platform_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  set(KWEB_WINDOW_CONTROLS_PLATFORM_SOURCE src/window_controls_platform_linux.cc)
else()
  message(FATAL_ERROR "KWebWindowControls supports only Windows, macOS, and Linux hosts.")
endif()

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

function(KWEB_CONFIGURE_TARGET target)
  target_include_directories(${target} PRIVATE include src)
  if(MSVC)
    target_compile_options(${target} PRIVATE /W4 /WX /utf-8 /EHsc)
  else()
    target_compile_options(${target} PRIVATE
      -Wall -Wextra -Wpedantic -Werror
      $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
    )
  endif()
endfunction()

add_library(kweb_window_controls_abi SHARED
  src/window_controls_abi.cc
  ${KWEB_WINDOW_CONTROLS_PLATFORM_SOURCE}
)
KWEB_CONFIGURE_TARGET(kweb_window_controls_abi)
target_compile_definitions(kweb_window_controls_abi PRIVATE KWEB_WINDOW_CONTROLS_ABI_BUILD)
if(APPLE)
  target_link_options(kweb_window_controls_abi PRIVATE
    "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/resources/window_controls_exports_mac.txt"
  )
  target_link_libraries(kweb_window_controls_abi PRIVATE "-framework AppKit")
elseif(WIN32)
  target_link_libraries(kweb_window_controls_abi PRIVATE user32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(X11 REQUIRED)
  target_link_options(kweb_window_controls_abi PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/resources/window_controls_exports_linux.map"
  )
  target_link_libraries(kweb_window_controls_abi PRIVATE X11::X11)
endif()
set_target_properties(kweb_window_controls_abi PROPERTIES
  OUTPUT_NAME "kwebshell_window_controls"
  VERSION "1.0.0"
  SOVERSION "1"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_window_controls_tests tests/window_controls_tests.cc)
KWEB_CONFIGURE_TARGET(kweb_window_controls_tests)
target_link_libraries(kweb_window_controls_tests PRIVATE kweb_window_controls_abi)
set_target_properties(kweb_window_controls_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_window_controls_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_window_controls_tests PROPERTIES
    BUILD_RPATH "\$ORIGIN"
  )
endif()
add_test(NAME kweb_window_controls_tests COMMAND kweb_window_controls_tests)
set_tests_properties(kweb_window_controls_tests PROPERTIES
  LABELS "abi;native;window-controls"
  TIMEOUT 30
)
