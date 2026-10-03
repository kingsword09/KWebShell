cmake_minimum_required(VERSION 3.21)

project(kweb_service_notifications_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_NOTIFICATIONS_PLATFORM_SOURCE src/notifications_mac.mm)
elseif(WIN32)
  set(KWEB_NOTIFICATIONS_PLATFORM_SOURCE src/notifications_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GIO REQUIRED IMPORTED_TARGET gio-2.0)
  set(KWEB_NOTIFICATIONS_PLATFORM_SOURCE src/notifications_linux.cc)
else()
  message(FATAL_ERROR "KWebNotifications supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_notifications_abi SHARED
  src/notifications_abi.cc
  ${KWEB_NOTIFICATIONS_PLATFORM_SOURCE}
)
target_include_directories(kweb_notifications_abi PRIVATE include src)
target_compile_definitions(kweb_notifications_abi PRIVATE KWEB_NOTIFICATIONS_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_notifications_abi PRIVATE /W4 /WX /utf-8 /EHsc)
  target_link_libraries(kweb_notifications_abi PRIVATE ole32 windowsapp)
else()
  target_compile_options(kweb_notifications_abi PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
endif()

if(APPLE)
  target_link_libraries(kweb_notifications_abi PRIVATE
    "-framework Foundation"
    "-framework UserNotifications"
  )
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_notifications_abi PRIVATE PkgConfig::GIO)
endif()

set_target_properties(kweb_notifications_abi PROPERTIES
  OUTPUT_NAME "kwebshell_notifications"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_notifications_tests tests/notifications_tests.cc)
target_include_directories(kweb_notifications_tests PRIVATE include)
if(MSVC)
  target_compile_options(kweb_notifications_tests PRIVATE /W4 /WX /utf-8 /EHsc)
else()
  target_compile_options(kweb_notifications_tests PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
target_link_libraries(kweb_notifications_tests PRIVATE kweb_notifications_abi)
set_target_properties(kweb_notifications_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_notifications_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_notifications_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_notifications_tests COMMAND kweb_notifications_tests)
set_tests_properties(kweb_notifications_tests PROPERTIES
  LABELS "abi;native;notifications"
  TIMEOUT 30
)
