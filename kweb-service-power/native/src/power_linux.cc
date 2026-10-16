#include "power_internal.h"

#if !defined(__linux__)
#error "The Linux power provider must only be compiled on Linux."
#endif

#include <gio/gio.h>
#include <unistd.h>

#include <atomic>
#include <cstring>
#include <thread>

namespace {

constexpr char kProviderId[] = "power.linux.logind-upower";
constexpr char kLogin1Name[] = "org.freedesktop.login1";
constexpr char kLogin1Path[] = "/org/freedesktop/login1";
constexpr char kLogin1Manager[] = "org.freedesktop.login1.Manager";
constexpr char kUPowerName[] = "org.freedesktop.UPower";
constexpr char kUPowerPath[] = "/org/freedesktop/UPower";
constexpr char kUPowerInterface[] = "org.freedesktop.UPower";
constexpr guint kCallTimeoutMillis = 5000;

using kwebshell::power::Facts;
using kwebshell::power::Lease;
using kwebshell::power::State;

/**
 * The system-bus session one opened service owns. The connection lives on its own
 * GMainContext so D-Bus signals are dispatched on a thread the caller never
 * blocks, and a synchronous call from the caller can never wait on a signal
 * callback that needs the state lock.
 */
struct LinuxState {
  GMainContext *context = nullptr;
  GDBusConnection *connection = nullptr;
  std::thread thread;
  std::atomic<bool> running{false};
  bool bus_available = false;
  bool login1 = false;
  bool upower = false;
  guint sleep_subscription = 0;
  guint lock_subscription = 0;
  guint unlock_subscription = 0;
  guint power_subscription = 0;
};

State *g_state = nullptr;

LinuxState *StateOf(State &state) { return static_cast<LinuxState *>(state.platform); }

/** True when the D-Bus error means the declared service or bus is absent. */
bool IsServiceMissing(const GError *error) {
  if (error == nullptr) return false;
  return g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SERVICE_UNKNOWN) ||
         g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_NAME_HAS_NO_OWNER) ||
         g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD) ||
         g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_DISCONNECTED) ||
         g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_FAILED);
}

bool NameHasOwner(GDBusConnection *connection, const char *name) {
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      connection, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
      "NameHasOwner", g_variant_new("(s)", name), G_VARIANT_TYPE("(b)"), G_DBUS_CALL_FLAGS_NONE,
      kCallTimeoutMillis, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  gboolean owned = FALSE;
  g_variant_get(reply, "(b)", &owned);
  g_variant_unref(reply);
  return owned == TRUE;
}

void OnPrepareForSleep(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
                       GVariant *parameters, gpointer) {
  if (g_state == nullptr) return;
  gboolean sleeping = FALSE;
  g_variant_get(parameters, "(b)", &sleeping);
  if (sleeping == TRUE) {
    kwebshell::power::PublishSuspended(*g_state);
  } else {
    kwebshell::power::PublishResumed(*g_state);
  }
}

void OnLock(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
            GVariant *, gpointer) {
  if (g_state != nullptr) kwebshell::power::PublishLocked(*g_state);
}

void OnUnlock(GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *,
              GVariant *, gpointer) {
  if (g_state != nullptr) kwebshell::power::PublishUnlocked(*g_state);
}

void OnUPowerProperties(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                        const gchar *signal, GVariant *parameters, gpointer) {
  (void)signal;
  if (g_state == nullptr) return;
  const gchar *interface_name = nullptr;
  GVariant *changed = nullptr;
  GVariant *invalidated = nullptr;
  g_variant_get(parameters, "(&s@a{sv}@as)", &interface_name, &changed, &invalidated);
  if (interface_name != nullptr && g_strcmp0(interface_name, kUPowerInterface) == 0) {
    GVariant *value = g_variant_lookup_value(changed, "OnBattery", G_VARIANT_TYPE_BOOLEAN);
    if (value != nullptr) {
      kwebshell::power::PublishPowerSource(*g_state, g_variant_get_boolean(value) == TRUE);
      g_variant_unref(value);
    }
  }
  g_variant_unref(changed);
  g_variant_unref(invalidated);
}

void IterateContext(LinuxState *state) {
  g_main_context_push_thread_default(state->context);
  while (state->running.load()) {
    g_main_context_iteration(state->context, TRUE);
  }
  g_main_context_pop_thread_default(state->context);
}

