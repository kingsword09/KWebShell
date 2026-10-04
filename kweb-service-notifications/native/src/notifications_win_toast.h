#pragma once

#include "kweb_notifications.h"
#include <winrt/Windows.UI.Notifications.h>
#include <string>

namespace kwebshell::notifications {
winrt::Windows::UI::Notifications::ToastNotification BuildToast(
    const std::string &application_id, const kweb_notifications_request &request);
}
