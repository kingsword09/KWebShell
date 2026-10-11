#include "preferences_internal.h"

#if !defined(__linux__)
#error "The Linux system-preferences provider must only be compiled on Linux."
#endif

#include <gio/gio.h>

#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace {

using kwebshell::preferences::Facts;
using kwebshell::preferences::State;

constexpr char kPortalName[] = "org.freedesktop.portal.Desktop";
constexpr char kPortalPath[] = "/org/freedesktop/portal/desktop";
constexpr char kPortalSettings[] = "org.freedesktop.portal.Settings";
constexpr char kAppearanceNamespace[] = "org.freedesktop.appearance";
constexpr char kA11yBusName[] = "org.a11y.Bus";
constexpr char kA11yBusPath[] = "/org/a11y/bus";
constexpr char kA11yStatusName[] = "org.a11y.Status";
constexpr char kA11yStatusPath[] = "/org/a11y/status";
constexpr char kPropertiesInterface[] = "org.freedesktop.DBus.Properties";
constexpr char kGnomeInterfaceSchema[] = "org.gnome.desktop.interface";

/** One bus thread owns the portal subscription, the a11y connection, and the desktop interface. */
struct LinuxState {
  std::thread thread;
  GMainContext *context = nullptr;
  GMainLoop *loop = nullptr;
  GDBusConnection *bus = nullptr;
  GDBusConnection *a11y_bus = nullptr;
  GSettings *interface_settings = nullptr;
  guint portal_subscription = 0;
  guint a11y_subscription = 0;
  bool portal_available = false;
  bool desktop_interface_available = false;
  std::mutex mutex;
  std::condition_variable ready;
  bool started = false;
  bool stopping = false;
};

kwebshell::preferences::State *g_state = nullptr;

/** Stops the bus thread of a provider that never became the process owner. */
void StopBusThread(LinuxState *state) {
  if (state == nullptr) return;
  if (state->context != nullptr) {
    g_main_context_invoke_full(
        state->context, G_PRIORITY_DEFAULT,
        [](gpointer user_data) -> gboolean {
          auto *linux = static_cast<LinuxState *>(user_data);
          if (linux->loop != nullptr) g_main_loop_quit(linux->loop);
          return G_SOURCE_REMOVE;
        },
        state, nullptr);
  }
  if (state->thread.joinable()) state->thread.join();
  if (state->portal_subscription != 0 && state->bus != nullptr) {
    g_dbus_connection_signal_unsubscribe(state->bus, state->portal_subscription);
  }
  if (state->a11y_subscription != 0 && state->a11y_bus != nullptr) {
    g_dbus_connection_signal_unsubscribe(state->a11y_bus, state->a11y_subscription);
  }
  if (state->interface_settings != nullptr) g_object_unref(state->interface_settings);
  if (state->a11y_bus != nullptr) g_object_unref(state->a11y_bus);
  if (state->bus != nullptr) g_object_unref(state->bus);
}

/** Runs one block on the provider's bus thread and waits for it. */
bool RunOnBusThread(LinuxState &state, const std::function<void()> &block) {
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.started || state.stopping || state.context == nullptr) return false;
  }
  std::mutex completion_mutex;
  std::condition_variable completion;
  bool done = false;
  auto *work = new std::function<void()>([&block, &done, &completion_mutex, &completion] {
    block();
    {
      std::lock_guard<std::mutex> lock(completion_mutex);
      done = true;
    }
    completion.notify_all();
  });
  g_main_context_invoke_full(
      state.context, G_PRIORITY_DEFAULT,
      [](gpointer user_data) -> gboolean {
        auto *queued = static_cast<std::function<void()> *>(user_data);
        (*queued)();
        delete queued;
        return G_SOURCE_REMOVE;
      },
      work, nullptr);
  std::unique_lock<std::mutex> lock(completion_mutex);
  completion.wait(lock, [&done] { return done; });
  return true;
}

/** Reads one settings-portal key through ReadOne (interface version 2). */
bool ReadPortalValue(LinuxState &state, const char *key, GVariant **value) {
  if (state.bus == nullptr) return false;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      state.bus, kPortalName, kPortalPath, kPortalSettings, "ReadOne",
      g_variant_new("(ss)", kAppearanceNamespace, key), nullptr, G_DBUS_CALL_FLAGS_NONE, 2000, nullptr,
      &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  GVariant *child = g_variant_get_child_value(reply, 0);
  *value = g_variant_get_variant(child);
  g_variant_unref(child);
  g_variant_unref(reply);
  return *value != nullptr;
}

