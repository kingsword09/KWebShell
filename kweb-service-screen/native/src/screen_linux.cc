#include "screen_internal.h"

#if !defined(__linux__)
#error "The Linux screen session dispatcher must only be compiled on Linux."
#endif

#include <cstdlib>

namespace kwebshell::screen::x11_provider {

const char *ProviderId();
kweb_screen_status Open(State &state);
kweb_screen_status ReadTopologyOf(State &state, Topology *topology);
kweb_screen_status ReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                              bool *display_known);
kweb_screen_status Close(State &state);

}  // namespace kwebshell::screen::x11_provider

namespace kwebshell::screen::wayland_provider {

const char *ProviderId();
kweb_screen_status Open(State &state);
kweb_screen_status ReadTopologyOf(State &state, Topology *topology);
kweb_screen_status ReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                              bool *display_known);
kweb_screen_status Close(State &state);

}  // namespace kwebshell::screen::wayland_provider

namespace {

/** The session type the environment declares, chosen before any provider opens. */
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

namespace kwebshell::screen {

const char *ProviderId() {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::ProviderId();
    case LinuxSession::kWayland:
      return wayland_provider::ProviderId();
    default:
      return "screen.linux.unavailable";
  }
}

/**
 * Chooses the provider the session declares. A Wayland session never falls back
 * to an X11 server; when its compositor facility is missing the service fails
 * typed instead.
 */
kweb_screen_status NativeOpen(State &state) {
  g_session = DeclaredSession();
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Open(state);
    case LinuxSession::kWayland:
      return wayland_provider::Open(state);
    default:
      return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_screen_status NativeReadTopology(State &state, Topology *topology) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::ReadTopologyOf(state, topology);
    case LinuxSession::kWayland:
      return wayland_provider::ReadTopologyOf(state, topology);
    default:
      return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_screen_status NativeReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                                    bool *display_known) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::ReadCursor(state, x, y, display_id, display_known);
    case LinuxSession::kWayland:
      return wayland_provider::ReadCursor(state, x, y, display_id, display_known);
    default:
      return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
}

kweb_screen_status NativeClose(State &state) {
  switch (g_session) {
    case LinuxSession::kX11:
      return x11_provider::Close(state);
    case LinuxSession::kWayland:
      return wayland_provider::Close(state);
    default:
      return KWEB_SCREEN_STATUS_OK;
  }
}

}  // namespace kwebshell::screen
