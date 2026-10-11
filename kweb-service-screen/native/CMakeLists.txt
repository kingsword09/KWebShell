cmake_minimum_required(VERSION 3.21)

project(kweb_service_screen_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_SCREEN_PLATFORM_SOURCE src/screen_mac.mm)
elseif(WIN32)
  set(KWEB_SCREEN_PLATFORM_SOURCE src/screen_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(X11 REQUIRED IMPORTED_TARGET x11)
  pkg_check_modules(XRANDR REQUIRED IMPORTED_TARGET xrandr)
  set(KWEB_SCREEN_PLATFORM_SOURCE src/screen_x11.cc)
else()
  message(FATAL_ERROR "KWebScreen supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_screen_abi SHARED
  src/screen_abi.cc
  ${KWEB_SCREEN_PLATFORM_SOURCE}
)
target_include_directories(kweb_screen_abi PRIVATE include src)
target_compile_definitions(kweb_screen_abi PRIVATE KWEB_SCREEN_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_screen_abi PRIVATE /W4 /WX /utf-8 /EHsc)
else()
  target_compile_options(kweb_screen_abi PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
endif()

if(APPLE)
  target_link_libraries(kweb_screen_abi PRIVATE "-framework AppKit" "-framework CoreGraphics")
elseif(WIN32)
  target_link_libraries(kweb_screen_abi PRIVATE user32 shcore)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_screen_abi PRIVATE PkgConfig::X11 PkgConfig::XRANDR)
endif()

set_target_properties(kweb_screen_abi PROPERTIES
  OUTPUT_NAME "kwebshell_screen"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_screen_tests tests/screen_tests.cc)
if(APPLE)
  target_sources(kweb_screen_tests PRIVATE tests/screen_structure_mac.mm)
elseif(WIN32)
  target_sources(kweb_screen_tests PRIVATE tests/screen_structure_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_sources(kweb_screen_tests PRIVATE tests/screen_structure_x11.cc)
endif()
target_include_directories(kweb_screen_tests PRIVATE include src)
if(MSVC)
  target_compile_options(kweb_screen_tests PRIVATE /W4 /WX /utf-8 /EHsc /UNDEBUG)
else()
  target_compile_options(kweb_screen_tests PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
endif()
# The tests link the ABI and platform sources directly so validation, topology
# reads and conversions are asserted without exporting implementation details.
target_sources(kweb_screen_tests PRIVATE src/screen_abi.cc ${KWEB_SCREEN_PLATFORM_SOURCE})
target_compile_definitions(kweb_screen_tests PRIVATE KWEB_SCREEN_ABI_BUILD)
if(APPLE)
  target_link_libraries(kweb_screen_tests PRIVATE "-framework AppKit" "-framework CoreGraphics")
elseif(WIN32)
  target_link_libraries(kweb_screen_tests PRIVATE user32 shcore)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_screen_tests PRIVATE PkgConfig::X11 PkgConfig::XRANDR)
endif()
set_target_properties(kweb_screen_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_screen_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_screen_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_screen_tests COMMAND kweb_screen_tests)
set_tests_properties(kweb_screen_tests PROPERTIES
  LABELS "abi;native;screen"
  TIMEOUT 60
)
