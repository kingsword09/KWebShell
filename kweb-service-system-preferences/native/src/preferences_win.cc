#include "preferences_internal.h"

#if !defined(_WIN32)
#error "The Windows system-preferences provider must only be compiled on Windows."
#endif

#include <windows.h>
#include <dwmapi.h>

#include <cstdio>
#include <string>

namespace {

constexpr wchar_t kWindowClass[] = L"KWebShellPreferencesMessageWindow";
constexpr wchar_t kPersonalizeKey[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize";
constexpr wchar_t kAccessibilityKey[] = L"Software\\Microsoft\\Accessibility";

/** The message window, the registry watch, and the provider thread. */
struct WindowsState {
  HWND window = nullptr;
  HANDLE ready = nullptr;
  HANDLE stop = nullptr;
  HANDLE registry_event = nullptr;
  HANDLE personalize_key = nullptr;
  HANDLE accessibility_key = nullptr;
  HANDLE thread = nullptr;
};

kwebshell::preferences::State *g_state = nullptr;

bool ReadDword(HKEY key, const wchar_t *name, DWORD *value) {
  if (key == nullptr || value == nullptr) return false;
  DWORD type = 0;
  DWORD size = sizeof(DWORD);
  DWORD data = 0;
  if (RegQueryValueExW(key, name, nullptr, &type, reinterpret_cast<LPBYTE>(&data), &size) != ERROR_SUCCESS) {
    return false;
  }
  if (type != REG_DWORD || size != sizeof(DWORD)) return false;
  *value = data;
  return true;
}

/**
 * Reads every declared fact. Windows publishes the application theme,
 * transparency, and text scale through its preference stores, the contrast,
 * reduced-motion, and screen-reader facts through `SystemParametersInfo`, and the
 * accent color through DWM. It publishes no differentiate-without-color or
 * invert-colors fact, so those stay absent.
 */
void ReadFacts(WindowsState &windows, kwebshell::preferences::State &state, Facts *facts) {
  uint32_t published = 0;
  uint32_t live = 0;
  DWORD light = 0;
  if (ReadDword(static_cast<HKEY>(windows.personalize_key), L"AppsUseLightTheme", &light)) {
    facts->color_scheme = light == 0 ? KWEB_COLOR_SCHEME_DARK : KWEB_COLOR_SCHEME_LIGHT;
    published |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
    live |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
  }
  HIGHCONTRASTW contrast{};
  contrast.cbSize = sizeof(contrast);
  if (SystemParametersInfoW(SPI_GETHIGHCONTRAST, sizeof(contrast), &contrast, 0)) {
    facts->contrast = (contrast.dwFlags & HCF_HIGHCONTRASTON) != 0 ? KWEB_CONTRAST_FORCED_COLORS
                                                                  : KWEB_CONTRAST_NONE;
    published |= KWEB_PREFERENCE_FACT_CONTRAST;
    live |= KWEB_PREFERENCE_FACT_CONTRAST;
  }
  BOOL animations = TRUE;
  if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &animations, 0)) {
    facts->reduced_motion = animations ? KWEB_PREFERENCE_FALSE : KWEB_PREFERENCE_TRUE;
    published |= KWEB_PREFERENCE_FACT_REDUCED_MOTION;
    live |= KWEB_PREFERENCE_FACT_REDUCED_MOTION;
  }
  DWORD transparency = 0;
  if (ReadDword(static_cast<HKEY>(windows.personalize_key), L"EnableTransparency", &transparency)) {
    facts->reduced_transparency = transparency == 0 ? KWEB_PREFERENCE_TRUE : KWEB_PREFERENCE_FALSE;
    published |= KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY;
    live |= KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY;
  }
  DWORD colorization = 0;
  BOOL opaque = FALSE;
  if (SUCCEEDED(DwmGetColorizationColor(&colorization, &opaque))) {
    facts->accent_source = KWEB_ACCENT_SOURCE_SYSTEM_COLORIZATION;
    facts->accent_red = (colorization >> 16) & 0xffu;
    facts->accent_green = (colorization >> 8) & 0xffu;
    facts->accent_blue = colorization & 0xffu;
    published |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
    live |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
  }
  DWORD scale = 0;
  if (ReadDword(static_cast<HKEY>(windows.accessibility_key), L"TextScaleFactor", &scale) &&
      scale >= KWEB_PREFERENCES_MIN_TEXT_SCALE && scale <= KWEB_PREFERENCES_MAX_TEXT_SCALE) {
    facts->text_scale_percent = scale;
    published |= KWEB_PREFERENCE_FACT_TEXT_SCALE;
    live |= KWEB_PREFERENCE_FACT_TEXT_SCALE;
  }
  const bool declared_keys = (state.sensitive_key_bits & state.target_key_bit) != 0;
  if (declared_keys) {
    // The assistive-technology fact is read only under its declared key.
    BOOL reader = FALSE;
    if (SystemParametersInfoW(SPI_GETSCREENREADER, 0, &reader, 0)) {
      facts->screen_reader = reader ? KWEB_PREFERENCE_TRUE : KWEB_PREFERENCE_FALSE;
      published |= KWEB_PREFERENCE_FACT_SCREEN_READER;
      live |= KWEB_PREFERENCE_FACT_SCREEN_READER;
    }
  }
  facts->published_bits = published;
  facts->live_bits = live;
}

LRESULT CALLBACK PreferencesWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  (void)wparam;
  (void)lparam;
  switch (message) {
    case WM_SETTINGCHANGE:
    case WM_THEMECHANGED:
    case WM_DWMCOLORIZATIONCOLORCHANGED:
      if (g_state != nullptr) kwebshell::preferences::PushChanged(*g_state);
      return 0;
    default:
      return DefWindowProcW(window, message, wparam, lparam);
  }
}

