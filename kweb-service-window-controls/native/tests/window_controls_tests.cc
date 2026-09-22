#include "kwebshell/services/window_controls_abi.h"

#include <cstdlib>
#include <cstring>

namespace {

void Require(bool condition) {
  if (!condition) {
    std::abort();
  }
}

} // namespace

int main() {
  Require(sizeof(kweb_window_handle) == sizeof(uint64_t));
  Require(kweb_window_controls_abi_version() ==
          KWEB_WINDOW_CONTROLS_ABI_VERSION);
  Require(std::strcmp(kweb_window_controls_status_name(
                          KWEB_WINDOW_CONTROLS_STATUS_OK),
                      "ok") == 0);
  Require(std::strcmp(kweb_window_controls_status_name(
                          KWEB_WINDOW_CONTROLS_STATUS_STATE_UNOBSERVED),
                      "state-unobserved") == 0);
  Require(std::strcmp(kweb_window_controls_provider_id(), "") != 0);
  Require(kweb_window_controls_probe(0) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_attach(0, 1, KWEB_WINDOW_MODALITY_NONE) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_detach(1, 1, KWEB_WINDOW_MODALITY_NONE) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_set_modal_enabled(0, 1) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_set_always_on_top(0, 1) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_request_attention(0) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  Require(kweb_window_controls_verify_parent(0, 1) ==
          KWEB_WINDOW_CONTROLS_STATUS_INVALID_ARGUMENT);
  return 0;
}
