#include <windows.h>

#include <iostream>
#include <string>
#include <vector>

#include "host_main.h"
#include "include/cef_command_line.h"

namespace {

std::vector<std::string> ReadProcessArguments() {
  CefRefPtr<CefCommandLine> command_line = CefCommandLine::CreateCommandLine();
  command_line->InitFromString(::GetCommandLineW());

  CefCommandLine::ArgumentList cef_arguments;
  command_line->GetArgv(cef_arguments);
  std::vector<std::string> arguments;
  arguments.reserve(cef_arguments.size());
  for (const CefString &argument : cef_arguments) {
    arguments.push_back(argument.ToString());
  }
  return arguments;
}

} // namespace

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE previous_instance,
                      wchar_t *command_line, int show_command) {
  (void)previous_instance;
  (void)command_line;
  (void)show_command;

  if (!::SetProcessDpiAwarenessContext(
          DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) &&
      !::AreDpiAwarenessContextsEqual(
          ::GetThreadDpiAwarenessContext(),
          DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)) {
    std::cerr << "{\"event\":\"startup_error\","
                 "\"code\":\"native.windows.dpi-awareness-failed\","
                 "\"win32_error\":\""
              << ::GetLastError() << "\"}" << std::endl;
    return static_cast<int>(kwebshell::HostExitCode::kConfigurationError);
  }

  CefMainArgs main_args(instance);
  const int subprocess_exit_code =
      CefExecuteProcess(main_args, nullptr, nullptr);
  if (subprocess_exit_code >= 0) {
    return subprocess_exit_code;
  }

  return kwebshell::RunBrowserProcess(main_args, ReadProcessArguments(),
                                      nullptr);
}
