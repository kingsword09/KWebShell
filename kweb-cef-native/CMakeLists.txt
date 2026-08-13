cmake_minimum_required(VERSION 3.21)

project(kweb_cef_native VERSION 0.1.0 LANGUAGES C CXX)

if(APPLE)
  enable_language(OBJC)
  enable_language(OBJCXX)
endif()

if(NOT DEFINED CEF_ROOT OR CEF_ROOT STREQUAL "")
  message(FATAL_ERROR "CEF_ROOT must point to an extracted, verified CEF binary distribution.")
endif()

get_filename_component(CEF_ROOT "${CEF_ROOT}" ABSOLUTE)
if(NOT EXISTS "${CEF_ROOT}/cmake/FindCEF.cmake")
  message(FATAL_ERROR "CEF_ROOT does not contain cmake/FindCEF.cmake: ${CEF_ROOT}")
endif()

set(USE_SANDBOX OFF CACHE BOOL "Phase 1 host runs without the production sandbox." FORCE)
set(CMAKE_MODULE_PATH "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
if(MSVC)
  set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
endif()

option(
  KWEB_EXPECT_HARDWARE_GPU_UNAVAILABLE
  "Run the strict no-hardware-GPU contract instead of the positive GPU test."
  OFF
)

find_package(Threads REQUIRED)
find_package(JNI REQUIRED COMPONENTS AWT JVM)
if(WIN32)
  find_program(KWEB_DUMPBIN_EXECUTABLE dumpbin REQUIRED)
endif()

set(KWEB_CONTRACT_OUTPUT_DIR "${CMAKE_CURRENT_BINARY_DIR}/contract")

function(KWEB_CONFIGURE_CONTRACT_TARGET target)
  target_include_directories(${target} PRIVATE include)
  if(MSVC)
    target_compile_options(${target} PRIVATE
      /W4 /WX /utf-8
      $<$<COMPILE_LANGUAGE:CXX>:/EHsc>
    )
  else()
    target_compile_options(${target} PRIVATE
      -Wall -Wextra -Wpedantic -Werror
    )
  endif()
endfunction()

add_library(kweb_contract_support STATIC src/utf8_validation.cc)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_contract_support)
target_include_directories(kweb_contract_support PUBLIC src)
set_target_properties(kweb_contract_support PROPERTIES
  POSITION_INDEPENDENT_CODE ON
)

add_library(kweb_jni SHARED
  src/engine_jni_bridge.cc
  src/jni_bridge.cc
  src/jni_string.cc
)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_jni)
target_include_directories(kweb_jni PRIVATE src)
target_link_libraries(kweb_jni PRIVATE
  JNI::JNI
  JNI::AWT
  ${CMAKE_DL_LIBS}
)
set_target_properties(kweb_jni PROPERTIES
  OUTPUT_NAME "kwebshell_jni"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
if(APPLE)
  set_target_properties(kweb_jni PROPERTIES
    BUILD_RPATH "@loader_path"
  )
  target_link_libraries(kweb_jni PRIVATE
    "-framework Cocoa"
    "-framework QuartzCore"
  )
elseif(UNIX)
  set_target_properties(kweb_jni PROPERTIES
    BUILD_RPATH "$ORIGIN"
  )
endif()

add_subdirectory("${CEF_LIBCEF_DLL_WRAPPER_PATH}" libcef_dll_wrapper)

if(NOT APPLE)
  ADD_LOGICAL_TARGET("libcef_lib" "${CEF_LIB_DEBUG}" "${CEF_LIB_RELEASE}")
endif()

add_library(kweb_engine_configuration STATIC
  src/engine_configuration.cc
  src/remote_debugging_port.cc
)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_engine_configuration)
target_include_directories(kweb_engine_configuration PUBLIC include src)
target_link_libraries(kweb_engine_configuration PUBLIC kweb_contract_support)
if(WIN32)
  target_link_libraries(kweb_engine_configuration PUBLIC ws2_32)
endif()
set_target_properties(kweb_engine_configuration PROPERTIES
  POSITION_INDEPENDENT_CODE ON
)

if(APPLE)
  set(KWEB_ENGINE_PLATFORM_SOURCE src/engine_platform_mac.mm)
  set(KWEB_BROWSER_SURFACE_SOURCE src/browser_surface_mac.mm)
  set(KWEB_AWT_PARENT_SOURCE src/awt_parent_mac.mm)
