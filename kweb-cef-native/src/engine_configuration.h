#ifndef KWEBSHELL_NATIVE_ENGINE_CONFIGURATION_H_
#define KWEBSHELL_NATIVE_ENGINE_CONFIGURATION_H_

#include <filesystem>

#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

struct ValidatedEngineConfiguration final {
  std::filesystem::path cef_runtime_path;
  std::filesystem::path browser_subprocess_path;
  std::filesystem::path resources_path;
  std::filesystem::path locales_path;
  std::filesystem::path root_cache_path;
  std::filesystem::path log_path;
  std::filesystem::path framework_dir_path;
  std::filesystem::path main_bundle_path;
};

kweb_status
ValidateEngineConfiguration(const kweb_engine_config &config,
                            ValidatedEngineConfiguration *validated_out);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_ENGINE_CONFIGURATION_H_
