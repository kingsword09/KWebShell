#include "menus_platform.h"

#if !defined(_WIN32)
#error "The Windows menus provider must only be compiled on Windows."
#endif

#include <windows.h>

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cwctype>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace {

constexpr UINT kFirstCommandId = 1000;

struct WindowEntry {
  std::string window_id;
  HWND window = nullptr;
  HMENU menu = nullptr;
  WNDPROC previous_proc = nullptr;
  kweb_menus_owner_kind owner_kind = KWEB_MENUS_OWNER_WINDOW;
  uint64_t tree_version = 0;
  bool subclassed = false;
  std::map<UINT, std::string> command_by_id;
  std::map<std::string, UINT> id_by_command;

  struct Accelerator {
    UINT modifiers = 0;
    UINT virtual_key = 0;
    std::string command;
  };

  std::vector<Accelerator> accelerators;
};

struct WindowsState {
  std::map<std::string, std::unique_ptr<WindowEntry>> windows;
};

kwebshell::menus::State *g_state = nullptr;

/**
 * Reports one native failure stage with its Win32 error code. The message
 * carries no user content, so a hosted failure stays diagnosable without
 * retaining menu data.
 */
void TraceWin32Failure(const char *stage) {
  std::fprintf(stderr, "KWEBSHELL_MENUS_FAILURE stage=%s error=%lu\n", stage,
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

struct KeyMapping {
  UINT virtual_key;
  const wchar_t *text;
};

/** Maps one published accelerator key code to its Win32 virtual key and text. */
KeyMapping KeyFor(uint32_t key) {
  if (key >= KWEB_MENUS_KEY_A && key <= KWEB_MENUS_KEY_Z) {
    return {static_cast<UINT>('A' + (key - KWEB_MENUS_KEY_A)), nullptr};
  }
  if (key >= KWEB_MENUS_KEY_DIGIT_0 && key <= KWEB_MENUS_KEY_DIGIT_9) {
    return {static_cast<UINT>('0' + (key - KWEB_MENUS_KEY_DIGIT_0)), nullptr};
  }
  if (key >= KWEB_MENUS_KEY_F1 && key <= KWEB_MENUS_KEY_F12) {
    return {static_cast<UINT>(VK_F1 + (key - KWEB_MENUS_KEY_F1)), nullptr};
  }
  switch (key) {
    case KWEB_MENUS_KEY_BACKSPACE: return {VK_BACK, L"Backspace"};
    case KWEB_MENUS_KEY_DELETE: return {VK_DELETE, L"Delete"};
    case KWEB_MENUS_KEY_INSERT: return {VK_INSERT, L"Insert"};
    case KWEB_MENUS_KEY_HOME: return {VK_HOME, L"Home"};
    case KWEB_MENUS_KEY_END: return {VK_END, L"End"};
    case KWEB_MENUS_KEY_PAGE_UP: return {VK_PRIOR, L"PageUp"};
    case KWEB_MENUS_KEY_PAGE_DOWN: return {VK_NEXT, L"PageDown"};
    case KWEB_MENUS_KEY_ARROW_LEFT: return {VK_LEFT, L"Left"};
    case KWEB_MENUS_KEY_ARROW_RIGHT: return {VK_RIGHT, L"Right"};
    case KWEB_MENUS_KEY_ARROW_UP: return {VK_UP, L"Up"};
    case KWEB_MENUS_KEY_ARROW_DOWN: return {VK_DOWN, L"Down"};
    case KWEB_MENUS_KEY_ENTER: return {VK_RETURN, L"Enter"};
    case KWEB_MENUS_KEY_ESCAPE: return {VK_ESCAPE, L"Esc"};
    case KWEB_MENUS_KEY_TAB: return {VK_TAB, L"Tab"};
    case KWEB_MENUS_KEY_SPACE: return {VK_SPACE, L"Space"};
    case KWEB_MENUS_KEY_PLUS: return {VK_OEM_PLUS, L"+"};
    case KWEB_MENUS_KEY_MINUS: return {VK_OEM_MINUS, L"-"};
    case KWEB_MENUS_KEY_EQUAL: return {VK_OEM_PLUS, L"="};
    case KWEB_MENUS_KEY_COMMA: return {VK_OEM_COMMA, L","};
    case KWEB_MENUS_KEY_PERIOD: return {VK_OEM_PERIOD, L"."};
    case KWEB_MENUS_KEY_SEMICOLON: return {VK_OEM_1, L";"};
    case KWEB_MENUS_KEY_SLASH: return {VK_OEM_2, L"/"};
    case KWEB_MENUS_KEY_BACKQUOTE: return {VK_OEM_3, L"`"};
    case KWEB_MENUS_KEY_BRACKET_LEFT: return {VK_OEM_4, L"["};
    case KWEB_MENUS_KEY_BACKSLASH: return {VK_OEM_5, L"\\"};
    case KWEB_MENUS_KEY_BRACKET_RIGHT: return {VK_OEM_6, L"]"};
    case KWEB_MENUS_KEY_QUOTE: return {VK_OEM_7, L"'"};
    default: return {0, nullptr};
  }
}

UINT ModifierFlags(uint32_t modifiers) {
  UINT flags = 0;
  if ((modifiers & KWEB_MENUS_MODIFIER_PRIMARY) != 0) flags |= MOD_CONTROL;
  if ((modifiers & KWEB_MENUS_MODIFIER_CONTROL) != 0) flags |= MOD_CONTROL;
  if ((modifiers & KWEB_MENUS_MODIFIER_ALT) != 0) flags |= MOD_ALT;
  if ((modifiers & KWEB_MENUS_MODIFIER_SHIFT) != 0) flags |= MOD_SHIFT;
  if ((modifiers & KWEB_MENUS_MODIFIER_META) != 0) flags |= MOD_WIN;
  return flags;
}

std::wstring AcceleratorText(uint32_t modifiers, uint32_t key) {
  const KeyMapping mapping = KeyFor(key);
  if (mapping.virtual_key == 0) return std::wstring();
  std::wstring text;
  const UINT flags = ModifierFlags(modifiers);
  if ((flags & MOD_CONTROL) != 0) text += L"Ctrl+";
  if ((flags & MOD_ALT) != 0) text += L"Alt+";
  if ((flags & MOD_SHIFT) != 0) text += L"Shift+";
  if ((flags & MOD_WIN) != 0) text += L"Win+";
  if (mapping.text != nullptr) {
    text += mapping.text;
  } else {
    text.push_back(static_cast<wchar_t>(mapping.virtual_key));
  }
  return text;
}

/** Inserts the '&' mnemonic marker before the published mnemonic letter. */
std::wstring MnemonicLabel(const std::string &label, uint32_t mnemonic) {
  std::wstring text = Widen(label);
  if (mnemonic == 0) return text;
  const wchar_t target = static_cast<wchar_t>(mnemonic);
  for (size_t index = 0; index < text.size(); ++index) {
    if (towlower(text[index]) == towlower(target)) {
      text.insert(index, 1, L'&');
      break;
    }
  }
  return text;
}

UINT CommandIdFor(WindowEntry &entry, const std::string &command) {
  const auto existing = entry.id_by_command.find(command);
  if (existing != entry.id_by_command.end()) return existing->second;
  UINT id = kFirstCommandId;
  while (entry.command_by_id.count(id) != 0) id += 1;
  entry.id_by_command[command] = id;
  entry.command_by_id[id] = command;
  return id;
}

void AppendItems(WindowEntry &entry, HMENU menu, const kwebshell::menus::Tree &tree, size_t index);

/** Appends one contiguous run of radio items with the selected member dotted. */
void AppendRadioRun(WindowEntry &entry, HMENU menu, const kwebshell::menus::Tree &tree,
                    const std::vector<size_t> &children, size_t begin, size_t end) {
  std::vector<UINT> ids;
  UINT selected = 0;
  bool any_selected = false;
  for (size_t position = begin; position < end; ++position) {
    const kwebshell::menus::Item &node = tree.items[children[position]];
    const UINT id = CommandIdFor(entry, node.id);
    std::wstring text = MnemonicLabel(node.label, node.mnemonic);
    UINT flags = MF_STRING;
    if (!node.enabled) flags |= MF_GRAYED;
    AppendMenuW(menu, flags, id, text.c_str());
    ids.push_back(id);
    if (node.checked && !any_selected) {
      selected = id;
      any_selected = true;
    }
  }
  if (any_selected && !ids.empty()) {
    CheckMenuRadioItem(menu, ids.front(), ids.back(), selected, MF_BYCOMMAND);
  }
}

void AppendItems(WindowEntry &entry, HMENU menu, const kwebshell::menus::Tree &tree, size_t index) {
  const kwebshell::menus::Item &node = tree.items[index];
  if (node.kind == KWEB_MENUS_ITEM_SEPARATOR) {
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    return;
  }
  if (!node.visible) return;
  if (node.kind == KWEB_MENUS_ITEM_SUBMENU) {
    HMENU child = CreatePopupMenu();
    if (child == nullptr) return;
    size_t position = 0;
    while (position < node.children.size()) {
      if (tree.items[node.children[position]].kind == KWEB_MENUS_ITEM_RADIO) {
        size_t end = position;
        while (end < node.children.size() &&
               tree.items[node.children[end]].kind == KWEB_MENUS_ITEM_RADIO) {
          end += 1;
        }
        AppendRadioRun(entry, child, tree, node.children, position, end);
        position = end;
        continue;
      }
      AppendItems(entry, child, tree, node.children[position]);
      position += 1;
    }
    std::wstring text = MnemonicLabel(node.label, node.mnemonic);
    UINT flags = MF_POPUP;
    if (!node.enabled) flags |= MF_GRAYED;
    AppendMenuW(menu, flags, reinterpret_cast<UINT_PTR>(child), text.c_str());
    return;
  }
  const UINT id = CommandIdFor(entry, node.id);
  std::wstring text = MnemonicLabel(node.label, node.mnemonic);
  if (node.accelerator_key != KWEB_MENUS_KEY_NONE) {
    const std::wstring accelerator = AcceleratorText(node.accelerator_modifiers, node.accelerator_key);
    if (!accelerator.empty()) {
      text.push_back(L'\t');
      text += accelerator;
    }
  }
  UINT flags = MF_STRING;
  if (!node.enabled) flags |= MF_GRAYED;
  if (node.kind == KWEB_MENUS_ITEM_CHECKBOX && node.checked) flags |= MF_CHECKED;
  if (node.kind == KWEB_MENUS_ITEM_RADIO && node.checked) flags |= MF_CHECKED;
  AppendMenuW(menu, flags, id, text.c_str());
}

HMENU BuildMenu(WindowEntry &entry, const kwebshell::menus::Tree &tree, bool popup) {
  entry.command_by_id.clear();
  entry.id_by_command.clear();
  entry.accelerators.clear();
  HMENU menu = popup ? CreatePopupMenu() : CreateMenu();
  if (menu == nullptr) return nullptr;
  for (const size_t root : tree.roots) {
    AppendItems(entry, menu, tree, root);
  }
  for (const kwebshell::menus::Item &item : tree.items) {
    if (item.kind == KWEB_MENUS_ITEM_SEPARATOR) continue;
    if (item.kind == KWEB_MENUS_ITEM_SUBMENU) continue;
    if (item.accelerator_key == KWEB_MENUS_KEY_NONE) continue;
    WindowEntry::Accelerator accelerator;
    accelerator.modifiers = ModifierFlags(item.accelerator_modifiers);
    accelerator.virtual_key = KeyFor(item.accelerator_key).virtual_key;
    accelerator.command = item.id;
    if (accelerator.virtual_key != 0) entry.accelerators.push_back(accelerator);
  }
  return menu;
}

bool MatchesAccelerator(const WindowEntry::Accelerator &accelerator) {
  UINT modifiers = 0;
  if ((GetKeyState(VK_CONTROL) & 0x8000) != 0) modifiers |= MOD_CONTROL;
  if ((GetKeyState(VK_MENU) & 0x8000) != 0) modifiers |= MOD_ALT;
  if ((GetKeyState(VK_SHIFT) & 0x8000) != 0) modifiers |= MOD_SHIFT;
  if ((GetKeyState(VK_LWIN) & 0x8000) != 0 || (GetKeyState(VK_RWIN) & 0x8000) != 0) modifiers |= MOD_WIN;
  return modifiers == accelerator.modifiers;
}

void ReportCommand(WindowEntry &entry, const std::string &command) {
  kwebshell::menus::ReportInvocation(
      *g_state,
      entry.owner_kind == KWEB_MENUS_OWNER_APPLICATION ? KWEB_MENUS_SOURCE_APPLICATION_MENU
                                                       : KWEB_MENUS_SOURCE_WINDOW_MENU,
      entry.owner_kind, entry.window_id, command, entry.tree_version);
}

LRESULT CALLBACK MenuWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  WindowEntry *entry = reinterpret_cast<WindowEntry *>(GetWindowLongPtrW(window, GWLP_USERDATA));
  if (entry != nullptr && g_state != nullptr) {
    if (message == WM_COMMAND && HIWORD(wparam) == 0) {
      const auto found = entry->command_by_id.find(LOWORD(wparam));
      if (found != entry->command_by_id.end()) {
        ReportCommand(*entry, found->second);
        return 0;
      }
    }
    if (message == WM_KEYDOWN || message == WM_SYSKEYDOWN) {
      const UINT key = static_cast<UINT>(wparam);
      for (const WindowEntry::Accelerator &accelerator : entry->accelerators) {
        if (accelerator.virtual_key == key && MatchesAccelerator(accelerator)) {
          ReportCommand(*entry, accelerator.command);
          return 0;
        }
      }
    }
    if (message == WM_NCDESTROY) {
      entry->subclassed = false;
      entry->previous_proc = nullptr;
      SetWindowLongPtrW(window, GWLP_USERDATA, 0);
    }
  }
  if (entry != nullptr && entry->previous_proc != nullptr) {
    return CallWindowProcW(entry->previous_proc, window, message, wparam, lparam);
  }
  return DefWindowProcW(window, message, wparam, lparam);
}

void DestroyEntryMenu(WindowEntry &entry) {
  if (entry.window != nullptr && IsWindow(entry.window) && entry.subclassed) {
    SetMenu(entry.window, nullptr);
  }
  if (entry.window != nullptr && IsWindow(entry.window) && entry.subclassed &&
      entry.previous_proc != nullptr) {
    SetWindowLongPtrW(entry.window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(entry.previous_proc));
    SetWindowLongPtrW(entry.window, GWLP_USERDATA, 0);
    entry.subclassed = false;
    entry.previous_proc = nullptr;
  }
  if (entry.menu != nullptr) {
    DestroyMenu(entry.menu);
    entry.menu = nullptr;
  }
  entry.command_by_id.clear();
  entry.id_by_command.clear();
  entry.accelerators.clear();
  entry.tree_version = 0;
}

WindowsState *StateOf(kwebshell::menus::State &state) {
  return static_cast<WindowsState *>(state.platform);
}

}  // namespace

namespace kwebshell::menus {

const char *ProviderId() {
  return "menus.windows.win32";
}

kweb_menus_status NativeOpen(State &state) {
  auto windows = std::make_unique<WindowsState>();
  state.platform = windows.release();
  g_state = &state;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeCapabilities(State &, kweb_menus_capabilities_result *result) {
  // Win32 has no application-level menu bar: the service installs an
  // application tree on every declared window instead, so APPLICATION_MENU
  // stays unadvertised.
  result->flags = KWEB_MENUS_CAP_WINDOW_MENU | KWEB_MENUS_CAP_PAGE_MENU |
                  KWEB_MENUS_CAP_SUBMENUS | KWEB_MENUS_CAP_CHECKBOX_ITEMS | KWEB_MENUS_CAP_RADIO_ITEMS |
                  KWEB_MENUS_CAP_MNEMONICS | KWEB_MENUS_CAP_ACCELERATOR_DISPLAY |
                  KWEB_MENUS_CAP_ACCELERATOR_ACTIVATION | KWEB_MENUS_CAP_POPUP_POSITIONING;
  result->native_roles = 0;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeSetApplicationMenu(State &, const Tree *tree) {
  // Win32 has no application-level menu bar: the service installs the
  // application tree on each declared window instead.
  if (tree == nullptr) return KWEB_MENUS_STATUS_OK;
  return KWEB_MENUS_STATUS_TARGET_UNSUPPORTED;
}

kweb_menus_status NativeSetWindowMenu(State &state, const std::string &window_id, uint64_t native_window,
                                     kweb_menus_owner_kind owner_kind, const Tree *tree) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  auto existing = windows->windows.find(window_id);
  if (tree == nullptr) {
    if (existing == windows->windows.end()) return KWEB_MENUS_STATUS_OK;
    DestroyEntryMenu(*existing->second);
    return KWEB_MENUS_STATUS_OK;
  }
  const HWND window = reinterpret_cast<HWND>(static_cast<uintptr_t>(native_window));
  if (window == nullptr || !IsWindow(window)) return KWEB_MENUS_STATUS_WINDOW_UNKNOWN;
  WindowEntry *entry = nullptr;
  if (existing == windows->windows.end()) {
    auto created = std::make_unique<WindowEntry>();
    created->window_id = window_id;
    entry = created.get();
    windows->windows[window_id] = std::move(created);
  } else {
    entry = existing->second.get();
  }
  HMENU menu = BuildMenu(*entry, *tree, false);
  if (menu == nullptr) {
    TraceWin32Failure("build-menu");
    return KWEB_MENUS_STATUS_NATIVE_FAILED;
  }
  if (entry->menu != nullptr) {
    SetMenu(entry->window, nullptr);
    DestroyMenu(entry->menu);
  }
  entry->menu = menu;
  entry->window = window;
  entry->owner_kind = owner_kind;
  entry->tree_version = tree->version;
  if (!entry->subclassed) {
    SetLastError(0);
    const LONG_PTR previous = SetWindowLongPtrW(
        window, GWLP_WNDPROC, reinterpret_cast<LONG_PTR>(MenuWindowProc));
    if (previous == 0 && GetLastError() != 0) {
      TraceWin32Failure("subclass-window");
      DestroyMenu(menu);
      entry->menu = nullptr;
      return KWEB_MENUS_STATUS_NATIVE_FAILED;
    }
    entry->previous_proc = reinterpret_cast<WNDPROC>(previous);
    SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(entry));
    entry->subclassed = true;
  }
  if (!SetMenu(window, menu)) {
    TraceWin32Failure("set-menu");
    DestroyMenu(menu);
    entry->menu = nullptr;
    return KWEB_MENUS_STATUS_NATIVE_FAILED;
  }
  DrawMenuBar(window);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeShowPopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind,
                                  const std::string &owner_id, kweb_menus_popup_source, int32_t screen_x,
                                  int32_t screen_y, uint64_t native_window, const std::string &popup_id,
                                  kweb_menus_popup_result *result) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  HWND window = reinterpret_cast<HWND>(static_cast<uintptr_t>(native_window));
  if (window == nullptr || !IsWindow(window)) return KWEB_MENUS_STATUS_WINDOW_UNKNOWN;
  // The caller thread tracks the popup and receives the chosen command from
  // TrackPopupMenuEx, so popup commands never depend on WM_COMMAND routing.
  WindowEntry scratch;
  scratch.window_id = owner_id;
  scratch.owner_kind = owner_kind;
  scratch.tree_version = tree.version;
  HMENU menu = BuildMenu(scratch, tree, true);
  if (menu == nullptr) {
    TraceWin32Failure("build-popup-menu");
    return KWEB_MENUS_STATUS_NATIVE_FAILED;
  }
  if (GetForegroundWindow() != window) SetForegroundWindow(window);
  const UINT selected = TrackPopupMenuEx(
      menu, TPM_RETURNCMD | TPM_LEFTALIGN | TPM_TOPALIGN | TPM_LEFTBUTTON | TPM_RIGHTBUTTON,
      screen_x, screen_y, window, nullptr);
  DestroyMenu(menu);
  if (selected != 0) {
    const auto found = scratch.command_by_id.find(selected);
    if (found != scratch.command_by_id.end()) {
      CompletePopup(state, tree, owner_kind, owner_id, popup_id, found->second, KWEB_MENUS_DISMISS_USER,
                    result);
      return KWEB_MENUS_STATUS_OK;
    }
  }
  CompletePopup(state, tree, owner_kind, owner_id, popup_id, std::string(), KWEB_MENUS_DISMISS_USER, result);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeClose(State &state) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr) return KWEB_MENUS_STATUS_OK;
  for (auto &pair : windows->windows) {
    WindowEntry &entry = *pair.second;
    // DestroyEntryMenu also restores the original window procedure.
    DestroyEntryMenu(entry);
  }
  windows->windows.clear();
  state.platform = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete windows;
  return KWEB_MENUS_STATUS_OK;
}

}  // namespace kwebshell::menus