/** Reads the portal's unsigned preference value, or reports it as unpublished. */
bool ReadPortalUint32(LinuxState &state, const char *key, guint32 *value) {
  GVariant *variant = nullptr;
  if (!ReadPortalValue(state, key, &variant)) return false;
  const bool typed = g_variant_is_of_type(variant, G_VARIANT_TYPE_UINT32);
  if (typed) *value = g_variant_get_uint32(variant);
  g_variant_unref(variant);
  return typed;
}

void OnPortalSettingChanged(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                            const gchar *, GVariant *parameters, gpointer user_data) {
  (void)user_data;
  const gchar *name = nullptr;
  const gchar *key = nullptr;
  GVariant *value = nullptr;
  g_variant_get(parameters, "(&s&s@v)", &name, &key, &value);
  const bool appearance = g_strcmp0(name, kAppearanceNamespace) == 0;
  const bool declared_key = key != nullptr && (g_strcmp0(key, "color-scheme") == 0 ||
                                               g_strcmp0(key, "contrast") == 0 ||
                                               g_strcmp0(key, "reduced-motion") == 0 ||
                                               g_strcmp0(key, "accent-color") == 0);
  if (value != nullptr) g_variant_unref(value);
  if (!appearance || !declared_key) return;
  if (g_state != nullptr) kwebshell::preferences::PushChanged(*g_state);
}

void OnA11yPropertiesChanged(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                             const gchar *, GVariant *parameters, gpointer) {
  // The signal's interface is org.freedesktop.DBus.Properties; the interface whose
  // properties changed is the first part of the payload.
  const gchar *changed_interface = nullptr;
  GVariant *changed = nullptr;
  GVariant *invalidated = nullptr;
  g_variant_get(parameters, "(&s@a{sv}@as)", &changed_interface, &changed, &invalidated);
  const bool status_changed = g_strcmp0(changed_interface, kA11yStatusName) == 0;
  g_variant_unref(changed);
  g_variant_unref(invalidated);
  if (status_changed && g_state != nullptr) kwebshell::preferences::PushChanged(*g_state);
}

void OnInterfaceSettingsChanged(GSettings *, gchar *, gpointer) {
  if (g_state != nullptr) kwebshell::preferences::PushChanged(*g_state);
}

/** Resolves the accessibility bus and subscribes to the screen-reader property. */
void SubscribeA11y(LinuxState &state, GDBusConnection *bus) {
  if (bus == nullptr) return;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(bus, kA11yBusName, kA11yBusPath, kA11yBusName,
                                                "GetAddress", nullptr, G_VARIANT_TYPE("(s)"),
                                                G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    std::fprintf(stderr, "KWEBSHELL_PREFERENCES_FAILURE stage=a11y-get-address error=%s\n",
                 error != nullptr ? error->message : "unknown");
    if (error != nullptr) g_error_free(error);
    return;
  }
  const gchar *address = nullptr;
  g_variant_get(reply, "(&s)", &address);
  if (address != nullptr && address[0] != '\0') {
    state.a11y_bus = g_dbus_connection_new_for_address_sync(
        address,
        static_cast<GDBusConnectionFlags>(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT |
                                          G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION),
        nullptr, nullptr, &error);
    if (state.a11y_bus == nullptr) {
      std::fprintf(stderr, "KWEBSHELL_PREFERENCES_FAILURE stage=a11y-connect error=%s\n",
                   error != nullptr ? error->message : "unknown");
      if (error != nullptr) g_error_free(error);
    }
  }
  g_variant_unref(reply);
  if (state.a11y_bus != nullptr) {
    state.a11y_subscription = g_dbus_connection_signal_subscribe(
        state.a11y_bus, nullptr, kPropertiesInterface, "PropertiesChanged", kA11yStatusPath, nullptr,
        G_DBUS_SIGNAL_FLAGS_NONE, OnA11yPropertiesChanged, nullptr, nullptr);
  }
}

/**
 * Reads every declared fact. Color scheme, contrast, reduced motion, and accent
 * color come from the settings portal; text scale comes from the declared GNOME
 * desktop interface when that schema is installed; the screen-reader fact comes
 * from the accessibility bus under its declared key. Facts the session does not
 * publish stay absent.
 */