/** Reads the UPower OnBattery property, or leaves the fact absent. */
void ReadPowerSource(LinuxState *state, Facts *facts) {
  if (!state->upower) return;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      state->connection, kUPowerName, kUPowerPath, "org.freedesktop.DBus.Properties", "Get",
      g_variant_new("(ss)", kUPowerInterface, "OnBattery"), G_VARIANT_TYPE("(v)"),
      G_DBUS_CALL_FLAGS_NONE, kCallTimeoutMillis, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return;
  }
  GVariant *value = nullptr;
  g_variant_get(reply, "(@v)", &value);
  // Properties.Get replies with a variant whose child carries the value.
  if (value != nullptr && g_variant_is_of_type(value, G_VARIANT_TYPE_VARIANT)) {
    GVariant *inner = g_variant_get_variant(value);
    g_variant_unref(value);
    value = inner;
  }
  if (value != nullptr) {
    if (g_variant_is_of_type(value, G_VARIANT_TYPE_BOOLEAN)) {
      facts->fact_bits |= KWEB_POWER_FACT_ON_BATTERY;
      facts->on_battery = g_variant_get_boolean(value) == TRUE;
    }
    g_variant_unref(value);
  }
  g_variant_unref(reply);
}

}  // namespace

namespace kwebshell::power {

kweb_power_status NativeOpen(State &state) {
  auto *linux = new LinuxState();
  state.platform = linux;
  g_state = &state;
  state.capabilities.provider_id = kProviderId;
  state.capabilities.max_leases = KWEB_POWER_MAX_LEASES;
  state.capabilities.max_leases_per_type = 4;
  state.capabilities.min_lease_seconds = 1;
  state.capabilities.max_lease_seconds = 14400;

  GError *error = nullptr;
  const gchar *configured = g_getenv("DBUS_SYSTEM_BUS_ADDRESS");
  gchar *address = nullptr;
  if (configured != nullptr && configured[0] != '\0') {
    address = g_strdup(configured);
  } else {
    address = g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SYSTEM, nullptr, &error);
  }
  if (address == nullptr) {
    if (error != nullptr) g_error_free(error);
    // No declared facility: every operation fails typed and nothing polls.
    return KWEB_POWER_STATUS_OK;
  }
  linux->context = g_main_context_new();
  GDBusConnection *connection = nullptr;
  {
    g_main_context_push_thread_default(linux->context);
    connection = g_dbus_connection_new_for_address_sync(
        address,
        static_cast<GDBusConnectionFlags>(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT |
                                          G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION),
        nullptr, nullptr, &error);
    g_main_context_pop_thread_default(linux->context);
  }
  g_free(address);
  if (connection == nullptr) {
    if (error != nullptr) g_error_free(error);
    g_main_context_unref(linux->context);
    linux->context = nullptr;
    return KWEB_POWER_STATUS_OK;
  }
  linux->connection = connection;
  linux->bus_available = true;
  linux->login1 = NameHasOwner(connection, kLogin1Name);
  linux->upower = NameHasOwner(connection, kUPowerName);

  if (linux->login1) {
    linux->sleep_subscription = g_dbus_connection_signal_subscribe(
        connection, nullptr, kLogin1Manager, "PrepareForSleep", kLogin1Path, nullptr,
        G_DBUS_SIGNAL_FLAGS_NONE, OnPrepareForSleep, nullptr, nullptr);
    linux->lock_subscription = g_dbus_connection_signal_subscribe(
        connection, nullptr, kLogin1Manager, "Lock", kLogin1Path, nullptr, G_DBUS_SIGNAL_FLAGS_NONE,
        OnLock, nullptr, nullptr);
    linux->unlock_subscription = g_dbus_connection_signal_subscribe(
        connection, nullptr, kLogin1Manager, "Unlock", kLogin1Path, nullptr,
        G_DBUS_SIGNAL_FLAGS_NONE, OnUnlock, nullptr, nullptr);
    state.capabilities.capability_bits |= KWEB_POWER_CAP_SUSPEND_EVENTS | KWEB_POWER_CAP_LOCK_EVENTS |
                                          KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER |
                                          KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER |
                                          KWEB_POWER_CAP_LEASE_RENEWAL;
  }
  if (linux->upower) {
    linux->power_subscription = g_dbus_connection_signal_subscribe(
        connection, nullptr, "org.freedesktop.DBus.Properties", "PropertiesChanged", kUPowerPath,
        nullptr, G_DBUS_SIGNAL_FLAGS_NONE, OnUPowerProperties, nullptr, nullptr);
    state.capabilities.capability_bits |= KWEB_POWER_CAP_POWER_SOURCE_STATE;
  }

