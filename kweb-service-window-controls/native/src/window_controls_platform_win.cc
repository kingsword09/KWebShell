#include "window_controls_platform.h"

#include <windows.h>

#include <mutex>
#include <unordered_map>

namespace kwebshell::window_controls {
namespace {

std::mutex relationship_mutex;
std::unordered_map<HWND, LONG_PTR> previous_owners;

HWND WindowForHandle(kweb_window_handle handle) {
  return reinterpret_cast<HWND>(static_cast<uintptr_t>(handle));
}

kweb_window_controls_status Validate(HWND window) {
  return ::IsWindow(window) ? KWEB_WINDOW_CONTROLS_STATUS_OK
                            : KWEB_WINDOW_CONTROLS_STATUS_STALE_HANDLE;
}

} // namespace

const char *ProviderId() { return "windows.Win32"; }

kweb_window_controls_status Probe(kweb_window_handle handle) {
  return Validate(WindowForHandle(handle));
}

kweb_window_controls_status Attach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  (void)modality;
  HWND child_window = WindowForHandle(child);
  HWND parent_window = WindowForHandle(parent);
  const kweb_window_controls_status child_status = Validate(child_window);
  const kweb_window_controls_status parent_status = Validate(parent_window);
  if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return child_status;
  }
  if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return parent_status;
  }
  {
    std::lock_guard lock(relationship_mutex);
    if (!previous_owners.contains(child_window)) {
      previous_owners.emplace(child_window,
                              ::GetWindowLongPtrW(child_window, GWLP_HWNDPARENT));
    }
  }
  ::SetLastError(ERROR_SUCCESS);
  const LONG_PTR previous = ::SetWindowLongPtrW(
      child_window, GWLP_HWNDPARENT, reinterpret_cast<LONG_PTR>(parent_window));
  if (previous == 0 && ::GetLastError() != ERROR_SUCCESS) {
    return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
  }
  if (!::SetWindowPos(child_window, HWND_TOP, 0, 0, 0, 0,
                      SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)) {
    return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
  }
  return ::GetWindow(child_window, GW_OWNER) == parent_window
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

kweb_window_controls_status Detach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality) {
  (void)modality;
  HWND child_window = WindowForHandle(child);
  HWND parent_window = WindowForHandle(parent);
  const kweb_window_controls_status child_status = Validate(child_window);
  const kweb_window_controls_status parent_status = Validate(parent_window);
  if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return child_status;
  }
  if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return parent_status;
  }
  if (::GetWindow(child_window, GW_OWNER) != parent_window) {
    return KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
  }
  LONG_PTR owner = 0;
  {
    std::lock_guard lock(relationship_mutex);
    const auto found = previous_owners.find(child_window);
    if (found != previous_owners.end()) {
      owner = found->second;
      previous_owners.erase(found);
    }
  }
  ::SetLastError(ERROR_SUCCESS);
  const LONG_PTR previous = ::SetWindowLongPtrW(child_window, GWLP_HWNDPARENT, owner);
  if (previous == 0 && ::GetLastError() != ERROR_SUCCESS) {
    return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
  }
  return ::GetWindow(child_window, GW_OWNER) == reinterpret_cast<HWND>(owner)
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

kweb_window_controls_status SetModalEnabled(kweb_window_handle handle,
                                            bool enabled) {
  HWND window = WindowForHandle(handle);
  const kweb_window_controls_status status = Validate(window);
  if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return status;
  }
  ::EnableWindow(window, enabled ? TRUE : FALSE);
  return ::IsWindowEnabled(window) == (enabled ? TRUE : FALSE)
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

kweb_window_controls_status SetAlwaysOnTop(kweb_window_handle handle,
                                           bool enabled) {
  HWND window = WindowForHandle(handle);
  const kweb_window_controls_status status = Validate(window);
  if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return status;
  }
  if (!::SetWindowPos(window, enabled ? HWND_TOPMOST : HWND_NOTOPMOST, 0, 0, 0,
                      0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE)) {
    return KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED;
  }
  const LONG_PTR style = ::GetWindowLongPtrW(window, GWL_EXSTYLE);
  const bool observed = (style & WS_EX_TOPMOST) != 0;
  return observed == enabled ? KWEB_WINDOW_CONTROLS_STATUS_OK
                             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

kweb_window_controls_status RequestAttention(kweb_window_handle handle) {
  HWND window = WindowForHandle(handle);
  const kweb_window_controls_status status = Validate(window);
  if (status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return status;
  }
  FLASHWINFO flash{sizeof(FLASHWINFO), window, FLASHW_TRAY | FLASHW_TIMERNOFG, 3,
                   0};
  ::FlashWindowEx(&flash);
  return KWEB_WINDOW_CONTROLS_STATUS_OK;
}

kweb_window_controls_status VerifyParent(kweb_window_handle child,
                                         kweb_window_handle parent) {
  HWND child_window = WindowForHandle(child);
  HWND parent_window = WindowForHandle(parent);
  const kweb_window_controls_status child_status = Validate(child_window);
  const kweb_window_controls_status parent_status = Validate(parent_window);
  if (child_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return child_status;
  }
  if (parent_status != KWEB_WINDOW_CONTROLS_STATUS_OK) {
    return parent_status;
  }
  return ::GetWindow(child_window, GW_OWNER) == parent_window
             ? KWEB_WINDOW_CONTROLS_STATUS_OK
             : KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED;
}

} // namespace kwebshell::window_controls
