#include "screen_internal.h"

#if !defined(_WIN32)
#error "The Windows screen provider must only be compiled on Windows."
#endif

#include <windows.h>
#include <shellscalingapi.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

namespace {

using ScreenDisplay = kwebshell::screen::Display;
using kwebshell::screen::State;
using kwebshell::screen::Topology;

constexpr char kProviderId[] = "screen.windows.monitor-dpi";
constexpr wchar_t kWindowClass[] = L"KWebShellScreenMessageWindow";

/** The hidden message window the provider watches display changes on. */
struct WindowsState {
  HWND window = nullptr;
  HANDLE ready = nullptr;
  HANDLE stop = nullptr;
  HANDLE thread = nullptr;
};

State *g_state = nullptr;

WindowsState *StateOf(State &state) { return static_cast<WindowsState *>(state.platform); }

/** The monitor device name without its object-manager prefix. */
std::string DisplayId(const wchar_t *device) {
  std::wstring name(device != nullptr ? device : L"");
  const size_t separator = name.find_last_of(L'\\');
  if (separator != std::wstring::npos) name = name.substr(separator + 1);
  std::string ascii;
  ascii.reserve(name.size());
  for (const wchar_t character : name) {
    ascii.push_back(character >= 0 && character < 0x80 ? static_cast<char>(character) : '_');
  }
  if (ascii.empty()) ascii = "monitor";
  return "display." + ascii;
}

uint32_t RotationFact(DWORD orientation) {
  switch (orientation) {
    case DMDO_DEFAULT:
      return KWEB_SCREEN_ROTATION_NONE;
    case DMDO_90:
      return KWEB_SCREEN_ROTATION_QUAD_1;
    case DMDO_180:
      return KWEB_SCREEN_ROTATION_QUAD_2;
    case DMDO_270:
      return KWEB_SCREEN_ROTATION_QUAD_3;
    default:
      return 0;
  }
}

/**
 * Converts one physical pixel coordinate into the 96-DPI logical space Windows
 * publishes for DPI-unaware callers and for Compose placement.
 */
int32_t ToLogical(int32_t physical, UINT dpi) {
  if (dpi == 0) return physical;
  return static_cast<int32_t>(std::lround(static_cast<double>(physical) * 96.0 / static_cast<double>(dpi)));
}

struct MonitorContext {
  std::vector<ScreenDisplay> *displays = nullptr;
  uint32_t *capability_bits = nullptr;
};

BOOL CALLBACK CollectMonitor(HMONITOR monitor, HDC, LPRECT, LPARAM user_data) {
  auto *context = reinterpret_cast<MonitorContext *>(user_data);
  MONITORINFOEXW info{};
  info.cbSize = sizeof(info);
  if (!GetMonitorInfoW(monitor, &info)) return TRUE;
  UINT dpi_x = 96;
  UINT dpi_y = 96;
  // MDT_EFFECTIVE_DPI is the scale the user configured for this monitor.
  if (GetDpiForMonitor(monitor, MDT_EFFECTIVE_DPI, &dpi_x, &dpi_y) != S_OK || dpi_x == 0) dpi_x = 96;
  ScreenDisplay display;
  display.id = DisplayId(info.szDevice);
  display.name = display.id;
  const RECT &physical = info.rcMonitor;
  display.native_left = physical.left;
  display.native_top = physical.top;
  display.native_width = physical.right - physical.left;
  display.native_height = physical.bottom - physical.top;
  display.left = ToLogical(physical.left, dpi_x);
  display.top = ToLogical(physical.top, dpi_x);
  display.width = ToLogical(display.native_width, dpi_x);
  display.height = ToLogical(display.native_height, dpi_x);
  if (display.width < 1) display.width = 1;
  if (display.height < 1) display.height = 1;
  const uint32_t permille = static_cast<uint32_t>(std::lround(static_cast<double>(dpi_x) * 1000.0 / 96.0));
  if (permille >= KWEB_SCREEN_MIN_SCALE_PERMILLE && permille <= KWEB_SCREEN_MAX_SCALE_PERMILLE) {
    display.scale_permille = permille;
    display.fact_bits |= KWEB_SCREEN_FACT_SCALE;
    *context->capability_bits |= KWEB_SCREEN_CAP_SCALE;
  }
  const RECT &work = info.rcWork;
  const int32_t work_width = ToLogical(work.right - work.left, dpi_x);
  const int32_t work_height = ToLogical(work.bottom - work.top, dpi_x);
  if (work_width > 0 && work_height > 0) {
    display.work_left = ToLogical(work.left, dpi_x);
    display.work_top = ToLogical(work.top, dpi_x);
    display.work_width = work_width;
    display.work_height = work_height;
    display.fact_bits |= KWEB_SCREEN_FACT_WORK_AREA;
    *context->capability_bits |= KWEB_SCREEN_CAP_WORK_AREA;
  }
  DEVMODEW mode{};
  mode.dmSize = sizeof(mode);
  if (EnumDisplaySettingsW(info.szDevice, ENUM_CURRENT_SETTINGS, &mode)) {
    if (mode.dmDisplayFrequency > 1 &&
        mode.dmDisplayFrequency <= KWEB_SCREEN_MAX_REFRESH_HERTZ) {
      display.refresh_hertz = mode.dmDisplayFrequency;
      display.fact_bits |= KWEB_SCREEN_FACT_REFRESH_RATE;
      *context->capability_bits |= KWEB_SCREEN_CAP_REFRESH_RATE;
    }
    if (mode.dmBitsPerPel > 0 && mode.dmBitsPerPel <= KWEB_SCREEN_MAX_COLOR_DEPTH) {
      display.color_depth_bits = mode.dmBitsPerPel;
      display.fact_bits |= KWEB_SCREEN_FACT_COLOR_DEPTH;
      *context->capability_bits |= KWEB_SCREEN_CAP_COLOR_DEPTH;
    }
    const uint32_t rotation = RotationFact(mode.dmDisplayOrientation);
    if (rotation != 0) {
      display.rotation = rotation;
      display.fact_bits |= KWEB_SCREEN_FACT_ROTATION;
      *context->capability_bits |= KWEB_SCREEN_CAP_ROTATION;
    }
  }
  if ((info.dwFlags & MONITORINFOF_PRIMARY) != 0) display.fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
  context->displays->push_back(display);
  return TRUE;
}

kweb_screen_status ReadTopology(Topology *topology) {
  topology->provider_id = kProviderId;
  topology->session = KWEB_SCREEN_SESSION_WINDOWS;
  topology->capability_bits = KWEB_SCREEN_CAP_CURSOR | KWEB_SCREEN_CAP_TOPOLOGY_EVENTS |
                              KWEB_SCREEN_CAP_MULTI_DISPLAY;
  topology->displays.clear();
  MonitorContext context{&topology->displays, &topology->capability_bits};
  if (EnumDisplayMonitors(nullptr, nullptr, CollectMonitor, reinterpret_cast<LPARAM>(&context)) == FALSE ||
      topology->displays.empty()) {
    return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  }
  return KWEB_SCREEN_STATUS_OK;
}

void PublishCurrentTopology() {
  if (g_state == nullptr) return;
  Topology topology;
  if (ReadTopology(&topology) != KWEB_SCREEN_STATUS_OK) return;
  if (!kwebshell::screen::ValidTopology(topology)) {
    kwebshell::screen::PushFailed(*g_state, "screen.topology-invalid");
    return;
  }
  kwebshell::screen::PublishTopology(*g_state, topology);
}

LRESULT CALLBACK ScreenWindowProc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  (void)wparam;
  (void)lparam;
  switch (message) {
    case WM_DISPLAYCHANGE:
    case WM_DPICHANGED:
    case WM_SETTINGCHANGE:
      PublishCurrentTopology();
      return 0;
    default:
      return DefWindowProcW(window, message, wparam, lparam);
  }
}

