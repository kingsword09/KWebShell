#include "notifications_platform.h"

#if !defined(__linux__)
#error "The Linux notification provider must only be compiled on Linux."
#endif

#include <gio/gio.h>

#include <cstring>
#include <string>

namespace {
struct LinuxState {
  GDBusConnection *connection = nullptr;
  GMainContext *context = nullptr;
  guint action_subscription = 0;
  guint closed_subscription = 0;
};

std::string NativeId(kwebshell::notifications::State &state, uint32_t id) {
  for (const auto &entry : state.native_ids) {
    if (entry.second == id) return entry.first;
  }
  return {};
}

void OnAction(
    GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
    GVariant *parameters, gpointer user_data) {
  auto *state = static_cast<kwebshell::notifications::State *>(user_data);
  guint32 native_id = 0;
  const gchar *action = nullptr;
  g_variant_get(parameters, "(us)", &native_id, &action);
  std::string notification_id;
  {
    std::lock_guard<std::mutex> lock(state->mutex);
    notification_id = NativeId(*state, native_id);
  }
  if (!notification_id.empty()) {
    kwebshell::notifications::PushAction(*state, notification_id, action ? action : "default", "");
  }
}

void OnClosed(
    GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
    GVariant *parameters, gpointer user_data) {
  auto *state = static_cast<kwebshell::notifications::State *>(user_data);
  guint32 native_id = 0;
  guint32 reason = 0;
  g_variant_get(parameters, "(uu)", &native_id, &reason);
  std::string notification_id;
  {
    std::lock_guard<std::mutex> lock(state->mutex);
    notification_id = NativeId(*state, native_id);
    if (!notification_id.empty()) state->native_ids.erase(notification_id);
  }
  if (!notification_id.empty()) {
    const auto close_reason = reason == 2
        ? KWEB_NOTIFICATIONS_CLOSE_USER_DISMISSED
        : KWEB_NOTIFICATIONS_CLOSE_NATIVE;
    kwebshell::notifications::PushClosed(*state, notification_id, close_reason);
  }
}

int32_t ExpireTimeout(kweb_notifications_timeout timeout) {
  switch (timeout) {
    case KWEB_NOTIFICATIONS_TIMEOUT_SHORT: return 5000;
    case KWEB_NOTIFICATIONS_TIMEOUT_LONG: return 30000;
    case KWEB_NOTIFICATIONS_TIMEOUT_PERSISTENT:
    case KWEB_NOTIFICATIONS_TIMEOUT_SYSTEM: return -1;
    default: return -1;
  }
}

kweb_notifications_status ErrorStatus(GError *error) {
  if (error != nullptr && error->domain == G_DBUS_ERROR && error->code == G_DBUS_ERROR_SERVICE_UNKNOWN) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  }
  return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
}
}  // namespace

namespace kwebshell::notifications {

const char *ProviderId() { return "linux.freedesktop.Notifications"; }

void NativePump(State &state) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  if (linux_state == nullptr || linux_state->context == nullptr) return;
  while (g_main_context_iteration(linux_state->context, false)) {}
}

