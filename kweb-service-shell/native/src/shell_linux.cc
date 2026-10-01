#include "shell_platform.h"

#include <gio/gio.h>
#include <string>

namespace kwebshell::shell {
namespace {
kweb_shell_status OpenUri(const std::string &uri) {
  GError *error = nullptr;
  const gboolean accepted = g_app_info_launch_default_for_uri(uri.c_str(), nullptr, &error);
  if (error != nullptr) g_error_free(error);
  return accepted ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_HANDLER_REJECTED;
}
kweb_shell_status AsFileUri(const std::string &path, gchar **uri) {
  GError *error = nullptr;
  *uri = g_filename_to_uri(path.c_str(), nullptr, &error);
  if (*uri == nullptr) {
    if (error != nullptr) g_error_free(error);
    return KWEB_SHELL_STATUS_NATIVE_FAILED;
  }
  return KWEB_SHELL_STATUS_OK;
}
kweb_shell_status Reveal(const std::string &path) {
  gchar *uri = nullptr;
  const kweb_shell_status uri_status = AsFileUri(path, &uri);
  if (uri_status != KWEB_SHELL_STATUS_OK) return uri_status;
  GError *error = nullptr;
  GDBusConnection *connection = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, &error);
  if (connection == nullptr) {
    if (error != nullptr) g_error_free(error);
    g_free(uri);
    return KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
  }
  const gchar *uris[] = {uri, nullptr};
  GVariant *reply = g_dbus_connection_call_sync(
      connection, "org.freedesktop.FileManager1", "/org/freedesktop/FileManager1",
      "org.freedesktop.FileManager1", "ShowItems",
      g_variant_new("(^ass)", uris, ""),
      G_VARIANT_TYPE("()"), G_DBUS_CALL_FLAGS_NONE, 5000, nullptr, &error);
  const bool accepted = reply != nullptr;
  if (reply != nullptr) g_variant_unref(reply);
  if (error != nullptr) g_error_free(error);
  g_object_unref(connection);
  g_free(uri);
  return accepted ? KWEB_SHELL_STATUS_OK : KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE;
}
kweb_shell_status Trash(const std::string &path) {
  GFile *file = g_file_new_for_path(path.c_str());
  GError *error = nullptr;
  const gboolean accepted = g_file_trash(file, nullptr, &error);
  if (error != nullptr) g_error_free(error);
  if (!accepted) {
    g_object_unref(file);
    return KWEB_SHELL_STATUS_TRASH_FAILED;
  }
  const gboolean exists = g_file_query_exists(file, nullptr);
  g_object_unref(file);
  return exists ? KWEB_SHELL_STATUS_TRASH_VERIFICATION_FAILED : KWEB_SHELL_STATUS_OK;
}
}  // namespace

const char *ProviderId() { return "linux.GIO.FileManager1"; }

kweb_shell_status ExecutePlatform(
    uint32_t action, uint32_t, const std::string &value, uint32_t *outcome) {
  kweb_shell_status status;
  if (action == KWEB_SHELL_ACTION_OPEN_EXTERNAL) {
    status = OpenUri(value);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
  } else if (action == KWEB_SHELL_ACTION_OPEN_RESOURCE) {
    gchar *uri = nullptr;
    status = AsFileUri(value, &uri);
    if (status == KWEB_SHELL_STATUS_OK) {
      status = OpenUri(uri);
      g_free(uri);
    }
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
  } else if (action == KWEB_SHELL_ACTION_REVEAL_RESOURCE) {
    status = Reveal(value);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_HANDLER_ACCEPTED;
  } else {
    status = Trash(value);
    if (status == KWEB_SHELL_STATUS_OK) *outcome = KWEB_SHELL_OUTCOME_MOVED_TO_TRASH;
  }
  return status;
}
}  // namespace kwebshell::shell