DWORD WINAPI ProviderThread(void *context) {
  auto *windows = static_cast<WindowsState *>(context);
  windows->window = CreateWindowExW(0, kWindowClass, L"", 0, 0, 0, 0, 0, HWND_MESSAGE, nullptr,
                                    GetModuleHandleW(nullptr), nullptr);
  SetEvent(windows->ready);
  if (windows->window == nullptr) return 0;
  MSG message;
  for (;;) {
    const DWORD wait = MsgWaitForMultipleObjects(1, &windows->stop, FALSE, INFINITE, QS_ALLINPUT);
    if (wait == WAIT_OBJECT_0) return 0;
    while (PeekMessageW(&message, nullptr, 0, 0, PM_REMOVE)) {
      if (message.message == WM_QUIT) return 0;
      TranslateMessage(&message);
      DispatchMessageW(&message);
    }
  }
}

/** Releases every Windows resource of a failed or finished open. */
void ReleaseWindows(WindowsState &windows, bool join_thread) {
  if (windows.stop != nullptr) SetEvent(windows.stop);
  if (join_thread && windows.thread != nullptr) WaitForSingleObject(windows.thread, 5000);
  if (windows.thread != nullptr) CloseHandle(windows.thread);
  if (windows.ready != nullptr) CloseHandle(windows.ready);
  if (windows.stop != nullptr) CloseHandle(windows.stop);
}

}  // namespace

