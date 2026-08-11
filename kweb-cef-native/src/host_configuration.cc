#include "kwebshell/native/host_configuration.h"

#include <charconv>
#include <cstddef>
#include <limits>
#include <string_view>

namespace kwebshell {
namespace {

constexpr std::string_view kRootCachePrefix = "--kweb-root-cache-path=";
constexpr std::string_view kProfilePrefix = "--kweb-profile-path=";
constexpr std::string_view kEventLogPrefix = "--kweb-event-log-path=";
constexpr std::string_view kUrlPrefix = "--kweb-url=";
constexpr std::string_view kWidthPrefix = "--kweb-width=";
constexpr std::string_view kHeightPrefix = "--kweb-height=";
constexpr std::string_view kSelfTest = "--kweb-self-test";
constexpr std::string_view kProfileSelfTestPrefix = "--kweb-profile-self-test=";
constexpr std::string_view kProfileTestValuePrefix =
    "--kweb-profile-test-value=";
constexpr size_t kMaximumProfileTestValueSize = 128;

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

bool IsProfileTestValueCharacter(char value) {
  return (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z') ||
         (value >= '0' && value <= '9') || value == '.' || value == '_' ||
         value == '-';
}

bool IsValidProfileTestValue(std::string_view value) {
  if (value.empty() || value.size() > kMaximumProfileTestValueSize) {
    return false;
  }
  for (const char character : value) {
    if (!IsProfileTestValueCharacter(character)) {
      return false;
    }
  }
  return true;
}

bool IsChromiumDefaultProfileName(const std::filesystem::path &profile_path) {
  constexpr std::u8string_view kDefaultProfileName = u8"default";
  const std::u8string profile_name = profile_path.filename().u8string();
  if (profile_name.size() != kDefaultProfileName.size()) {
    return false;
  }
  for (size_t index = 0; index < profile_name.size(); ++index) {
    char8_t character = profile_name[index];
    if (character >= u8'A' && character <= u8'Z') {
      character += u8'a' - u8'A';
    }
    if (character != kDefaultProfileName[index]) {
      return false;
    }
  }
  return true;
}

std::optional<ProfileSelfTestMode>
ParseProfileSelfTestMode(std::string_view value) {
  if (value == "write") {
    return ProfileSelfTestMode::kWrite;
  }
  if (value == "read") {
    return ProfileSelfTestMode::kRead;
  }
  if (value == "expect-absent") {
    return ProfileSelfTestMode::kExpectAbsent;
  }
  return std::nullopt;
}

} // namespace

bool HostConfiguration::IsProfileSelfTest() const {
  return profile_self_test_mode != ProfileSelfTestMode::kNone;
}

bool HostConfiguration::IsAnySelfTest() const {
  return self_test || IsProfileSelfTest();
}

bool IsDirectProfileChild(const std::filesystem::path &root_cache_path,
                          const std::filesystem::path &profile_path) {
  if (root_cache_path.empty() || profile_path.empty() ||
      !root_cache_path.is_absolute() || !profile_path.is_absolute()) {
    return false;
  }
  const std::filesystem::path relative =
      profile_path.lexically_normal().lexically_relative(
          root_cache_path.lexically_normal());
  if (relative.empty() || relative == "." || relative == ".." ||
      relative.is_absolute()) {
    return false;
  }
  auto component = relative.begin();
  if (component == relative.end()) {
    return false;
  }
  ++component;
  return component == relative.end();
}

bool IsSupportedPersistentProfilePath(
    const std::filesystem::path &root_cache_path,
    const std::filesystem::path &profile_path) {
  return IsDirectProfileChild(root_cache_path, profile_path) &&
         !IsChromiumDefaultProfileName(profile_path);
}

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
    } else if (argument.starts_with(kProfilePrefix)) {
      configuration.profile_path =
          PathFromUtf8(ValueAfter(argument, kProfilePrefix));
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
    } else if (argument.starts_with(kProfileSelfTestPrefix)) {
      const auto mode = ParseProfileSelfTestMode(
          ValueAfter(argument, kProfileSelfTestPrefix));
      if (!mode) {
        *error = "--kweb-profile-self-test must be 'write', 'read', or "
                 "'expect-absent'.";
        return std::nullopt;
      }
      configuration.profile_self_test_mode = *mode;
    } else if (argument.starts_with(kProfileTestValuePrefix)) {
      configuration.profile_test_value =
          std::string(ValueAfter(argument, kProfileTestValuePrefix));
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
  if (configuration.profile_path.empty()) {
    *error = "--kweb-profile-path is required.";
    return std::nullopt;
  }
  if (!configuration.profile_path.is_absolute()) {
    *error = "--kweb-profile-path must be absolute.";
    return std::nullopt;
  }
  if (!IsSupportedPersistentProfilePath(configuration.root_cache_path,
                                        configuration.profile_path)) {
    *error = "--kweb-profile-path must be a direct child of "
             "--kweb-root-cache-path and must not use Chromium's reserved "
             "'Default' Profile name.";
    return std::nullopt;
  }
  if (configuration.event_log_path &&
      !configuration.event_log_path->is_absolute()) {
    *error = "--kweb-event-log-path must be absolute.";
    return std::nullopt;
  }
  if (configuration.self_test && configuration.IsProfileSelfTest()) {
    *error = "--kweb-self-test and --kweb-profile-self-test are mutually "
             "exclusive.";
    return std::nullopt;
  }
  if (configuration.IsProfileSelfTest()) {
    if (!IsValidProfileTestValue(configuration.profile_test_value)) {
      *error = "--kweb-profile-test-value must contain 1 to 128 ASCII letters, "
               "digits, '.', '_', or '-'.";
      return std::nullopt;
    }
  } else if (!configuration.profile_test_value.empty()) {
    *error = "--kweb-profile-test-value requires --kweb-profile-self-test.";
    return std::nullopt;
  }
  if (!configuration.IsAnySelfTest() && configuration.url.empty()) {
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
