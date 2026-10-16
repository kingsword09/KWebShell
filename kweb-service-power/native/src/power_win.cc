#include "power_internal.h"

#if !defined(_WIN32)
#error "The Win32 power provider must only be compiled on Windows."
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

using kwebshell::power::Facts;
using kwebshell::power::Lease;
using kwebshell::power::State;

constexpr char kProviderId[] = "power.windows.power-request";
constexpr wchar_t kWindowClass[] = L"KWebShellPowerWindow";

struct NativeState {
  HWND window = nullptr;
  DWORD thread_id = 0;
  HANDLE ready = nullptr;
  std::thread thread;
  bool session_registered = false;
};

NativeState *g_native = nullptr;
State *g_state = nullptr;

/** Reads the AC/battery facts the session publishes. */
void ReadPowerStatus(Facts *facts) {
  SYSTEM_POWER_STATUS status{};
  if (GetSystemPowerStatus(&status) == FALSE) return;
  if (status.ACLineStatus == 0 || status.ACLineStatus == 1) {
    facts->fact_bits |= KWEB_POWER_FACT_ON_BATTERY;
    facts->on_battery = status.ACLineStatus == 0;
  }
  if (status.BatteryLifePercent != 255) {
    facts->fact_bits |= KWEB_POWER_FACT_BATTERY_PERCENT;
    facts->battery_percent = status.BatteryLifePercent;
  }
}

/** The execution-state request type one blocker maps onto. */
POWER_REQUEST_TYPE RequestTypeOf(uint32_t type) {
  return type == KWEB_POWER_TYPE_SYSTEM_SLEEP ? PowerRequestSystemRequired
                                              : PowerRequestDisplayRequired;
}

LRESULT CALLBACK WindowProcedure(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
  switch (message) {
    case WM_POWERBROADCAST:
      if (g_state == nullptr) return TRUE;
      switch (wparam) {
        case PBT_APMSUSPEND:
          kwebshell::power::PublishSuspended(*g_state);
          return TRUE;
        case PBT_APMRESUMEAUTOMATIC:
        case PBT_APMRESUMESUSPEND:
          kwebshell::power::PublishResumed(*g_state);
          return TRUE;
        case PBT_APMPOWERSTATUSCHANGE: {
          Facts facts;
          {
            std::lock_guard<std::mutex> lock(g_state->mutex);
            facts = g_state->facts;
          }
          ReadPowerStatus(&facts);
          if ((facts.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0) {
            kwebshell::power::PublishPowerSource(*g_state, facts.on_battery);
          }
          return TRUE;
        }
        default:
          break;
      }
      return TRUE;
    case WM_WTSSESSION_CHANGE:
      if (g_state == nullptr) return 0;
      if (wparam == WTS_SESSION_LOCK) {
        kwebshell::power::PublishLocked(*g_state);
      } else if (wparam == WTS_SESSION_UNLOCK) {
        kwebshell::power::PublishUnlocked(*g_state);
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

namespace kwebshell::power {

kweb_power_status NativeOpen(State &state) {
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
    return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  }
  state.capabilities.provider_id = kProviderId;
  state.capabilities.capability_bits = KWEB_POWER_CAP_SUSPEND_EVENTS | KWEB_POWER_CAP_LOCK_EVENTS |
                                       KWEB_POWER_CAP_POWER_SOURCE_STATE |
                                       KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER |
                                       KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER | KWEB_POWER_CAP_LEASE_RENEWAL;
  state.capabilities.max_leases = KWEB_POWER_MAX_LEASES;
  state.capabilities.max_leases_per_type = 4;
  state.capabilities.min_lease_seconds = 1;
  state.capabilities.max_lease_seconds = 14400;
  Facts facts;
  ReadPowerStatus(&facts);
  state.facts = facts;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeReadFacts(State &state, Facts *facts) {
  if (static_cast<NativeState *>(state.platform) == nullptr || facts == nullptr) {
    return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  }
  ReadPowerStatus(facts);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeAcquire(State &state, const Lease &lease, uint64_t *token) {
  if (static_cast<NativeState *>(state.platform) == nullptr || token == nullptr) {
    return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  }
  POWER_REQUEST_CONTEXT context{};
  context.Version = POWER_REQUEST_CONTEXT_VERSION;
  context.Flags = POWER_REQUEST_CONTEXT_SIMPLE_STRING;
  context.Reason.SimpleReasonString = const_cast<PWSTR>(L"KWebShell application power lease");
  HANDLE request = PowerCreateRequest(&context);
  if (request == INVALID_HANDLE_VALUE) return KWEB_POWER_STATUS_NATIVE_FAILED;
  if (PowerSetRequest(request, RequestTypeOf(lease.type)) == FALSE) {
    CloseHandle(request);
    return KWEB_POWER_STATUS_NATIVE_FAILED;
  }
  *token = reinterpret_cast<uint64_t>(request);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeRelease(State &state, const Lease &lease) {
  if (static_cast<NativeState *>(state.platform) == nullptr) {
    return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  }
  if (lease.token == 0) return KWEB_POWER_STATUS_OK;
  HANDLE request = reinterpret_cast<HANDLE>(lease.token);
  const BOOL cleared = PowerClearRequest(request, RequestTypeOf(lease.type));
  CloseHandle(request);
  return cleared == FALSE ? KWEB_POWER_STATUS_NATIVE_FAILED : KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeClose(State &state) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_POWER_STATUS_OK;
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
  return KWEB_POWER_STATUS_OK;
}

}  // namespace kwebshell::power
