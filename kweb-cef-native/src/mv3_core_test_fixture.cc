#include "mv3_core_test_fixture.h"

#include <array>
#include <system_error>

namespace kwebshell {

std::optional<std::filesystem::path>
ValidateMv3CoreTestFixture(const std::filesystem::path &requested_path,
                           std::string &error) {
  error.clear();
  std::error_code filesystem_error;
  const std::filesystem::path canonical_path =
      std::filesystem::canonical(requested_path, filesystem_error);
  if (filesystem_error) {
    error = "MV3 core fixture path cannot be canonicalized: " +
            filesystem_error.message();
    return std::nullopt;
  }

  const std::filesystem::file_status root_status =
      std::filesystem::status(canonical_path, filesystem_error);
  if (filesystem_error || !std::filesystem::is_directory(root_status)) {
    error = filesystem_error
                ? "MV3 core fixture directory cannot be inspected: " +
                      filesystem_error.message()
                : "MV3 core fixture path must identify a directory.";
    return std::nullopt;
  }
  if (canonical_path.native().find(
          static_cast<std::filesystem::path::value_type>(',')) !=
      std::filesystem::path::string_type::npos) {
    error = "MV3 core fixture path cannot contain ','.";
    return std::nullopt;
  }

  constexpr std::array<const char *, 11> kRequiredFiles = {
      "manifest.json",    "worker.js",
      "content.js",       "options.html",
      "options.js",       "popup.html",
      "popup.js",         "devtools.html",
      "devtools.js",      "devtools-panel.html",
      "devtools-panel.js"};
  for (const char *required_file : kRequiredFiles) {
    const std::filesystem::path file_path = canonical_path / required_file;
    const std::filesystem::file_status file_status =
        std::filesystem::symlink_status(file_path, filesystem_error);
    if (filesystem_error || !std::filesystem::is_regular_file(file_status)) {
      error = filesystem_error
                  ? "MV3 core fixture file cannot be inspected ('" +
                        std::string(required_file) + "'): " +
                        filesystem_error.message()
                  : "MV3 core fixture requires a regular '" +
                        std::string(required_file) + "' file.";
      return std::nullopt;
    }
  }
  return canonical_path;
}

} // namespace kwebshell
