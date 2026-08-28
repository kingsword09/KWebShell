#include "kwebshell/services/app_paths_abi.h"

#include <cstddef>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>

namespace {

void Require(bool condition) {
  if (!condition) {
    std::abort();
  }
}

kweb_services_string_view View(const std::string &value) {
  return {value.data(), value.size()};
}

void CheckAbsolute(const kweb_app_paths_result &result) {
  Require(result.path.data != nullptr);
  Require(result.path.size > 0);
  const std::filesystem::path path(
      std::string(result.path.data, result.path.size));
#if defined(_WIN32)
  Require(path.has_root_name() && path.has_root_directory());
#else
  Require(path.is_absolute());
#endif
  Require(result.source.data != nullptr);
  Require(result.source.size > 0);
}

} // namespace

int main() {
  Require(sizeof(kweb_services_string_view) == sizeof(void *) * 2);
  Require(alignof(kweb_services_string_view) == alignof(void *));
  Require(sizeof(kweb_app_paths_request) == 64);
  Require(sizeof(kweb_app_paths_result) == 48);
  Require(offsetof(kweb_app_paths_request, struct_size) == 0);
  Require(offsetof(kweb_app_paths_request, abi_version) == 4);
  Require(offsetof(kweb_app_paths_request, kind) == 8);
  Require(offsetof(kweb_app_paths_request, reserved) == 12);
  Require(offsetof(kweb_app_paths_request, application_id) == 16);
  Require(offsetof(kweb_app_paths_request, application_data_root) == 32);
  Require(offsetof(kweb_app_paths_request, session_data_root) == 48);
  Require(offsetof(kweb_app_paths_result, struct_size) == 0);
  Require(offsetof(kweb_app_paths_result, abi_version) == 4);
  Require(offsetof(kweb_app_paths_result, kind) == 8);
  Require(offsetof(kweb_app_paths_result, reserved) == 12);
  Require(offsetof(kweb_app_paths_result, path) == 16);
  Require(offsetof(kweb_app_paths_result, source) == 32);
  Require(kweb_services_abi_version() == KWEB_SERVICES_ABI_VERSION);
  Require(std::strcmp(kweb_services_status_name(KWEB_SERVICES_STATUS_OK),
                      "ok") == 0);
  Require(
      std::strcmp(kweb_services_status_name(KWEB_SERVICES_STATUS_NATIVE_FAILED),
                  "native-failed") == 0);

  const std::filesystem::path root =
      std::filesystem::temp_directory_path() / "kweb-services-app-paths-tests";
  const std::filesystem::path app = root / "app-data";
  const std::filesystem::path session = root / "session-data";
  std::filesystem::create_directories(app);
  std::filesystem::create_directories(session);
  const std::string application_id = "io.github.kwebshell.tests";
  const std::string app_root = app.string();
  const std::string session_root = session.string();

  for (uint32_t kind = KWEB_APP_PATH_HOME; kind <= KWEB_APP_PATH_VIDEOS;
       ++kind) {
    kweb_app_paths_request request{
        sizeof(kweb_app_paths_request),
        KWEB_SERVICES_ABI_VERSION,
        kind,
        0,
        View(application_id),
        View(app_root),
        View(session_root),
    };
    kweb_app_paths_result result{};
    const kweb_services_status status =
        kweb_app_paths_resolve(&request, &result);
    if (kind == KWEB_APP_PATH_DESKTOP || kind == KWEB_APP_PATH_DOCUMENTS ||
        kind == KWEB_APP_PATH_DOWNLOADS || kind == KWEB_APP_PATH_MUSIC ||
        kind == KWEB_APP_PATH_PICTURES || kind == KWEB_APP_PATH_VIDEOS) {
#if defined(__linux__)
      if (status == KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE) {
        kweb_app_paths_result_free(&result);
        continue;
      }
#endif
    }
    Require(status == KWEB_SERVICES_STATUS_OK);
    Require(result.kind == kind);
    CheckAbsolute(result);
    kweb_app_paths_result_free(&result);
    Require(result.path.data == nullptr && result.source.data == nullptr);
  }

  const std::string missing = (root / "not-created").string();
  kweb_app_paths_request configured{
      sizeof(kweb_app_paths_request),
      KWEB_SERVICES_ABI_VERSION,
      KWEB_APP_PATH_USER_DATA,
      0,
      View(application_id),
      View(missing),
      View(session_root),
  };
  kweb_app_paths_result configured_result{};
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_OK);
  CheckAbsolute(configured_result);
  kweb_app_paths_result_free(&configured_result);
  Require(!std::filesystem::exists(missing));

  configured.kind = 999;
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN);
  configured.kind = KWEB_APP_PATH_HOME;
  const std::string invalid_application_id = "io..invalid";
  configured.application_id = View(invalid_application_id);
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_INVALID_ARGUMENT);
  configured.application_id = View(application_id);
  const std::string invalid_relative_root = "relative/path";
  configured.application_data_root = View(invalid_relative_root);
  configured.kind = KWEB_APP_PATH_USER_DATA;
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_PATH_INVALID);
  configured.application_data_root = View(app_root);
  const char malformed_utf8[] = "\xC3\x28";
  configured.application_id = {malformed_utf8, sizeof(malformed_utf8) - 1};
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_INVALID_ARGUMENT);
  configured.application_id = View(application_id);
  configured.application_data_root = {malformed_utf8,
                                      sizeof(malformed_utf8) - 1};
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_INVALID_ARGUMENT);
  configured.application_data_root = View(app_root);
  configured.kind = KWEB_APP_PATH_HOME;
  configured.abi_version = 99;
  Require(kweb_app_paths_resolve(&configured, &configured_result) ==
          KWEB_SERVICES_STATUS_ABI_MISMATCH);

  kweb_app_paths_result_free(&configured_result);
  kweb_app_paths_result_free(&configured_result);

  std::filesystem::remove_all(root);
  return 0;
}
