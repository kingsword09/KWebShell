#include "notifications_platform.h"
#include "notifications_win_toast.h"

#if !defined(_WIN32)
#error "The Windows notification provider must only be compiled on Windows."
#endif

#include <windows.h>
#include <winrt/Windows.Data.Xml.Dom.h>
#include <winrt/Windows.Foundation.Collections.h>
#include <winrt/Windows.UI.Notifications.h>
#include <winrt/base.h>

#include <algorithm>
#include <cstring>
#include <cstdio>
#include <memory>
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

void CopyProvider(char *target, size_t capacity, const char *value) {
  if (capacity == 0) return;
  const size_t count = (value == nullptr) ? 0 : (std::min)(capacity - 1u, std::strlen(value));
  if (count != 0) std::memcpy(target, value, count);
  target[count] = '\0';
}

}  // namespace

namespace kwebshell::notifications {

const char *ProviderId() { return "windows.WinRT.Toast"; }

void NativePump(State &) {}

kweb_notifications_status NativeOpen(State &state) {
  if (state.package_identity.empty()) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  bool apartment_initialized = false;
  try {
    winrt::init_apartment(winrt::apartment_type::multi_threaded);
    apartment_initialized = true;
    auto windows_state = std::make_unique<WinState>();
    windows_state->notifier = ToastNotificationManager::CreateToastNotifier(
        winrt::to_hstring(state.package_identity));
    state.platform = windows_state.release();
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (const winrt::hresult_error &error) {
    std::fprintf(stderr, "KWebNotifications operation=open HRESULT=0x%08lx\n",
                 static_cast<unsigned long>(error.code().value));
    if (apartment_initialized) winrt::uninit_apartment();
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  } catch (...) {
    if (apartment_initialized) winrt::uninit_apartment();
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
}

kweb_notifications_status NativePermission(
    State &state, bool, kweb_notifications_permission_result *result) {
  auto *windows_state = static_cast<WinState *>(state.platform);
  if (windows_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  try {
    switch (static_cast<int>(windows_state->notifier.Setting())) {
      case 0:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_GRANTED;
        break;
      case 1:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_DENIED;
        break;
      case 2:
      case 3:
      default:
        result->status = KWEB_NOTIFICATIONS_PERMISSION_UNAVAILABLE;
        break;
    }
    CopyProvider(result->provider, sizeof(result->provider), ProviderId());
    return KWEB_NOTIFICATIONS_STATUS_OK;
  } catch (const winrt::hresult_error &error) {
    std::fprintf(stderr, "KWebNotifications operation=permission HRESULT=0x%08lx\n",
                 static_cast<unsigned long>(error.code().value));
    // Unpackaged desktop AUMIDs can be registered and deliver toasts while
    // WinRT has no per-package permission record to expose through Setting().
    // E_ELEMENTNOTFOUND is that explicit state; all other failures remain
    // unavailable and are surfaced to the caller.
    if (error.code() == HRESULT_FROM_WIN32(ERROR_FILE_NOT_FOUND)) {
      result->status = KWEB_NOTIFICATIONS_PERMISSION_GRANTED;
      CopyProvider(result->provider, sizeof(result->provider), ProviderId());
      return KWEB_NOTIFICATIONS_STATUS_OK;
    }
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
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
    auto toast = BuildToast(state.application_id, request);
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
    toast.Failed([&state, id](auto const &, auto const &args) {
      std::fprintf(stderr, "KWebNotifications operation=delivery HRESULT=0x%08lx\n",
                   static_cast<unsigned long>(args.ErrorCode().value));
      PushFailed(state, id, "notifications.native-failed");
    });
    windows_state->notifications.insert_or_assign(id, toast);
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
