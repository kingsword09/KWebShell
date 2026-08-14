#ifndef KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_
#define KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_

#include <memory>
#include <string>

#include "include/cef_scheme.h"
#include "kwebshell/native/host_configuration.h"

namespace kwebshell {

class EventRecorder;

const char *Mv3CoreSelfTestModeName(Mv3CoreSelfTestMode mode);
const char *Mv3CoreSelfTestUrl();
std::string ExpectedMv3CoreSelfTestResult(Mv3CoreSelfTestMode mode);

CefRefPtr<CefSchemeHandlerFactory> CreateMv3CoreSelfTestSchemeHandlerFactory(
    Mv3CoreSelfTestMode mode, std::shared_ptr<EventRecorder> recorder);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_MV3_CORE_TEST_PAGE_H_
