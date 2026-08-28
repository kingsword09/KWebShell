#include "app_paths_platform.h"

#include <cstdlib>
#include <fstream>
#include <optional>
#include <string>
#include <string_view>

namespace kwebshell::services {
namespace {

std::optional<std::filesystem::path>
EnvironmentPath(const char *name, const std::filesystem::path &home,
                const char *default_suffix) {
  const char *value = std::getenv(name);
  if (value != nullptr && *value != '\0') {
    std::filesystem::path path = PathFromUtf8(value);
    if (!path.is_absolute()) {
      return std::nullopt;
    }
    return path;
  }
  return (home / default_suffix).lexically_normal();
}

std::optional<std::filesystem::path> HomePath() {
  const char *value = std::getenv("HOME");
  if (value == nullptr || *value == '\0') {
    return std::nullopt;
  }
  std::filesystem::path home = PathFromUtf8(value);
  return home.is_absolute() ? std::optional(home) : std::nullopt;
}

std::string Trim(std::string value) {
  const auto first = value.find_first_not_of(" \t\r\n");
  if (first == std::string::npos) {
    return {};
  }
  const auto last = value.find_last_not_of(" \t\r\n");
  return value.substr(first, last - first + 1);
}

std::optional<std::filesystem::path>
XdgUserDirectory(const std::filesystem::path &home, std::string_view variable,
                 std::string *source) {
  std::filesystem::path config;
  const char *config_home = std::getenv("XDG_CONFIG_HOME");
  if (config_home != nullptr && *config_home != '\0') {
    config = PathFromUtf8(config_home);
    if (!config.is_absolute()) {
      return std::nullopt;
    }
  } else {
    config = home / ".config";
  }
  std::ifstream file(config / "user-dirs.dirs");
  if (!file.is_open()) {
    return std::nullopt;
  }
  std::string line;
  const std::string prefix = std::string(variable) + "=";
  while (std::getline(file, line)) {
    line = Trim(std::move(line));
    if (line.rfind(prefix, 0) != 0) {
      continue;
    }
    std::string value = Trim(line.substr(prefix.size()));
    if (value.size() < 2 || value.front() != '"' || value.back() != '"') {
      return std::nullopt;
    }
    value = value.substr(1, value.size() - 2);
    const std::string home_token = "$HOME";
    if (value.rfind(home_token, 0) == 0) {
      value = home.string() + value.substr(home_token.size());
    }
    std::filesystem::path path = PathFromUtf8(value);
    if (!path.is_absolute()) {
      return std::nullopt;
    }
    if (source != nullptr) {
      *source = "linux.XDG.user-dirs." + std::string(variable).substr(4);
    }
    return path;
  }
  return std::nullopt;
}

bool Assign(PlatformPathResult *result, const std::filesystem::path &path,
            const char *source) {
  if (result == nullptr || source == nullptr || path.empty()) {
    return false;
  }
  result->path = path;
  result->source = source;
  return true;
}

} // namespace

bool ResolvePlatformPath(uint32_t kind, PlatformPathResult *result,
                         uint32_t *status) {
  if (result == nullptr || status == nullptr) {
    return false;
  }
  const auto home = HomePath();
  if (!home) {
    *status = KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE;
    return false;
  }
  bool resolved = false;
  switch (kind) {
  case KWEB_APP_PATH_HOME:
    resolved = Assign(result, *home, "linux.POSIX.HOME");
    break;
  case KWEB_APP_PATH_APP_DATA: {
    const auto path = EnvironmentPath("XDG_CONFIG_HOME", *home, ".config");
    resolved = path && Assign(result, *path, "linux.XDG_CONFIG_HOME");
    break;
  }
  case KWEB_APP_PATH_APP_CACHE: {
    const auto path = EnvironmentPath("XDG_CACHE_HOME", *home, ".cache");
    resolved = path && Assign(result, *path, "linux.XDG_CACHE_HOME");
    break;
  }
  case KWEB_APP_PATH_TEMP: {
    const char *tmp = std::getenv("TMPDIR");
    const std::filesystem::path path = tmp != nullptr && *tmp != '\0'
                                           ? PathFromUtf8(tmp)
                                           : PathFromUtf8("/tmp");
    resolved = path.is_absolute() && Assign(result, path,
                                            tmp != nullptr && *tmp != '\0'
                                                ? "linux.POSIX.TMPDIR"
                                                : "linux.POSIX.default-temp");
    break;
  }
  case KWEB_APP_PATH_DESKTOP:
  case KWEB_APP_PATH_DOCUMENTS:
  case KWEB_APP_PATH_DOWNLOADS:
  case KWEB_APP_PATH_MUSIC:
  case KWEB_APP_PATH_PICTURES:
  case KWEB_APP_PATH_VIDEOS: {
    const char *variable =
        kind == KWEB_APP_PATH_DESKTOP     ? "XDG_DESKTOP_DIR"
        : kind == KWEB_APP_PATH_DOCUMENTS ? "XDG_DOCUMENTS_DIR"
        : kind == KWEB_APP_PATH_DOWNLOADS ? "XDG_DOWNLOAD_DIR"
        : kind == KWEB_APP_PATH_MUSIC     ? "XDG_MUSIC_DIR"
        : kind == KWEB_APP_PATH_PICTURES  ? "XDG_PICTURES_DIR"
                                          : "XDG_VIDEOS_DIR";
    std::string source;
    const auto path = XdgUserDirectory(*home, variable, &source);
    resolved = path && Assign(result, *path, source.c_str());
    break;
  }
  default:
    *status = KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN;
    return false;
  }
  *status = resolved ? KWEB_SERVICES_STATUS_OK
                     : KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE;
  return resolved;
}

} // namespace kwebshell::services
