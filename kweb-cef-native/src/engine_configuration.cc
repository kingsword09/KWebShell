#include "engine_configuration.h"

#include <algorithm>
#include <cctype>
#include <fstream>
#include <optional>
#include <string>
#include <system_error>

#if !defined(_WIN32)
#include <unistd.h>
#endif

#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumPathSize = 32768;

std::optional<std::filesystem::path> ReadPath(kweb_string_view view,
                                              kweb_status *status_out) {
  if (view.data == nullptr || view.size == 0) {
    *status_out = KWEB_STATUS_PATH_REQUIRED;
    return std::nullopt;
  }
  if (view.size > kMaximumPathSize) {
    *status_out = KWEB_STATUS_TEXT_TOO_LARGE;
    return std::nullopt;
  }
  if (!IsValidUtf8(view.data, view.size)) {
    *status_out = KWEB_STATUS_INVALID_TEXT_ENCODING;
    return std::nullopt;
  }
  try {
#if defined(_WIN32)
    const auto *begin = reinterpret_cast<const char8_t *>(view.data);
    std::filesystem::path path(std::u8string(begin, begin + view.size));
#else
    std::filesystem::path path(std::string(view.data, view.size));
#endif
    if (!path.is_absolute()) {
      *status_out = KWEB_STATUS_PATH_NOT_ABSOLUTE;
      return std::nullopt;
    }
    return path.lexically_normal();
  } catch (...) {
    *status_out = KWEB_STATUS_PATH_TYPE_INVALID;
    return std::nullopt;
  }
}

bool IsRegularFile(const std::filesystem::path &path) {
  std::error_code error;
  const bool result = std::filesystem::is_regular_file(path, error);
  return !error && result;
}

bool IsDirectory(const std::filesystem::path &path) {
  std::error_code error;
  const bool result = std::filesystem::is_directory(path, error);
  return !error && result;
}

std::optional<std::filesystem::path>
CanonicalPath(const std::filesystem::path &path) {
  std::error_code error;
  auto canonical = std::filesystem::canonical(path, error);
  if (error) {
    return std::nullopt;
  }
  return canonical;
}

bool SameCanonicalPath(const std::filesystem::path &left,
                       const std::filesystem::path &right) {
  const auto canonical_left = CanonicalPath(left);
  const auto canonical_right = CanonicalPath(right);
  return canonical_left && canonical_right &&
         *canonical_left == *canonical_right;
}

#if defined(__APPLE__)
bool IsPathWithin(const std::filesystem::path &parent,
                  const std::filesystem::path &child) {
  auto parent_iterator = parent.begin();
  auto child_iterator = child.begin();
  for (; parent_iterator != parent.end(); ++parent_iterator, ++child_iterator) {
    if (child_iterator == child.end() || *parent_iterator != *child_iterator) {
      return false;
    }
  }
  return child_iterator != child.end();
}
#endif

#if defined(_WIN32)
std::string AsciiLower(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](char character) {
    const auto byte = static_cast<unsigned char>(character);
    return static_cast<char>(std::tolower(byte));
  });
  return value;
}
#endif

bool HasExpectedFileName(const std::filesystem::path &path,
                         const std::string &expected) {
#if defined(_WIN32)
  return AsciiLower(path.filename().string()) == AsciiLower(expected);
#else
  return path.filename() == expected;
#endif
}

bool IsExecutable(const std::filesystem::path &path) {
#if defined(_WIN32)
  return IsRegularFile(path);
#else
  return IsRegularFile(path) && ::access(path.c_str(), X_OK) == 0;
#endif
}