else()
  set(KWEB_ENGINE_PLATFORM_SOURCE src/engine_platform_standard.cc)
  set(KWEB_BROWSER_SURFACE_SOURCE src/browser_surface.cc)
  set(KWEB_AWT_PARENT_SOURCE src/awt_parent.cc)
endif()

target_sources(kweb_jni PRIVATE ${KWEB_AWT_PARENT_SOURCE})

add_library(kweb_engine_abi SHARED
  src/bridge_protocol.cc
  src/browser_session.cc
  ${KWEB_BROWSER_SURFACE_SOURCE}
  src/engine_abi.cc
  ${KWEB_ENGINE_PLATFORM_SOURCE}
)
SET_LIBRARY_TARGET_PROPERTIES(kweb_engine_abi)
target_include_directories(kweb_engine_abi PRIVATE include src)
target_compile_definitions(kweb_engine_abi PRIVATE KWEB_ENGINE_ABI_BUILD)
target_link_libraries(kweb_engine_abi PRIVATE
  kweb_engine_configuration
  libcef_dll_wrapper
  ${CEF_STANDARD_LIBS}
  ${CMAKE_DL_LIBS}
)
if(MSVC)
  target_compile_options(kweb_engine_abi PRIVATE /EHsc)
else()
  target_compile_options(kweb_engine_abi PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-fexceptions -fthreadsafe-statics>
    $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc -fexceptions -fthreadsafe-statics>
  )
endif()
if(WIN32)
  target_link_libraries(kweb_engine_abi PRIVATE libcef_lib ws2_32)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(X11 REQUIRED)
  target_link_libraries(kweb_jni PRIVATE X11::X11)
  target_link_libraries(kweb_engine_abi PRIVATE X11::X11 libcef_lib)
endif()
if(APPLE)
  target_link_options(kweb_engine_abi PRIVATE
    "LINKER:-exported_symbols_list,${CMAKE_CURRENT_SOURCE_DIR}/resources/engine_exports_mac.txt"
  )
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_options(kweb_engine_abi PRIVATE
    "LINKER:--version-script=${CMAKE_CURRENT_SOURCE_DIR}/resources/engine_exports_linux.map"
  )
