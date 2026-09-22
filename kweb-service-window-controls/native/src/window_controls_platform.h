#ifndef KWEBSHELL_SERVICES_WINDOW_CONTROLS_PLATFORM_H_
#define KWEBSHELL_SERVICES_WINDOW_CONTROLS_PLATFORM_H_

#include "kwebshell/services/window_controls_abi.h"

namespace kwebshell::window_controls {

kweb_window_controls_status Probe(kweb_window_handle handle);
kweb_window_controls_status Attach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality);
kweb_window_controls_status Detach(kweb_window_handle child,
                                   kweb_window_handle parent,
                                   kweb_window_modality modality);
kweb_window_controls_status SetModalEnabled(kweb_window_handle handle,
                                            bool enabled);
kweb_window_controls_status SetAlwaysOnTop(kweb_window_handle handle,
                                           bool enabled);
kweb_window_controls_status RequestAttention(kweb_window_handle handle);
kweb_window_controls_status VerifyParent(kweb_window_handle child,
                                         kweb_window_handle parent);
const char *ProviderId();

} // namespace kwebshell::window_controls

#endif // KWEBSHELL_SERVICES_WINDOW_CONTROLS_PLATFORM_H_
