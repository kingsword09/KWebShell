#include "kwebshell/native/engine_abi.h"

#include <cstring>
#include <iostream>
#include <string>

namespace {

int failures = 0;

void Check(bool condition, const char *message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << std::endl;
    ++failures;
  }
}

void KWEB_ABI_CALL IgnoreExtensionResult(void *,
                                         const kweb_extension_result *) {}

} // namespace

int main() {
  Check(kweb_engine_abi_version() == KWEB_ABI_VERSION,
        "engine ABI version must match the header");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PROFILE_PATH_INVALID),
                    "profile-path-invalid") == 0,
        "browser Profile status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_BROWSER_CLOSING),
                    "browser-closing") == 0,
        "browser closing status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE),
                    "remote-debugging-port-unavailable") == 0,
        "remote debugging port collision status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_DEVTOOLS_ALREADY_OPEN),
                    "devtools-already-open") == 0,
        "DevTools duplicate-open status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_DEVTOOLS_NOT_OPEN),
                    "devtools-not-open") == 0,
        "DevTools missing-close status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_DEVTOOLS_OPEN_FAILED),
                    "devtools-open-failed") == 0,
        "DevTools open failure status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_DEVTOOLS_CLOSING),
                    "devtools-closing") == 0,
        "DevTools closing status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_BRIDGE_ORIGIN_INVALID),
                    "bridge-origin-invalid") == 0,
        "bridge origin status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_BRIDGE_REQUEST_NOT_FOUND),
                    "bridge-request-not-found") == 0,
        "bridge request lookup status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_BRIDGE_RESPONSE_INVALID),
                    "bridge-response-invalid") == 0,
        "bridge response validation status must have a stable name");
  Check(std::strcmp(
            kweb_status_name(KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISSING),
            "extension-runtime-abi-missing") == 0,
        "missing extension adapter status must have a stable name");
  Check(std::strcmp(
            kweb_status_name(KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISMATCH),
            "extension-runtime-abi-mismatch") == 0,
        "extension adapter mismatch status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_EXTENSION_OPERATION_INVALID),
                    "extension-operation-invalid") == 0,
        "invalid extension operation status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_EXTENSION_OPERATION_ACTIVE),
                    "extension-operation-active") == 0,
        "active extension operation status must have a stable name");
  Check(std::strcmp(
            kweb_status_name(KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND),
            "extension-operation-not-found") == 0,
        "missing extension operation status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_EXTENSION_RESULT_INVALID),
                    "extension-result-invalid") == 0,
        "invalid extension result status must have a stable name");
  Check(kweb_browser_create(nullptr, nullptr) == KWEB_STATUS_INVALID_ARGUMENT,
        "null browser create arguments must fail immediately");
  Check(kweb_browser_navigate(KWEB_INVALID_BROWSER_HANDLE, nullptr, 0) ==
            KWEB_STATUS_NAVIGATION_INVALID,
        "empty browser URL must fail before handle lookup");
  Check(kweb_browser_resize(KWEB_INVALID_BROWSER_HANDLE, 0, 600) ==
            KWEB_STATUS_INVALID_DIMENSIONS,
        "invalid browser dimensions must fail before handle lookup");
  Check(kweb_browser_close(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale browser close must return invalid handle");
  Check(kweb_browser_open_devtools(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale DevTools open must return invalid handle");
  Check(kweb_browser_close_devtools(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale DevTools close must return invalid handle");
  Check(kweb_browser_bridge_respond(KWEB_INVALID_BROWSER_HANDLE, 1, "{}", 2) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale bridge response must return invalid handle");
  Check(kweb_browser_bridge_fail(KWEB_INVALID_BROWSER_HANDLE, 1, "not-json",
                                 8) == KWEB_STATUS_INVALID_HANDLE,
        "stale bridge failure must return invalid handle before CEF parsing");
  Check(kweb_live_browser_count() == 0,
        "browser ABI contract must not own a browser in its unit process");
  kweb_extension_operation_handle extension_operation = 99;
  Check(kweb_extension_start(KWEB_INVALID_BROWSER_HANDLE, nullptr,
                             &extension_operation) ==
            KWEB_STATUS_EXTENSION_OPERATION_INVALID &&
            extension_operation == KWEB_INVALID_EXTENSION_OPERATION_HANDLE,
        "null extension config must fail and clear the output handle");
  const kweb_extension_config wrong_abi = {
      sizeof(kweb_extension_config),
      KWEB_ABI_VERSION + 1,
      KWEB_EXTENSION_OPERATION_QUERY,
      0,
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {nullptr, 0},
      {nullptr, 0},
      &IgnoreExtensionResult,
      nullptr,
  };
  Check(kweb_extension_start(KWEB_INVALID_BROWSER_HANDLE, &wrong_abi,
                             &extension_operation) == KWEB_STATUS_ABI_MISMATCH,
        "extension config ABI mismatch must fail before browser lookup");
  const kweb_extension_config valid_query = {
      sizeof(kweb_extension_config),
      KWEB_ABI_VERSION,
      KWEB_EXTENSION_OPERATION_QUERY,
      0,
      {"abcdefghijklmnopabcdefghijklmnop", 32},
      {nullptr, 0},
      {nullptr, 0},
      &IgnoreExtensionResult,
      nullptr,
  };
  Check(kweb_extension_start(KWEB_INVALID_BROWSER_HANDLE, &valid_query,
                             &extension_operation) == KWEB_STATUS_INVALID_HANDLE,
        "valid extension query must require a live browser");
  Check(kweb_extension_cancel(KWEB_INVALID_EXTENSION_OPERATION_HANDLE) ==
            KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND,
        "invalid extension cancel handle must fail explicitly");
  Check(kweb_live_extension_operation_count() == 0,
        "ABI contract process must not own an extension operation");
  if (failures != 0) {
    std::cerr << failures << " browser ABI assertion(s) failed." << std::endl;
    return 1;
  }
  std::cout << "All browser ABI contract tests passed." << std::endl;
  return 0;
}
