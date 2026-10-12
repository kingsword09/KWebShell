#include "shortcuts_internal.h"

#if !defined(_WIN32)
#error "The Win32 shortcut provider must only be compiled on Windows."
#endif

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <wtsapi32.h>

#include <string>
#include <thread>
#include <vector>

namespace {

using kwebshell::shortcuts::Registration;
using kwebshell::shortcuts::State;

constexpr char kProviderId[] = "shortcuts.windows.register-hotkey";
constexpr wchar_t kWindowClass[] = L"KWebShellShortcutWindow";

/** One registered hotkey and the registration it represents. */
struct Entry {
  Registration registration;
  int hotkey_id = 0;
  UINT modifiers = 0;
  UINT virtual_key = 0;
};

struct NativeState {
  HWND window = nullptr;
  DWORD thread_id = 0;
  HANDLE ready = nullptr;
  std::thread thread;
  std::vector<Entry> entries;
  int next_id = 1;
  bool session_registered = false;
};

NativeState *g_native = nullptr;
State *g_state = nullptr;

UINT ModifiersOf(uint32_t bits) {
  UINT modifiers = MOD_NOREPEAT;
  if ((bits & KWEB_SHORTCUTS_MOD_CONTROL) != 0) modifiers |= MOD_CONTROL;
  if ((bits & KWEB_SHORTCUTS_MOD_ALT) != 0) modifiers |= MOD_ALT;
  if ((bits & KWEB_SHORTCUTS_MOD_SHIFT) != 0) modifiers |= MOD_SHIFT;
  if ((bits & KWEB_SHORTCUTS_MOD_META) != 0) modifiers |= MOD_WIN;
  return modifiers;
}

/** The Win32 virtual key for one declared named or media key, or 0 when unmapped. */
UINT VirtualKeyOf(uint32_t key_kind, uint32_t key_code) {
  if (key_kind == KWEB_SHORTCUTS_KEY_MEDIA) {
    switch (key_code) {
      case KWEB_SHORTCUTS_MEDIA_PLAY_PAUSE: return VK_MEDIA_PLAY_PAUSE;
      case KWEB_SHORTCUTS_MEDIA_NEXT_TRACK: return VK_MEDIA_NEXT_TRACK;
      case KWEB_SHORTCUTS_MEDIA_PREVIOUS_TRACK: return VK_MEDIA_PREV_TRACK;
      case KWEB_SHORTCUTS_MEDIA_STOP: return VK_MEDIA_STOP;
      case KWEB_SHORTCUTS_MEDIA_VOLUME_UP: return VK_VOLUME_UP;
      case KWEB_SHORTCUTS_MEDIA_VOLUME_DOWN: return VK_VOLUME_DOWN;
      case KWEB_SHORTCUTS_MEDIA_MUTE: return VK_VOLUME_MUTE;
      default: return 0;
    }
  }
  if (key_code >= 'A' && key_code <= 'Z') return key_code;
  if (key_code >= '0' && key_code <= '9') return key_code;
  if (key_code >= KWEB_SHORTCUTS_NAMED_F1 && key_code <= KWEB_SHORTCUTS_NAMED_F24) {
    return VK_F1 + (key_code - KWEB_SHORTCUTS_NAMED_F1);
  }
  switch (key_code) {
    case KWEB_SHORTCUTS_NAMED_SPACE: return VK_SPACE;
    case KWEB_SHORTCUTS_NAMED_TAB: return VK_TAB;
    case KWEB_SHORTCUTS_NAMED_ENTER: return VK_RETURN;
    case KWEB_SHORTCUTS_NAMED_ESCAPE: return VK_ESCAPE;
    case KWEB_SHORTCUTS_NAMED_BACKSPACE: return VK_BACK;
    case KWEB_SHORTCUTS_NAMED_DELETE: return VK_DELETE;
    case KWEB_SHORTCUTS_NAMED_INSERT: return VK_INSERT;
    case KWEB_SHORTCUTS_NAMED_HOME: return VK_HOME;
    case KWEB_SHORTCUTS_NAMED_END: return VK_END;
    case KWEB_SHORTCUTS_NAMED_PAGE_UP: return VK_PRIOR;
    case KWEB_SHORTCUTS_NAMED_PAGE_DOWN: return VK_NEXT;
    case KWEB_SHORTCUTS_NAMED_ARROW_LEFT: return VK_LEFT;
    case KWEB_SHORTCUTS_NAMED_ARROW_RIGHT: return VK_RIGHT;
    case KWEB_SHORTCUTS_NAMED_ARROW_UP: return VK_UP;
    case KWEB_SHORTCUTS_NAMED_ARROW_DOWN: return VK_DOWN;
    case KWEB_SHORTCUTS_NAMED_MINUS: return VK_OEM_MINUS;
    case KWEB_SHORTCUTS_NAMED_EQUALS: return VK_OEM_PLUS;
    case KWEB_SHORTCUTS_NAMED_BRACKET_LEFT: return VK_OEM_4;
    case KWEB_SHORTCUTS_NAMED_BRACKET_RIGHT: return VK_OEM_6;
    case KWEB_SHORTCUTS_NAMED_BACKSLASH: return VK_OEM_5;
    case KWEB_SHORTCUTS_NAMED_SEMICOLON: return VK_OEM_1;
    case KWEB_SHORTCUTS_NAMED_QUOTE: return VK_OEM_7;
    case KWEB_SHORTCUTS_NAMED_COMMA: return VK_OEM_COMMA;
    case KWEB_SHORTCUTS_NAMED_PERIOD: return VK_OEM_PERIOD;
    case KWEB_SHORTCUTS_NAMED_SLASH: return VK_OEM_2;
    case KWEB_SHORTCUTS_NAMED_BACKQUOTE: return VK_OEM_3;
    default: return 0;
  }
}

/** Releases every live hotkey and reports the reason it happened. */
void ReleaseAll(UINT reason) {
  if (g_native == nullptr) return;
  const std::vector<Entry> entries = g_native->entries;
  g_native->entries.clear();
  for (const Entry &entry : entries) {
    UnregisterHotKey(g_native->window, entry.hotkey_id);
    if (g_state != nullptr) kwebshell::shortcuts::PublishLost(*g_state, entry.registration, reason);
  }
}

/** Reinstalls every registration the state currently owns after an unlock. */
void RestoreAll() {
  if (g_native == nullptr || g_state == nullptr) return;
  for (const auto &registration : g_state->registrations) {
    kwebshell::shortcuts::NativeRegister(*g_state, registration);
  }
}

LRESULT CALLBACK WindowProcedure(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  switch (message) {
    case WM_HOTKEY: {
      if (g_native == nullptr) return 0;
      for (const Entry &entry : g_native->entries) {
        if (entry.hotkey_id == static_cast<int>(wparam)) {
          if (g_state != nullptr) {
            kwebshell::shortcuts::PublishInvocation(*g_state, entry.registration,
                                                    KWEB_SHORTCUTS_SOURCE_PLATFORM);
          }
          break;
        }
      }
      return 0;
    }
    case WM_WTSSESSION_CHANGE:
      if (wparam == WTS_SESSION_LOCK) {
        ReleaseAll(KWEB_SHORTCUTS_LOSS_SESSION_LOCK);
      } else if (wparam == WTS_SESSION_UNLOCK) {
        RestoreAll();
      }
      return 0;
    case WM_DESTROY:
      PostQuitMessage(0);
      return 0;
    default:
      break;
  }
  return DefWindowProcW(window, message, wparam, lparam);
}

void MessageThread() {
  NativeState *native = g_native;
  WNDCLASSEXW window_class{};
  window_class.cbSize = sizeof(window_class);
  window_class.lpfnWndProc = WindowProcedure;
  window_class.hInstance = GetModuleHandleW(nullptr);
  window_class.lpszClassName = kWindowClass;
  RegisterClassExW(&window_class);
  native->window = CreateWindowExW(0, kWindowClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr,
                                   window_class.hInstance, nullptr);
  native->thread_id = GetCurrentThreadId();
  native->session_registered =
      native->window != nullptr &&
      WTSRegisterSessionNotification(native->window, NOTIFY_FOR_THIS_SESSION) != FALSE;
  SetEvent(native->ready);
  if (native->window == nullptr) return;
  MSG message{};
  while (GetMessageW(&message, nullptr, 0, 0) > 0) {
    TranslateMessage(&message);
    DispatchMessageW(&message);
  }
}

}  // namespace

