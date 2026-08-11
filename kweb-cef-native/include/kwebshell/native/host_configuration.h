#ifndef KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_
#define KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_

#include <filesystem>
#include <optional>
#include <string>
#include <vector>

namespace kwebshell {

struct HostConfiguration final {
  std::filesystem::path root_cache_path;
  std::optional<std::filesystem::path> event_log_path;
  std::string url;
  int width = 800;
  int height = 600;
  bool self_test = false;

  static std::optional<HostConfiguration>
  Parse(const std::vector<std::string> &arguments, std::string *error);
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_HOST_CONFIGURATION_H_
