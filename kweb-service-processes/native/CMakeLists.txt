cmake_minimum_required(VERSION 3.21)

project(kweb_service_processes_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(WIN32)
  set(KWEB_PROCESSES_PLATFORM_SOURCE src/processes_win.cc)
elseif(APPLE OR CMAKE_SYSTEM_NAME STREQUAL "Linux")
  set(KWEB_PROCESSES_PLATFORM_SOURCE src/processes_posix.cc)
else()
  message(FATAL_ERROR "KWebProcesses supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_processes_abi SHARED
  src/processes_abi.cc
  ${KWEB_PROCESSES_PLATFORM_SOURCE}
)
target_include_directories(kweb_processes_abi PRIVATE include src)
target_compile_definitions(kweb_processes_abi PRIVATE KWEB_PROCESSES_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_processes_abi PRIVATE /W4 /WX /utf-8 /EHsc)
  target_link_libraries(kweb_processes_abi PRIVATE psapi)
else()
  target_compile_options(kweb_processes_abi PRIVATE -Wall -Wextra -Wpedantic -Werror)
  find_package(Threads REQUIRED)
  target_link_libraries(kweb_processes_abi PRIVATE Threads::Threads)
endif()
if(APPLE)
  # libproc provides the per-child resident and CPU counters.
  target_link_libraries(kweb_processes_abi PRIVATE "-framework CoreFoundation")
endif()

set_target_properties(kweb_processes_abi PROPERTIES
  OUTPUT_NAME "kwebshell_processes"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

# The declared helper executes real child behaviour: streams, stdio, limits,
# environment, working directory, process trees, and crashes.
add_executable(kweb_processes_helper tests/helpers/processes_helper.cc)
target_include_directories(kweb_processes_helper PRIVATE include)
if(MSVC)
  target_compile_options(kweb_processes_helper PRIVATE /W4 /WX /utf-8 /EHsc)
else()
  target_compile_options(kweb_processes_helper PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
set_target_properties(kweb_processes_helper PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

target_compile_definitions(kweb_processes_helper PRIVATE
  KWEB_PROCESSES_HELPER_PATH="$<TARGET_FILE:kweb_processes_helper>"
)

enable_testing()
add_executable(kweb_processes_tests
  tests/processes_tests.cc
  tests/processes_structure_shared.cc
)
if(APPLE)
  target_sources(kweb_processes_tests PRIVATE tests/processes_structure_mac.mm)
elseif(WIN32)
  target_sources(kweb_processes_tests PRIVATE tests/processes_structure_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_sources(kweb_processes_tests PRIVATE tests/processes_structure_linux.cc)
endif()
target_include_directories(kweb_processes_tests PRIVATE include src)
if(MSVC)
  target_compile_options(kweb_processes_tests PRIVATE /W4 /WX /utf-8 /EHsc /UNDEBUG)
else()
  target_compile_options(kweb_processes_tests PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG)
endif()
target_sources(kweb_processes_tests PRIVATE src/processes_abi.cc ${KWEB_PROCESSES_PLATFORM_SOURCE})
target_compile_definitions(kweb_processes_tests PRIVATE
  KWEB_PROCESSES_ABI_BUILD
  KWEB_PROCESSES_HELPER_PATH="$<TARGET_FILE:kweb_processes_helper>"
)
if(MSVC)
  target_link_libraries(kweb_processes_tests PRIVATE psapi)
else()
  find_package(Threads REQUIRED)
  target_link_libraries(kweb_processes_tests PRIVATE Threads::Threads)
endif()
if(APPLE)
  target_link_libraries(kweb_processes_tests PRIVATE "-framework CoreFoundation")
endif()
set_target_properties(kweb_processes_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_processes_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_processes_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_processes_tests COMMAND kweb_processes_tests)
set_tests_properties(kweb_processes_tests PROPERTIES
  LABELS "abi;native;processes"
  TIMEOUT 300
)
