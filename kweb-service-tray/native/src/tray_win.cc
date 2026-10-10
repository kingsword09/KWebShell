#include "tray_internal.h"

#if !defined(_WIN32)
#error "The Windows tray provider must only be compiled on Windows."
#endif

#include <windows.h>
#include <shellapi.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <map>
#include <string>
#include <vector>

namespace {

constexpr UINT kTrayCallbackMessage = WM_APP + 0x5452;  // 'TR'
constexpr wchar_t kTrayWindowClass[] = L"KWebShellTrayMessageWindow";
constexpr UINT kFirstMenuCommandId = 2000;

using kwebshell::tray::Item;
using kwebshell::tray::Menu;
using kwebshell::tray::MenuNode;

/** One live notification-area item. */
struct TrayEntry {
  std::string item_id;
  UINT uid = 0;
  HICON icon = nullptr;
  std::wstring tooltip;
  uint32_t activation_bits = 0;
  bool has_menu = false;
  Menu menu;
  std::map<UINT, std::string> command_by_id;
};

struct WindowsState {
  HWND window = nullptr;
  UINT taskbar_created = 0;
  std::vector<TrayEntry *> entries;
  UINT next_uid = 1;
};

kwebshell::tray::State *g_state = nullptr;

void TraceWin32Failure(const char *stage) {
  std::fprintf(stderr, "KWEBSHELL_TRAY_FAILURE stage=%s error=%lu\n", stage,
               static_cast<unsigned long>(::GetLastError()));
}

std::wstring Widen(const std::string &value) {
  if (value.empty()) return std::wstring();
  const int needed = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                                         static_cast<int>(value.size()), nullptr, 0);
  if (needed <= 0) return std::wstring();
  std::wstring result(static_cast<size_t>(needed), L'\0');
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()),
                      result.data(), needed);
  return result;
}

WindowsState *StateOf(kwebshell::tray::State &state) {
  return static_cast<WindowsState *>(state.platform);
}

/** Creates one HICON from straight RGBA8 pixels. */
HICON BuildIcon(const kwebshell::tray::IconVariant &variant) {
  BITMAPV5HEADER header{};
  header.bV5Size = sizeof(BITMAPV5HEADER);
  header.bV5Width = static_cast<LONG>(variant.width);
  header.bV5Height = -static_cast<LONG>(variant.height);  // top-down rows
  header.bV5Planes = 1;
  header.bV5BitCount = 32;
  header.bV5Compression = BI_BITFIELDS;
  header.bV5RedMask = 0x00ff0000;
  header.bV5GreenMask = 0x0000ff00;
  header.bV5BlueMask = 0x000000ff;
  header.bV5AlphaMask = 0xff000000;

  void *bits = nullptr;
  HDC screen = GetDC(nullptr);
  if (screen == nullptr) return nullptr;
  HBITMAP colour = CreateDIBSection(screen, reinterpret_cast<BITMAPINFO *>(&header), DIB_RGB_COLORS, &bits,
                                    nullptr, 0);
  ReleaseDC(nullptr, screen);
  if (colour == nullptr || bits == nullptr) return nullptr;
  auto *destination = static_cast<uint8_t *>(bits);
  for (size_t pixel = 0; pixel < variant.pixels.size(); pixel += 4) {
    destination[pixel + 0] = variant.pixels[pixel + 2];
    destination[pixel + 1] = variant.pixels[pixel + 1];
    destination[pixel + 2] = variant.pixels[pixel + 0];
    destination[pixel + 3] = variant.pixels[pixel + 3];
  }
  HBITMAP mask =
      CreateBitmap(static_cast<int>(variant.width), static_cast<int>(variant.height), 1, 1, nullptr);
  ICONINFO info{};
  info.fIcon = TRUE;
  info.hbmColor = colour;
  info.hbmMask = mask;
  HICON icon = CreateIconIndirect(&info);
  DeleteObject(colour);
  if (mask != nullptr) DeleteObject(mask);
  return icon;
}

/**
 * Selects one variant: the highest declared scale that does not exceed the
 * system scale, otherwise the smallest declared variant.
 */
