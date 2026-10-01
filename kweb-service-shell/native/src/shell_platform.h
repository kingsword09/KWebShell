#ifndef KWEB_SHELL_PLATFORM_H_
#define KWEB_SHELL_PLATFORM_H_

#include "kweb_shell.h"
#include <string>

namespace kwebshell::shell {
const char *ProviderId();
kweb_shell_status ExecutePlatform(uint32_t action, uint32_t resource_kind,
                                  const std::string &value, uint32_t *outcome);
}  // namespace kwebshell::shell

#endif
