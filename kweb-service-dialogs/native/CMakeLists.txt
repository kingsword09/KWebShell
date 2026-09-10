cmake_minimum_required(VERSION 3.21)
project(kweb_dialogs VERSION 0.1.0 LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
find_package(Threads REQUIRED)
if(APPLE)
  enable_language(OBJCXX)
  set(PLATFORM_SOURCE src/dialogs_mac.mm)
elseif(WIN32)
  set(PLATFORM_SOURCE src/dialogs_win.cc)
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(GIO REQUIRED IMPORTED_TARGET gio-2.0)
  set(PLATFORM_SOURCE src/dialogs_linux.cc)
else()
  message(FATAL_ERROR "KWebDialogs supports macOS, Windows, and Linux.")
endif()
add_library(kweb_dialogs SHARED src/dialogs_abi.cc ${PLATFORM_SOURCE})
target_include_directories(kweb_dialogs PUBLIC include PRIVATE src)
target_link_libraries(kweb_dialogs PRIVATE Threads::Threads)
if(MSVC)
  target_compile_options(kweb_dialogs PRIVATE /W4 /WX /utf-8 /EHsc)
  target_link_libraries(kweb_dialogs PRIVATE ole32 shell32 user32 uuid)
else()
  target_compile_options(kweb_dialogs PRIVATE -Wall -Wextra -Wpedantic -Werror)
endif()
if(APPLE)
  target_compile_options(kweb_dialogs PRIVATE $<$<COMPILE_LANGUAGE:OBJCXX>:-fobjc-arc>)
  target_link_libraries(kweb_dialogs PRIVATE "-framework Cocoa" "-framework UniformTypeIdentifiers")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  target_link_libraries(kweb_dialogs PRIVATE PkgConfig::GIO)
endif()
set_target_properties(kweb_dialogs PROPERTIES
  OUTPUT_NAME kwebshell_dialogs
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN YES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/contract"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/contract"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/contract"
)
enable_testing()
add_executable(kweb_dialogs_abi_test tests/abi_test.cc)
target_link_libraries(kweb_dialogs_abi_test PRIVATE kweb_dialogs)
set_target_properties(kweb_dialogs_abi_test PROPERTIES
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/contract"
  RUNTIME_OUTPUT_DIRECTORY_RELEASE "${CMAKE_BINARY_DIR}/contract"
)
add_test(NAME kweb_dialogs_abi_test COMMAND kweb_dialogs_abi_test)
set_tests_properties(kweb_dialogs_abi_test PROPERTIES TIMEOUT 15)