kweb_status
ValidatePlatformLayout(const ValidatedEngineConfiguration &configuration,
                       ValidatedEngineConfiguration *validated_out) {
#if defined(__APPLE__)
  if (!HasExpectedFileName(configuration.cef_runtime_path,
                           "Chromium Embedded Framework")) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto framework = configuration.cef_runtime_path.parent_path();
  if (framework.extension() != ".framework") {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto expected_resources = framework / "Resources";
  if (!SameCanonicalPath(configuration.resources_path, expected_resources) ||
      !SameCanonicalPath(configuration.locales_path, expected_resources)) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto frameworks_directory = framework.parent_path();
  const auto contents_directory = frameworks_directory.parent_path();
  const auto application_bundle = contents_directory.parent_path();
  if (frameworks_directory.filename() != "Frameworks" ||
      contents_directory.filename() != "Contents" ||
      application_bundle.extension() != ".app") {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto canonical_frameworks = CanonicalPath(frameworks_directory);
  const auto canonical_subprocess =
      CanonicalPath(configuration.browser_subprocess_path);
  if (!canonical_frameworks || !canonical_subprocess ||
      !IsPathWithin(*canonical_frameworks, *canonical_subprocess)) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  if (!IsRegularFile(configuration.resources_path / "resources.pak") ||
      !IsRegularFile(configuration.resources_path / "icudtl.dat") ||
      !IsRegularFile(configuration.resources_path / "en.lproj" /
                     "locale.pak")) {
    return KWEB_STATUS_PATH_NOT_FOUND;
  }
  *validated_out = configuration;
  validated_out->framework_dir_path = framework;
  validated_out->main_bundle_path = application_bundle;
#elif defined(_WIN32)
  if (!HasExpectedFileName(configuration.cef_runtime_path, "libcef.dll")) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto runtime_directory = configuration.cef_runtime_path.parent_path();
  if (!SameCanonicalPath(configuration.resources_path, runtime_directory) ||
      !SameCanonicalPath(configuration.locales_path,
                         runtime_directory / "locales") ||
      !SameCanonicalPath(configuration.browser_subprocess_path.parent_path(),
                         runtime_directory)) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  if (!IsRegularFile(configuration.resources_path / "resources.pak") ||
      !IsRegularFile(configuration.resources_path / "icudtl.dat") ||
      !IsRegularFile(configuration.locales_path / "en-US.pak")) {
    return KWEB_STATUS_PATH_NOT_FOUND;
  }
  *validated_out = configuration;
#elif defined(__linux__)
  if (!HasExpectedFileName(configuration.cef_runtime_path, "libcef.so")) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  const auto runtime_directory = configuration.cef_runtime_path.parent_path();
  if (!SameCanonicalPath(configuration.resources_path, runtime_directory) ||
      !SameCanonicalPath(configuration.locales_path,
                         runtime_directory / "locales") ||
      !SameCanonicalPath(configuration.browser_subprocess_path.parent_path(),
                         runtime_directory)) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  if (!IsRegularFile(configuration.resources_path / "resources.pak") ||
      !IsRegularFile(configuration.resources_path / "icudtl.dat") ||
      !IsRegularFile(configuration.locales_path / "en-US.pak")) {
    return KWEB_STATUS_PATH_NOT_FOUND;
  }
  *validated_out = configuration;
#else
  (void)configuration;
  (void)validated_out;
  return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
#endif
  return KWEB_STATUS_OK;
}

} // namespace

kweb_status
ValidateEngineConfiguration(const kweb_engine_config &config,
                            ValidatedEngineConfiguration *validated_out) {
  if (validated_out == nullptr) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }

  kweb_status status = KWEB_STATUS_OK;
  auto cef_runtime = ReadPath(config.cef_runtime_path, &status);
  if (!cef_runtime) {
    return status;
  }
  auto subprocess = ReadPath(config.browser_subprocess_path, &status);
  if (!subprocess) {
    return status;
  }
  auto resources = ReadPath(config.resources_path, &status);
  if (!resources) {
    return status;
  }
  auto locales = ReadPath(config.locales_path, &status);
  if (!locales) {
    return status;
  }
  auto root_cache = ReadPath(config.root_cache_path, &status);
  if (!root_cache) {
    return status;
  }
  auto log = ReadPath(config.log_path, &status);
  if (!log) {
    return status;
  }

  if (!IsRegularFile(*cef_runtime) || !IsExecutable(*subprocess) ||
      !IsDirectory(*resources) || !IsDirectory(*locales) ||
      !IsDirectory(*root_cache)) {
    return KWEB_STATUS_PATH_NOT_FOUND;
  }
  if (std::filesystem::exists(*log) && !IsRegularFile(*log)) {
    return KWEB_STATUS_PATH_TYPE_INVALID;
  }
  const auto canonical_root = CanonicalPath(*root_cache);
  const auto canonical_log_parent = CanonicalPath(log->parent_path());
  if (!canonical_root || !canonical_log_parent ||
      *canonical_root != *canonical_log_parent) {
    return KWEB_STATUS_PATH_MISMATCH;
  }
  {
    std::ofstream log_stream(*log, std::ios::app);
    if (!log_stream.is_open()) {
      return KWEB_STATUS_PATH_NOT_WRITABLE;
    }
  }

  ValidatedEngineConfiguration configuration{
      *cef_runtime,    *subprocess, *resources, *locales,
      *canonical_root, *log,        {},         {}};
  return ValidatePlatformLayout(configuration, validated_out);
}

} // namespace kwebshell
