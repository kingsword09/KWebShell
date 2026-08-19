#ifndef KWEBSHELL_NATIVE_BROWSER_SESSION_H_
#define KWEBSHELL_NATIVE_BROWSER_SESSION_H_

#include <filesystem>

#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

kweb_status CreateBrowserSession(const kweb_browser_config *config,
                                 kweb_browser_handle *browser_out);
kweb_status NavigateBrowserSession(kweb_browser_handle browser,
                                   const char *url_utf8, size_t url_size);
kweb_status ResizeBrowserSession(kweb_browser_handle browser, int32_t width,
                                 int32_t height);
kweb_status CloseBrowserSession(kweb_browser_handle browser);
kweb_status OpenDevToolsSession(kweb_browser_handle browser);
kweb_status CloseDevToolsSession(kweb_browser_handle browser);
kweb_status RespondToBridgeSession(kweb_browser_handle browser,
                                   uint64_t request_id,
                                   const char *response_utf8,
                                   size_t response_size, bool success);
uint64_t LiveBrowserSessionCount();
// Releases the shared per-profile request contexts on the CEF UI thread.
// Must run after the last browser session completed and before CefShutdown.
kweb_status ReleaseEngineProfileContexts();
kweb_status GetBrowserExtensionContext(kweb_browser_handle browser,
                                       kweb_engine_handle *engine_out,
                                       std::filesystem::path *profile_path_out);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BROWSER_SESSION_H_
