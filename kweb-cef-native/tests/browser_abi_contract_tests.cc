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
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PAGE_REQUEST_NOT_FOUND),
                    "page-request-not-found") == 0,
        "page request lookup status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PAGE_REQUEST_INVALID),
                    "page-request-invalid") == 0,
        "invalid page request status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PAGE_OPERATION_PENDING),
                    "page-operation-pending") == 0,
        "pending page operation status must have a stable name");
  Check(kweb_browser_create(nullptr, nullptr) == KWEB_STATUS_INVALID_ARGUMENT,
        "null browser create arguments must fail immediately");
  Check(kweb_browser_navigate(KWEB_INVALID_BROWSER_HANDLE, nullptr, 0) ==
            KWEB_STATUS_NAVIGATION_INVALID,
        "empty browser URL must fail before handle lookup");
  Check(kweb_browser_reload(KWEB_INVALID_BROWSER_HANDLE, 2) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "invalid reload mode must fail before handle lookup");
  Check(kweb_browser_reload(KWEB_INVALID_BROWSER_HANDLE, 0) ==
            KWEB_STATUS_INVALID_HANDLE,
        "reload must reject a stale handle");
  Check(kweb_browser_before_unload_respond(KWEB_INVALID_BROWSER_HANDLE, 0, 0) ==
            KWEB_STATUS_PAGE_REQUEST_INVALID,
        "zero before-unload request id must fail before handle lookup");
  Check(kweb_browser_popup_respond(KWEB_INVALID_BROWSER_HANDLE, 0, 0) ==
            KWEB_STATUS_PAGE_REQUEST_INVALID,
        "zero popup request id must fail before handle lookup");
  Check(kweb_browser_before_unload_respond(KWEB_INVALID_BROWSER_HANDLE, 1, 2) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "invalid before-unload decision must fail before handle lookup");
  Check(kweb_browser_popup_respond(KWEB_INVALID_BROWSER_HANDLE, 1, 2) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "invalid popup decision must fail before handle lookup");
  Check(kweb_browser_set_bounds(KWEB_INVALID_BROWSER_HANDLE, 0, 0, 0, 600) ==
            KWEB_STATUS_INVALID_DIMENSIONS,
        "invalid browser dimensions must fail before handle lookup");
  Check(kweb_browser_set_bounds(KWEB_INVALID_BROWSER_HANDLE, -1, 0, 800, 600) ==
            KWEB_STATUS_INVALID_DIMENSIONS,
        "invalid browser coordinates must fail before handle lookup");
  Check(kweb_browser_set_surface_state(KWEB_INVALID_BROWSER_HANDLE, 2, 0) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "invalid browser visibility must fail before handle lookup");
  Check(kweb_browser_set_bounds(KWEB_INVALID_BROWSER_HANDLE, 0, 0, 800, 600) ==
            KWEB_STATUS_INVALID_HANDLE,
        "valid browser bounds must reject a stale handle");
  Check(kweb_browser_set_surface_state(KWEB_INVALID_BROWSER_HANDLE, 1, 0) ==
            KWEB_STATUS_INVALID_HANDLE,
        "valid browser surface state must reject a stale handle");
  Check(kweb_browser_close(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale browser close must return invalid handle");
  Check(kweb_browser_open_devtools(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale DevTools open must return invalid handle");
  Check(kweb_browser_close_devtools(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale DevTools close must return invalid handle");
  Check(kweb_browser_crash_renderer(KWEB_INVALID_BROWSER_HANDLE) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale renderer crash must return invalid handle");
  Check(kweb_browser_bridge_respond(KWEB_INVALID_BROWSER_HANDLE, 1, "{}", 2) ==
            KWEB_STATUS_INVALID_HANDLE,
        "stale bridge response must return invalid handle");
  Check(kweb_browser_bridge_fail(KWEB_INVALID_BROWSER_HANDLE, 1, "not-json",
                                 8) == KWEB_STATUS_INVALID_HANDLE,
        "stale bridge failure must return invalid handle before CEF parsing");
  Check(kweb_browser_security_respond(KWEB_INVALID_BROWSER_HANDLE, 0, "{}", 2) ==
            KWEB_STATUS_SECURITY_CHALLENGE_DECISION_INVALID,
        "security response must reject a zero request id before handle lookup");
  Check(kweb_browser_security_respond(KWEB_INVALID_BROWSER_HANDLE, 1, nullptr, 0) ==
            KWEB_STATUS_SECURITY_CHALLENGE_DECISION_INVALID,
        "security response must reject an empty decision before handle lookup");
  Check(kweb_browser_security_respond(KWEB_INVALID_BROWSER_HANDLE, 1, "{}", 2) ==
            KWEB_STATUS_INVALID_HANDLE,
        "valid security response must reject a stale browser handle");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_SECURITY_CHALLENGE_NOT_FOUND),
                    "security-challenge-not-found") == 0,
        "security challenge status names must be stable");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_FIND_SESSION_STALE),
                    "find-session-stale") == 0,
        "stale find session status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_ZOOM_LEVEL_INVALID),
                    "zoom-level-invalid") == 0,
        "invalid page zoom status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PDF_EXPORT_INVALID),
                    "pdf-export-invalid") == 0,
        "invalid PDF settings status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PDF_EXPORT_ACTIVE),
                    "pdf-export-active") == 0,
        "active PDF export status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PDF_EXPORT_FAILED),
                    "pdf-export-failed") == 0,
        "failed PDF export status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PDF_PATH_INVALID),
                    "pdf-path-invalid") == 0,
        "invalid PDF path status must have a stable name");
  Check(std::strcmp(kweb_status_name(KWEB_STATUS_PAGE_CONTENT_INVALID),
                    "page-content-invalid") == 0,
        "invalid page content status must have a stable name");
  {
    Check(kweb_browser_page_content(KWEB_INVALID_BROWSER_HANDLE, 0,
                                    KWEB_PAGE_CONTENT_SOURCE, 1024) ==
              KWEB_STATUS_PAGE_CONTENT_INVALID,
          "a content read without a request id must fail before browser lookup");
    Check(kweb_browser_page_content(KWEB_INVALID_BROWSER_HANDLE, 1, 7, 1024) ==
              KWEB_STATUS_PAGE_CONTENT_INVALID,
          "an unknown content kind must fail before browser lookup");
    Check(kweb_browser_page_content(KWEB_INVALID_BROWSER_HANDLE, 1,
                                    KWEB_PAGE_CONTENT_TEXT, 0) ==
              KWEB_STATUS_PAGE_CONTENT_INVALID,
          "an empty content bound must fail before browser lookup");
    Check(kweb_browser_page_content(KWEB_INVALID_BROWSER_HANDLE, 1,
                                    KWEB_PAGE_CONTENT_TEXT,
                                    KWEB_PAGE_CONTENT_MAXIMUM_BYTES + 1) ==
              KWEB_STATUS_PAGE_CONTENT_INVALID,
          "a content bound above the transfer ceiling must fail before browser lookup");
    Check(kweb_browser_page_content(KWEB_INVALID_BROWSER_HANDLE, 1,
                                    KWEB_PAGE_CONTENT_TEXT, 1024) ==
              KWEB_STATUS_INVALID_HANDLE,
          "a valid content read must require a live browser");
  }
  {
    const char *settings =
        "{\"version\":1,\"paperWidthInches\":8.5,\"paperHeightInches\":11,"
        "\"landscape\":false,\"printBackground\":false,\"scale\":1,"
        "\"marginType\":0,\"displayHeaderFooter\":false,"
        "\"headerTemplate\":\"\",\"footerTemplate\":\"\","
        "\"pageRanges\":\"\",\"preferCssPageSize\":false}";
    Check(kweb_browser_export_pdf(KWEB_INVALID_BROWSER_HANDLE, 0, "/tmp/a.pdf",
                                 10, settings, std::strlen(settings)) ==
              KWEB_STATUS_PDF_EXPORT_INVALID,
          "a PDF export without a request id must fail before browser lookup");
    Check(kweb_browser_export_pdf(KWEB_INVALID_BROWSER_HANDLE, 1, nullptr, 0,
                                  settings, std::strlen(settings)) ==
              KWEB_STATUS_PDF_EXPORT_INVALID,
          "a PDF export without a path must fail before browser lookup");
    Check(kweb_browser_export_pdf(KWEB_INVALID_BROWSER_HANDLE, 1, "/tmp/a.pdf",
                                  10, settings, std::strlen(settings)) ==
              KWEB_STATUS_INVALID_HANDLE,
          "a valid PDF export must require a live browser");
  }
  {
    double observed = -1.0;
    Check(kweb_browser_find(KWEB_INVALID_BROWSER_HANDLE, 0, "text", 4,
                            KWEB_FIND_FORWARD, 0, KWEB_FIND_START) ==
              KWEB_STATUS_INVALID_ARGUMENT,
          "a find request without a session id must fail before browser lookup");
    Check(kweb_browser_find(KWEB_INVALID_BROWSER_HANDLE, 1, "", 0,
                            KWEB_FIND_FORWARD, 0, KWEB_FIND_START) ==
              KWEB_STATUS_INVALID_ARGUMENT,
          "an empty find query must fail before browser lookup");
    Check(kweb_browser_find(KWEB_INVALID_BROWSER_HANDLE, 1, "x", 1,
                            KWEB_FIND_FORWARD, 0, 7) ==
              KWEB_STATUS_INVALID_ARGUMENT,
          "an unknown find mode must fail before browser lookup");
    Check(kweb_browser_find(KWEB_INVALID_BROWSER_HANDLE, 1, "text", 4,
                            KWEB_FIND_FORWARD, 0, KWEB_FIND_START) ==
              KWEB_STATUS_INVALID_HANDLE,
          "a valid find request must require a live browser");
    Check(kweb_browser_stop_finding(KWEB_INVALID_BROWSER_HANDLE, 7) ==
              KWEB_STATUS_INVALID_ARGUMENT,
          "an unknown find stop mode must fail before browser lookup");
    Check(kweb_browser_set_zoom_level(KWEB_INVALID_BROWSER_HANDLE, 1.0,
                                      nullptr) == KWEB_STATUS_ZOOM_LEVEL_INVALID,
          "a zoom request without an output level must fail");
    Check(kweb_browser_set_zoom_level(KWEB_INVALID_BROWSER_HANDLE, 0.0,
                                      &observed) == KWEB_STATUS_INVALID_HANDLE,
          "a valid zoom request must require a live browser");
    Check(kweb_browser_zoom_level(KWEB_INVALID_BROWSER_HANDLE, &observed) ==
              KWEB_STATUS_INVALID_HANDLE,
          "reading the page zoom must require a live browser");
  }
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
