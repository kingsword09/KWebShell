#include "app_paths_platform.h"

#include <windows.h>
#include <knownfolders.h>
#include <shlobj.h>

#include <string>
#include <utility>

namespace kwebshell::services {
namespace {

bool WideToUtf8(const wchar_t *value, std::string *output) {
  if (value == nullptr || output == nullptr || *value == L'\0') {
    return false;
  }
  const int required = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value,
                                           -1, nullptr, 0, nullptr, nullptr);
  if (required <= 1) {
    return false;
  }
  std::string converted(static_cast<size_t>(required), '\0');
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1,
                          converted.data(), required, nullptr, nullptr) <= 0) {
    return false;
  }
  converted.resize(static_cast<size_t>(required - 1));
  *output = std::move(converted);
  return true;
}

bool KnownFolder(const KNOWNFOLDERID &folder, PlatformPathResult *result,
                 const char *source) {
  PWSTR raw = nullptr;
  const HRESULT status =
      SHGetKnownFolderPath(folder, KF_FLAG_DEFAULT, nullptr, &raw);
  if (FAILED(status) || raw == nullptr) {
    return false;
  }
  std::string utf8;
  const bool converted = WideToUtf8(raw, &utf8);
  CoTaskMemFree(raw);
  if (!converted) {
    return false;
  }
  result->path = PathFromUtf8(utf8);
  result->source = source;
  return true;
}

bool TemporaryDirectory(PlatformPathResult *result) {
  wchar_t buffer[MAX_PATH + 1] = {};
  const DWORD length =
      GetTempPathW(static_cast<DWORD>(std::size(buffer)), buffer);
  if (length == 0 || length >= std::size(buffer)) {
    return false;
  }
  std::string utf8;
  if (!WideToUtf8(buffer, &utf8)) {
    return false;
  }
  result->path = PathFromUtf8(utf8);
  result->source = "windows.Win32.GetTempPathW";
  return true;
}

} // namespace

bool ResolvePlatformPath(uint32_t kind, PlatformPathResult *result,
                         uint32_t *status) {
  if (result == nullptr || status == nullptr) {
    return false;
  }
  bool resolved = false;
  switch (kind) {
  case KWEB_APP_PATH_HOME:
    resolved = KnownFolder(FOLDERID_Profile, result,
                           "windows.KnownFolder.FOLDERID_Profile");
    break;
  case KWEB_APP_PATH_APP_DATA:
    resolved = KnownFolder(FOLDERID_RoamingAppData, result,
                           "windows.KnownFolder.FOLDERID_RoamingAppData");
    break;
  case KWEB_APP_PATH_APP_CACHE:
    resolved = KnownFolder(FOLDERID_LocalAppData, result,
                           "windows.KnownFolder.FOLDERID_LocalAppData");
    break;
  case KWEB_APP_PATH_TEMP:
    resolved = TemporaryDirectory(result);
    break;
  case KWEB_APP_PATH_DESKTOP:
    resolved = KnownFolder(FOLDERID_Desktop, result,
                           "windows.KnownFolder.FOLDERID_Desktop");
    break;
  case KWEB_APP_PATH_DOCUMENTS:
    resolved = KnownFolder(FOLDERID_Documents, result,
                           "windows.KnownFolder.FOLDERID_Documents");
    break;
  case KWEB_APP_PATH_DOWNLOADS:
    resolved = KnownFolder(FOLDERID_Downloads, result,
                           "windows.KnownFolder.FOLDERID_Downloads");
    break;
  case KWEB_APP_PATH_MUSIC:
    resolved = KnownFolder(FOLDERID_Music, result,
                           "windows.KnownFolder.FOLDERID_Music");
    break;
  case KWEB_APP_PATH_PICTURES:
    resolved = KnownFolder(FOLDERID_Pictures, result,
                           "windows.KnownFolder.FOLDERID_Pictures");
    break;
  case KWEB_APP_PATH_VIDEOS:
    resolved = KnownFolder(FOLDERID_Videos, result,
                           "windows.KnownFolder.FOLDERID_Videos");
    break;
  default:
    *status = KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN;
    return false;
  }
  *status = resolved ? KWEB_SERVICES_STATUS_OK
                     : KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE;
  return resolved;
}

} // namespace kwebshell::services
