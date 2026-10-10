#include "menus_platform_test.h"

#if defined(__linux__)

#include "menus_tests_fixture.h"

#include <gio/gio.h>

#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

/**
 * A session-bus registrar double. It owns com.canonical.AppMenu.Registrar on its
 * own thread so the provider can call it synchronously while the test thread is
 * blocked inside the ABI.
 */
class FakeRegistrar {
 public:
  FakeRegistrar() {
    thread_ = std::thread([this] {
      GMainContext *context = g_main_context_new();
      g_main_context_push_thread_default(context);
      GMainLoop *loop = g_main_loop_new(context, FALSE);
      connection_ = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
      if (connection_ != nullptr) {
        static const char kXml[] =
            "<node><interface name='com.canonical.AppMenu.Registrar'>"
            "<method name='RegisterWindow'><arg type='u' direction='in'/>"
            "<arg type='o' direction='in'/></method>"
            "<method name='UnregisterWindow'><arg type='u' direction='in'/></method>"
            "</interface></node>";
        GError *error = nullptr;
        GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(kXml, &error);
        if (error != nullptr) g_error_free(error);
        if (info != nullptr) {
          registration_ = g_dbus_connection_register_object(connection_, kRegistrarPath, info->interfaces[0],
                                                            &kVTable, this, nullptr, nullptr);
        }
        owned_name_ = g_bus_own_name_on_connection(connection_, kRegistrarName, G_BUS_NAME_OWNER_FLAGS_NONE,
                                                   nullptr, nullptr, nullptr, nullptr);
      }
      {
        std::lock_guard<std::mutex> lock(mutex_);
        loop_ = loop;
        ready_ = true;
      }
      ready_condition_.notify_all();
      if (connection_ != nullptr) g_main_loop_run(loop);
      g_main_loop_unref(loop);
      g_main_context_pop_thread_default(context);
      g_main_context_unref(context);
    });
    std::unique_lock<std::mutex> lock(mutex_);
    ready_condition_.wait(lock, [this] { return ready_; });
  }

  ~FakeRegistrar() { Stop(); }

  void Stop() {
    if (connection_ != nullptr) {
      GMainContext *context = g_main_context_ref_thread_default();
      g_main_context_invoke_full(
          context, G_PRIORITY_DEFAULT,
          [](gpointer user_data) -> gboolean {
            auto *registrar = static_cast<FakeRegistrar *>(user_data);
            if (registrar->loop_ != nullptr) g_main_loop_quit(registrar->loop_);
            return G_SOURCE_REMOVE;
          },
          this, nullptr);
      g_main_context_unref(context);
    }
    if (thread_.joinable()) thread_.join();
    connection_ = nullptr;
  }

  bool registered() {
    std::lock_guard<std::mutex> lock(mutex_);
    return registered_;
  }

  uint64_t registered_xid() {
    std::lock_guard<std::mutex> lock(mutex_);
    return xid_;
  }

  std::string registered_path() {
    std::lock_guard<std::mutex> lock(mutex_);
    return path_;
  }

  uint64_t unregistered_xid() {
    std::lock_guard<std::mutex> lock(mutex_);
    return unregistered_xid_;
  }

 private:
  static constexpr const char *kRegistrarName = "com.canonical.AppMenu.Registrar";
  static constexpr const char *kRegistrarPath = "/com/canonical/AppMenu/Registrar";
  static constexpr const char *kRegistrarInterface = "com.canonical.AppMenu.Registrar";

  static void HandleCall(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *method_name,
                         GVariant *parameters, GDBusMethodInvocation *invocation, gpointer user_data) {
    auto *registrar = static_cast<FakeRegistrar *>(user_data);
    if (std::strcmp(method_name, "RegisterWindow") == 0) {
      guint32 xid = 0;
      const gchar *path = nullptr;
      g_variant_get(parameters, "(u&o)", &xid, &path);
      {
        std::lock_guard<std::mutex> lock(registrar->mutex_);
        registrar->registered_ = true;
        registrar->xid_ = xid;
        registrar->path_ = path != nullptr ? path : "";
      }
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    if (std::strcmp(method_name, "UnregisterWindow") == 0) {
      guint32 xid = 0;
      g_variant_get(parameters, "(u)", &xid);
      {
        std::lock_guard<std::mutex> lock(registrar->mutex_);
        registrar->unregistered_xid_ = xid;
      }
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                          "The registrar double does not implement this call.");
  }

  static const GDBusInterfaceVTable kVTable;

  std::thread thread_;
  GMainContext *context_ = nullptr;
  GMainLoop *loop_ = nullptr;
  GDBusConnection *connection_ = nullptr;
  guint registration_ = 0;
  guint owned_name_ = 0;
  std::mutex mutex_;
  std::condition_variable ready_condition_;
  bool ready_ = false;
  bool registered_ = false;
  uint64_t xid_ = 0;
  uint64_t unregistered_xid_ = 0;
  std::string path_;
};

const GDBusInterfaceVTable FakeRegistrar::kVTable = {FakeRegistrar::HandleCall, nullptr, nullptr, {nullptr}};

GDBusConnection *TestBusConnection() {
  return g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
}

std::string FirstLayoutLabel(GDBusConnection *connection, const std::string &path) {
  GError *error = nullptr;
  GVariantBuilder names;
  g_variant_builder_init(&names, G_VARIANT_TYPE("as"));
  GVariant *reply = g_dbus_connection_call_sync(
      connection, g_dbus_connection_get_unique_name(connection), path.c_str(), "com.canonical.dbusmenu",
      "GetLayout", g_variant_new("(ii@as)", 0, -1, g_variant_builder_end(&names)), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) {
      std::printf("GetLayout failed: %s\n", error->message);
      g_error_free(error);
    }
    return std::string();
  }
  guint32 revision = 0;
  std::string label;
  GVariant *layout = nullptr;
  if (const char *force_debug = g_getenv("KWEB_MENUS_TEST_DEBUG")) {
    if (force_debug[0] != '\0') {
      gchar *printed = g_variant_print(reply, TRUE);
      std::printf("GetLayout reply: %s\n", printed);
      g_free(printed);
    }
  }
  g_variant_get(reply, "(u@(ia{sv}av))", &revision, &layout);
  GVariant *children = g_variant_get_child_value(layout, 2);
  if (g_variant_n_children(children) > 0) {
    GVariant *child = g_variant_get_child_value(children, 0);
    GVariant *entry = g_variant_get_variant(child);
    GVariant *properties = g_variant_get_child_value(entry, 1);
    GVariant *value = g_variant_lookup_value(properties, "label", G_VARIANT_TYPE_STRING);
    if (value != nullptr) {
      label = g_variant_get_string(value, nullptr);
      g_variant_unref(value);
    }
    g_variant_unref(properties);
    g_variant_unref(entry);
    g_variant_unref(child);
  }
  g_variant_unref(children);
  g_variant_unref(layout);
  g_variant_unref(reply);
  return label;
}