endif()
set_target_properties(kweb_engine_abi PROPERTIES
  OUTPUT_NAME "kwebshell_engine"
  VERSION "1.0.0"
  SOVERSION "1"
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  ARCHIVE_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  LIBRARY_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
add_dependencies(kweb_jni kweb_engine_abi)

add_library(kweb_host_support STATIC
  src/event_recorder.cc
  src/host_configuration.cc
  src/shutdown_watchdog.cc
)
target_include_directories(kweb_host_support PUBLIC include)

if(MSVC)
  SET_LIBRARY_TARGET_PROPERTIES(kweb_host_support)
  target_compile_options(kweb_host_support PRIVATE /EHsc)
else()
  target_compile_options(kweb_host_support PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()

enable_testing()
add_executable(kweb_engine_abi_header_c_test
  tests/engine_abi_header_c_test.c
)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_engine_abi_header_c_test)
set_target_properties(kweb_engine_abi_header_c_test PROPERTIES
  C_STANDARD 11
  C_STANDARD_REQUIRED ON
  C_EXTENSIONS OFF
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
add_test(NAME kweb_engine_abi_header_c_test
  COMMAND kweb_engine_abi_header_c_test
)
set_tests_properties(kweb_engine_abi_header_c_test PROPERTIES
  LABELS "abi;c;engine;header;native"
  TIMEOUT 10
)

add_executable(kweb_browser_abi_contract_tests
  tests/browser_abi_contract_tests.cc
)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_browser_abi_contract_tests)
target_link_libraries(kweb_browser_abi_contract_tests PRIVATE kweb_engine_abi)
set_target_properties(kweb_browser_abi_contract_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
add_test(NAME kweb_browser_abi_contract_tests
  COMMAND kweb_browser_abi_contract_tests
)
set_tests_properties(kweb_browser_abi_contract_tests PROPERTIES
  LABELS "abi;browser;contract;native"
  TIMEOUT 30
)

add_test(NAME kweb_engine_export_contract
  COMMAND ${CMAKE_COMMAND}
    "-DENGINE_LIBRARY=$<TARGET_FILE:kweb_engine_abi>"
    "-DPLATFORM=${CMAKE_SYSTEM_NAME}"
    "-DDUMPBIN=${KWEB_DUMPBIN_EXECUTABLE}"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/verify_engine_exports.cmake"
)
set_tests_properties(kweb_engine_export_contract PROPERTIES
  LABELS "abi;exports;native"
  TIMEOUT 30
)

add_executable(kweb_engine_configuration_tests
  tests/engine_configuration_tests.cc
)
KWEB_CONFIGURE_CONTRACT_TARGET(kweb_engine_configuration_tests)
target_include_directories(kweb_engine_configuration_tests PRIVATE src)
target_link_libraries(kweb_engine_configuration_tests PRIVATE
  kweb_engine_configuration
)
set_target_properties(kweb_engine_configuration_tests PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${KWEB_CONTRACT_OUTPUT_DIR}"
)
add_test(NAME kweb_engine_configuration_tests
  COMMAND kweb_engine_configuration_tests
)
set_tests_properties(kweb_engine_configuration_tests PROPERTIES
  LABELS "configuration;engine;native;paths"
  TIMEOUT 30
)

add_executable(kweb_host_unit_tests tests/host_unit_tests.cc)
target_link_libraries(kweb_host_unit_tests PRIVATE kweb_host_support)
if(MSVC)
  SET_EXECUTABLE_TARGET_PROPERTIES(kweb_host_unit_tests)
  target_compile_options(kweb_host_unit_tests PRIVATE /EHsc)
endif()
add_test(NAME kweb_host_unit_tests COMMAND kweb_host_unit_tests)

set(KWEB_HOST_COMMON_SOURCES
  src/bridge_protocol.cc
  src/bridge_renderer_app.cc
  src/browser_app.cc
  src/browser_client.cc
  src/host_main.cc
  src/profile_test_page.cc
  src/self_test_page.cc
)

set(KWEB_HOST_TARGET "kweb_cef_host")
SET_CEF_TARGET_OUT_DIR()
set(KWEB_TARGET_OUT_DIR "${CEF_TARGET_OUT_DIR}")

if(APPLE)
  set(KWEB_HELPER_TARGET "kweb_cef_host_Helper")
  set(KWEB_HELPER_OUTPUT_NAME "KWebShell Helper")
  set(KWEB_APP "${KWEB_TARGET_OUT_DIR}/KWebShell.app")

  set(EXECUTABLE_NAME "KWebShell")
  set(PRODUCT_NAME "KWebShell")
  set(VERSION_SHORT "${PROJECT_VERSION}")

  add_executable(${KWEB_HOST_TARGET} MACOSX_BUNDLE
    ${KWEB_HOST_COMMON_SOURCES}
    src/main_mac.mm
    src/native_window_mac.mm
  )
  SET_EXECUTABLE_TARGET_PROPERTIES(${KWEB_HOST_TARGET})
  target_include_directories(${KWEB_HOST_TARGET} PRIVATE include src)
  target_link_libraries(${KWEB_HOST_TARGET} PRIVATE
    kweb_host_support
    kweb_contract_support
    libcef_dll_wrapper
    ${CEF_STANDARD_LIBS}
  )
  target_compile_options(${KWEB_HOST_TARGET} PRIVATE
    $<$<COMPILE_LANGUAGE:OBJC,OBJCXX>:-fobjc-arc>
  )
  set_target_properties(${KWEB_HOST_TARGET} PROPERTIES
    OUTPUT_NAME "KWebShell"
    RUNTIME_OUTPUT_DIRECTORY "${KWEB_TARGET_OUT_DIR}"
    MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_SOURCE_DIR}/resources/mac/Info.plist.in"
    XCODE_ATTRIBUTE_PRODUCT_BUNDLE_IDENTIFIER "io.github.kingsword09.kwebshell.host"
  )

  COPY_MAC_FRAMEWORK(${KWEB_HOST_TARGET} "${CEF_BINARY_DIR}" "${KWEB_APP}")

  foreach(_suffix_list ${CEF_HELPER_APP_SUFFIXES})
    string(REPLACE ":" ";" _suffix_list "${_suffix_list}")
    list(GET _suffix_list 0 _name_suffix)
    list(GET _suffix_list 1 _target_suffix)
    list(GET _suffix_list 2 _plist_suffix)

    set(_helper_target "${KWEB_HELPER_TARGET}${_target_suffix}")
    set(_helper_output_name "${KWEB_HELPER_OUTPUT_NAME}${_name_suffix}")
    set(_helper_info_plist "${CMAKE_CURRENT_BINARY_DIR}/helper-Info${_target_suffix}.plist")

    file(READ "${CMAKE_CURRENT_SOURCE_DIR}/resources/mac/helper-Info.plist.in" _plist_contents)
    string(REPLACE "\${EXECUTABLE_NAME}" "${_helper_output_name}" _plist_contents "${_plist_contents}")
    string(REPLACE "\${PRODUCT_NAME}" "${_helper_output_name}" _plist_contents "${_plist_contents}")
    string(REPLACE "\${BUNDLE_ID_SUFFIX}" "${_plist_suffix}" _plist_contents "${_plist_contents}")
    string(REPLACE "\${VERSION_SHORT}" "${PROJECT_VERSION}" _plist_contents "${_plist_contents}")
    file(WRITE "${_helper_info_plist}" "${_plist_contents}")

    add_executable(${_helper_target} MACOSX_BUNDLE
      src/bridge_protocol.cc
      src/bridge_renderer_app.cc
      src/process_helper_mac.cc
    )
    SET_EXECUTABLE_TARGET_PROPERTIES(${_helper_target})
    target_include_directories(${_helper_target} PRIVATE include src "${CEF_ROOT}")
    target_link_libraries(${_helper_target} PRIVATE
      kweb_contract_support
      libcef_dll_wrapper
      ${CEF_STANDARD_LIBS}
    )
    set_target_properties(${_helper_target} PROPERTIES
      OUTPUT_NAME "${_helper_output_name}"
      RUNTIME_OUTPUT_DIRECTORY "${KWEB_TARGET_OUT_DIR}"
      MACOSX_BUNDLE_INFO_PLIST "${_helper_info_plist}"
    )

    add_dependencies(${KWEB_HOST_TARGET} ${_helper_target})
    add_custom_command(TARGET ${KWEB_HOST_TARGET} POST_BUILD
      COMMAND ${CMAKE_COMMAND} -E copy_directory
        "${KWEB_TARGET_OUT_DIR}/${_helper_output_name}.app"
        "${KWEB_APP}/Contents/Frameworks/${_helper_output_name}.app"
      VERBATIM
    )
  endforeach()
elseif(WIN32)
  add_executable(${KWEB_HOST_TARGET} WIN32
    ${KWEB_HOST_COMMON_SOURCES}
    src/main_win.cc
    src/native_window_win.cc
  )
  SET_EXECUTABLE_TARGET_PROPERTIES(${KWEB_HOST_TARGET})
  target_compile_options(${KWEB_HOST_TARGET} PRIVATE /EHsc)
  target_include_directories(${KWEB_HOST_TARGET} PRIVATE include src)
  target_link_libraries(${KWEB_HOST_TARGET} PRIVATE
    kweb_host_support
    kweb_contract_support
    libcef_lib
    libcef_dll_wrapper
    ${CEF_STANDARD_LIBS}
  )
  set_target_properties(${KWEB_HOST_TARGET} PROPERTIES
    OUTPUT_NAME "KWebShell"
    RUNTIME_OUTPUT_DIRECTORY "${KWEB_TARGET_OUT_DIR}"
  )

  COPY_FILES("${KWEB_HOST_TARGET}" "${CEF_BINARY_FILES}"
             "${CEF_BINARY_DIR}" "${KWEB_TARGET_OUT_DIR}")
  COPY_FILES("${KWEB_HOST_TARGET}" "${CEF_RESOURCE_FILES}"
             "${CEF_RESOURCE_DIR}" "${KWEB_TARGET_OUT_DIR}")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GTK3 REQUIRED IMPORTED_TARGET gtk+-3.0 gdk-x11-3.0)

  add_executable(${KWEB_HOST_TARGET}
    ${KWEB_HOST_COMMON_SOURCES}
    src/main_linux.cc
    src/native_window_linux.cc
  )
  SET_EXECUTABLE_TARGET_PROPERTIES(${KWEB_HOST_TARGET})
  target_include_directories(${KWEB_HOST_TARGET} PRIVATE include src)
  target_link_libraries(${KWEB_HOST_TARGET} PRIVATE
    kweb_host_support
    kweb_contract_support
    libcef_lib
    libcef_dll_wrapper
    PkgConfig::GTK3
    ${CEF_STANDARD_LIBS}
  )
  set_target_properties(${KWEB_HOST_TARGET} PROPERTIES
    OUTPUT_NAME "KWebShell"
    INSTALL_RPATH "$ORIGIN"
    BUILD_WITH_INSTALL_RPATH TRUE
    RUNTIME_OUTPUT_DIRECTORY "${KWEB_TARGET_OUT_DIR}"
  )

  COPY_FILES("${KWEB_HOST_TARGET}" "${CEF_BINARY_FILES}"
             "${CEF_BINARY_DIR}" "${KWEB_TARGET_OUT_DIR}")
  COPY_FILES("${KWEB_HOST_TARGET}" "${CEF_RESOURCE_FILES}"
             "${CEF_RESOURCE_DIR}" "${KWEB_TARGET_OUT_DIR}")
  if(EXISTS "${CEF_BINARY_DIR}/libminigbm.so")
    COPY_FILES("${KWEB_HOST_TARGET}" "libminigbm.so"
               "${CEF_BINARY_DIR}" "${KWEB_TARGET_OUT_DIR}")
  endif()
