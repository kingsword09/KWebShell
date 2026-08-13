#ifndef KWEBSHELL_NATIVE_BASE_ABI_H_
#define KWEBSHELL_NATIVE_BASE_ABI_H_

#include <stdint.h>

#if defined(_WIN32)
#define KWEB_ABI_CALL __cdecl
#elif defined(__GNUC__)
#define KWEB_ABI_CALL
#else
#define KWEB_ABI_CALL
#endif

#define KWEB_ABI_VERSION ((uint32_t)5)

typedef uint32_t kweb_status;

#define KWEB_STATUS_OK ((kweb_status)0)
#define KWEB_STATUS_INVALID_ARGUMENT ((kweb_status)1)
#define KWEB_STATUS_ABI_MISMATCH ((kweb_status)2)
#define KWEB_STATUS_ALLOCATION_FAILED ((kweb_status)3)
#define KWEB_STATUS_THREAD_START_FAILED ((kweb_status)4)
#define KWEB_STATUS_HANDLE_EXHAUSTED ((kweb_status)5)
#define KWEB_STATUS_INVALID_HANDLE ((kweb_status)6)
#define KWEB_STATUS_SESSION_CLOSING ((kweb_status)7)
#define KWEB_STATUS_INVALID_TEXT_ENCODING ((kweb_status)8)
#define KWEB_STATUS_TEXT_TOO_LARGE ((kweb_status)9)
#define KWEB_STATUS_INVALID_DIMENSIONS ((kweb_status)10)
#define KWEB_STATUS_REENTRANT_CLOSE ((kweb_status)11)
#define KWEB_STATUS_CALLBACK_FAILED ((kweb_status)12)
#define KWEB_STATUS_INTERNAL_ERROR ((kweb_status)13)
#define KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED ((kweb_status)14)
#define KWEB_STATUS_ENGINE_SYMBOL_MISSING ((kweb_status)15)
#define KWEB_STATUS_CEF_RUNTIME_LOAD_FAILED ((kweb_status)16)
#define KWEB_STATUS_CEF_RUNTIME_MISMATCH ((kweb_status)17)
#define KWEB_STATUS_PATH_REQUIRED ((kweb_status)18)
#define KWEB_STATUS_PATH_NOT_ABSOLUTE ((kweb_status)19)
#define KWEB_STATUS_PATH_NOT_FOUND ((kweb_status)20)
#define KWEB_STATUS_PATH_TYPE_INVALID ((kweb_status)21)
#define KWEB_STATUS_PATH_MISMATCH ((kweb_status)22)
#define KWEB_STATUS_PATH_NOT_WRITABLE ((kweb_status)23)
#define KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED ((kweb_status)24)
#define KWEB_STATUS_ENGINE_ALREADY_EXISTS ((kweb_status)25)
#define KWEB_STATUS_ENGINE_RESTART_FORBIDDEN ((kweb_status)26)
#define KWEB_STATUS_WRONG_THREAD ((kweb_status)27)
#define KWEB_STATUS_CEF_INITIALIZE_FAILED ((kweb_status)28)
#define KWEB_STATUS_ENGINE_CLOSING ((kweb_status)29)
#define KWEB_STATUS_ENGINE_HAS_LIVE_BROWSERS ((kweb_status)30)
#define KWEB_STATUS_PROFILE_PATH_INVALID ((kweb_status)31)
#define KWEB_STATUS_PARENT_SURFACE_INVALID ((kweb_status)32)
#define KWEB_STATUS_BROWSER_CREATE_FAILED ((kweb_status)33)
#define KWEB_STATUS_BROWSER_NOT_READY ((kweb_status)34)
#define KWEB_STATUS_BROWSER_CLOSING ((kweb_status)35)
#define KWEB_STATUS_CEF_UI_TASK_FAILED ((kweb_status)36)
#define KWEB_STATUS_NAVIGATION_INVALID ((kweb_status)37)
#define KWEB_STATUS_REMOTE_DEBUGGING_PORT_INVALID ((kweb_status)38)
#define KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE ((kweb_status)39)
#define KWEB_STATUS_DEVTOOLS_ALREADY_OPEN ((kweb_status)40)
#define KWEB_STATUS_DEVTOOLS_NOT_OPEN ((kweb_status)41)
#define KWEB_STATUS_DEVTOOLS_OPEN_FAILED ((kweb_status)42)
#define KWEB_STATUS_DEVTOOLS_CLOSING ((kweb_status)43)
#define KWEB_STATUS_BRIDGE_ORIGIN_INVALID ((kweb_status)44)
#define KWEB_STATUS_BRIDGE_REQUEST_NOT_FOUND ((kweb_status)45)
#define KWEB_STATUS_BRIDGE_RESPONSE_INVALID ((kweb_status)46)

#endif // KWEBSHELL_NATIVE_BASE_ABI_H_
