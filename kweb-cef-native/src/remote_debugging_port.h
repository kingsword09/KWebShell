#ifndef KWEBSHELL_NATIVE_REMOTE_DEBUGGING_PORT_H_
#define KWEBSHELL_NATIVE_REMOTE_DEBUGGING_PORT_H_

#include <cstdint>

#include "kwebshell/native/base_abi.h"

namespace kwebshell {

kweb_status ValidateRemoteDebuggingPortAvailability(int32_t port);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_REMOTE_DEBUGGING_PORT_H_