  linux->running.store(true);
  linux->thread = std::thread(IterateContext, linux);

  Facts facts;
  ReadPowerSource(linux, &facts);
  state.facts = facts;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeReadFacts(State &state, Facts *facts) {
  LinuxState *linux = StateOf(state);
  if (linux == nullptr || facts == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  if (!linux->bus_available) return KWEB_POWER_STATUS_SERVICE_UNAVAILABLE;
  ReadPowerSource(linux, facts);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeAcquire(State &state, const Lease &lease, uint64_t *token) {
  LinuxState *linux = StateOf(state);
  if (linux == nullptr || token == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  if (!linux->bus_available || !linux->login1) return KWEB_POWER_STATUS_SERVICE_UNAVAILABLE;
  const char *what = lease.type == KWEB_POWER_TYPE_SYSTEM_SLEEP ? "sleep" : "idle";
  GError *error = nullptr;
  GUnixFDList *fds = nullptr;
  GVariant *reply = g_dbus_connection_call_with_unix_fd_list_sync(
      linux->connection, kLogin1Name, kLogin1Path, kLogin1Manager, "Inhibit",
      g_variant_new("(ssss)", what, "KWebShell", lease.reason.c_str(), "block"),
      G_VARIANT_TYPE("(h)"), G_DBUS_CALL_FLAGS_NONE, kCallTimeoutMillis, nullptr, &fds, nullptr,
      &error);
  if (reply == nullptr) {
    const bool missing = IsServiceMissing(error);
    if (error != nullptr) g_error_free(error);
    return missing ? KWEB_POWER_STATUS_SERVICE_UNAVAILABLE : KWEB_POWER_STATUS_NATIVE_FAILED;
  }
  gint32 index = -1;
  g_variant_get(reply, "(h)", &index);
  g_variant_unref(reply);
  if (fds == nullptr) return KWEB_POWER_STATUS_NATIVE_FAILED;
  GError *fd_error = nullptr;
  const int descriptor = g_unix_fd_list_get(fds, index, &fd_error);
  g_object_unref(fds);
  if (descriptor < 0) {
    if (fd_error != nullptr) g_error_free(fd_error);
    return KWEB_POWER_STATUS_NATIVE_FAILED;
  }
  // The inhibitor lives exactly as long as this descriptor is open.
  *token = static_cast<uint64_t>(descriptor);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeRelease(State &state, const Lease &lease) {
  LinuxState *linux = StateOf(state);
  if (linux == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  if (lease.token == 0) return KWEB_POWER_STATUS_OK;
  if (close(static_cast<int>(lease.token)) != 0) return KWEB_POWER_STATUS_NATIVE_FAILED;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeClose(State &state) {
  LinuxState *linux = StateOf(state);
  if (linux == nullptr) return KWEB_POWER_STATUS_OK;
  if (linux->connection != nullptr) {
    if (linux->sleep_subscription != 0) {
      g_dbus_connection_signal_unsubscribe(linux->connection, linux->sleep_subscription);
    }
    if (linux->lock_subscription != 0) {
      g_dbus_connection_signal_unsubscribe(linux->connection, linux->lock_subscription);
    }
    if (linux->unlock_subscription != 0) {
      g_dbus_connection_signal_unsubscribe(linux->connection, linux->unlock_subscription);
    }
    if (linux->power_subscription != 0) {
      g_dbus_connection_signal_unsubscribe(linux->connection, linux->power_subscription);
    }
  }
  linux->running.store(false);
  if (linux->context != nullptr) {
    g_main_context_wakeup(linux->context);
  }
  if (linux->thread.joinable()) linux->thread.join();
  if (linux->connection != nullptr) {
    g_dbus_connection_close_sync(linux->connection, nullptr, nullptr);
    g_object_unref(linux->connection);
    linux->connection = nullptr;
  }
  if (linux->context != nullptr) {
    g_main_context_unref(linux->context);
    linux->context = nullptr;
  }
  if (g_state == &state) g_state = nullptr;
  delete linux;
  state.platform = nullptr;
  return KWEB_POWER_STATUS_OK;
}

}  // namespace kwebshell::power