const kwebshell::tray::IconVariant *SelectVariant(const Item &item) {
  HDC screen = GetDC(nullptr);
  const int dpi = screen != nullptr ? GetDeviceCaps(screen, LOGPIXELSX) : 96;
  if (screen != nullptr) ReleaseDC(nullptr, screen);
  const int32_t platform_scale = dpi >= 192 ? 2 : 1;
  const kwebshell::tray::IconVariant *selected = nullptr;
  for (const kwebshell::tray::IconVariant &variant : item.variants) {
    if (static_cast<int32_t>(variant.scale) <= platform_scale) {
      if (selected == nullptr || variant.scale > selected->scale) selected = &variant;
    }
  }
  if (selected != nullptr) return selected;
  for (const kwebshell::tray::IconVariant &variant : item.variants) {
    if (selected == nullptr || variant.scale < selected->scale) selected = &variant;
  }
  return selected;
}

TrayEntry *FindEntry(WindowsState &windows, UINT uid) {
  for (TrayEntry *entry : windows.entries) {
    if (entry != nullptr && entry->uid == uid) return entry;
  }
  return nullptr;
}

TrayEntry *FindEntry(WindowsState &windows, const std::string &item_id) {
  for (TrayEntry *entry : windows.entries) {
    if (entry != nullptr && entry->item_id == item_id) return entry;
  }
  return nullptr;
}

NOTIFYICONDATAW NotifyData(WindowsState &windows, TrayEntry &entry) {
  NOTIFYICONDATAW data{};
  data.cbSize = sizeof(NOTIFYICONDATAW);
  data.hWnd = windows.window;
  data.uID = entry.uid;
  return data;
}

/** Publishes one item to the notification area, replacing any previous icon. */
bool PublishIcon(WindowsState &windows, TrayEntry &entry, const std::wstring &tooltip, bool re_add) {
  NOTIFYICONDATAW data = NotifyData(windows, entry);
  data.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
  data.uCallbackMessage = kTrayCallbackMessage;
  data.hIcon = entry.icon;
  const std::wstring bounded = tooltip.substr(0, 127);
  wcsncpy_s(data.szTip, 128, bounded.c_str(), bounded.size());
  if (re_add) {
    // A rebuilt taskbar dropped every icon, so the provider replaces its own
    // entry with exactly one registration.
    Shell_NotifyIconW(NIM_DELETE, &data);
  }
  bool registered = Shell_NotifyIconW(re_add ? NIM_ADD : NIM_MODIFY, &data) != FALSE;
  if (!registered) {
    // The shell may have restarted without delivering TaskbarCreated; one
    // explicit add keeps exactly one icon.
    registered = Shell_NotifyIconW(NIM_ADD, &data) != FALSE;
  }
  if (!registered) return false;
  data.uVersion = NOTIFYICON_VERSION_4;
  Shell_NotifyIconW(NIM_SETVERSION, &data);
  return true;
}

void AppendMenuItems(HMENU menu, TrayEntry &entry, const std::vector<size_t> &indices, UINT *next_id) {
  for (const size_t index : indices) {
    const MenuNode &node = entry.menu.items[index];
    if (!node.visible) continue;
    if (node.kind == KWEB_TRAY_MENU_SEPARATOR) {
      AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
      continue;
    }
    const std::wstring label = Widen(node.label);
    if (node.kind == KWEB_TRAY_MENU_SUBMENU) {
      HMENU child = CreatePopupMenu();
      if (child == nullptr) continue;
      AppendMenuItems(child, entry, node.children, next_id);
      UINT flags = MF_POPUP;
      if (!node.enabled) flags |= MF_GRAYED;
      AppendMenuW(menu, flags, reinterpret_cast<UINT_PTR>(child), label.c_str());
      continue;
    }
    const UINT command_id = (*next_id)++;
    entry.command_by_id[command_id] = node.id;
    UINT flags = MF_STRING;
    if (!node.enabled) flags |= MF_GRAYED;
    if (node.checked) flags |= MF_CHECKED;
    AppendMenuW(menu, flags, command_id, label.c_str());
  }
}

