#ifndef KWEBSHELL_NATIVE_BRIDGE_RENDERER_APP_H_
#define KWEBSHELL_NATIVE_BRIDGE_RENDERER_APP_H_

#include "include/cef_app.h"

namespace kwebshell {

CefRefPtr<CefApp> CreateBridgeRendererApplication();

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BRIDGE_RENDERER_APP_H_
