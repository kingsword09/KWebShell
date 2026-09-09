#include "dialog_operation.h"
#include <gio/gio.h>
#include <chrono>
#include <iomanip>
#include <sstream>

namespace kwebshell::dialogs {
namespace {
struct PortalResult {
  Operation *operation;
  bool complete = false;
};
void Response(GDBusConnection *, const gchar *, const gchar *, const gchar *,
              const gchar *, GVariant *parameters, gpointer data) {
  auto &result = *static_cast<PortalResult *>(data);
  guint response;
  GVariant *values = nullptr;
  g_variant_get(parameters, "(u@a{sv})", &response, &values);
  if (response == 1 || result.operation->cancel_requested.load()) {
    result.operation->state = KWEB_DIALOG_CANCELLED;
    result.operation->failure = 0;
  } else if (response == 0) {
    GVariant *uris = g_variant_lookup_value(values, "uris", G_VARIANT_TYPE_STRING_ARRAY);
    if (uris != nullptr && g_variant_n_children(uris) == 1) {
      const gchar *uri = nullptr;
      g_variant_get_child(uris, 0, "&s", &uri);
      gchar *host = nullptr;
      gchar *path = g_filename_from_uri(uri, &host, nullptr);
      if (path != nullptr && (host == nullptr || *host == '\0' || g_str_equal(host, "localhost"))) {
        result.operation->path = path;
        result.operation->state = KWEB_DIALOG_SELECTED;
        result.operation->failure = 0;
      }
      g_free(host);
      g_free(path);
    }
    if (uris != nullptr) g_variant_unref(uris);
  }
  g_variant_unref(values);
  result.complete = true;
}
}

void RunDialog(Operation &op) {
  op.state = KWEB_DIALOG_FAILED;
  op.failure = KWEB_DIALOG_UNAVAILABLE;
  if (op.cancel_requested.load()) { op.state = KWEB_DIALOG_CANCELLED; op.failure = 0; return; }
  GMainContext *context = g_main_context_new();
  g_main_context_push_thread_default(context);
  GDBusConnection *connection = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
  if (connection == nullptr) {
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
    return;
  }
  // Subscribe before calling FileChooser to catch an immediate user response.
  gchar *uuid = g_uuid_string_random();
  std::string token = "kweb_";
  for (const char *c = uuid; *c; ++c) if (*c != '-') token += *c;
  g_free(uuid);
  std::string sender = g_dbus_connection_get_unique_name(connection);
  sender.erase(0, 1);
  for (char &c : sender) if (c == '.') c = '_';
  std::string request_path = "/org/freedesktop/portal/desktop/request/" + sender + "/" + token;
  PortalResult result{&op};
  guint subscription = g_dbus_connection_signal_subscribe(
      connection, "org.freedesktop.portal.Desktop", "org.freedesktop.portal.Request",
      "Response", request_path.c_str(), nullptr, G_DBUS_SIGNAL_FLAGS_NONE, Response, &result, nullptr);
  GVariantBuilder options;
  g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string(token.c_str()));
  g_variant_builder_add(&options, "{sv}", "modal", g_variant_new_boolean(TRUE));
  g_variant_builder_add(&options, "{sv}", "multiple", g_variant_new_boolean(FALSE));
  if (op.mode == 1 && !op.name.empty()) {
    g_variant_builder_add(&options, "{sv}", "current_name", g_variant_new_string(op.name.c_str()));
  }
  if (!op.directory.empty()) {
    g_variant_builder_add(&options, "{sv}", "current_folder",
        g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, op.directory.c_str(), op.directory.size() + 1, 1));
  }
  if (!op.extensions.empty()) {
    GVariantBuilder patterns;
    g_variant_builder_init(&patterns, G_VARIANT_TYPE("a(us)"));
    for (const auto &extension : op.extensions) {
      const std::string pattern = "*." + extension;
      g_variant_builder_add(&patterns, "(us)", 0u, pattern.c_str());
    }
    GVariantBuilder filters;
    g_variant_builder_init(&filters, G_VARIANT_TYPE("a(sa(us))"));
    g_variant_builder_add(&filters, "(s@a(us))", "Allowed files", g_variant_builder_end(&patterns));
    g_variant_builder_add(&options, "{sv}", "filters", g_variant_builder_end(&filters));
  }
  std::ostringstream parent;
  parent << "x11:" << std::hex << op.owner;
  GVariant *reply = g_dbus_connection_call_sync(
      connection, "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
      "org.freedesktop.portal.FileChooser", op.mode == 0 ? "OpenFile" : "SaveFile",
      g_variant_new("(ss@a{sv})", parent.str().c_str(), op.title.c_str(), g_variant_builder_end(&options)),
      G_VARIANT_TYPE("(o)"), G_DBUS_CALL_FLAGS_NONE, 5000, nullptr, nullptr);
  if (reply != nullptr) {
    const gchar *returned_path;
    g_variant_get(reply, "(&o)", &returned_path);
    if (request_path != returned_path) {
      request_path = returned_path;
      g_dbus_connection_signal_unsubscribe(connection, subscription);
      subscription = g_dbus_connection_signal_subscribe(connection, "org.freedesktop.portal.Desktop",
          "org.freedesktop.portal.Request", "Response", request_path.c_str(), nullptr,
          G_DBUS_SIGNAL_FLAGS_NONE, Response, &result, nullptr);
    }
    // Portal has accepted and owns the request. It does not expose a native window ID.
    op.visible.store(true);
    while (!result.complete) {
      while (g_main_context_iteration(context, FALSE)) {}
      if (result.complete) break;
      if (op.cancel_requested.load()) {
        GVariant *closed = g_dbus_connection_call_sync(connection, "org.freedesktop.portal.Desktop",
            request_path.c_str(), "org.freedesktop.portal.Request", "Close", nullptr, nullptr,
            G_DBUS_CALL_FLAGS_NONE, 5000, nullptr, nullptr);
        op.state = closed == nullptr ? KWEB_DIALOG_FAILED : KWEB_DIALOG_CANCELLED;
        op.failure = closed == nullptr ? KWEB_DIALOG_NATIVE_FAILED : 0;
        if (closed != nullptr) g_variant_unref(closed);
        break;
      }
      std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    g_variant_unref(reply);
  }
  g_dbus_connection_signal_unsubscribe(connection, subscription);
  g_object_unref(connection);
  g_main_context_pop_thread_default(context);
  g_main_context_unref(context);
}
} // namespace kwebshell::dialogs
