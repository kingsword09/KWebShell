#include "kweb_notifications.h"

#include <cassert>
#include <cstdio>
#include <cstring>

#if defined(_WIN32)
#include "notifications_win_toast.h"
#include <winrt/Windows.Data.Xml.Dom.h>

static void VerifyWinRtToastPayload() {
  winrt::init_apartment(winrt::apartment_type::multi_threaded);
  const auto text = [](const char *value) {
    return kweb_notifications_string{reinterpret_cast<const uint8_t *>(value), std::strlen(value)};
  };
  kweb_notifications_request request{};
  request.id = text("payload-regression");
  request.title = text("Title <&> literal");
  request.body = text("Body <action/> & literal");
  request.action_count = 1;
  request.actions[0].id = text("open");
  request.actions[0].title = text("Open");
  request.actions[0].kind = KWEB_NOTIFICATIONS_ACTION_BUTTON;
  {
    const auto toast = kwebshell::notifications::BuildToast("io.github.kwebshell.test", request);
    const auto content = toast.Content();
    const auto texts = content.GetElementsByTagName(L"text");
    assert(texts.Length() == 2);
    assert(texts.Item(0).InnerText() == L"Title <&> literal");
    assert(texts.Item(1).InnerText() == L"Body <action/> & literal");
    const auto actions = content.GetElementsByTagName(L"action");
    assert(actions.Length() == 1);
    const auto action = actions.Item(0).as<winrt::Windows::Data::Xml::Dom::XmlElement>();
    assert(action.GetAttribute(L"content") == L"Open");
    assert(action.GetAttribute(L"arguments") == L"action=open");
    assert(content.DocumentElement().GetAttribute(L"launch") ==
           L"kweb://notification?app=io.github.kwebshell.test&id=payload-regression");
    request.action_count = 0;
    assert(kwebshell::notifications::BuildToast("io.github.kwebshell.test", request)
               .Content().GetElementsByTagName(L"action").Length() == 0);
  }
  winrt::uninit_apartment();
}

static int VerifyRegisteredFixturePermission() {
  const char identity[] = "io.github.kwebshell.migration.fixture";
  kweb_notifications_configuration configuration{};
  configuration.struct_size = sizeof(configuration);
  configuration.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  configuration.application_id = {reinterpret_cast<const uint8_t *>(identity), std::strlen(identity)};
  configuration.package_identity = configuration.application_id;
  uint64_t handle = 0;
  const auto opened = kweb_notifications_open(&configuration, &handle);
  if (opened != KWEB_NOTIFICATIONS_STATUS_OK) {
    std::fprintf(stderr, "Notification fixture: native open status=%s\n", kweb_notifications_status_name(opened));
    return 1;
  }
  kweb_notifications_permission_result permission{};
  permission.struct_size = sizeof(permission);
  permission.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  const auto queried = kweb_notifications_permission(handle, &permission);
  const auto closed = kweb_notifications_close(handle);
  if (queried != KWEB_NOTIFICATIONS_STATUS_OK ||
      permission.status != KWEB_NOTIFICATIONS_PERMISSION_GRANTED ||
      closed != KWEB_NOTIFICATIONS_STATUS_OK || kweb_notifications_live_count() != 0) {
    std::fprintf(stderr, "Notification fixture: query=%s permission=%u close=%s\n",
                 kweb_notifications_status_name(queried), permission.status,
                 kweb_notifications_status_name(closed));
    return 1;
  }
  std::puts("Notification fixture: registered WinRT provider permission=GRANTED; live count=0.");
  return 0;
}
#endif

int main(int argc, [[maybe_unused]] char **argv) {
  if (argc != 1) {
#if defined(_WIN32)
    if (argc == 2 && std::strcmp(argv[1], "--fixture-permission") == 0) {
      return VerifyRegisteredFixturePermission();
    }
#endif
    std::fputs("Unknown notification test invocation.\n", stderr);
    return 2;
  }
#if defined(_WIN32)
  VerifyWinRtToastPayload();
#endif
  assert(kweb_notifications_abi_version() == KWEB_NOTIFICATIONS_ABI_VERSION);
  assert(kweb_notifications_provider_id() != nullptr);
  assert(kweb_notifications_live_count() == 0);

  kweb_notifications_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  assert(kweb_notifications_poll_event(0, &event) == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE);

  kweb_notifications_configuration configuration{};
  configuration.struct_size = sizeof(configuration);
  configuration.abi_version = KWEB_NOTIFICATIONS_ABI_VERSION;
  const char application[] = "io.github.kwebshell.test";
  configuration.application_id = {reinterpret_cast<const uint8_t *>(application), std::strlen(application)};
  uint64_t handle = 0;
  const auto status = kweb_notifications_open(&configuration, &handle);
#if defined(__linux__) || defined(__APPLE__)
  if (status == KWEB_NOTIFICATIONS_STATUS_OK) {
    assert(handle == 1);
    assert(kweb_notifications_close(handle) == KWEB_NOTIFICATIONS_STATUS_OK);
    assert(kweb_notifications_live_count() == 0);
  } else {
    assert(status == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE ||
           status == KWEB_NOTIFICATIONS_STATUS_PERMISSION_DENIED);
  }
#else
  assert(status == KWEB_NOTIFICATIONS_STATUS_OK ||
         status == KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE);
  if (status == KWEB_NOTIFICATIONS_STATUS_OK) {
    assert(kweb_notifications_close(handle) == KWEB_NOTIFICATIONS_STATUS_OK);
  }
#endif
  return 0;
}