else()
  message(FATAL_ERROR
    "kweb-cef-native supports only Windows, macOS, and Linux hosts; got '${CMAKE_SYSTEM_NAME}'.")
endif()

if(UNIX AND NOT APPLE)
  find_program(KWEB_SELF_TEST_LAUNCHER NAMES xvfb-run REQUIRED)
else()
  set(KWEB_SELF_TEST_LAUNCHER "")
endif()

function(KWEB_ADD_NATIVE_SCRIPT_TEST test_name script_name timeout labels)
  add_test(
    NAME ${test_name}
    COMMAND ${CMAKE_COMMAND}
      "-DHOST_EXECUTABLE=$<TARGET_FILE:${KWEB_HOST_TARGET}>"
      "-DHOST_LAUNCHER=${KWEB_SELF_TEST_LAUNCHER}"
      "-DTEST_ROOT=${CMAKE_CURRENT_BINARY_DIR}/${test_name}"
      "-DPLATFORM=${CMAKE_SYSTEM_NAME}"
      ${ARGN}
      -P "${CMAKE_CURRENT_SOURCE_DIR}/tests/${script_name}"
  )
  set_tests_properties(${test_name} PROPERTIES
    LABELS "${labels}"
    RUN_SERIAL TRUE
    TIMEOUT ${timeout}
  )