void ReadFacts(LinuxState &state, kwebshell::preferences::State &abi_state, Facts *facts) {
  uint32_t published = 0;
  guint32 value = 0;
  if (ReadPortalUint32(state, "color-scheme", &value)) {
    if (value == 1u) {
      facts->color_scheme = KWEB_COLOR_SCHEME_DARK;
      published |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
    } else if (value == 2u) {
      facts->color_scheme = KWEB_COLOR_SCHEME_LIGHT;
      published |= KWEB_PREFERENCE_FACT_COLOR_SCHEME;
    }
    // Zero means the portal publishes no preference, so the fact stays absent.
  }
  if (ReadPortalUint32(state, "contrast", &value)) {
    facts->contrast = value == 1u ? KWEB_CONTRAST_MORE : KWEB_CONTRAST_NONE;
    published |= KWEB_PREFERENCE_FACT_CONTRAST;
  }
  if (ReadPortalUint32(state, "reduced-motion", &value)) {
    facts->reduced_motion = value == 1u ? KWEB_PREFERENCE_TRUE : KWEB_PREFERENCE_FALSE;
    published |= KWEB_PREFERENCE_FACT_REDUCED_MOTION;
  }
  GVariant *accent = nullptr;
  if (ReadPortalValue(state, "accent-color", &accent)) {
    if (g_variant_is_of_type(accent, G_VARIANT_TYPE("(ddd)"))) {
      double red = 0.0;
      double green = 0.0;
      double blue = 0.0;
      g_variant_get(accent, "(ddd)", &red, &green, &blue);
      const bool in_range = red >= 0.0 && red <= 1.0 && green >= 0.0 && green <= 1.0 && blue >= 0.0 &&
                            blue <= 1.0;
      if (in_range) {
        facts->accent_source = KWEB_ACCENT_SOURCE_DESKTOP_PORTAL;
        facts->accent_red = static_cast<uint32_t>(std::lround(red * 255.0));
        facts->accent_green = static_cast<uint32_t>(std::lround(green * 255.0));
        facts->accent_blue = static_cast<uint32_t>(std::lround(blue * 255.0));
        published |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
      }
      // Out-of-range values are treated as an unset accent color.
    }
    g_variant_unref(accent);
  }
  if (state.interface_settings != nullptr) {
    const double scale = g_settings_get_double(state.interface_settings, "text-scaling-factor");
    if (scale >= 0.5 && scale <= 5.0) {
      facts->text_scale_percent = static_cast<uint32_t>(std::lround(scale * 100.0));
      published |= KWEB_PREFERENCE_FACT_TEXT_SCALE;
    }
  }
  const bool declared_keys = (abi_state.sensitive_key_bits & abi_state.target_key_bit) != 0;
  if (declared_keys && state.a11y_bus != nullptr) {
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(
        state.a11y_bus, kA11yStatusName, kA11yStatusPath, kPropertiesInterface, "Get",
        g_variant_new("(ss)", kA11yStatusName, "ScreenReaderEnabled"), nullptr, G_DBUS_CALL_FLAGS_NONE,
        2000, nullptr, &error);
    if (reply == nullptr) {
      std::fprintf(stderr, "KWEBSHELL_PREFERENCES_FAILURE stage=a11y-property error=%s\n",
                   error != nullptr ? error->message : "unknown");
      if (error != nullptr) g_error_free(error);
    } else {
      // A property reply wraps the value in one variant layer.
      GVariant *wrapped = g_variant_get_child_value(reply, 0);
      GVariant *value = g_variant_get_variant(wrapped);
      if (value != nullptr && g_variant_is_of_type(value, G_VARIANT_TYPE_BOOLEAN)) {
        facts->screen_reader =
            g_variant_get_boolean(value) ? KWEB_PREFERENCE_TRUE : KWEB_PREFERENCE_FALSE;
        published |= KWEB_PREFERENCE_FACT_SCREEN_READER;
      }
      if (value != nullptr) g_variant_unref(value);
      g_variant_unref(wrapped);
      g_variant_unref(reply);
    }
  }
  // The portal publishes no reduced-transparency, differentiate-without-color,
  // or invert-colors fact, so those stay absent.
  facts->published_bits = published;
  facts->live_bits = published;
}

}  // namespace