uint32_t FirstChildItemId(GDBusConnection *connection, const std::string &path) {
  GError *error = nullptr;
  GVariantBuilder names;
  g_variant_builder_init(&names, G_VARIANT_TYPE("as"));
  GVariant *reply = g_dbus_connection_call_sync(
      connection, g_dbus_connection_get_unique_name(connection), path.c_str(), "com.canonical.dbusmenu",
      "GetLayout", g_variant_new("(ii@as)", 0, 1, g_variant_builder_end(&names)), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) {
      std::printf("GetLayout(depth) failed: %s\n", error->message);
      g_error_free(error);
    }
    return 0;
  }
  guint32 revision = 0;
  GVariant *layout = nullptr;
  g_variant_get(reply, "(u@(ia{sv}av))", &revision, &layout);
  GVariant *children = g_variant_get_child_value(layout, 2);
  uint32_t id = 0;
  if (g_variant_n_children(children) > 0) {
    GVariant *child = g_variant_get_child_value(children, 0);
    GVariant *entry = g_variant_get_variant(child);
    gint32 value = 0;
    GVariant *id_value = g_variant_get_child_value(entry, 0);
    g_variant_get(id_value, "i", &value);
    id = static_cast<uint32_t>(value);
    g_variant_unref(id_value);
    g_variant_unref(entry);
    g_variant_unref(child);
  }
  g_variant_unref(children);
  g_variant_unref(layout);
  g_variant_unref(reply);
  return id;
}

bool ClickMenuItem(GDBusConnection *connection, const std::string &path, uint32_t id) {
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      connection, g_dbus_connection_get_unique_name(connection), path.c_str(), "com.canonical.dbusmenu",
      "Event", g_variant_new("(isvu)", static_cast<gint32>(id), "clicked", g_variant_new_string(""), 0), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) {
      std::printf("Event failed: %s\n", error->message);
      g_error_free(error);
    }
    return false;
  }
  g_variant_unref(reply);
  return true;
}

int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  FakeRegistrar registrar;
  GDBusConnection *bus = TestBusConnection();
  if (bus == nullptr) {
    std::printf("SKIP: no session bus for the desktop menu test.\n");
    return 0;
  }
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  kweb_menus_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_MENUS_ABI_VERSION;
  KWEB_CHECK(kweb_menus_capabilities(handle, &capabilities) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_WINDOW_MENU) != 0);
  KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_GLOBAL_MENU_HOST) != 0);

  Fixture fixture;
  fixture.Command("file.open", "Open");
  fixture.Separator();
  fixture.Command("file.quit", "Quit");
  kweb_menus_tree tree = fixture.Build();
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("main-window"), 4242,
                                        KWEB_MENUS_OWNER_WINDOW, &tree) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(registrar.registered());
  KWEB_CHECK(registrar.registered_xid() == 4242);
  const std::string path = registrar.registered_path();
  KWEB_CHECK(!path.empty());
  if (!path.empty()) {
    KWEB_CHECK(FirstLayoutLabel(bus, path) == "Open");
    const uint32_t first = FirstChildItemId(bus, path);
    KWEB_CHECK(first != 0);
    KWEB_CHECK(ClickMenuItem(bus, path, first));
    kweb_menus_event event{};
    event.struct_size = sizeof(event);
    event.abi_version = KWEB_MENUS_ABI_VERSION;
    bool observed = false;
    for (int attempt = 0; attempt < 200; ++attempt) {
      const kweb_menus_status status = kweb_menus_poll_event(handle, &event);
      if (status == KWEB_MENUS_STATUS_OK) {
        if (event.kind == KWEB_MENUS_EVENT_INVOKED) observed = true;
        break;
      }
      g_usleep(10000);
    }
    KWEB_CHECK(observed);
    KWEB_CHECK(std::strcmp(event.command_id, "file.open") == 0);
    KWEB_CHECK(event.source == KWEB_MENUS_SOURCE_WINDOW_MENU);
    KWEB_CHECK(event.owner_kind == KWEB_MENUS_OWNER_WINDOW);
  }
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("main-window"), 4242,
                                        KWEB_MENUS_OWNER_WINDOW, nullptr) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(registrar.unregistered_xid() == 4242);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
  g_object_unref(bus);
  return g_failures - failures_before;
}


#endif
