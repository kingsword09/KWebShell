#include <gio/gio.h>

#include <signal.h>
#include <stdlib.h>
#include <string.h>

static const gchar *k_object_path = "/org/freedesktop/Notifications";
static const gchar *k_interface = "org.freedesktop.Notifications";
static GDBusConnection *g_connection = NULL;
static GMainLoop *g_loop = NULL;
static guint g_next_notification_id = 1;

typedef struct {
  guint notification_id;
  gchar *destination;
  gchar *action_id;
} PendingAction;

static const gchar k_introspection_xml[] =
    "<node>"
    "<interface name='org.freedesktop.Notifications'>"
    "<method name='Notify'>"
    "<arg direction='in' type='s'/>"
    "<arg direction='in' type='u'/>"
    "<arg direction='in' type='s'/>"
    "<arg direction='in' type='s'/>"
    "<arg direction='in' type='s'/>"
    "<arg direction='in' type='as'/>"
    "<arg direction='in' type='a{sv}'/>"
    "<arg direction='in' type='i'/>"
    "<arg direction='out' type='u'/>"
    "</method>"
    "<method name='CloseNotification'><arg direction='in' type='u'/></method>"
    "<method name='GetCapabilities'><arg direction='out' type='as'/></method>"
    "<method name='GetServerInformation'>"
    "<arg direction='out' type='s'/><arg direction='out' type='s'/><arg direction='out' type='s'/><arg direction='out' type='s'/>"
    "</method>"
    "<signal name='ActionInvoked'><arg type='u'/><arg type='s'/></signal>"
    "<signal name='NotificationClosed'><arg type='u'/><arg type='u'/></signal>"
    "</interface>"
    "</node>";

static gboolean emit_action(gpointer user_data) {
  PendingAction *action = user_data;
  if (g_connection != NULL) {
    g_dbus_connection_emit_signal(
        g_connection, action->destination, k_object_path, k_interface, "ActionInvoked",
        g_variant_new("(us)", action->notification_id, action->action_id),
        NULL);
    g_dbus_connection_flush_sync(g_connection, NULL, NULL);
  }
  g_free(action->destination);
  g_free(action->action_id);
  g_free(action);
  return G_SOURCE_REMOVE;
}

static void method_call(
    GDBusConnection *connection, const gchar *sender, const gchar *object_path,
    const gchar *interface_name, const gchar *method_name, GVariant *parameters,
    GDBusMethodInvocation *invocation, gpointer user_data) {
  (void)object_path;
  (void)interface_name;
  (void)user_data;
  if (g_strcmp0(method_name, "Notify") == 0) {
    const gchar *application = NULL;
    const gchar *icon = NULL;
    const gchar *summary = NULL;
    const gchar *body = NULL;
    guint replaces_id = 0;
    GVariant *actions = NULL;
    GVariant *hints = NULL;
    gint expire_timeout = 0;
    g_variant_get(
        parameters, "(&susss@as@a{sv}i)", &application, &replaces_id, &icon,
        &summary, &body, &actions, &hints, &expire_timeout);
    (void)application;
    (void)icon;
    (void)summary;
    (void)body;
    (void)hints;
    (void)expire_timeout;
    guint notification_id = replaces_id == 0 ? g_next_notification_id++ : replaces_id;
    gsize action_count = 0;
    const gchar **action_values = g_variant_get_strv(actions, &action_count);
    if (action_count >= 2 || replaces_id == 0) {
      PendingAction *pending = g_new0(PendingAction, 1);
      pending->notification_id = notification_id;
      pending->destination = sender == NULL ? NULL : g_strdup(sender);
      pending->action_id = g_strdup(action_count >= 2 ? action_values[0] : "open");
      g_timeout_add(1000, emit_action, pending);
    }
    g_free(action_values);
    g_variant_unref(actions);
    g_variant_unref(hints);
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(u)", notification_id));
    return;
  }
  if (g_strcmp0(method_name, "CloseNotification") == 0) {
    guint notification_id = 0;
    g_variant_get(parameters, "(u)", &notification_id);
    g_dbus_connection_emit_signal(
        connection, NULL, k_object_path, k_interface, "NotificationClosed",
        g_variant_new("(uu)", notification_id, 3u), NULL);
    g_dbus_method_invocation_return_value(invocation, NULL);
    return;
  }
  if (g_strcmp0(method_name, "GetCapabilities") == 0) {
    GVariantBuilder capabilities;
    g_variant_builder_init(&capabilities, G_VARIANT_TYPE("as"));
    g_variant_builder_add(&capabilities, "s", "actions");
    g_variant_builder_add(&capabilities, "s", "body");
    g_variant_builder_add(&capabilities, "s", "persistence");
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(@as)", g_variant_builder_end(&capabilities)));
    return;
  }
  if (g_strcmp0(method_name, "GetServerInformation") == 0) {
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(ssss)", "KWebShell", "KWebShell", "1.0", "1.2"));
    return;
  }
  g_dbus_method_invocation_return_error(
      invocation, G_IO_ERROR, G_IO_ERROR_NOT_SUPPORTED, "Unsupported notification fixture method.");
}

static const GDBusInterfaceVTable k_vtable = {
    .method_call = method_call,
    .get_property = NULL,
    .set_property = NULL,
    .padding = {NULL, NULL, NULL},
};

static void bus_acquired(
    GDBusConnection *connection, const gchar *name, gpointer user_data) {
  (void)name;
  (void)user_data;
  g_connection = g_object_ref(connection);
  GError *error = NULL;
  GDBusNodeInfo *node = g_dbus_node_info_new_for_xml(k_introspection_xml, &error);
  if (node == NULL) {
    g_printerr("notification fixture introspection failed: %s\n", error->message);
    g_clear_error(&error);
    g_main_loop_quit(g_loop);
    return;
  }
  guint registration = g_dbus_connection_register_object(
      connection, k_object_path, node->interfaces[0], &k_vtable, NULL, NULL, &error);
  g_dbus_node_info_unref(node);
  if (registration == 0) {
    g_printerr("notification fixture registration failed: %s\n", error->message);
    g_clear_error(&error);
    g_main_loop_quit(g_loop);
  }
}

static void name_lost(
    GDBusConnection *connection, const gchar *name, gpointer user_data) {
  (void)connection;
  (void)name;
  (void)user_data;
  if (g_loop != NULL) g_main_loop_quit(g_loop);
}

int main(void) {
  g_loop = g_main_loop_new(NULL, FALSE);
  guint owner = g_bus_own_name(
      G_BUS_TYPE_SESSION, k_interface, G_BUS_NAME_OWNER_FLAGS_NONE,
      bus_acquired, NULL, name_lost, NULL, NULL);
  g_main_loop_run(g_loop);
  g_bus_unown_name(owner);
  if (g_connection != NULL) g_object_unref(g_connection);
  g_main_loop_unref(g_loop);
  return 0;
}