namespace kwebshell::preferences {

const char *ProviderId() { return "preferences.linux.settings-portal"; }

kweb_preferences_status NativeOpen(State &state) {
  auto *linux = new LinuxState();
  state.platform = linux;
  state.target_key_bit = KWEB_PREFERENCES_KEY_LINUX_A11Y;
  std::unique_lock<std::mutex> lock(linux->mutex);
  linux->thread = std::thread([linux] {
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GMainLoop *loop = g_main_loop_new(context, FALSE);
    GDBusConnection *bus = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
    bool portal_present = false;
    if (bus != nullptr) {
      GError *error = nullptr;
      GVariant *reply = g_dbus_connection_call_sync(
          bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
          g_variant_new("(s)", kPortalName), G_VARIANT_TYPE("(b)"), G_DBUS_CALL_FLAGS_NONE, 2000, nullptr,
          &error);
      if (reply != nullptr) {
        gboolean owned = FALSE;
        g_variant_get(reply, "(b)", &owned);
        portal_present = owned == TRUE;
        g_variant_unref(reply);
      } else if (error != nullptr) {
        g_error_free(error);
      }
      if (portal_present) {
        linux->portal_subscription = g_dbus_connection_signal_subscribe(
            bus, kPortalName, kPortalSettings, "SettingChanged", kPortalPath, nullptr,
            G_DBUS_SIGNAL_FLAGS_NONE, OnPortalSettingChanged, linux, nullptr);
      }
      linux->portal_available = portal_present;
      SubscribeA11y(*linux, bus);
      GSettingsSchemaSource *source = g_settings_schema_source_get_default();
      if (source != nullptr &&
          g_settings_schema_source_lookup(source, kGnomeInterfaceSchema, TRUE) != nullptr) {
        linux->interface_settings = g_settings_new(kGnomeInterfaceSchema);
        linux->desktop_interface_available = true;
        g_signal_connect(linux->interface_settings, "changed::text-scaling-factor",
                         G_CALLBACK(OnInterfaceSettingsChanged), nullptr);
      }
    }
    {
      std::lock_guard<std::mutex> guard(linux->mutex);
      linux->context = context;
      linux->loop = loop;
      linux->bus = bus;
      linux->started = true;
    }
    linux->ready.notify_all();
    if (bus != nullptr) g_main_loop_run(loop);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
  });
  linux->ready.wait(lock, [linux] { return linux->started; });
  const bool any_facility =
      linux->bus != nullptr &&
      (linux->portal_available || linux->desktop_interface_available || linux->a11y_bus != nullptr);
  lock.unlock();
  if (!any_facility) {
    // A session without the settings portal, the declared desktop interface, or
    // the accessibility bus publishes nothing, so the service fails typed
    // instead of guessing an environment variable.
    StopBusThread(linux);
    delete linux;
    state.platform = nullptr;
    return KWEB_PREFERENCES_STATUS_PLATFORM_UNAVAILABLE;
  }
  g_state = &state;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeReadFacts(State &state, Facts *facts) {
  auto *linux = static_cast<LinuxState *>(state.platform);
  if (linux == nullptr || facts == nullptr) return KWEB_PREFERENCES_STATUS_PLATFORM_UNAVAILABLE;
  kweb_preferences_status status = KWEB_PREFERENCES_STATUS_NATIVE_FAILED;
  RunOnBusThread(*linux, [&] {
    Facts result;
    ReadFacts(*linux, state, &result);
    *facts = result;
    status = KWEB_PREFERENCES_STATUS_OK;
  });
  return status;
}

kweb_preferences_status NativeRequestAppearance(State &state, uint32_t source, uint32_t *effective) {
  (void)state;
  if (effective == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  if (source != KWEB_APPEARANCE_SYSTEM) {
    // The portal publishes appearance preferences but has no override request.
    return KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED;
  }
  *effective = KWEB_APPEARANCE_SYSTEM;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeClose(State &state) {
  auto *linux = static_cast<LinuxState *>(state.platform);
  if (linux == nullptr) return KWEB_PREFERENCES_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  {
    std::lock_guard<std::mutex> lock(linux->mutex);
    linux->stopping = true;
  }
  StopBusThread(linux);
  delete linux;
  state.platform = nullptr;
  return KWEB_PREFERENCES_STATUS_OK;
}

}  // namespace kwebshell::preferences