kweb_notifications_status NativeOpen(State &state) {
  GError *error = nullptr;
  auto *linux_state = new LinuxState();
  linux_state->context = g_main_context_new();
  g_main_context_push_thread_default(linux_state->context);
  linux_state->connection = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, &error);
  if (linux_state->connection == nullptr) {
    const auto status = ErrorStatus(error);
    g_clear_error(&error);
    g_main_context_pop_thread_default(linux_state->context);
    g_main_context_unref(linux_state->context);
    delete linux_state;
    return status;
  }
  auto *proxy = g_dbus_proxy_new_sync(
      linux_state->connection,
      G_DBUS_PROXY_FLAGS_DO_NOT_AUTO_START,
      nullptr,
      "org.freedesktop.Notifications",
      "/org/freedesktop/Notifications",
      "org.freedesktop.Notifications",
      nullptr,
      &error);
  if (proxy == nullptr) {
    const auto status = ErrorStatus(error);
    g_clear_error(&error);
    g_object_unref(linux_state->connection);
    g_main_context_pop_thread_default(linux_state->context);
    g_main_context_unref(linux_state->context);
    delete linux_state;
    return status;
  }
  g_object_unref(proxy);
  linux_state->action_subscription = g_dbus_connection_signal_subscribe(
      linux_state->connection,
      "org.freedesktop.Notifications",
      "org.freedesktop.Notifications",
      "ActionInvoked",
      "/org/freedesktop/Notifications",
      nullptr,
      G_DBUS_SIGNAL_FLAGS_NONE,
      OnAction,
      &state,
      nullptr);
  linux_state->closed_subscription = g_dbus_connection_signal_subscribe(
      linux_state->connection,
      "org.freedesktop.Notifications",
      "org.freedesktop.Notifications",
      "NotificationClosed",
      "/org/freedesktop/Notifications",
      nullptr,
      G_DBUS_SIGNAL_FLAGS_NONE,
      OnClosed,
      &state,
      nullptr);
  g_main_context_pop_thread_default(linux_state->context);
  state.platform = linux_state;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativePermission(
    State &, bool, kweb_notifications_permission_result *result) {
  result->status = KWEB_NOTIFICATIONS_PERMISSION_NOT_APPLICABLE;
  std::strncpy(result->provider, ProviderId(), KWEB_NOTIFICATIONS_MAX_PROVIDER - 1u);
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeCapabilities(
    State &, kweb_notifications_capabilities_result *result) {
  result->flags = KWEB_NOTIFICATIONS_CAP_ACTIONS |
                  KWEB_NOTIFICATIONS_CAP_REPLACEMENT |
                  KWEB_NOTIFICATIONS_CAP_TIMEOUT |
                  KWEB_NOTIFICATIONS_CAP_ACTIVATION;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeShow(State &state, const kweb_notifications_request &request) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  if (linux_state == nullptr || linux_state->connection == nullptr) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
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
  GVariantBuilder actions;
  g_variant_builder_init(&actions, G_VARIANT_TYPE("as"));
  for (uint32_t index = 0; index < request.action_count; ++index) {
    std::string action_id;
    std::string action_title;
    if (!ReadString(request.actions[index].id, KWEB_NOTIFICATIONS_MAX_ACTION_ID, &action_id) ||
        !ReadString(request.actions[index].title, KWEB_NOTIFICATIONS_MAX_ACTION_TITLE, &action_title)) {
      return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
    }
    g_variant_builder_add(&actions, "s", action_id.c_str());
    g_variant_builder_add(&actions, "s", action_title.c_str());
  }
  GVariantBuilder hints;
  g_variant_builder_init(&hints, G_VARIANT_TYPE("a{sv}"));
  GVariant *parameters = g_variant_new(
      "(susssasa{sv}i)",
      state.application_id.c_str(),
      0u,
      "",
      title.c_str(),
      body.c_str(),
      &actions,
      &hints,
      ExpireTimeout(request.timeout));
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      linux_state->connection,
      "org.freedesktop.Notifications",
      "/org/freedesktop/Notifications",
      "org.freedesktop.Notifications",
      "Notify",
      parameters,
      G_VARIANT_TYPE("(u)"),
      G_DBUS_CALL_FLAGS_NONE,
      10000,
      nullptr,
      &error);
  if (reply == nullptr) {
    const auto status = ErrorStatus(error);
    g_clear_error(&error);
    return status;
  }
  guint32 native_id = 0;
  g_variant_get(reply, "(u)", &native_id);
  g_variant_unref(reply);
  state.native_ids[id] = native_id;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeCloseNotification(State &state, const std::string &id) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  const auto iterator = state.native_ids.find(id);
  if (iterator == state.native_ids.end()) return KWEB_NOTIFICATIONS_STATUS_NOT_FOUND;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      linux_state->connection,
      "org.freedesktop.Notifications",
      "/org/freedesktop/Notifications",
      "org.freedesktop.Notifications",
      "CloseNotification",
      g_variant_new("(u)", iterator->second),
      nullptr,
      G_DBUS_CALL_FLAGS_NONE,
      10000,
      nullptr,
      &error);
  if (reply != nullptr) g_variant_unref(reply);
  if (error != nullptr) {
    const auto status = ErrorStatus(error);
    g_clear_error(&error);
    return status;
  }
  state.native_ids.erase(iterator);
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeClose(State &state) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  if (linux_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_OK;
  if (linux_state->action_subscription != 0) {
    g_dbus_connection_signal_unsubscribe(linux_state->connection, linux_state->action_subscription);
  }
  if (linux_state->closed_subscription != 0) {
    g_dbus_connection_signal_unsubscribe(linux_state->connection, linux_state->closed_subscription);
  }
  g_object_unref(linux_state->connection);
  g_main_context_unref(linux_state->context);
  delete linux_state;
  state.platform = nullptr;
  state.native_ids.clear();
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

}  // namespace kwebshell::notifications
