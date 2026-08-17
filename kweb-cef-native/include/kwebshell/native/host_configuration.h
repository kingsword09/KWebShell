#ifndef KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_
#define KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace kwebshell {

enum class ProfileSelfTestMode {
  kNone,
  kWrite,
  kRead,
  kExpectAbsent,
};

enum class Mv3CoreSelfTestMode {
  kNone,
  kInitial,
  kRestart,
  kIsolated,
  kOptions,
  kActionPopup,
  kContextMenu,
  kDevTools,
  kOffscreen,
};

struct HostConfiguration final {
  std::filesystem::path root_cache_path;
  std::filesystem::path profile_path;
  std::optional<std::filesystem::path> event_log_path;
  std::string url;
  std::string profile_test_value;
  std::filesystem::path mv3_extension_path;
  int width = 800;
  int height = 600;
  bool self_test = false;
  ProfileSelfTestMode profile_self_test_mode = ProfileSelfTestMode::kNone;
  Mv3CoreSelfTestMode mv3_core_self_test_mode = Mv3CoreSelfTestMode::kNone;

  bool IsProfileSelfTest() const;
  bool IsMv3CoreSelfTest() const;
  bool IsAnySelfTest() const;

  static std::optional<HostConfiguration>
  Parse(const std::vector<std::string> &arguments, std::string *error);
};

bool IsDirectProfileChild(const std::filesystem::path &root_cache_path,
                          const std::filesystem::path &profile_path);
bool IsSupportedPersistentProfilePath(
    const std::filesystem::path &root_cache_path,
    const std::filesystem::path &profile_path);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_
