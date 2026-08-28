cmake_minimum_required(VERSION 3.21)

project(kweb_service_app_paths_native VERSION 0.1.0 LANGUAGES C CXX)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_APP_PATHS_PLATFORM_SOURCE src/app_paths_platform_mac.mm)
elseif(WIN32)
  set(KWEB_APP_PATHS_PLATFORM_SOURCE src/app_paths_platform_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  set(KWEB_APP_PATHS_PLATFORM_SOURCE src/app_paths_platform_linux.cc)
else()
  message(FATAL_ERROR "KWebAppPaths supports only Windows, macOS, and Linux hosts.")
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
    target_compile_options(${target} PRIVATE -Wall -Wextra -Wpedantic -Werror)
  endif()
endfunction()

add_library(kweb_services_abi SHARED
  src/app_paths_abi.cc
  ${KWEB_APP_PATHS_PLATFORM_SOURCE}
)
KWEB_CONFIGURE_TARGET(kweb_services_abi)
target_compile_definitions(kweb_services_abi PRIVATE KWEB_SERVICES_ABI_BUILD)
if(APPLE)
  target_compile_options(kweb_services_abi PRIVATE
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
  target_link_options(kweb_services_abi PRIVATE
    "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/resources/services_exports_mac.txt"
  )
  target_link_libraries(kweb_services_abi PRIVATE "-framework Foundation")
elseif(WIN32)
  target_link_libraries(kweb_services_abi PRIVATE shell32 ole32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_options(kweb_services_abi PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/resources/services_exports_linux.map"
  )
endif()
set_target_properties(kweb_services_abi PROPERTIES
  OUTPUT_NAME "kwebshell_services"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_service_app_paths_tests tests/app_paths_tests.cc)
KWEB_CONFIGURE_TARGET(kweb_service_app_paths_tests)
target_link_libraries(kweb_service_app_paths_tests PRIVATE kweb_services_abi)
set_target_properties(kweb_service_app_paths_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_service_app_paths_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_service_app_paths_tests PROPERTIES
    BUILD_RPATH "\$ORIGIN"
  )
endif()
add_test(NAME kweb_service_app_paths_tests COMMAND kweb_service_app_paths_tests)
set_tests_properties(kweb_service_app_paths_tests PROPERTIES
  LABELS "abi;native;paths;services"
  TIMEOUT 30
)
