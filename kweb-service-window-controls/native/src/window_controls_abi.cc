#include "kwebshell/services/window_controls_abi.h"

#include "window_controls_platform.h"

#include <cstring>

namespace {

bool ValidModality(kweb_window_modality modality) {
  return modality == KWEB_WINDOW_MODALITY_NONE ||
         modality == KWEB_WINDOW_MODALITY_WINDOW ||
         modality == KWEB_WINDOW_MODALITY_APPLICATION;
}

} // namespace

extern "C" {

uint32_t kweb_window_controls_abi_version() {
  return KWEB_WINDOW_CONTROLS_ABI_VERSION;
}

const char *kweb_window_controls_status_name(
    kweb_window_controls_status status) {
  switch (status) {
  case KWEB_WINDOW_CONTROLS_STATUS_OK:
    return "ok";
  case KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT:
    return "invalid-argument";
  case KWEB_WINDOW_CONTROLS_STATUS_ABI_MISMATCH:
    return "abi-mismatch";
  case KWEB_WINDOW_CONTROLS_STATUS_NATIVE_UNAVAILABLE:
    return "native-unavailable";
  case KWEB_WINDOW_CONTROLS_STATUS_STALE_HANDLE:
    return "stale-handle";
  case KWEB_WINDOW_CONTROLS_STATUS_UNSUPPORTED:
    return "unsupported";
  case KWEB_WINDOW_CONTROLS_STATUS_NATIVE_FAILED:
    return "native-failed";
  case KWEB_WINDOW_CONTROLS_STATUS_WRONG_THREAD:
    return "wrong-thread";
  case KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED:
    return "state-unobserved";
  default:
    return "unknown";
  }
}

const char *kweb_window_controls_provider_id() {
  return kwebshell::window_controls::ProviderId();
}

kweb_window_controls_status kweb_window_controls_probe(
    kweb_window_handle handle) {
  return handle == 0 ? KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT
                    : kwebshell::window_controls::Probe(handle);
}

kweb_window_controls_status kweb_window_controls_attach(
    kweb_window_handle child, kweb_window_handle parent,
    kweb_window_modality modality) {
  if (child == 0 || parent == 0 || !ValidModality(modality) || child == parent) {
    return KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT;
  }
  return kwebshell::window_controls::Attach(child, parent, modality);
}

kweb_window_controls_status kweb_window_controls_detach(
    kweb_window_handle child, kweb_window_handle parent,
    kweb_window_modality modality) {
  if (child == 0 || parent == 0 || !ValidModality(modality) || child == parent) {
    return KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT;
  }
  return kwebshell::window_controls::Detach(child, parent, modality);
}

kweb_window_controls_status kweb_window_controls_set_modal_enabled(
    kweb_window_handle handle, uint32_t enabled) {
  if (handle == 0 || enabled > 1) {
    return KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT;
  }
  return kwebshell::window_controls::SetModalEnabled(handle, enabled != 0);
}

kweb_window_controls_status kweb_window_controls_set_always_on_top(
    kweb_window_handle handle, uint32_t enabled) {
  if (handle == 0 || enabled > 1) {
    return KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT;
  }
  return kwebshell::window_controls::SetAlwaysOnTop(handle, enabled != 0);
}

kweb_window_controls_status kweb_window_controls_request_attention(
    kweb_window_handle handle) {
  return handle == 0 ? KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT
                    : kwebshell::window_controls::RequestAttention(handle);
}

kweb_window_controls_status kweb_window_controls_verify_parent(
    kweb_window_handle child, kweb_window_handle parent) {
  if (child == 0 || parent == 0 || child == parent) {
    return KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT;
  }
  return kwebshell::window_controls::VerifyParent(child, parent);
}

} // extern "C"
