#ifndef KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_
#define KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_

#include <memory>
#include <string>
#include <string_view>

#include "include/cef_scheme.h"
#include "kwebshell/native/host_configuration.h"

namespace kwebshell {

class EventRecorder;

struct Mv3CoreExtensionPageSelfTest final {
  const char *surface;
  const char *url;
};

const char *Mv3CoreSelfTestModeName(Mv3CoreSelfTestMode mode);
const char *Mv3CoreSelfTestUrl();
const Mv3CoreExtensionPageSelfTest *
Mv3CoreExtensionPageSelfTestForMode(Mv3CoreSelfTestMode mode);
bool IsMv3CoreExtensionPagePassResult(std::string_view result);
bool IsMv3CoreExtensionPageFailureResult(std::string_view result);
const char *Mv3CoreContextMenuItemId();
const char *Mv3CoreContextMenuItemLabel();
int Mv3CoreContextMenuX();
int Mv3CoreContextMenuY();
std::string ExpectedMv3CoreSelfTestResult(Mv3CoreSelfTestMode mode);
std::string ExpectedMv3CoreExtensionPageResult(Mv3CoreSelfTestMode mode);
std::string ExpectedMv3CoreContextMenuResult();

CefRefPtr<CefSchemeHandlerFactory> CreateMv3CoreSelfTestSchemeHandlerFactory(
    Mv3CoreSelfTestMode mode, std::shared_ptr<EventRecorder> recorder);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_
