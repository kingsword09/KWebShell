#include <gio/gio.h>

static const gchar introspection_xml[] =
    "<node>"
    "  <interface name='org.freedesktop.FileManager1'>"
    "    <method name='ShowItems'>"
    "      <arg name='uris' type='as' direction='in'/>"
    "      <arg name='startup_id' type='s' direction='in'/>"
    "    </method>"
    "  </interface>"
    "</node>";

static GDBusNodeInfo *node_info;
static GMainLoop *main_loop;
static guint registration_id;

static void handle_method_call(
    GDBusConnection *connection,
    const gchar *sender,
    const gchar *object_path,
    const gchar *interface_name,
    const gchar *method_name,
    GVariant *parameters,
    GDBusMethodInvocation *invocation,
    gpointer user_data) {
  (void)connection;
  (void)sender;
  (void)object_path;
  (void)interface_name;
  (void)parameters;
  (void)user_data;

  if (g_strcmp0(method_name, "ShowItems") != 0) {
    g_dbus_method_invocation_return_error(
        invocation,
        G_IO_ERROR,
        G_IO_ERROR_NOT_SUPPORTED,
        "The hosted FileManager1 fixture does not implement '%s'.",
        method_name);
    return;
  }

  g_dbus_method_invocation_return_value(invocation, NULL);
}

static const GDBusInterfaceVTable interface_vtable = {
    .method_call = handle_method_call,
    .get_property = NULL,
    .set_property = NULL,
};

static void on_bus_acquired(
    GDBusConnection *connection,
    const gchar *name,
    gpointer user_data) {
  (void)name;
  (void)user_data;

  GError *error = NULL;
  registration_id = g_dbus_connection_register_object(
      connection,
      "/org/freedesktop/FileManager1",
      node_info->interfaces[0],
      &interface_vtable,
      NULL,
      NULL,
      &error);
  if (registration_id == 0) {
    g_printerr("Could not register hosted FileManager1 fixture: %s\n", error->message);
    g_clear_error(&error);
    g_main_loop_quit(main_loop);
  }
}

static void on_name_lost(
    GDBusConnection *connection,
    const gchar *name,
    gpointer user_data) {
  (void)connection;
  (void)name;
  (void)user_data;
  g_main_loop_quit(main_loop);
}

int main(void) {
  GError *error = NULL;
  node_info = g_dbus_node_info_new_for_xml(introspection_xml, &error);
  if (node_info == NULL) {
    g_printerr("Could not parse hosted FileManager1 introspection: %s\n", error->message);
    g_clear_error(&error);
    return 1;
  }

  main_loop = g_main_loop_new(NULL, FALSE);
  const guint owner_id = g_bus_own_name(
      G_BUS_TYPE_SESSION,
      "org.freedesktop.FileManager1",
      G_BUS_NAME_OWNER_FLAGS_NONE,
      on_bus_acquired,
      NULL,
      on_name_lost,
      NULL,
      NULL);
  g_main_loop_run(main_loop);

  g_dbus_node_info_unref(node_info);
  g_bus_unown_name(owner_id);
  g_main_loop_unref(main_loop);
  return 0;
}