/** Owns the message loop and the registry watch of the opened service. */
DWORD WINAPI ProviderThread(void *context) {
  auto *windows = static_cast<WindowsState *>(context);
  windows->window = CreateWindowExW(0, kWindowClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr,
                                    GetModuleHandleW(nullptr), nullptr);
  SetEvent(windows->ready);
  if (windows->window == nullptr) return 0;
  HANDLE handles[2] = {windows->registry_event, windows->stop};
  for (;;) {
    // The watch is one-shot, so it is armed again before every wait.
    if (windows->personalize_key != nullptr) {
      RegNotifyChangeKeyValue(static_cast<HKEY>(windows->personalize_key), TRUE, REG_NOTIFY_CHANGE_LAST_SET,
                              windows->registry_event, TRUE);
    }
    const DWORD wait = MsgWaitForMultipleObjectsEx(2, handles, INFINITE, QS_ALLINPUT, 0);
    if (wait == WAIT_OBJECT_0) {
      if (g_state != nullptr) kwebshell::preferences::PushChanged(*g_state);
      continue;
    }
    if (wait == WAIT_OBJECT_0 + 1) return 0;
    MSG message;
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
      if (message.message == WM_QUIT) return 0;
      TranslateMessage(&message);
      DispatchMessageW(&message);
    }
  }
}

}  // namespace

namespace kwebshell::preferences {

const char *ProviderId() { return "preferences.windows.system-parameters"; }

namespace {

/** Releases every Windows resource of a failed or finished open. */
void ReleaseWindows(WindowsState &windows, bool join_thread) {
  if (windows.stop != nullptr) SetEvent(windows.stop);
  if (join_thread && windows.thread != nullptr) {
    WaitForSingleObject(windows.thread, 5000);
  }
  if (windows.thread != nullptr) CloseHandle(windows.thread);
  if (windows.window != nullptr) DestroyWindow(windows.window);
  if (windows.personalize_key != nullptr) RegCloseKey(static_cast<HKEY>(windows.personalize_key));
  if (windows.accessibility_key != nullptr) RegCloseKey(static_cast<HKEY>(windows.accessibility_key));
  if (windows.ready != nullptr) CloseHandle(windows.ready);
  if (windows.stop != nullptr) CloseHandle(windows.stop);
  if (windows.registry_event != nullptr) CloseHandle(windows.registry_event);
}

}  // namespace

kweb_preferences_status NativeOpen(State &state) {
  auto *windows = new WindowsState();
  state.platform = windows;
  state.target_key_bit = KWEB_PREFERENCES_KEY_WINDOWS_SCREEN_READER;
  windows->ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  windows->stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  windows->registry_event = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (windows->ready == nullptr || windows->stop == nullptr || windows->registry_event == nullptr) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_PREFERENCES_STATUS_NATIVE_FAILED;
  }
  HKEY personalize = nullptr;
  if (RegOpenKeyExW(HKEY_CURRENT_USER, kPersonalizeKey, 0, KEY_READ | KEY_NOTIFY, &personalize) ==
      ERROR_SUCCESS) {
    windows->personalize_key = personalize;
  }
  HKEY accessibility = nullptr;
  if (RegOpenKeyExW(HKEY_CURRENT_USER, kAccessibilityKey, 0, KEY_READ | KEY_NOTIFY, &accessibility) ==
      ERROR_SUCCESS) {
    windows->accessibility_key = accessibility;
  }
  WNDCLASSEXW definition{};
  definition.cbSize = sizeof(WNDCLASSEXW);
  definition.lpfnWndProc = PreferencesWindowProc;
  definition.hInstance = GetModuleHandleW(nullptr);
  definition.lpszClassName = kWindowClass;
  if (RegisterClassExW(&definition) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_PREFERENCES_STATUS_NATIVE_FAILED;
  }
  windows->thread = CreateThread(nullptr, 0, ProviderThread, windows, 0, nullptr);
  if (windows->thread == nullptr) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_PREFERENCES_STATUS_NATIVE_FAILED;
  }
  const DWORD started = WaitForSingleObject(windows->ready, 5000);
  if (started != WAIT_OBJECT_0 || windows->window == nullptr) {
    ReleaseWindows(*windows, true);
    delete windows;
    state.platform = nullptr;
    return KWEB_PREFERENCES_STATUS_NATIVE_UNAVAILABLE;
  }
  g_state = &state;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeReadFacts(State &state, Facts *facts) {
  auto *windows = static_cast<WindowsState *>(state.platform);
  if (windows == nullptr || facts == nullptr) return KWEB_PREFERENCES_STATUS_PLATFORM_UNAVAILABLE;
  Facts result;
  ReadFacts(*windows, state, &result);
  *facts = result;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeRequestAppearance(State &state, uint32_t source, uint32_t *effective) {
  (void)state;
  if (effective == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  if (source != KWEB_APPEARANCE_SYSTEM) {
    // Win32 publishes no documented per-application appearance override.
    return KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED;
  }
  *effective = KWEB_APPEARANCE_SYSTEM;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeClose(State &state) {
  auto *windows = static_cast<WindowsState *>(state.platform);
  if (windows == nullptr) return KWEB_PREFERENCES_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  ReleaseWindows(*windows, true);
  delete windows;
  state.platform = nullptr;
  return KWEB_PREFERENCES_STATUS_OK;
}

}  // namespace kwebshell::preferences
