#include "notifications_win_toast.h"
#include "notifications_internal.h"
#include <winrt/Windows.Data.Xml.Dom.h>

namespace kwebshell::notifications {
namespace {
using namespace winrt;
using namespace Windows::Data::Xml::Dom;
using namespace Windows::UI::Notifications;

void AddText(XmlDocument const &document, XmlElement const &binding, const std::string &value) {
  auto element = document.CreateElement(L"text");
  element.InnerText(winrt::to_hstring(value));
  binding.AppendChild(element);
}

std::string ActivationArguments(const std::string &application, const std::string &id) {
  return "kweb://notification?app=" + application + "&id=" + id;
}
}

ToastNotification BuildToast(const std::string &application_id,
                             const kweb_notifications_request &request) {
  std::string id;
  std::string title;
  std::string body;
  if (!ReadString(request.id, KWEB_NOTIFICATIONS_MAX_ID, &id) ||
      !ReadString(request.title, KWEB_NOTIFICATIONS_MAX_TITLE, &title) ||
      !ReadString(request.body, KWEB_NOTIFICATIONS_MAX_BODY, &body)) {
    throw winrt::hresult_invalid_argument();
  }
  XmlDocument document;
  document.LoadXml(L"<toast><visual><binding template='ToastGeneric'/></visual></toast>");
  auto toastElement = document.DocumentElement();
  toastElement.SetAttribute(L"launch", winrt::to_hstring(ActivationArguments(application_id, id)));
  auto binding = document.GetElementsByTagName(L"binding").Item(0).as<XmlElement>();
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
        throw winrt::hresult_invalid_argument();
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
  // WinRT snapshots the XML at construction; mutations afterward do not update it.
  return ToastNotification(document);
}
}
