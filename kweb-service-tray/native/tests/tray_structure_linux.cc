#include "tray_platform_test.h"

#if defined(__linux__)

#include <gio/gio.h>

#include "tray_tests_fixture.h"

#include <condition_variable>
#include <cstring>
#include <mutex>
#include <string>
#include <thread>

namespace {

/**
 * A session-bus watcher double. It owns org.kde.StatusNotifierWatcher on its own
 * thread so the provider can register synchronously while the test thread waits.
 */
class FakeWatcher {
 public:
  FakeWatcher() {
    thread_ = std::thread([this] {
      GMainContext *context = g_main_context_new();
      g_main_context_push_thread_default(context);
      GMainLoop *loop = g_main_loop_new(context, FALSE);
      connection_ = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
      if (connection_ != nullptr) {
        static const char kXml[] =
            "<node><interface name='org.kde.StatusNotifierWatcher'>"
            "<method name='RegisterStatusNotifierItem'>"
            "<arg type='s' direction='in'/></method>"
            "<method name='RegisterStatusNotifierHost'>"
            "<arg type='s' direction='in'/></method>"
            "</interface></node>";
        GError *error = nullptr;
        GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(kXml, &error);
        if (error != nullptr) g_error_free(error);
        if (info != nullptr) {
          registration_ = g_dbus_connection_register_object(connection_, kWatcherPath, info->interfaces[0],
                                                            &kVTable, this, nullptr, nullptr);
        }
        owned_name_ = g_bus_own_name_on_connection(connection_, kWatcherName, G_BUS_NAME_OWNER_FLAGS_NONE,
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

  ~FakeWatcher() { Stop(); }

  void Stop() {
    if (connection_ != nullptr) {
      GMainContext *context = g_main_context_ref_thread_default();
      g_main_context_invoke_full(
          context, G_PRIORITY_DEFAULT,
          [](gpointer user_data) -> gboolean {
            auto *watcher = static_cast<FakeWatcher *>(user_data);
            if (watcher->loop_ != nullptr) g_main_loop_quit(watcher->loop_);
            return G_SOURCE_REMOVE;
          },
          this, nullptr);
      g_main_context_unref(context);
    }
    if (thread_.joinable()) thread_.join();
    connection_ = nullptr;
  }

  /** Drops the watcher name so the provider observes a host loss. */
  void Release() {
    if (connection_ == nullptr) return;
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(
        connection_, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
        "ReleaseName", g_variant_new("(s)", kWatcherName), G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE,
        2000, nullptr, &error);
    if (reply != nullptr) {
      g_variant_unref(reply);
    } else if (error != nullptr) {
      g_error_free(error);
    }
  }

  /** Re-claims the watcher name so the provider can reconnect. */
  void Reclaim() {
    if (connection_ == nullptr) return;
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(
        connection_, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName",
        g_variant_new("(su)", kWatcherName, 0u), G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 2000,
        nullptr, &error);
    if (reply != nullptr) {
      g_variant_unref(reply);
    } else if (error != nullptr) {
      g_error_free(error);
    }
  }

  std::string registered_service() {
    std::lock_guard<std::mutex> lock(mutex_);
    return service_;
  }

  int registrations() {
    std::lock_guard<std::mutex> lock(mutex_);
    return registrations_;
  }

 private:
  static constexpr const char *kWatcherName = "org.kde.StatusNotifierWatcher";
  static constexpr const char *kWatcherPath = "/StatusNotifierWatcher";

  static void HandleCall(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                         const gchar *method_name, GVariant *parameters,
                         GDBusMethodInvocation *invocation, gpointer user_data) {
    auto *watcher = static_cast<FakeWatcher *>(user_data);
    if (std::strcmp(method_name, "RegisterStatusNotifierItem") == 0) {
      const gchar *service = nullptr;
      g_variant_get(parameters, "(&s)", &service);
      {
        std::lock_guard<std::mutex> lock(watcher->mutex_);
        watcher->service_ = service != nullptr ? service : "";
        watcher->registrations_ += 1;
      }
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    if (std::strcmp(method_name, "RegisterStatusNotifierHost") == 0) {
      g_dbus_method_invocation_return_value(invocation, nullptr);
      return;
    }
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                          "The watcher double does not implement this call.");
  }

  static const GDBusInterfaceVTable kVTable;

  std::thread thread_;
  GMainLoop *loop_ = nullptr;
  GDBusConnection *connection_ = nullptr;
  guint registration_ = 0;
  guint owned_name_ = 0;
  std::mutex mutex_;
  std::condition_variable ready_condition_;
  bool ready_ = false;
  int registrations_ = 0;
  std::string service_;
};

const GDBusInterfaceVTable FakeWatcher::kVTable = {FakeWatcher::HandleCall, nullptr, nullptr, {nullptr}};

GDBusConnection *TestBus() {
  return g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
}

/** Reads one status-notifier property through the shared bus connection. */
GVariant *ReadItemProperty(GDBusConnection *bus, const std::string &service, const char *property) {
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      bus, service.c_str(), "/StatusNotifierItem", "org.freedesktop.DBus.Properties", "Get",
      g_variant_new("(ss)", "org.kde.StatusNotifierItem", property), G_VARIANT_TYPE("(v)"),
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return nullptr;
  }
  return reply;
}

bool CallItemMethod(GDBusConnection *bus, const std::string &service, const char *method) {
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(bus, service.c_str(), "/StatusNotifierItem",
                                                "org.kde.StatusNotifierItem", method,
                                                g_variant_new("(ii)", 0, 0), nullptr,
                                                G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  g_variant_unref(reply);
  return true;
}

std::string FirstLayoutLabel(GDBusConnection *bus, const std::string &service) {
  GError *error = nullptr;
  GVariantBuilder names;
  g_variant_builder_init(&names, G_VARIANT_TYPE("as"));
  GVariant *reply = g_dbus_connection_call_sync(
      bus, service.c_str(), "/MenuBar", "com.canonical.dbusmenu", "GetLayout",
      g_variant_new("(ii@as)", 0, -1, g_variant_builder_end(&names)), nullptr, G_DBUS_CALL_FLAGS_NONE, 2000,
      nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return std::string();
  }
  guint32 revision = 0;
  GVariant *layout = nullptr;
  g_variant_get(reply, "(u@(ia{sv}av))", &revision, &layout);
  GVariant *children = g_variant_get_child_value(layout, 2);
  std::string label;
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

uint32_t FirstChildItemId(GDBusConnection *bus, const std::string &service) {
  GError *error = nullptr;
  GVariantBuilder names;
  g_variant_builder_init(&names, G_VARIANT_TYPE("as"));
  GVariant *reply = g_dbus_connection_call_sync(
      bus, service.c_str(), "/MenuBar", "com.canonical.dbusmenu", "GetLayout",
      g_variant_new("(ii@as)", 0, 1, g_variant_builder_end(&names)), nullptr, G_DBUS_CALL_FLAGS_NONE, 2000,
      nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
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
    GVariant *id_value = g_variant_get_child_value(entry, 0);
    gint32 value = 0;
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

bool ClickMenuItem(GDBusConnection *bus, const std::string &service, uint32_t id) {
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      bus, service.c_str(), "/MenuBar", "com.canonical.dbusmenu", "Event",
      g_variant_new("(isvu)", static_cast<gint32>(id), "clicked", g_variant_new_string(""), 0), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  g_variant_unref(reply);
  return true;
}

bool AwaitEvent(uint64_t handle, kweb_tray_event *event, kweb_tray_event_kind kind) {
  for (int attempt = 0; attempt < 400; ++attempt) {
    kweb_tray_event candidate{};
    candidate.struct_size = sizeof(candidate);
    candidate.abi_version = KWEB_TRAY_ABI_VERSION;
    const kweb_tray_status status = kweb_tray_poll_event(handle, &candidate);
    if (status == KWEB_TRAY_STATUS_OK) {
      if (candidate.kind == kind) {
        *event = candidate;
        return true;
      }
      *event = candidate;
    }
    g_usleep(10000);
  }
  return false;
}

}  // namespace

/** The real session-bus status-notifier item reflects the declared spec. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  FakeWatcher watcher;
  GDBusConnection *bus = TestBus();
  if (bus == nullptr) {
    std::printf("SKIP: no session bus for the tray structure test.\n");
    return g_failures - failures_before;
  }
  uint64_t handle = 0;
  kweb_tray_configuration configuration = TrayConfiguration();
  const kweb_tray_status opened = kweb_tray_open(&configuration, &handle);
  KWEB_CHECK(opened == KWEB_TRAY_STATUS_OK);
  if (opened != KWEB_TRAY_STATUS_OK) {
    g_object_unref(bus);
    return g_failures - failures_before;
  }
  TrayFixture fixture;
  uint32_t created = 0;
  kweb_tray_item_spec item = fixture.Item("structure.item", "KWebShell tray");
  item.activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY | KWEB_TRAY_ACTIVATION_SECONDARY;
  KWEB_CHECK(kweb_tray_set_item(handle, &item, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(created == 1u);
  KWEB_CHECK(watcher.registrations() == 1);
  const std::string service = watcher.registered_service();
  KWEB_CHECK(!service.empty());

  if (!service.empty()) {
    GVariant *tooltip = ReadItemProperty(bus, service, "ToolTip");
    KWEB_CHECK(tooltip != nullptr);
    if (tooltip != nullptr) g_variant_unref(tooltip);
    GVariant *pixmaps = ReadItemProperty(bus, service, "IconPixmap");
    KWEB_CHECK(pixmaps != nullptr);
    if (pixmaps != nullptr) g_variant_unref(pixmaps);

    kweb_tray_menu_tree tree = fixture.Menu(
        "tray.structure", 1,
        {fixture.MenuCommand("tray.open", "Open"), fixture.MenuCommand("tray.quit", "Quit")});
    KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("structure.item"), &tree) == KWEB_TRAY_STATUS_OK);
    KWEB_CHECK(FirstLayoutLabel(bus, service) == "Open");
    const uint32_t first = FirstChildItemId(bus, service);
    KWEB_CHECK(first != 0);
    KWEB_CHECK(ClickMenuItem(bus, service, first));
    kweb_tray_event command{};
    KWEB_CHECK(AwaitEvent(handle, &command, KWEB_TRAY_EVENT_MENU_COMMAND));
    KWEB_CHECK(command.kind == KWEB_TRAY_EVENT_MENU_COMMAND);
    KWEB_CHECK(std::strcmp(command.command_id, "tray.open") == 0);

    KWEB_CHECK(CallItemMethod(bus, service, "Activate"));
    kweb_tray_event activated{};
    KWEB_CHECK(AwaitEvent(handle, &activated, KWEB_TRAY_EVENT_ACTIVATED));
    KWEB_CHECK(activated.activation == KWEB_TRAY_ACTIVATION_PRIMARY);

    // Watcher loss is reported once; the reconnected watcher sees the item again.
    watcher.Release();
    kweb_tray_event failure{};
    KWEB_CHECK(AwaitEvent(handle, &failure, KWEB_TRAY_EVENT_FAILED));
    KWEB_CHECK(std::strcmp(failure.code, "tray.host-lost") == 0);
    const int before_reconnect = watcher.registrations();
    watcher.Reclaim();
    g_usleep(300000);
    kweb_tray_capabilities_result capabilities{};
    capabilities.struct_size = sizeof(capabilities);
    capabilities.abi_version = KWEB_TRAY_ABI_VERSION;
    KWEB_CHECK(kweb_tray_capabilities(handle, &capabilities) == KWEB_TRAY_STATUS_OK);
    KWEB_CHECK(watcher.registrations() > before_reconnect);
    KWEB_CHECK(!watcher.registered_service().empty());
    KWEB_CHECK((capabilities.flags & KWEB_TRAY_CAP_WATCHER_RECONNECT) != 0);
    KWEB_CHECK(kweb_tray_bounds(handle, fixture.Text("structure.item"), nullptr) ==
               KWEB_TRAY_STATUS_INVALID_ARGUMENT);
    kweb_tray_bounds_result bounds{};
    bounds.struct_size = sizeof(bounds);
    bounds.abi_version = KWEB_TRAY_ABI_VERSION;
    KWEB_CHECK(kweb_tray_bounds(handle, fixture.Text("structure.item"), &bounds) ==
               KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE);
  }

  KWEB_CHECK(kweb_tray_close_item(handle, fixture.Text("structure.item")) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
  g_object_unref(bus);
  return g_failures - failures_before;
}

#endif