endfunction()

if(KWEB_EXPECT_HARDWARE_GPU_UNAVAILABLE)
  KWEB_ADD_NATIVE_SCRIPT_TEST(
    kweb_gpu_unavailable_test
    run_gpu_unavailable_test.cmake
    90
    "cef;capability;failure;integration;native"
  )
else()
  KWEB_ADD_NATIVE_SCRIPT_TEST(
    kweb_native_self_test
    run_native_self_test.cmake
    90
    "cef;gpu;integration;native"
  )
endif()
KWEB_ADD_NATIVE_SCRIPT_TEST(
  kweb_profile_persistence_test
  run_profile_persistence_test.cmake
  240
  "cef;integration;native;profile;storage"
)
KWEB_ADD_NATIVE_SCRIPT_TEST(
  kweb_renderer_failure_test
  run_browser_failure_test.cmake
  60
  "cef;failure;integration;native"
  "-DFAILURE_URL=chrome://crash"
  "-DEXPECTED_ERROR_CODE=native.renderer.terminated"
  "-DEXPECTED_PROCESS_TYPE=renderer"
  "-DEXPECTED_PROCESS_LAUNCHES=1"
  "-DFAILURE_NAME=renderer"
)
KWEB_ADD_NATIVE_SCRIPT_TEST(
  kweb_gpu_failure_test
  run_browser_failure_test.cmake
  60
  "cef;failure;integration;native"
  "-DFAILURE_URL=chrome://gpucrash"
  "-DEXPECTED_ERROR_CODE=native.gpu.process-restarted"
  "-DEXPECTED_PROCESS_TYPE=gpu-process"
  "-DEXPECTED_PROCESS_LAUNCHES=2"
  "-DFAILURE_NAME=gpu-process"
)
