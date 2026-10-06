#include "engine_platform.h"

#include <iostream>

#include "include/cef_api_hash.h"

namespace {
int failures = 0;

void Check(bool condition, const char *message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << std::endl;
    ++failures;
  }
}
} // namespace

int main() {
  if (cef_api_hash(CEF_API_VERSION, 0) == nullptr) {
    std::cerr << "CEF API negotiation failed." << std::endl;
    return 1;
  }
  for (const char *process_type : {"", "renderer", "utility", "gpu-process"}) {
    auto command_line = CefCommandLine::CreateCommandLine();
    command_line->AppendSwitchWithValue("disable-features", "ExistingFeature");
    command_line->AppendSwitchWithValue("user-data-dir", "explicit-profile");
    for (int repetition = 0; repetition < 2; ++repetition) {
      kwebshell::ConfigureEngineCommandLineOnPlatform(process_type, command_line);
      const bool browser = *process_type == '\0';
      Check(command_line->HasSwitch("do-not-de-elevate") == browser,
            "Only the Windows browser must prevent host replacement.");
      Check(command_line->HasSwitch("remote-debugging-address") == browser,
            "The CDP address remains scoped to the browser.");
      if (browser) {
        Check(command_line->GetSwitchValue("remote-debugging-address") ==
                  "127.0.0.1",
              "CDP must remain loopback-only.");
      }
      Check(!command_line->HasSwitch("remote-debugging-port") &&
                !command_line->HasSwitch("no-sandbox") &&
                !command_line->HasSwitch("enable-automation"),
            "Host ownership must not enable debugging, disable sandboxing or select automation mode.");
      Check(command_line->GetSwitchValue("user-data-dir") == "explicit-profile" &&
                command_line->GetSwitchValue("disable-features") == "ExistingFeature",
            "Explicit profile and feature configuration must be preserved.");
    }
  }
  if (failures != 0) return 1;
  std::cout << "Windows CEF host command-line policy passed." << std::endl;
  return 0;
}
