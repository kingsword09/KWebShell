#ifndef KWEBSHELL_SERVICES_APP_PATHS_PLATFORM_H_
#define KWEBSHELL_SERVICES_APP_PATHS_PLATFORM_H_

#include <cstdint>
#include <filesystem>
#include <string>
#include <string_view>

#include "kwebshell/services/app_paths_abi.h"

namespace kwebshell::services {

inline std::filesystem::path PathFromUtf8(std::string_view value) {
  const auto *data = reinterpret_cast<const char8_t *>(value.data());
  return std::filesystem::path(std::u8string(data, value.size()));
}

struct PlatformPathResult {
  std::filesystem::path path;
  std::string source;
};

bool ResolvePlatformPath(uint32_t kind, PlatformPathResult *result,
                         uint32_t *status);

} // namespace kwebshell::services

#endif // KWEBSHELL_SERVICES_APP_PATHS_PLATFORM_H_
