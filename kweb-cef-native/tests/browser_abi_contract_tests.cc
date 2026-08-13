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
  if (failures != 0) {
    std::cerr << failures << " browser ABI assertion(s) failed." << std::endl;
    return 1;
  }
  std::cout << "All browser ABI contract tests passed." << std::endl;
  return 0;
}
