#ifndef KWEBSHELL_NATIVE_PROFILE_TEST_PAGE_H_
#define KWEBSHELL_NATIVE_PROFILE_TEST_PAGE_H_

#include <memory>
#include <string>

#include "include/cef_scheme.h"
#include "kwebshell/native/host_configuration.h"

namespace kwebshell {

class EventRecorder;

const char *ProfileSelfTestModeName(ProfileSelfTestMode mode);
const char *ProfileSelfTestUrl();

CefRefPtr<CefSchemeHandlerFactory> CreateProfileSelfTestSchemeHandlerFactory(
    ProfileSelfTestMode mode, const std::string &expected_value,
    std::shared_ptr<EventRecorder> recorder);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_PROFILE_TEST_PAGE_H_
