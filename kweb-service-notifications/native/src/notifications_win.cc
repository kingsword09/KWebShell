#include "notifications_platform.h"

#if !defined(_WIN32)
#error "The Windows notification provider must only be compiled on Windows."
#endif

#include <windows.h>
#include <winrt/Windows.Data.Xml.Dom.h>
#include <winrt/Windows.UI.Notifications.h>
#include <winrt/base.h>

#include <cstring>
#include <string>
#include <unordered_map>

namespace {
using namespace winrt;
using namespace Windows::Data::Xml::Dom;
using namespace Windows::UI::Notifications;

struct WinState {
  ToastNotifier notifier{nullptr};
  std::unordered_map<std::string, ToastNotification> notifications;
};

std::string Argument(const std::string &arguments, const std::string &name) {
  const std::string prefix = name + "=";
  size_t start = 0;
  while (start < arguments.size()) {
    const size_t end = arguments.find('&', start);
    const std::string part = arguments.substr(start, end == std::string::npos ? end : end - start);
    if (part.rfind(prefix, 0) == 0) return part.substr(prefix.size());
    if (end == std::string::npos) break;
    start = end + 1;
  }
  return {};
}

std::string HString(hstring value) { return winrt::to_string(value); }

std::string ActivationArguments(const std::string &application, const std::string &id) {
  return "kweb://notification?app=" + application + "&id=" + id;
}

void AddText(XmlDocument const &document, XmlElement const &binding, const std::string &value) {
  auto element = document.CreateElement(L"text");
  element.InnerText(winrt::to_hstring(value));
  binding.AppendChild(element);
}
}  // namespace

