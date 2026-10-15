#include "power_platform_test.h"

#if defined(__linux__)

#include <gio/gio.h>
#include <signal.h>
#include <unistd.h>

#include "power_tests_fixture.h"

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr char kLogin1Name[] = "org.freedesktop.login1";
constexpr char kLogin1Path[] = "/org/freedesktop/login1";
constexpr char kLogin1Manager[] = "org.freedesktop.login1.Manager";
constexpr char kUPowerName[] = "org.freedesktop.UPower";
constexpr char kUPowerPath[] = "/org/freedesktop/UPower";
constexpr char kUPowerInterface[] = "org.freedesktop.UPower";

/**
 * A real D-Bus peer that owns the logind and UPower names on the test bus. It is
 * a test double for the *service* side of the declared contract: the provider
 * still speaks the real system-bus protocol, and the inhibitor descriptor it
 * receives is a real pipe end that closes when the provider closes it.
 */
struct Double {
  GMainContext *context = nullptr;
  GDBusConnection *connection = nullptr;
  std::thread thread;
  std::atomic<bool> running{false};
  guint login1_registration = 0;
  guint upower_registration = 0;
  guint login1_owner = 0;
  guint upower_owner = 0;
  int inhibit_count = 0;
  std::string last_what;
  std::vector<int> write_ends;
};

/** Dispatches the double's incoming calls on the process default context. */
void IterateDouble(Double *service) {
  while (service->running.load()) {
    g_main_context_iteration(g_main_context_default(), TRUE);
  }
}

Double *g_double = nullptr;

const char *kLogin1Xml =
    "<node>"
    "  <interface name='org.freedesktop.login1.Manager'>"
    "    <method name='Inhibit'>"
    "      <arg type='s' direction='in'/>"
    "      <arg type='s' direction='in'/>"
    "      <arg type='s' direction='in'/>"
    "      <arg type='s' direction='in'/>"
    "      <arg type='h' direction='out'/>"
    "    </method>"
    "    <signal name='PrepareForSleep'><arg type='b'/></signal>"
    "    <signal name='Lock'/>"
    "    <signal name='Unlock'/>"
    "  </interface>"
    "</node>";

const char *kUPowerXml =
    "<node>"
    "  <interface name='org.freedesktop.UPower'>"
    "    <property name='OnBattery' type='b' access='read'/>"
    "  </interface>"
    "</node>";

void OnInhibit(GDBusConnection *connection, const gchar *, const gchar *, const gchar *,
               const gchar *method, GVariant *parameters, GDBusMethodInvocation *invocation,
               gpointer) {
  (void)method;
  if (g_double == nullptr) {
    g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.login1.Error",
                                               "Double is not ready.");
    return;
  }
  const gchar *what = nullptr;
  const gchar *who = nullptr;
  const gchar *why = nullptr;
  const gchar *mode = nullptr;
  g_variant_get(parameters, "(&s&s&s&s)", &what, &who, &why, &mode);
  g_double->inhibit_count += 1;
  g_double->last_what = what != nullptr ? what : "";
  int descriptors[2] = {-1, -1};
  if (pipe(descriptors) != 0) {
    g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.login1.Error",
                                               "Cannot create the inhibitor pipe.");
    return;
  }
  g_double->write_ends.push_back(descriptors[1]);
  GUnixFDList *list = g_unix_fd_list_new();
  const gint index = g_unix_fd_list_append(list, descriptors[0], nullptr);
  close(descriptors[0]);
  GDBusMessage *reply =
      g_dbus_message_new_method_reply(g_dbus_method_invocation_get_message(invocation));
  g_dbus_message_set_unix_fd_list(reply, list);
  g_dbus_message_set_body(reply, g_variant_new("(h)", index));
  g_dbus_connection_send_message(connection, reply, G_DBUS_SEND_MESSAGE_FLAGS_NONE, nullptr, nullptr);
  g_object_unref(reply);
  g_object_unref(list);
}

GVariant *OnUPowerGetProperty(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                              const gchar *property, GError **error, gpointer) {
  if (g_strcmp0(property, "OnBattery") == 0) return g_variant_new_boolean(FALSE);
  g_set_error(error, G_IO_ERROR, G_IO_ERROR_FAILED, "Unknown property %s", property);
  return nullptr;
}

/** True when the provider closed the inhibitor descriptor it received. */
bool InhibitorClosed(int write_end) {
  signal(SIGPIPE, SIG_IGN);
  const char byte = 'x';
  errno = 0;
  const ssize_t written = write(write_end, &byte, 1);
  return written < 0 && errno == EPIPE;
}

}  // namespace

/**
 * Points the provider at a private bus the test owns, so neither the portable
 * tests nor the structure test can depend on the host's real system bus, and a
 * hosted runner whose logind denies the inhibitor cannot change the result.
 * `owned_pid` receives a daemon the caller should terminate.
 */
