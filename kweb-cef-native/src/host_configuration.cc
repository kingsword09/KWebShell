#include "kwebshell/native/host_configuration.h"

#include <charconv>
#include <limits>
#include <string_view>

namespace kwebshell {
namespace {

constexpr std::string_view kRootCachePrefix = "--kweb-root-cache-path=";
constexpr std::string_view kEventLogPrefix = "--kweb-event-log-path=";
constexpr std::string_view kUrlPrefix = "--kweb-url=";
constexpr std::string_view kWidthPrefix = "--kweb-width=";
constexpr std::string_view kHeightPrefix = "--kweb-height=";
constexpr std::string_view kSelfTest = "--kweb-self-test";

bool ParseDimension(std::string_view value, const char *name, int *output,
                    std::string *error) {
  int parsed = 0;
  const auto result =
      std::from_chars(value.data(), value.data() + value.size(), parsed);
  if (result.ec != std::errc() || result.ptr != value.data() + value.size() ||
      parsed < 200 || parsed > 8192) {
    *error = std::string(name) + " must be an integer between 200 and 8192.";
    return false;
  }
  *output = parsed;
  return true;
}

std::string_view ValueAfter(std::string_view argument,
                            std::string_view prefix) {
  return argument.substr(prefix.size());
}

std::filesystem::path PathFromUtf8(std::string_view value) {
  std::u8string encoded;
  encoded.reserve(value.size());
  for (const unsigned char byte : value) {
    encoded.push_back(static_cast<char8_t>(byte));
  }
  return std::filesystem::path(encoded);
}

} // namespace

std::optional<HostConfiguration>
HostConfiguration::Parse(const std::vector<std::string> &arguments,
                         std::string *error) {
  HostConfiguration configuration;

  for (const std::string &argument_string : arguments) {
    const std::string_view argument(argument_string);
    if (argument == kSelfTest) {
      configuration.self_test = true;
    } else if (argument.starts_with(kRootCachePrefix)) {
      configuration.root_cache_path =
          PathFromUtf8(ValueAfter(argument, kRootCachePrefix));
    } else if (argument.starts_with(kEventLogPrefix)) {
      configuration.event_log_path =
          PathFromUtf8(ValueAfter(argument, kEventLogPrefix));
    } else if (argument.starts_with(kUrlPrefix)) {
      configuration.url = std::string(ValueAfter(argument, kUrlPrefix));
    } else if (argument.starts_with(kWidthPrefix)) {
      if (!ParseDimension(ValueAfter(argument, kWidthPrefix), "kweb-width",
                          &configuration.width, error)) {
        return std::nullopt;
      }
    } else if (argument.starts_with(kHeightPrefix)) {
      if (!ParseDimension(ValueAfter(argument, kHeightPrefix), "kweb-height",
                          &configuration.height, error)) {
        return std::nullopt;
      }
    } else if (argument.starts_with("--kweb-")) {
      *error = "Unknown KWebShell argument: " + argument_string;
      return std::nullopt;
    }
  }

  if (configuration.root_cache_path.empty()) {
    *error = "--kweb-root-cache-path is required.";
    return std::nullopt;
  }
  if (!configuration.root_cache_path.is_absolute()) {
    *error = "--kweb-root-cache-path must be absolute.";
    return std::nullopt;
  }
  if (configuration.event_log_path &&
      !configuration.event_log_path->is_absolute()) {
    *error = "--kweb-event-log-path must be absolute.";
    return std::nullopt;
  }
  if (!configuration.self_test && configuration.url.empty()) {
    *error = "--kweb-url is required outside self-test mode.";
    return std::nullopt;
  }
  if (configuration.url.find('\0') != std::string::npos) {
    *error = "--kweb-url contains a null byte.";
    return std::nullopt;
  }

  return configuration;
}

} // namespace kwebshell
