#include "profile_test_page.h"

#include <memory>
#include <string>
#include <utility>

#include "include/cef_request.h"
#include "include/cef_stream.h"
#include "include/wrapper/cef_byte_read_handler.h"
#include "include/wrapper/cef_helpers.h"
#include "include/wrapper/cef_stream_resource_handler.h"
#include "kwebshell/native/event_recorder.h"

namespace kwebshell {
namespace {

constexpr char kProfileSelfTestUrl[] =
    "https://kwebshell.test/profile-self-test";

std::string BuildProfileSelfTestHtml(ProfileSelfTestMode mode,
                                     const std::string &expected_value) {
  const std::string mode_name = ProfileSelfTestModeName(mode);
  return R"HTML(<!doctype html>
<html>
<head><meta charset="utf-8"><title>KWEB_PROFILE_SELF_TEST_LOADING</title></head>
<body>
<script>
(() => {
  const mode = ')HTML" +
         mode_name + R"HTML(';
  const expected = ')HTML" +
         expected_value + R"HTML(';
  const storageKey = 'kwebshell.profile.persistence';
  const cookieName = 'kwebshell_profile';
  const readCookie = () => {
    const entry = document.cookie.split('; ').find(value => value.startsWith(`${cookieName}=`));
    return entry ? entry.slice(cookieName.length + 1) : null;
  };

  if (mode === 'write') {
    localStorage.setItem(storageKey, expected);
    document.cookie = `${cookieName}=${expected}; Path=/; SameSite=Strict`;
  }

  const storageValue = localStorage.getItem(storageKey);
  const cookieValue = readCookie();
  const passed = mode === 'expect-absent'
    ? storageValue === null && cookieValue === null
    : storageValue === expected && cookieValue === expected;
  const state = `${encodeURIComponent(storageValue ?? 'missing')}|${encodeURIComponent(cookieValue ?? 'missing')}`;
  document.title = `KWEB_PROFILE_SELF_TEST_${passed ? 'PASS' : 'FAIL'}|${mode}|${state}`;
})();
</script>
</body>
</html>)HTML";
}

class ProfileSelfTestSchemeHandlerFactory final
    : public CefSchemeHandlerFactory {
public:
  ProfileSelfTestSchemeHandlerFactory(ProfileSelfTestMode mode,
                                      const std::string &expected_value,
                                      std::shared_ptr<EventRecorder> recorder)
      : html_(BuildProfileSelfTestHtml(mode, expected_value)),
        recorder_(std::move(recorder)) {}

  CefRefPtr<CefResourceHandler> Create(CefRefPtr<CefBrowser> browser,
                                       CefRefPtr<CefFrame> frame,
                                       const CefString &scheme_name,
                                       CefRefPtr<CefRequest> request) override {
    CEF_REQUIRE_IO_THREAD();
    const std::string url = request->GetURL().ToString();
    if (scheme_name != "https" || url != kProfileSelfTestUrl) {
      return nullptr;
    }
    recorder_->Record("profile_test_request_intercepted", {{"url", url}});
    auto source = CefRefPtr<CefBaseRefCounted>(this);
    auto read_handler = new CefByteReadHandler(
        reinterpret_cast<const unsigned char *>(html_.data()), html_.size(),
        source);
    CefRefPtr<CefStreamReader> stream =
        CefStreamReader::CreateForHandler(read_handler);
    return new CefStreamResourceHandler("text/html", stream);
  }

private:
  const std::string html_;
  const std::shared_ptr<EventRecorder> recorder_;

  IMPLEMENT_REFCOUNTING(ProfileSelfTestSchemeHandlerFactory);
};

} // namespace

const char *ProfileSelfTestModeName(ProfileSelfTestMode mode) {
  switch (mode) {
  case ProfileSelfTestMode::kNone:
    return "none";
  case ProfileSelfTestMode::kWrite:
    return "write";
  case ProfileSelfTestMode::kRead:
    return "read";
  case ProfileSelfTestMode::kExpectAbsent:
    return "expect-absent";
  }
  return "invalid";
}

const char *ProfileSelfTestUrl() { return kProfileSelfTestUrl; }

CefRefPtr<CefSchemeHandlerFactory> CreateProfileSelfTestSchemeHandlerFactory(
    ProfileSelfTestMode mode, const std::string &expected_value,
    std::shared_ptr<EventRecorder> recorder) {
  return new ProfileSelfTestSchemeHandlerFactory(mode, expected_value,
                                                 std::move(recorder));
}

} // namespace kwebshell
