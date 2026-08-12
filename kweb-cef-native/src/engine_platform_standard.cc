#include "engine_platform.h"

#include <filesystem>
#include <mutex>
#include <string>
#include <system_error>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#include <dlfcn.h>
#endif

#include "include/cef_api_hash.h"
#include "include/cef_version.h"
#include "include/cef_version_info.h"
#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumRuntimePathSize = 32768;

std::mutex platform_mutex;
std::filesystem::path configured_runtime_path;
bool platform_started = false;

std::filesystem::path PathFromUtf8(const char *data, size_t size) {
#if defined(_WIN32)
  const auto *begin = reinterpret_cast<const char8_t *>(data);
  return std::filesystem::path(std::u8string(begin, begin + size));
#else
  return std::filesystem::path(std::string(data, size));
#endif
}

std::filesystem::path CanonicalPath(const std::filesystem::path &path) {
  std::error_code error;
  auto canonical = std::filesystem::canonical(path, error);
  return error ? std::filesystem::path() : canonical;
}

std::filesystem::path
LoadedCefRuntimePath(const std::filesystem::path &requested_path) {
#if defined(_WIN32)
  (void)requested_path;
  const HMODULE module = ::GetModuleHandleW(L"libcef.dll");
  if (module == nullptr) {
    return {};
  }
  std::wstring buffer(32768, L'\0');
  const DWORD length = ::GetModuleFileNameW(module, buffer.data(),
                                            static_cast<DWORD>(buffer.size()));
  if (length == 0 || length >= buffer.size()) {
    return {};
  }
  buffer.resize(length);
  return CanonicalPath(std::filesystem::path(buffer));
#elif defined(__linux__)
  void *library = ::dlopen(requested_path.c_str(), RTLD_NOW | RTLD_NOLOAD);
  if (library == nullptr) {
    return {};
  }
  void *version_symbol = ::dlsym(library, "cef_version_info");
  Dl_info information{};
  const bool resolved = version_symbol != nullptr &&
                        ::dladdr(version_symbol, &information) != 0 &&
                        information.dli_fname != nullptr;
  ::dlclose(library);
  if (!resolved || information.dli_fname == nullptr) {
    return {};
  }
  return CanonicalPath(std::filesystem::path(information.dli_fname));
#endif
}

bool RuntimeVersionMatches() {
  const char *full_version = cef_version_full();
  const char *api_hash = cef_api_hash(CEF_API_VERSION, 0);
  return full_version != nullptr && api_hash != nullptr &&
         std::string(full_version) == CEF_VERSION &&
         std::string(api_hash) == CEF_API_HASH_PLATFORM;
}

} // namespace

kweb_status EnginePlatformStartup(const char *cef_runtime_path_utf8,
                                  size_t cef_runtime_path_size) {
  if (cef_runtime_path_utf8 == nullptr || cef_runtime_path_size == 0) {
    return KWEB_STATUS_PATH_REQUIRED;
  }
  if (cef_runtime_path_size > kMaximumRuntimePathSize) {
    return KWEB_STATUS_TEXT_TOO_LARGE;
  }
  if (!IsValidUtf8(cef_runtime_path_utf8, cef_runtime_path_size)) {
    return KWEB_STATUS_INVALID_TEXT_ENCODING;
  }
  try {
    auto requested = PathFromUtf8(cef_runtime_path_utf8, cef_runtime_path_size);
    if (!requested.is_absolute()) {
      return KWEB_STATUS_PATH_NOT_ABSOLUTE;
    }
    requested = CanonicalPath(requested);
    if (requested.empty()) {
      return KWEB_STATUS_PATH_NOT_FOUND;
    }

    std::lock_guard lock(platform_mutex);
    if (platform_started) {
      return requested == configured_runtime_path
                 ? KWEB_STATUS_OK
                 : KWEB_STATUS_CEF_RUNTIME_MISMATCH;
    }
#if defined(__linux__)
    if (::XInitThreads() == 0) {
      return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
    }
#endif
    if (LoadedCefRuntimePath(requested) != requested ||
        !RuntimeVersionMatches()) {
      return KWEB_STATUS_CEF_RUNTIME_MISMATCH;
    }
    configured_runtime_path = std::move(requested);
    platform_started = true;
    return KWEB_STATUS_OK;
  } catch (...) {
    return KWEB_STATUS_INTERNAL_ERROR;
  }
}

bool EnginePlatformRuntimeMatches(
    const std::filesystem::path &cef_runtime_path) {
  const auto requested = CanonicalPath(cef_runtime_path);
  std::lock_guard lock(platform_mutex);
  return platform_started && !requested.empty() &&
         requested == configured_runtime_path;
}

bool InitializeCefOnPlatform(const CefMainArgs &main_args,
                             const CefSettings &settings,
                             CefRefPtr<CefApp> application) {
  return CefInitialize(main_args, settings, application.get(), nullptr);
}

void ConfigureEngineCommandLineOnPlatform(
    const CefString &process_type, CefRefPtr<CefCommandLine> command_line) {
  if (process_type.empty()) {
    command_line->AppendSwitchWithValue("remote-debugging-address",
                                        "127.0.0.1");
  }
}

void CleanupPlatformAfterCefInitializeFailure() {}

kweb_status ShutdownCefOnPlatform(CefShutdownCompletion completion,
                                  void *context) {
  CefShutdown();
  completion(context, KWEB_STATUS_OK);
  return KWEB_STATUS_OK;
}

bool ScheduleCefMessagePumpWorkOnPlatform(int64_t delay_ms) {
  (void)delay_ms;
  return false;
}

} // namespace kwebshell