/** Presents the item menu at the cursor and reports one command exactly once. */
void PresentMenu(WindowsState &windows, TrayEntry &entry) {
  if (!entry.has_menu) return;
  entry.command_by_id.clear();
  UINT next_id = kFirstMenuCommandId;
  HMENU menu = CreatePopupMenu();
  if (menu == nullptr) {
    TraceWin32Failure("build-tray-menu");
    return;
  }
  AppendMenuItems(menu, entry, entry.menu.roots, &next_id);
  POINT cursor{};
  GetCursorPos(&cursor);
  SetForegroundWindow(windows.window);
  const UINT selected = TrackPopupMenuEx(
      menu, TPM_RETURNCMD | TPM_LEFTALIGN | TPM_TOPALIGN | TPM_LEFTBUTTON | TPM_RIGHTBUTTON,
      cursor.x, cursor.y, windows.window, nullptr);
  DestroyMenu(menu);
  if (selected == 0 || g_state == nullptr) return;
  const auto found = entry.command_by_id.find(selected);
  if (found != entry.command_by_id.end()) {
    kwebshell::tray::PushMenuCommand(*g_state, entry.item_id, entry.menu.menu_id, found->second,
                                     entry.menu.version);
  }
}

/** Republishes every live item exactly once after a shell restart. */
void RepublishAll(WindowsState &windows) {
  for (TrayEntry *entry : windows.entries) {
    if (entry == nullptr || entry->icon == nullptr) continue;
    if (!PublishIcon(windows, *entry, entry->tooltip, /*re_add=*/true)) {
      TraceWin32Failure("republish-item");
    }
  }
}

LRESULT CALLBACK TrayWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  WindowsState *windows = reinterpret_cast<WindowsState *>(GetWindowLongPtrW(window, GWLP_USERDATA));
  if (windows == nullptr) return DefWindowProcW(window, message, wparam, lparam);
  if (windows->taskbar_created != 0 && message == windows->taskbar_created) {
    RepublishAll(*windows);
    return 0;
  }
  if (message != kTrayCallbackMessage) return DefWindowProcW(window, message, wparam, lparam);
  const UINT event = LOWORD(lparam);
  const UINT uid = HIWORD(lparam);
  TrayEntry *entry = FindEntry(*windows, uid);
  if (entry == nullptr || g_state == nullptr) return 0;
  switch (event) {
    case NIN_SELECT:
    case WM_LBUTTONUP:
    case NIN_KEYSELECT:
      if ((entry->activation_bits & KWEB_TRAY_ACTIVATION_PRIMARY) != 0) {
        kwebshell::tray::PushActivated(*g_state, entry->item_id, KWEB_TRAY_ACTIVATION_PRIMARY);
      }
      return 0;
    case WM_LBUTTONDBLCLK:
      if ((entry->activation_bits & KWEB_TRAY_ACTIVATION_DOUBLE) != 0) {
        kwebshell::tray::PushActivated(*g_state, entry->item_id, KWEB_TRAY_ACTIVATION_DOUBLE);
      }
      return 0;
    case WM_CONTEXTMENU:
    case WM_RBUTTONUP:
      if ((entry->activation_bits & KWEB_TRAY_ACTIVATION_SECONDARY) != 0) {
        kwebshell::tray::PushActivated(*g_state, entry->item_id, KWEB_TRAY_ACTIVATION_SECONDARY);
      }
      PresentMenu(*windows, *entry);
      return 0;
    case NIN_BALLOONUSERCLICK:
      kwebshell::tray::PushBalloon(*g_state, entry->item_id);
      return 0;
    default:
      return 0;
  }
}

bool RegisterTrayWindowClass(HINSTANCE instance) {
  WNDCLASSEXW definition{};
  definition.cbSize = sizeof(WNDCLASSEXW);
  definition.lpfnWndProc = TrayWindowProc;
  definition.hInstance = instance;
  definition.lpszClassName = kTrayWindowClass;
  if (RegisterClassExW(&definition) != 0) return true;
  // A second provider in the same process reuses the registered class.
  return GetLastError() == ERROR_CLASS_ALREADY_EXISTS;
}

}  // namespace

