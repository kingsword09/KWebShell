#include "shortcuts_internal.h"

#if !defined(__linux__)
#error "The Linux shortcut session dispatcher must only be compiled on Linux."
#endif

#include <cstdlib>

namespace kwebshell::shortcuts::x11_provider {

const char *ProviderId();
kweb_shortcuts_status Open(State &state);
kweb_shortcuts_status Register(State &state, const Registration &registration);
kweb_shortcuts_status Unregister(State &state, const Registration &registration);
kweb_shortcuts_status Close(State &state);

}  // namespace kwebshell::shortcuts::x11_provider

namespace kwebshell::shortcuts::wayland_provider {

const char *ProviderId();
kweb_shortcuts_status Open(State &state);
kweb_shortcuts_status Register(State &state, const Registration &registration);
kweb_shortcuts_status Unregister(State &state, const Registration &registration);
kweb_shortcuts_status Close(State &state);

}  // namespace kwebshell::shortcuts::wayland_provider

namespace {

enum class LinuxSession { kNone, kX11, kWayland };

LinuxSession DeclaredSession() {
  const char *wayland = std::getenv("WAYLAND_DISPLAY");
  if (wayland != nullptr && wayland[0] != '\0') return LinuxSession::kWayland;
  const char *x11 = std::getenv("DISPLAY");
  if (x11 != nullptr && x11[0] != '\0') return LinuxSession::kX11;
  return LinuxSession::kNone;
}

LinuxSession g_session = LinuxSession::kNone;

}  // namespace

namespace kwebshell::shortcuts {

kweb_shortcuts_status NativeOpen(State &state) {
  g_session = DeclaredSession();
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Open(state);
    case LinuxSession::kWayland:
      return wayland_provider::Open(state);
    default:
      return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_shortcuts_status NativeRegister(State &state, const Registration &registration) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Register(state, registration);
    case LinuxSession::kWayland:
      return wayland_provider::Register(state, registration);
    default:
      return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_shortcuts_status NativeUnregister(State &state, const Registration &registration) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Unregister(state, registration);
    case LinuxSession::kWayland:
      return wayland_provider::Unregister(state, registration);
    default:
      return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_shortcuts_status NativeClose(State &state) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Close(state);
    case LinuxSession::kWayland:
      return wayland_provider::Close(state);
    default:
      return KWEB_SHORTCUTS_STATUS_OK;
  }
}

}  // namespace kwebshell::shortcuts
