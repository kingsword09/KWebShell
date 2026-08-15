#ifndef KWEBSHELL_NATIVE_EXTENSION_SESSION_H_
#define KWEBSHELL_NATIVE_EXTENSION_SESSION_H_

#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

kweb_status
StartExtensionOperation(kweb_browser_handle browser,
                        const kweb_extension_config *config,
                        kweb_extension_operation_handle *operation_out);
kweb_status CancelExtensionOperation(kweb_extension_operation_handle operation);
kweb_status CancelExtensionOperationsForBrowser(kweb_browser_handle browser);
uint64_t LiveExtensionOperationCount();

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_EXTENSION_SESSION_H_
