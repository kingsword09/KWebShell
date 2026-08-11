#ifndef KWEBSHELL_NATIVE_HOST_MAIN_H_
#define KWEBSHELL_NATIVE_HOST_MAIN_H_

#include <functional>
#include <string>
#include <vector>

#include "include/cef_app.h"

namespace kwebshell {

enum class HostExitCode : int {
  kSuccess = 0,
  kConfigurationError = 64,
  kEventLogError = 65,
  kCefInitializationError = 70,
  kBrowserRuntimeError = 71,
};

using PlatformInitializer = std::function<bool(std::string *error)>;

int RunBrowserProcess(const CefMainArgs &main_args,
                      const std::vector<std::string> &arguments,
                      void *sandbox_info,
                      PlatformInitializer platform_initializer = {});

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_HOST_MAIN_H_