bool EnsurePrivatePowerBus(std::string *owned_pid) {
  GError *error = nullptr;
  gchar *output = nullptr;
  if (!g_spawn_command_line_sync("dbus-daemon --session --fork --print-address=1 --print-pid=1",
                                 &output, nullptr, nullptr, &error)) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  std::string text = output != nullptr ? output : "";
  g_free(output);
  const size_t newline = text.find('\n');
  if (newline == std::string::npos) return false;
  const std::string address = text.substr(0, newline);
  const std::string pid = text.substr(newline + 1);
  const size_t pid_end = pid.find_first_of("\r\n");
  *owned_pid = pid_end == std::string::npos ? pid : pid.substr(0, pid_end);
  g_setenv("DBUS_SYSTEM_BUS_ADDRESS", address.c_str(), TRUE);
  return true;
}

namespace {

bool StartDouble(Double *service) {
  GError *error = nullptr;
  // A shared bus connection dispatches on the process default context, which this
  // test owns on its own thread, so an incoming call is answered while the test
  // thread is blocked inside the provider.
  service->connection = g_bus_get_sync(G_BUS_TYPE_SYSTEM, nullptr, &error);
  if (service->connection == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  GDBusNodeInfo *login1 = g_dbus_node_info_new_for_xml(kLogin1Xml, &error);
  GDBusNodeInfo *upower = g_dbus_node_info_new_for_xml(kUPowerXml, &error);
  if (login1 == nullptr || upower == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  static const GDBusInterfaceVTable kLogin1Vtable = {OnInhibit, nullptr, nullptr, {nullptr}};
  static const GDBusInterfaceVTable kUPowerVtable = {nullptr, OnUPowerGetProperty, nullptr, {nullptr}};
  service->login1_registration = g_dbus_connection_register_object(
      service->connection, kLogin1Path, login1->interfaces[0], &kLogin1Vtable, nullptr, nullptr, &error);
  service->upower_registration = g_dbus_connection_register_object(
      service->connection, kUPowerPath, upower->interfaces[0], &kUPowerVtable, nullptr, nullptr, &error);
  g_dbus_node_info_unref(login1);
  g_dbus_node_info_unref(upower);
  if (service->login1_registration == 0 || service->upower_registration == 0) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  service->login1_owner = g_bus_own_name_on_connection(service->connection, kLogin1Name,
                                                       G_BUS_NAME_OWNER_FLAGS_NONE, nullptr, nullptr,
                                                       nullptr, nullptr);
  service->upower_owner = g_bus_own_name_on_connection(
      service->connection, kUPowerName, G_BUS_NAME_OWNER_FLAGS_NONE, nullptr, nullptr, nullptr,
      nullptr);
  if (service->login1_owner == 0 || service->upower_owner == 0) return false;
  service->running.store(true);
  service->thread = std::thread(IterateDouble, service);
  // Let the bus publish both names before the provider probes for their owners.
  for (int attempt = 0; attempt < 200; ++attempt) {
    GVariant *reply = g_dbus_connection_call_sync(
        service->connection, "org.freedesktop.DBus", "/org/freedesktop/DBus",
        "org.freedesktop.DBus", "NameHasOwner", g_variant_new("(s)", kLogin1Name),
        G_VARIANT_TYPE("(b)"), G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, nullptr);
    gboolean owned = FALSE;
    if (reply != nullptr) {
      g_variant_get(reply, "(b)", &owned);
      g_variant_unref(reply);
    }
    if (owned == TRUE) return true;
    g_usleep(20000);
  }
  return false;
}

void StopDouble(Double *service) {
  for (const int descriptor : service->write_ends) close(descriptor);
  service->write_ends.clear();
  service->running.store(false);
  g_main_context_wakeup(g_main_context_default());
  if (service->thread.joinable()) service->thread.join();
  if (service->connection != nullptr) {
    if (service->login1_owner != 0) g_bus_unown_name(service->login1_owner);
    if (service->upower_owner != 0) g_bus_unown_name(service->upower_owner);
    if (service->login1_registration != 0) {
      g_dbus_connection_unregister_object(service->connection, service->login1_registration);
    }
    if (service->upower_registration != 0) {
      g_dbus_connection_unregister_object(service->connection, service->upower_registration);
    }
    g_object_unref(service->connection);
    service->connection = nullptr;
  }
}

void EmitSleep(Double *service, bool sleeping) {
  g_dbus_connection_emit_signal(service->connection, nullptr, kLogin1Path, kLogin1Manager,
                                "PrepareForSleep", g_variant_new("(b)", sleeping ? TRUE : FALSE),
                                nullptr);
}

void EmitLockSignal(Double *service, const char *name) {
  g_dbus_connection_emit_signal(service->connection, nullptr, kLogin1Path, kLogin1Manager, name,
                                g_variant_new("()"), nullptr);
}

void EmitPowerSource(Double *service, bool on_battery) {
  GVariantBuilder changed;
  g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
  g_variant_builder_add(&changed, "{sv}", "OnBattery", g_variant_new_boolean(on_battery ? TRUE : FALSE));
  GVariantBuilder invalidated;
  g_variant_builder_init(&invalidated, G_VARIANT_TYPE("as"));
  g_dbus_connection_emit_signal(
      service->connection, nullptr, kUPowerPath, "org.freedesktop.DBus.Properties",
      "PropertiesChanged",
      g_variant_new("(sa{sv}as)", kUPowerInterface, &changed, &invalidated), nullptr);
}

}  // namespace

/** The provider observes on its own D-Bus thread; nothing else to pump. */
void PowerFixture::pump_platform_queue() {}

/** The real logind/UPower system-bus protocol against a test double service. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  std::string spawned_pid;
  if (!EnsurePrivatePowerBus(&spawned_pid)) {
    std::printf("SKIP: no D-Bus session is available for the power provider test.\n");
    return g_failures - failures_before;
  }
  Double service;
  g_double = &service;
  if (!StartDouble(&service)) {
    std::printf("SKIP: the logind/UPower test double could not start.\n");
    StopDouble(&service);
    g_double = nullptr;
    return g_failures - failures_before;
  }

  PowerFixture fixture;
  uint64_t handle = 0;
  kweb_power_configuration configuration = fixture.Configuration();
  const kweb_power_status opened = kweb_power_open(&configuration, &handle);
  if (opened != KWEB_POWER_STATUS_OK) {
    std::printf("SKIP: the Linux power host is unavailable (status %u).\n", opened);
    StopDouble(&service);
    g_double = nullptr;
    return g_failures - failures_before;
  }

  const kweb_power_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "power.linux.logind-upower") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SUSPEND_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_LOCK_EVENTS) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_POWER_SOURCE_STATE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER) != 0);
  // Linux publishes no thermal level through the declared facility.
  KWEB_CHECK((capabilities.capability_bits & KWEB_POWER_CAP_THERMAL_STATE) == 0);

  const kweb_power_state_result state = fixture.State(handle);
  KWEB_CHECK((state.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0);
  KWEB_CHECK((state.fact_bits & KWEB_POWER_FACT_THERMAL) == 0);

  // The real inhibitor request reaches the service and returns a live descriptor.
  kweb_power_lease_result result{};
  auto system_lease = fixture.Request("system", KWEB_POWER_TYPE_SYSTEM_SLEEP, "Keep working", 600);
  const kweb_power_status acquired = fixture.Acquire(handle, system_lease, &result);
  KWEB_CHECK(acquired == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(service.inhibit_count == 1);
  KWEB_CHECK(service.last_what == "sleep");
  auto display_lease = fixture.Request("display", KWEB_POWER_TYPE_DISPLAY_SLEEP, "Show progress", 600);
  KWEB_CHECK(fixture.Acquire(handle, display_lease, &result) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(service.inhibit_count == 2);
  KWEB_CHECK(service.last_what == "idle");

  // Releasing one lease closes exactly its own descriptor.
  kweb_power_release_result released{};
  KWEB_CHECK(fixture.Release(handle, "system", &released) == KWEB_POWER_STATUS_OK);
  KWEB_CHECK(released.released == 1u);
  if (service.write_ends.size() == 2) {
    for (int attempt = 0; attempt < 100 && !InhibitorClosed(service.write_ends[0]); ++attempt) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    KWEB_CHECK(InhibitorClosed(service.write_ends[0]));
    KWEB_CHECK(!InhibitorClosed(service.write_ends[1]));
  }

  // The declared signals publish the matching ordered events. The lease checks
  // above already published their end events, so drain them first.
  fixture.DrainEvents(handle);
  kweb_power_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_POWER_ABI_VERSION;
  EmitSleep(&service, true);
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 3000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_SUSPENDED);
  EmitSleep(&service, false);
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 3000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_RESUMED);
  EmitLockSignal(&service, "Lock");
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 3000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_LOCKED);
  EmitLockSignal(&service, "Unlock");
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 3000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_UNLOCKED);
  EmitPowerSource(&service, true);
  KWEB_CHECK(fixture.AwaitEvent(handle, &event, 3000));
  KWEB_CHECK(event.kind == KWEB_POWER_EVENT_POWER_SOURCE_CHANGED);
  KWEB_CHECK(event.value == 1u);

  // Closing releases the remaining descriptor.
  KWEB_CHECK(kweb_power_close(handle) == KWEB_POWER_STATUS_OK);
  if (service.write_ends.size() == 2) {
    for (int attempt = 0; attempt < 100 && !InhibitorClosed(service.write_ends[1]); ++attempt) {
      std::this_thread::sleep_for(std::chrono::milliseconds(20));
    }
    KWEB_CHECK(InhibitorClosed(service.write_ends[1]));
  }

  StopDouble(&service);
  g_double = nullptr;
  if (!spawned_pid.empty()) {
    const pid_t pid = static_cast<pid_t>(std::atoi(spawned_pid.c_str()));
    if (pid > 0) kill(pid, SIGTERM);
  }
  return g_failures - failures_before;
}

#endif