namespace kwebshell::notifications {

const char *ProviderId() { return "windows.WinRT.Toast"; }

void NativePump(State &) {}

kweb_notifications_status NativeOpen(State &state) {
  if (state.package_identity.empty()) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  try {
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    auto *windows_state = new WinState();
    windows_state->notifier = ToastNotificationManager::CreateToastNotifier(
        winrt::to_hstring(state.package_identity));
    state.platform = windows_state;
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (const winrt::hresult_error &) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  } catch (...) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

kweb_notifications_status NativePermission(
    State &state, bool, kweb_notifications_permission_result *result) {
  auto *windows_state = static_cast<WinState *>(state.platform);
  if (windows_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  try {
    switch (windows_state->notifier.Setting()) {
      case NotificationSetting::Enabled:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_GRANTED;
        break;
      case NotificationSetting::Disabled:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_DENIED;
        break;
      case NotificationSetting::Unknown:
      case NotificationSetting::NotSupported:
      default:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_UNAVAILABLE;
        break;
    }
    std::strncpy(result->provider, ProviderId(), KWEB_NOTIFICATIONS_MAX_PROVIDER - 1u);
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (...) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

kweb_notifications_status NativeCapabilities(
    State &, kweb_notifications_capabilities_result *result) {
  result->flags = KWEB_NOTIFICATIONS_CAP_ACTIONS |
                  KWEB_NOTIFICATIONS_CAP_REPLIES |
                  KWEB_NOTIFICATIONS_CAP_REPLACEMENT |
                  KWEB_NOTIFICATIONS_CAP_TIMEOUT |
                  KWEB_NOTIFICATIONS_CAP_ACTIVATION;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeShow(State &state, const kweb_notifications_request &request) {
  auto *windows_state = static_cast<WinState *>(state.platform);
  if (windows_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::string id;
  std::string tag;
  std::string title;
  std::string body;
  if (!ReadString(request.id, KWEB_NOTIFICATIONS_MAX_ID, &id) ||
      !ReadString(request.tag, KWEB_NOTIFICATIONS_MAX_TAG, &tag) ||
      !ReadString(request.title, KWEB_NOTIFICATIONS_MAX_TITLE, &title) ||
      !ReadString(request.body, KWEB_NOTIFICATIONS_MAX_BODY, &body)) {
    return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  }
  try {
    XmlDocument document;
    document.LoadXml(L"<toast><visual><binding template='ToastGeneric'/></visual></toast>");
    auto toast = ToastNotification(document);
    auto toastElement = document.DocumentElement();
    toastElement.SetAttribute(L"launch", winrt::to_hstring(ActivationArguments(state.application_id, id)));
    auto binding = document.GetElementsByTagName(L"binding").GetAt(0).as<XmlElement>();
    AddText(document, binding, title);
    AddText(document, binding, body);
    if (request.action_count != 0) {
      auto actions = document.CreateElement(L"actions");
      for (uint32_t index = 0; index < request.action_count; ++index) {
        std::string action_id;
        std::string action_title;
        std::string placeholder;
        if (!ReadString(request.actions[index].id, KWEB_NOTIFICATIONS_MAX_ACTION_ID, &action_id) ||
            !ReadString(request.actions[index].title, KWEB_NOTIFICATIONS_MAX_ACTION_TITLE, &action_title) ||
            !ReadString(request.actions[index].reply_placeholder, KWEB_NOTIFICATIONS_MAX_REPLY, &placeholder)) {
          return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
        }
        auto action = document.CreateElement(L"action");
        action.SetAttribute(L"content", winrt::to_hstring(action_title));
        action.SetAttribute(L"arguments", winrt::to_hstring("action=" + action_id));
        action.SetAttribute(L"activationType", L"foreground");
        actions.AppendChild(action);
        if (request.actions[index].kind == KWEB_NOTIFICATIONS_ACTION_REPLY) {
          auto input = document.CreateElement(L"input");
          input.SetAttribute(L"id", L"reply");
          input.SetAttribute(L"type", L"text");
          input.SetAttribute(L"placeHolderContent", winrt::to_hstring(placeholder));
          actions.AppendChild(input);
        }
      }
      toastElement.AppendChild(actions);
    }
    toast.Activated([&state, id](auto const &, auto const &inspectable) {
      try {
        auto args = inspectable.as<ToastActivatedEventArgs>();
        const std::string arguments = HString(args.Arguments());
        std::string reply;
        auto userInput = args.UserInput();
        auto value = userInput.TryLookup(L"reply");
        if (value) reply = HString(unbox_value<hstring>(value));
        PushAction(state, id, Argument(arguments, "action").empty() ? "default" : Argument(arguments, "action"), reply);
      } catch (...) {
        PushFailed(state, id, "notifications.native-failed");
      }
    });
    toast.Dismissed([&state, id](auto const &, auto const &args) {
      const auto reason = args.Reason() == ToastDismissalReason::UserCanceled
          ? KWEB_NOTIFICATIONS_CLOSE_USER_DISMISSED
          : KWEB_NOTIFICATIONS_CLOSE_NATIVE;
      PushClosed(state, id, reason);
    });
    toast.Failed([&state, id](auto const &, auto const &) {
      PushFailed(state, id, "notifications.native-failed");
    });
    windows_state->notifications[id] = toast;
    windows_state->notifier.Show(toast);
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (const winrt::hresult_error &) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  } catch (...) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

kweb_notifications_status NativeCloseNotification(State &state, const std::string &id) {
  auto *windows_state = static_cast<WinState *>(state.platform);
  if (windows_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  const auto iterator = windows_state->notifications.find(id);
  if (iterator == windows_state->notifications.end()) return KWEB_NOTIFICATIONS_STATUS_NOT_FOUND;
  try {
    windows_state->notifier.Hide(iterator->second);
    windows_state->notifications.erase(iterator);
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (...) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

kweb_notifications_status NativeClose(State &state) {
  auto *windows_state = static_cast<WinState *>(state.platform);
  if (windows_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_OK;
  try {
    for (auto const &entry : windows_state->notifications) {
      windows_state->notifier.Hide(entry.second);
    }
    delete windows_state;
    state.platform = nullptr;
    winrt::uninit_apartment();
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (...) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

}  // namespace kwebshell::notifications
