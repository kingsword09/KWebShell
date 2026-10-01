#define NOMINMAX
#include "shell_platform.h"

#include <windows.h>
#include <shlobj.h>
#include <shellapi.h>

#include <string>
#include <vector>

namespace kwebshell::shell {
namespace {
std::wstring Wide(const std::string &value) {
  if (value.empty()) return {};
  const int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
      value.data(), static_cast<int>(value.size()), nullptr, 0);
  if (length <= 0) return {};
  std::wstring result(static_cast<size_t>(length), L'\0');
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                          static_cast<int>(value.size()), result.data(), length) <= 0) {
    return {};
  }
  return result;
}

kweb_shell_status OpenWithShell(const std::wstring &value) {
  SHELLEXECUTEINFOW info{};
  info.cbSize = sizeof(info);
  info.fMask = SEE_MASK_FLAG_NO_UI | SEE_MASK_NOCLOSEPROCESS;
  info.lpFile = value.c_str();
  info.nShow = SW_SHOWNORMAL;
  if (!ShellExecuteExW(&info)) return KWEB_SHELL_STATUS_HANDLER_REJECTED;
  if (info.hProcess != nullptr) CloseHandle(info.hProcess);
  return KWEB_SHELL_STATUS_OK;
}

kweb_shell_status Reveal(const std::wstring &value) {
  PIDLIST_ABSOLUTE item = nullptr;
  if (FAILED(SHParseDisplayName(value.c_str(), nullptr, &item, 0, nullptr)) || item == nullptr) {
    return KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
  }
  PIDLIST_ABSOLUTE folder = ILClone(item);
  if (folder == nullptr || !ILRemoveLastID(folder)) {
    if (item != nullptr) CoTaskMemFree(item);
    if (folder != nullptr) CoTaskMemFree(folder);
    return KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
  }
  PCUITEMID_CHILD child = ILFindLastID(item);
  const HRESULT status = SHOpenFolderAndSelectItems(folder, 1, &child, 0);
  CoTaskMemFree(item);
  CoTaskMemFree(folder);
  return SUCCEEDED(status) ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
}

kweb_shell_status Trash(const std::wstring &value) {
  std::vector<wchar_t> paths(value.begin(), value.end());
  paths.push_back(L'\0');
  paths.push_back(L'\0');
  SHFILEOPSTRUCTW operation{};
  operation.wFunc = FO_DELETE;
  operation.pFrom = paths.data();
  operation.fFlags = FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT;
  const int status = SHFileOperationW(&operation);
  if (status != 0 || operation.fAnyOperationsAborted) return KWEB_SHELL_STATUS_TRASH_FAILED;
  return GetFileAttributesW(value.c_str()) == INVALID_FILE_ATTRIBUTES
      ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_TRASH_VERIFICATION_FAILED;
}
}  // namespace

const char *ProviderId() { return "windows.Win32.Shell"; }

kweb_shell_status ExecutePlatform(
    uint32_t action, uint32_t, const std::string &value, uint32_t *outcome) {
  const std::wstring wide = Wide(value);
  if (wide.empty()) return KWEB_SHELL_STATUS_INVALID_ARGUMENT;
  const HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
  const bool should_uninitialize = initialized == S_OK || initialized == S_FALSE;
  if (FAILED(initialized) && initialized != RPC_E_CHANGED_MODE) {
    return KWEB_SHELL_STATUS_NATIVE_UNAVAILABLE;
  }
  kweb_shell_status status;
  if (action == KWEB_SHELL_ACTION_OPEN_EXTERNAL ||
      action == KWEB_SHELL_ACTION_OPEN_RESOURCE) {
    status = OpenWithShell(wide);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
  } else if (action == KWEB_SHELL_ACTION_REVEAL_RESOURCE) {
    status = Reveal(wide);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
  } else {
    status = Trash(wide);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_MOVED_TO_TRASH;
  }
  if (should_uninitialize) CoUninitialize();
  return status;
}
}  // namespace kwebshell::shell
