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
  Check(kweb_live_browser_count() == 0,
        "browser ABI contract must not own a browser in its unit process");
  if (failures != 0) {
    std::cerr << failures << " browser ABI assertion(s) failed." << std::endl;
    return 1;
  }
  std::cout << "All browser ABI contract tests passed." << std::endl;
  return 0;
}