namespace kwebshell::tray {

const char *ProviderId() {
  return "tray.windows.shell-notify-icon";
}

kweb_tray_status NativeOpen(State &state) {
  auto *windows = new WindowsState();
  HINSTANCE instance = GetModuleHandleW(nullptr);
  if (!RegisterTrayWindowClass(instance)) {
    TraceWin32Failure("register-tray-window-class");
    delete windows;
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  windows->window = CreateWindowExW(0, kTrayWindowClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, instance,
                                    nullptr);
  if (windows->window == nullptr) {
    TraceWin32Failure("create-tray-window");
    delete windows;
    return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  }
  SetWindowLongPtrW(windows->window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(windows));
  windows->taskbar_created = RegisterWindowMessageW(L"TaskbarCreated");
  state.platform = windows;
  g_state = &state;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeCapabilities(State &, kweb_tray_capabilities_result *result) {
  result->flags = KWEB_TRAY_CAP_MENU | KWEB_TRAY_CAP_TOOLTIP | KWEB_TRAY_CAP_BALLOON |
                  KWEB_TRAY_CAP_BOUNDS | KWEB_TRAY_CAP_EXPLORER_RESTART_RECOVERY;
  result->activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY | KWEB_TRAY_ACTIVATION_SECONDARY |
                            KWEB_TRAY_ACTIVATION_DOUBLE;
  CopyBounded(result->provider_id, sizeof(result->provider_id), ProviderId());
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeSetItem(State &state, const Item &item, bool replacing) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr || windows->window == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  const kwebshell::tray::IconVariant *variant = SelectVariant(item);
  if (variant == nullptr) return KWEB_TRAY_STATUS_ICON_INVALID;
  HICON icon = BuildIcon(*variant);
  if (icon == nullptr) {
    TraceWin32Failure("build-icon");
    return KWEB_TRAY_STATUS_ICON_UNSUPPORTED;
  }
  TrayEntry *entry = FindEntry(*windows, item.id);
  if (entry == nullptr) {
    entry = new TrayEntry();
    entry->item_id = item.id;
    entry->uid = windows->next_uid++;
    windows->entries.push_back(entry);
  } else {
    if (entry->icon != nullptr) DestroyIcon(entry->icon);
  }
  entry->icon = icon;
  entry->activation_bits = item.activation_bits;
  entry->tooltip = Widen(item.tooltip);
  if (!PublishIcon(*windows, *entry, entry->tooltip, /*re_add=*/false)) {
    TraceWin32Failure(replacing ? "modify-item" : "add-item");
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeCloseItem(State &state, const std::string &item_id) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr || windows->window == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  TrayEntry *entry = FindEntry(*windows, item_id);
  if (entry == nullptr) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  NOTIFYICONDATAW data = NotifyData(*windows, *entry);
  Shell_NotifyIconW(NIM_DELETE, &data);
  if (entry->icon != nullptr) DestroyIcon(entry->icon);
  windows->entries.erase(std::remove(windows->entries.begin(), windows->entries.end(), entry),
                         windows->entries.end());
  delete entry;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeSetMenu(State &state, const std::string &item_id, const Menu *menu) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr || windows->window == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  TrayEntry *entry = FindEntry(*windows, item_id);
  if (entry == nullptr) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  if (menu == nullptr) {
    entry->has_menu = false;
    entry->menu = Menu();
    entry->command_by_id.clear();
    return KWEB_TRAY_STATUS_OK;
  }
  entry->menu = *menu;
  entry->has_menu = true;
  entry->command_by_id.clear();
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeBounds(State &state, const std::string &item_id, kweb_tray_bounds_result *result) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr || windows->window == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  TrayEntry *entry = FindEntry(*windows, item_id);
  if (entry == nullptr) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  NOTIFYICONIDENTIFIER identifier{};
  identifier.cbSize = sizeof(NOTIFYICONIDENTIFIER);
  identifier.hWnd = windows->window;
  identifier.uID = entry->uid;
  RECT rect{};
  if (FAILED(Shell_NotifyIconGetRect(&identifier, &rect))) return KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE;
  result->x = rect.left;
  result->y = rect.top;
  result->width = rect.right - rect.left;
  result->height = rect.bottom - rect.top;
  if (result->width <= 0 || result->height <= 0) return KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeClose(State &state) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr) return KWEB_TRAY_STATUS_OK;
  for (TrayEntry *entry : windows->entries) {
    if (entry == nullptr) continue;
    NOTIFYICONDATAW data = NotifyData(*windows, *entry);
    Shell_NotifyIconW(NIM_DELETE, &data);
    if (entry->icon != nullptr) DestroyIcon(entry->icon);
    delete entry;
  }
  windows->entries.clear();
  if (windows->window != nullptr) DestroyWindow(windows->window);
  windows->window = nullptr;
  state.platform = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete windows;
  return KWEB_TRAY_STATUS_OK;
}

}  // namespace kwebshell::tray
