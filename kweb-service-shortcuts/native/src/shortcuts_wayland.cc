#include "shortcuts_internal.h"

#if !defined(__linux__)
#error "The Wayland shortcut provider must only be compiled on Linux."
#endif

namespace kwebshell::shortcuts::wayland_provider {

const char *ProviderId() { return "shortcuts.linux.wayland-unavailable"; }

kweb_shortcuts_status Open(State &state) {
  state.platform = nullptr;
  // No compositor-independent global-hotkey facility exists without a tested
  // portal contract, so no capability is advertised and every registration fails
  // typed instead of falling back to X11.
  state.capabilities.provider_id = ProviderId();
  state.capabilities.capability_bits = 0;
  state.capabilities.modifier_bits = 0;
  state.capabilities.media_key_bits = 0;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status Register(State &, const Registration &) {
  return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
}

kweb_shortcuts_status Unregister(State &, const Registration &) {
  return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
}

kweb_shortcuts_status Close(State &) { return KWEB_SHORTCUTS_STATUS_OK; }

}  // namespace kwebshell::shortcuts::wayland_provider
