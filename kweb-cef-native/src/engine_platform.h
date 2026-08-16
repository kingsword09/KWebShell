#ifndef KWEBSHELL_NATIVE_ENGINE_PLATFORM_H_
#define KWEBSHELL_NATIVE_ENGINE_PLATFORM_H_

#include <cstddef>
#include <filesystem>

#include "include/cef_app.h"
#include "kwebshell/native/base_abi.h"
#include "remote_debugging_port.h"

namespace kwebshell {

using CefShutdownCompletion = void (*)(void *context, kweb_status status);

kweb_status EnginePlatformStartup(const char *cef_runtime_path_utf8,
                                  size_t cef_runtime_path_size);

bool EnginePlatformRuntimeMatches(
    const std::filesystem::path &cef_runtime_path);

void *ResolveCefRuntimeSymbol(const char *name);

bool InitializeCefOnPlatform(const CefMainArgs &main_args,
                             const CefSettings &settings,
                             CefRefPtr<CefApp> application);

void ConfigureEngineCommandLineOnPlatform(
    const CefString &process_type, CefRefPtr<CefCommandLine> command_line);

void CleanupPlatformAfterCefInitializeFailure();

kweb_status ShutdownCefOnPlatform(CefShutdownCompletion completion,
                                  void *context);

bool ScheduleCefMessagePumpWorkOnPlatform(int64_t delay_ms);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_ENGINE_PLATFORM_H_
