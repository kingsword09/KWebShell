cmake_minimum_required(VERSION 3.21)

project(kweb_service_clipboard_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_CLIPBOARD_PLATFORM_SOURCE src/clipboard_mac.mm)
elseif(WIN32)
  set(KWEB_CLIPBOARD_PLATFORM_SOURCE src/clipboard_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GTK3 REQUIRED IMPORTED_TARGET gtk+-3.0)
  set(KWEB_CLIPBOARD_PLATFORM_SOURCE src/clipboard_linux.cc)
else()
  message(FATAL_ERROR "KWebClipboard supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_clipboard_abi SHARED
  src/clipboard_abi.cc
  ${KWEB_CLIPBOARD_PLATFORM_SOURCE}
)
target_include_directories(kweb_clipboard_abi PRIVATE include src)
target_compile_definitions(kweb_clipboard_abi PRIVATE KWEB_CLIPBOARD_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_clipboard_abi PRIVATE /W4 /WX /utf-8 /EHsc)
  target_link_libraries(kweb_clipboard_abi PRIVATE user32 ole32)
else()
  target_compile_options(kweb_clipboard_abi PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
endif()

if(APPLE)
  target_link_libraries(kweb_clipboard_abi PRIVATE "-framework AppKit")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_clipboard_abi PRIVATE PkgConfig::GTK3)
endif()

set_target_properties(kweb_clipboard_abi PROPERTIES
  OUTPUT_NAME "kwebshell_clipboard"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_clipboard_tests tests/clipboard_tests.cc)
target_include_directories(kweb_clipboard_tests PRIVATE include)
if(MSVC)
  target_compile_options(kweb_clipboard_tests PRIVATE /W4 /WX /utf-8 /EHsc /UNDEBUG)
  target_link_libraries(kweb_clipboard_tests PRIVATE user32)
else()
  target_compile_options(kweb_clipboard_tests PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
endif()
target_link_libraries(kweb_clipboard_tests PRIVATE kweb_clipboard_abi)
set_target_properties(kweb_clipboard_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_clipboard_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_clipboard_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_clipboard_tests COMMAND kweb_clipboard_tests)
set_tests_properties(kweb_clipboard_tests PROPERTIES
  LABELS "abi;native;clipboard"
  TIMEOUT 30
)