namespace kwebshell::screen {

const char *ProviderId() { return kProviderId; }

kweb_screen_status NativeOpen(State &state) {
  auto *windows = new WindowsState();
  state.platform = windows;
  windows->ready = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  windows->stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
  if (windows->ready == nullptr || windows->stop == nullptr) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_NATIVE_FAILED;
  }
  WNDCLASSEXW definition{};
  definition.cbSize = sizeof(WNDCLASSEXW);
  definition.lpfnWndProc = ScreenWindowProc;
  definition.hInstance = GetModuleHandleW(nullptr);
  definition.lpszClassName = kWindowClass;
  if (RegisterClassExW(&definition) == 0 && GetLastError() != ERROR_CLASS_ALREADY_EXISTS) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_NATIVE_FAILED;
  }
  windows->thread = CreateThread(nullptr, 0, ProviderThread, windows, 0, nullptr);
  if (windows->thread == nullptr) {
    ReleaseWindows(*windows, false);
    delete windows;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_NATIVE_FAILED;
  }
  const DWORD started = WaitForSingleObject(windows->ready, 5000);
  if (started != WAIT_OBJECT_0 || windows->window == nullptr) {
    ReleaseWindows(*windows, true);
    delete windows;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
  g_state = &state;
  Topology topology;
  const kweb_screen_status read = ReadTopology(&topology);
  if (read != KWEB_SCREEN_STATUS_OK) {
    NativeClose(state);
    return read;
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status NativeReadTopology(State &state, Topology *topology) {
  if (StateOf(state) == nullptr || topology == nullptr) return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  return ReadTopology(topology);
}

kweb_screen_status NativeReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                                    bool *display_known) {
  if (StateOf(state) == nullptr || x == nullptr || y == nullptr || display_id == nullptr ||
      display_known == nullptr) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  POINT point{};
  if (GetCursorPos(&point) == FALSE) return KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE;
  // A per-monitor DPI aware process receives physical pixels; otherwise Windows
  // reports the 96-DPI logical space and the cursor keeps that space.
  const bool physical = AreDpiAwarenessContextsEqual(GetThreadDpiAwarenessContext(),
                                                     DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2) ||
                        AreDpiAwarenessContextsEqual(GetThreadDpiAwarenessContext(),
                                                     DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE);
  *x = point.x;
  *y = point.y;
  *display_known = false;
  display_id->clear();
  Topology topology;
  if (ReadTopology(&topology) != KWEB_SCREEN_STATUS_OK) return KWEB_SCREEN_STATUS_OK;
  for (const ScreenDisplay &display : topology.displays) {
    const int32_t left = physical ? display.native_left : display.left;
    const int32_t top = physical ? display.native_top : display.top;
    const int32_t width = physical ? display.native_width : display.width;
    const int32_t height = physical ? display.native_height : display.height;
    if (point.x >= left && point.x < left + width && point.y >= top && point.y < top + height) {
      *display_id = display.id;
      *display_known = true;
      break;
    }
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status NativeClose(State &state) {
  WindowsState *windows = StateOf(state);
  if (windows == nullptr) return KWEB_SCREEN_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  ReleaseWindows(*windows, true);
  if (windows->window != nullptr) DestroyWindow(windows->window);
  delete windows;
  state.platform = nullptr;
  return KWEB_SCREEN_STATUS_OK;
}

}  // namespace kwebshell::screen
