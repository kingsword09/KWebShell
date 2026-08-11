#ifndef KWEBSHELL_NATIVE_SELF_TEST_PAGE_H_
#define KWEBSHELL_NATIVE_SELF_TEST_PAGE_H_

#include <string>

#include "include/cef_browser.h"

namespace kwebshell {

std::string BuildSelfTestUrl();
void SendSelfTestMouseInput(CefRefPtr<CefBrowser> browser);
void SendSelfTestWheelInput(CefRefPtr<CefBrowser> browser);
void SendSelfTestKeyboardInput(CefRefPtr<CefBrowser> browser,
                               int native_key_code);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_SELF_TEST_PAGE_H_
