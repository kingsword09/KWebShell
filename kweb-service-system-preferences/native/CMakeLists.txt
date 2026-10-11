cmake_minimum_required(VERSION 3.21)

project(kweb_service_system_preferences_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_PREFERENCES_PLATFORM_SOURCE src/preferences_mac.mm)
elseif(WIN32)
  set(KWEB_PREFERENCES_PLATFORM_SOURCE src/preferences_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GIO REQUIRED IMPORTED_TARGET gio-2.0)
  set(KWEB_PREFERENCES_PLATFORM_SOURCE src/preferences_linux.cc)
else()
  message(FATAL_ERROR "KWebSystemPreferences supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_preferences_abi SHARED
  src/preferences_abi.cc
  ${KWEB_PREFERENCES_PLATFORM_SOURCE}
)
target_include_directories(kweb_preferences_abi PRIVATE include src)
target_compile_definitions(kweb_preferences_abi PRIVATE KWEB_PREFERENCES_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_preferences_abi PRIVATE /W4 /WX /utf-8 /EHsc)
else()
  target_compile_options(kweb_preferences_abi PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
endif()

if(APPLE)
  target_link_libraries(kweb_preferences_abi PRIVATE "-framework AppKit")
elseif(WIN32)
  target_link_libraries(kweb_preferences_abi PRIVATE user32 advapi32 dwmapi)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_preferences_abi PRIVATE PkgConfig::GIO)
endif()

set_target_properties(kweb_preferences_abi PROPERTIES
  OUTPUT_NAME "kwebshell_system_preferences"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_preferences_tests tests/preferences_tests.cc)
if(APPLE)
  target_sources(kweb_preferences_tests PRIVATE tests/preferences_structure_mac.mm)
elseif(WIN32)
  target_sources(kweb_preferences_tests PRIVATE tests/preferences_structure_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_sources(kweb_preferences_tests PRIVATE tests/preferences_structure_linux.cc)
endif()
target_include_directories(kweb_preferences_tests PRIVATE include src)
if(MSVC)
  target_compile_options(kweb_preferences_tests PRIVATE /W4 /WX /utf-8 /EHsc /UNDEBUG)
else()
  target_compile_options(kweb_preferences_tests PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
endif()
# The tests link the ABI and platform sources directly so validation, structure
# reads and the sensitive-key policy are asserted without exporting
# implementation details from the published C ABI library.
target_sources(kweb_preferences_tests PRIVATE src/preferences_abi.cc ${KWEB_PREFERENCES_PLATFORM_SOURCE})
target_compile_definitions(kweb_preferences_tests PRIVATE KWEB_PREFERENCES_ABI_BUILD)
if(APPLE)
  target_link_libraries(kweb_preferences_tests PRIVATE "-framework AppKit")
elseif(WIN32)
  target_link_libraries(kweb_preferences_tests PRIVATE user32 advapi32 dwmapi)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_preferences_tests PRIVATE PkgConfig::GIO)
endif()
set_target_properties(kweb_preferences_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_preferences_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_preferences_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_preferences_tests COMMAND kweb_preferences_tests)
set_tests_properties(kweb_preferences_tests PROPERTIES
  LABELS "abi;native;preferences"
  TIMEOUT 60
)
