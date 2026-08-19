#ifndef KWEBSHELL_NATIVE_CEF_COMMAND_LINE_POLICY_H_
#define KWEBSHELL_NATIVE_CEF_COMMAND_LINE_POLICY_H_

#include <string>
#include <string_view>

#include "include/cef_command_line.h"

namespace kwebshell {

inline bool AppendDisabledCefFeatures(CefRefPtr<CefCommandLine> command_line,
                                      std::string_view features) {
  if (!command_line || features.empty()) {
    return false;
  }
  std::string disabled_features =
      command_line->GetSwitchValue("disable-features").ToString();
  if (!disabled_features.empty()) {
    disabled_features.append(",");
  }
  disabled_features.append(features);
  command_line->RemoveSwitch("disable-features");
  command_line->AppendSwitchWithValue("disable-features", disabled_features);
  return command_line->GetSwitchValue("disable-features")
             .ToString()
             .find(features) != std::string::npos;
}

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_CEF_COMMAND_LINE_POLICY_H_
