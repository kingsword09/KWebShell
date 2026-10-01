cmake_minimum_required(VERSION 3.21)
project(kweb_shell_native VERSION 0.1.0 LANGUAGES C CXX)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_SHELL_PLATFORM_SOURCE src/shell_mac.mm)
elseif(WIN32)
  set(KWEB_SHELL_PLATFORM_SOURCE src/shell_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GIO REQUIRED IMPORTED_TARGET gio-2.0)
  set(KWEB_SHELL_PLATFORM_SOURCE src/shell_linux.cc)
else()
  message(FATAL_ERROR "KWebShell supports only Windows, macOS, and Linux.")
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
add_library(kwebshell_shell SHARED src/shell_abi.cc ${KWEB_SHELL_PLATFORM_SOURCE})
target_include_directories(kwebshell_shell PRIVATE include src)
if(MSVC)
  target_compile_options(kwebshell_shell PRIVATE /W4 /WX /utf-8 /EHsc)
  target_link_libraries(kwebshell_shell PRIVATE shell32 ole32)
else()
  target_compile_options(kwebshell_shell PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
if(APPLE)
  target_compile_options(kwebshell_shell PRIVATE $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
  target_link_libraries(kwebshell_shell PRIVATE "-framework Cocoa")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kwebshell_shell PRIVATE PkgConfig::GIO)
endif()
set_target_properties(kwebshell_shell PROPERTIES
  OUTPUT_NAME kwebshell_shell
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${KWEB_CONTRACT_OUTPUT_DIR}"
)
enable_testing()
add_executable(kweb_service_shell_tests tests/shell_tests.cc)
target_include_directories(kweb_service_shell_tests PRIVATE include)
target_link_libraries(kweb_service_shell_tests PRIVATE kwebshell_shell)
set_target_properties(kweb_service_shell_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE OR UNIX)
  set_target_properties(kweb_service_shell_tests PROPERTIES BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}")
endif()
add_test(NAME kweb_service_shell_tests COMMAND kweb_service_shell_tests)
set_tests_properties(kweb_service_shell_tests PROPERTIES LABELS "abi;native;shell" TIMEOUT 30)