namespace kwebshell::shortcuts {

kweb_shortcuts_status NativeOpen(State &state) {
  auto *native = new NativeState();
  native->ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  state.platform = native;
  g_native = native;
  g_state = &state;
  native->thread = std::thread(MessageThread);
  WaitForSingleObject(native->ready, 5000);
  CloseHandle(native->ready);
  native->ready = nullptr;
  if (native->window == nullptr) {
    if (native->thread.joinable()) native->thread.join();
    g_native = nullptr;
    g_state = nullptr;
    delete native;
    state.platform = nullptr;
    return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
  state.capabilities.provider_id = kProviderId;
  state.capabilities.capability_bits = KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION |
                                       KWEB_SHORTCUTS_CAP_MEDIA_KEYS |
                                       KWEB_SHORTCUTS_CAP_ATOMIC_REPLACEMENT |
                                       KWEB_SHORTCUTS_CAP_INVOCATION_REPORTING;
  if (native->session_registered) {
    state.capabilities.capability_bits |= KWEB_SHORTCUTS_CAP_SESSION_LOCK_RELEASE;
  }
  state.capabilities.modifier_bits = KWEB_SHORTCUTS_MOD_ALL;
  state.capabilities.media_key_bits = KWEB_SHORTCUTS_MEDIA_ALL;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeRegister(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr || native->window == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  const UINT virtual_key = VirtualKeyOf(registration.key_kind, registration.key_code);
  if (virtual_key == 0) return KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED;
  const UINT modifiers = ModifiersOf(registration.modifier_bits);
  Entry entry;
  entry.registration = registration;
  entry.hotkey_id = native->next_id++;
  entry.modifiers = modifiers;
  entry.virtual_key = virtual_key;
  if (RegisterHotKey(native->window, entry.hotkey_id, modifiers, virtual_key) == FALSE) {
    const DWORD error = GetLastError();
    if (error == ERROR_HOTKEY_ALREADY_REGISTERED) return KWEB_SHORTCUTS_STATUS_CONFLICT;
    return KWEB_SHORTCUTS_STATUS_NATIVE_FAILED;
  }
  native->entries.push_back(entry);
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeUnregister(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  for (size_t index = 0; index < native->entries.size(); ++index) {
    if (native->entries[index].registration.id != registration.id) continue;
    UnregisterHotKey(native->window, native->entries[index].hotkey_id);
    native->entries.erase(native->entries.begin() + static_cast<long>(index));
    return KWEB_SHORTCUTS_STATUS_OK;
  }
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeClose(State &state) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_OK;
  for (const Entry &entry : native->entries) {
    UnregisterHotKey(native->window, entry.hotkey_id);
  }
  native->entries.clear();
  if (native->session_registered && native->window != nullptr) {
    WTSUnRegisterSessionNotification(native->window);
    native->session_registered = false;
  }
  if (native->window != nullptr) {
    PostMessageW(native->window, WM_CLOSE, 0, 0);
    PostThreadMessageW(native->thread_id, WM_QUIT, 0, 0);
  }
  if (native->thread.joinable()) native->thread.join();
  if (native->window != nullptr) {
    DestroyWindow(native->window);
    native->window = nullptr;
  }
  if (g_native == native) g_native = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete native;
  state.platform = nullptr;
  return KWEB_SHORTCUTS_STATUS_OK;
}

}  // namespace kwebshell::shortcuts
