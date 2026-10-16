cmake_minimum_required(VERSION 3.21)

project(kweb_service_secure_storage_native VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

if(APPLE)
  enable_language(OBJCXX)
  set(KWEB_SECURE_STORAGE_PLATFORM_SOURCE src/secure_storage_mac.mm)
elseif(WIN32)
  set(KWEB_SECURE_STORAGE_PLATFORM_SOURCE src/secure_storage_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(SECRET REQUIRED IMPORTED_TARGET libsecret-1)
  set(KWEB_SECURE_STORAGE_PLATFORM_SOURCE src/secure_storage_linux.cc)
else()
  message(FATAL_ERROR "KWebSecureStorage supports only Windows, macOS, and Linux.")
endif()

if(NOT DEFINED KWEB_PROJECT_ARCH OR KWEB_PROJECT_ARCH STREQUAL "")
  message(FATAL_ERROR "KWEB_PROJECT_ARCH must identify the configured desktop architecture.")
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

add_library(kweb_secure_storage_abi SHARED
  src/secure_storage_abi.cc
  ${KWEB_SECURE_STORAGE_PLATFORM_SOURCE}
)
target_include_directories(kweb_secure_storage_abi PRIVATE include src)
target_compile_definitions(kweb_secure_storage_abi PRIVATE KWEB_SECURE_STORAGE_ABI_BUILD)
if(MSVC)
  target_compile_options(kweb_secure_storage_abi PRIVATE /W4 /WX /utf-8 /EHsc)
else()
  target_compile_options(kweb_secure_storage_abi PRIVATE
    -Wall -Wextra -Wpedantic -Werror
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>
  )
endif()

if(APPLE)
  target_link_libraries(kweb_secure_storage_abi PRIVATE "-framework Foundation" "-framework Security")
elseif(WIN32)
  target_link_libraries(kweb_secure_storage_abi PRIVATE advapi32 crypt32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_secure_storage_abi PRIVATE PkgConfig::SECRET)
endif()

set_target_properties(kweb_secure_storage_abi PROPERTIES
  OUTPUT_NAME "kwebshell_secure_storage"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)

enable_testing()
add_executable(kweb_secure_storage_tests tests/secure_storage_tests.cc)
if(APPLE)
  target_sources(kweb_secure_storage_tests PRIVATE tests/secure_storage_structure_mac.mm)
elseif(WIN32)
  target_sources(kweb_secure_storage_tests PRIVATE tests/secure_storage_structure_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_sources(kweb_secure_storage_tests PRIVATE tests/secure_storage_structure_linux.cc)
endif()
target_include_directories(kweb_secure_storage_tests PRIVATE include src)
if(MSVC)
  target_compile_options(kweb_secure_storage_tests PRIVATE /W4 /WX /utf-8 /EHsc /UNDEBUG)
else()
  target_compile_options(kweb_secure_storage_tests PRIVATE -Wall -Wextra -Wpedantic -Werror -UNDEBUG
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
endif()
target_sources(kweb_secure_storage_tests PRIVATE src/secure_storage_abi.cc ${KWEB_SECURE_STORAGE_PLATFORM_SOURCE})
target_compile_definitions(kweb_secure_storage_tests PRIVATE KWEB_SECURE_STORAGE_ABI_BUILD)
if(APPLE)
  target_link_libraries(kweb_secure_storage_tests PRIVATE "-framework Foundation" "-framework Security")
elseif(WIN32)
  target_link_libraries(kweb_secure_storage_tests PRIVATE advapi32 crypt32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_secure_storage_tests PRIVATE PkgConfig::SECRET)
endif()
set_target_properties(kweb_secure_storage_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_secure_storage_tests PROPERTIES
    BUILD_RPATH "${KWEB_CONTRACT_OUTPUT_DIR}"
  )
elseif(UNIX)
  set_target_properties(kweb_secure_storage_tests PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()
add_test(NAME kweb_secure_storage_tests COMMAND kweb_secure_storage_tests)
set_tests_properties(kweb_secure_storage_tests PROPERTIES
  LABELS "abi;native;secure-storage"
  TIMEOUT 60
)
