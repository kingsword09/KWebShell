#include "preferences_platform_test.h"

#if defined(__linux__)

#include <gio/gio.h>

#include "preferences_tests_fixture.h"

#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <thread>
#include <tuple>

namespace {

constexpr char kPortalName[] = "org.freedesktop.portal.Desktop";
constexpr char kPortalPath[] = "/org/freedesktop/portal/desktop";
constexpr char kPortalSettings[] = "org.freedesktop.portal.Settings";
constexpr char kAppearanceNamespace[] = "org.freedesktop.appearance";
constexpr char kA11yBusName[] = "org.a11y.Bus";
constexpr char kA11yBusPath[] = "/org/a11y/bus";
constexpr char kA11yStatusName[] = "org.a11y.Status";
constexpr char kA11yStatusPath[] = "/org/a11y/status";

constexpr char kPortalXml[] =
    "<node>"
    "<interface name='org.freedesktop.portal.Settings'>"
    "<method name='ReadOne'>"
    "<arg type='s' name='namespace' direction='in'/>"
    "<arg type='s' name='key' direction='in'/>"
    "<arg type='v' name='value' direction='out'/>"
    "</method>"
    "<method name='ReadAll'>"
    "<arg type='as' name='namespaces' direction='in'/>"
    "<arg type='a{sa{sv}}' name='value' direction='out'/>"
    "</method>"
    "<signal name='SettingChanged'>"
    "<arg type='s' name='namespace'/>"
    "<arg type='s' name='key'/>"
    "<arg type='v' name='value'/>"
    "</signal>"
    "</interface>"
    "</node>";

constexpr char kA11yBusXml[] =
    "<node>"
    "<interface name='org.a11y.Bus'>"
    "<method name='GetAddress'><arg type='s' name='address' direction='out'/></method>"
    "</interface>"
    "</node>";

constexpr char kA11yStatusXml[] =
    "<node>"
    "<interface name='org.a11y.Status'>"
    "<property name='IsEnabled' type='b' access='read'/>"
    "<property name='ScreenReaderEnabled' type='b' access='read'/>"
    "</interface>"
    "</node>";

/**
 * One private session-bus double that owns the settings portal, the
 * accessibility bus address, and the declared accessibility status. It answers
 * the provider's reads and emits the real protocol signals.
 */
class PreferencesDouble {
 public:
  PreferencesDouble() {
    thread_ = std::thread([this] {
      GMainContext *context = g_main_context_new();
      g_main_context_push_thread_default(context);
      GMainLoop *loop = g_main_loop_new(context, FALSE);
      connection_ = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
      if (connection_ != nullptr) {
        RegisterPortal();
        RegisterA11y();
      }
      {
        std::lock_guard<std::mutex> lock(mutex_);
        loop_ = loop;
        ready_ = connection_ != nullptr;
      }
      ready_condition_.notify_all();
      if (connection_ != nullptr) g_main_loop_run(loop);
      g_main_loop_unref(loop);
      g_main_context_pop_thread_default(context);
      g_main_context_unref(context);
    });
    std::unique_lock<std::mutex> lock(mutex_);
    ready_condition_.wait(lock, [this] { return ready_; });
    lock.unlock();
    WaitForNames();
  }

  ~PreferencesDouble() { Stop(); }

  void Stop() {
    GMainLoop *loop = nullptr;
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (stopping_) return;
      stopping_ = true;
      loop = loop_;
    }
    if (loop != nullptr) {
      g_main_context_invoke_full(
          g_main_loop_get_context(loop), G_PRIORITY_DEFAULT,
          [](gpointer user_data) -> gboolean {
            g_main_loop_quit(static_cast<GMainLoop *>(user_data));
            return G_SOURCE_REMOVE;
          },
          loop, nullptr);
    }
    if (thread_.joinable()) thread_.join();
    connection_ = nullptr;
  }

  bool available() const { return connection_ != nullptr; }

  void SetUint32(const char *key, guint32 value) {
    std::lock_guard<std::mutex> lock(mutex_);
    uint32_[key] = value;
  }

  void SetAccent(double red, double green, double blue) {
    std::lock_guard<std::mutex> lock(mutex_);
    accent_ = std::make_tuple(red, green, blue);
    accent_set_ = true;
  }

  void SetScreenReader(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    screen_reader_ = enabled;
  }

  /** Emits the portal change signal for one key. */
  bool EmitSettingChanged(const char *key) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (connection_ == nullptr || uint32_.count(key) == 0) return false;
    GError *error = nullptr;
    GVariant *parameters =
        g_variant_new("(ssv)", kAppearanceNamespace, key, g_variant_new_uint32(uint32_[key]));
    g_dbus_connection_emit_signal(connection_, nullptr, kPortalPath, kPortalSettings, "SettingChanged",
                                  parameters, &error);
    if (error != nullptr) {
      g_error_free(error);
      return false;
    }
    return true;
  }

  /** Emits the portal change signal for the accent color. */
  bool EmitAccentChanged() {
    {
      std::lock_guard<std::mutex> lock(mutex_);
      if (connection_ == nullptr) return false;
    }
    GError *error = nullptr;
    GVariant *parameters = g_variant_new("(ssv)", kAppearanceNamespace, "accent-color",
                                         g_variant_new("(ddd)", std::get<0>(accent_),
                                                       std::get<1>(accent_), std::get<2>(accent_)));
    g_dbus_connection_emit_signal(connection_, nullptr, kPortalPath, kPortalSettings, "SettingChanged",
                                  parameters, &error);
    if (error != nullptr) {
      g_error_free(error);
      return false;
    }
    return true;
  }

  /** Emits the accessibility property change signal. */
  bool EmitScreenReaderChanged() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (connection_ == nullptr) return false;
    GVariantBuilder changed;
    g_variant_builder_init(&changed, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&changed, "{sv}", "ScreenReaderEnabled",
                          g_variant_new_boolean(screen_reader_ ? TRUE : FALSE));
    GError *error = nullptr;
    g_dbus_connection_emit_signal(
        connection_, nullptr, kA11yStatusPath, "org.freedesktop.DBus.Properties", "PropertiesChanged",
        g_variant_new("(s@a{sv}@as)", kA11yStatusName, g_variant_builder_end(&changed),
                      g_variant_new_strv(nullptr, 0)),
        &error);
    if (error != nullptr) {
      g_error_free(error);
      return false;
    }
    return true;
  }

 private:
  static void OnNameAcquired(GDBusConnection *, const gchar *name, gpointer user_data) {
    auto *self = static_cast<PreferencesDouble *>(user_data);
    (void)name;
    {
      std::lock_guard<std::mutex> lock(self->mutex_);
      self->names_owned_ += 1;
    }
    self->names_condition_.notify_all();
  }

  static void OnNameLost(GDBusConnection *, const gchar *name, gpointer user_data) {
    (void)user_data;
    std::printf("The preferences double lost the name %s.\n", name);
  }

  /** Waits until every name of the double is owned, so the provider never races it. */
  bool WaitForNames() {
    std::unique_lock<std::mutex> lock(mutex_);
    return names_condition_.wait_for(lock, std::chrono::seconds(5),
                                     [this] { return names_owned_ >= kExpectedNames; });
  }

  void RegisterPortal() {
    GError *error = nullptr;
    GDBusNodeInfo *info = g_dbus_node_info_new_for_xml(kPortalXml, &error);
    if (error != nullptr) g_error_free(error);
    if (info == nullptr) return;
    portal_registration_ = g_dbus_connection_register_object(connection_, kPortalPath, info->interfaces[0],
                                                             &kPortalVTable, this, nullptr, nullptr);
    portal_name_ = g_bus_own_name_on_connection(connection_, kPortalName, kOwnerFlags,
                                                OnNameAcquired, OnNameLost, this, nullptr);
    g_dbus_node_info_unref(info);
  }

  void RegisterA11y() {
    GError *error = nullptr;
    GDBusNodeInfo *bus_info = g_dbus_node_info_new_for_xml(kA11yBusXml, &error);
    if (error != nullptr) g_error_free(error);
    if (bus_info != nullptr) {
      a11y_bus_registration_ = g_dbus_connection_register_object(connection_, kA11yBusPath,
                                                                 bus_info->interfaces[0], &kA11yBusVTable,
                                                                 this, nullptr, nullptr);
      g_dbus_node_info_unref(bus_info);
    }
    GDBusNodeInfo *status_info = g_dbus_node_info_new_for_xml(kA11yStatusXml, &error);
    if (error != nullptr) g_error_free(error);
    if (status_info != nullptr) {
      a11y_status_registration_ = g_dbus_connection_register_object(
          connection_, kA11yStatusPath, status_info->interfaces[0], &kA11yStatusVTable, this, nullptr,
          nullptr);
      g_dbus_node_info_unref(status_info);
    }
    a11y_name_ = g_bus_own_name_on_connection(connection_, kA11yBusName, kOwnerFlags,
                                              OnNameAcquired, OnNameLost, this, nullptr);
    // The accessibility status is a name of its own on the accessibility bus.
    a11y_status_name_ = g_bus_own_name_on_connection(connection_, kA11yStatusName, kOwnerFlags,
                                                     OnNameAcquired, nullptr,
                                                     this, nullptr);
  }

  static void HandlePortalCall(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                               const gchar *method_name, GVariant *parameters,
                               GDBusMethodInvocation *invocation, gpointer user_data) {
    auto *self = static_cast<PreferencesDouble *>(user_data);
    if (std::strcmp(method_name, "ReadAll") == 0) {
      GVariantBuilder namespaces;
      g_variant_builder_init(&namespaces, G_VARIANT_TYPE("a{sa{sv}}"));
      g_dbus_method_invocation_return_value(
          invocation, g_variant_new("(@a{sa{sv}})", g_variant_builder_end(&namespaces)));
      return;
    }
    if (std::strcmp(method_name, "ReadOne") != 0) {
      g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                            "The portal double implements ReadOne.");
      return;
    }
    const gchar *name = nullptr;
    const gchar *key = nullptr;
    g_variant_get(parameters, "(&s&s)", &name, &key);
    std::lock_guard<std::mutex> lock(self->mutex_);
    GVariant *value = nullptr;
    if (g_strcmp0(name, kAppearanceNamespace) == 0) {
      if (std::strcmp(key, "accent-color") == 0 && self->accent_set_) {
        value = g_variant_new("(ddd)", std::get<0>(self->accent_), std::get<1>(self->accent_),
                              std::get<2>(self->accent_));
      } else if (self->uint32_.count(key) != 0) {
        value = g_variant_new_uint32(self->uint32_[key]);
      }
    }
    if (value == nullptr) {
      // The portal double reports an unpublished key the way a portal backend does.
      g_dbus_method_invocation_return_dbus_error(invocation, "org.freedesktop.portal.Error.NotFound",
                                                 "The portal double publishes no such key.");
      return;
    }
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(v)", value));
  }

  static void HandleA11yBusCall(GDBusConnection *, const gchar *, const gchar *, const gchar *,
                                const gchar *, GVariant *, GDBusMethodInvocation *invocation,
                                gpointer user_data) {
    (void)user_data;
    GError *error = nullptr;
    gchar *address = g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SESSION, nullptr, &error);
    if (error != nullptr) g_error_free(error);
    g_dbus_method_invocation_return_value(invocation,
                                          g_variant_new("(s)", address != nullptr ? address : ""));
    g_free(address);
  }

  static GVariant *HandleA11yStatusProperty(GDBusConnection *, const gchar *, const gchar *,
                                            const gchar *interface_name, const gchar *property_name,
                                            GError **, gpointer user_data) {
    auto *self = static_cast<PreferencesDouble *>(user_data);
    if (std::strcmp(interface_name, kA11yStatusName) != 0) return nullptr;
    std::lock_guard<std::mutex> lock(self->mutex_);
    if (std::strcmp(property_name, "IsEnabled") == 0 ||
        std::strcmp(property_name, "ScreenReaderEnabled") == 0) {
      return g_variant_new_boolean(self->screen_reader_ ? TRUE : FALSE);
    }
    return nullptr;
  }

  static const GDBusInterfaceVTable kPortalVTable;
  static const GDBusInterfaceVTable kA11yBusVTable;
  static const GDBusInterfaceVTable kA11yStatusVTable;

  std::thread thread_;
  std::mutex mutex_;
  std::condition_variable ready_condition_;
  GMainLoop *loop_ = nullptr;
  GDBusConnection *connection_ = nullptr;
  guint portal_registration_ = 0;
  guint portal_name_ = 0;
  guint a11y_bus_registration_ = 0;
  guint a11y_name_ = 0;
  guint a11y_status_registration_ = 0;
  guint a11y_status_name_ = 0;
  static constexpr int kExpectedNames = 3;
  /** The test session bus is private, so taking over a placeholder name is safe. */
  static constexpr GBusNameOwnerFlags kOwnerFlags = G_BUS_NAME_OWNER_FLAGS_REPLACE;
  bool ready_ = false;
  bool stopping_ = false;
  int names_owned_ = 0;
  std::condition_variable names_condition_;
  std::map<std::string, guint32> uint32_;
  std::tuple<double, double, double> accent_{0.0, 0.0, 0.0};
  bool accent_set_ = false;
  bool screen_reader_ = false;
};

const GDBusInterfaceVTable PreferencesDouble::kPortalVTable = {HandlePortalCall, nullptr, nullptr, {nullptr}};
const GDBusInterfaceVTable PreferencesDouble::kA11yBusVTable = {HandleA11yBusCall, nullptr, nullptr, {nullptr}};
const GDBusInterfaceVTable PreferencesDouble::kA11yStatusVTable = {nullptr, HandleA11yStatusProperty, nullptr,
                                                                    {nullptr}};

}  // namespace

void PreferencesFixture::pump_platform_queue() {}

/**
 * The test session bus is private (`dbus-run-session`), so the double takes the
 * names it needs from any placeholder service that already answered them.
 */
void PreparePlatformEnvironment() {
  const gchar *address = g_getenv("DBUS_SESSION_BUS_ADDRESS");
  if (address == nullptr || address[0] == '\0') {
    std::printf("SKIP: the preferences structure test needs a private session bus.\n");
  }
}

void ReleasePlatformEnvironment() {}

/** The real portal facts, the absent-fact boundaries, and the ordered change stream. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  PreferencesFixture fixture;
  PreferencesDouble double_bus;
  if (!double_bus.available()) {
    std::printf("SKIP: no session bus for the preferences structure test.\n");
    return g_failures - failures_before;
  }
  double_bus.SetUint32("color-scheme", 1u);      // prefer dark
  double_bus.SetUint32("contrast", 1u);          // higher contrast
  double_bus.SetUint32("reduced-motion", 1u);    // reduced motion
  double_bus.SetAccent(0.2, 0.4, 0.8);
  double_bus.SetScreenReader(true);

  // 1. Without the declared key the assistive-technology fact stays unpublished.
  uint64_t handle = 0;
  kweb_preferences_configuration without_key = fixture.Configuration(0);
  const kweb_preferences_status opened = kweb_preferences_open(&without_key, &handle);
  if (opened != KWEB_PREFERENCES_STATUS_OK) {
    std::printf("SKIP: the settings-portal preferences host is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }
  const kweb_preferences_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "preferences.linux.settings-portal") == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_CONTRAST) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_MOTION) != 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0);
  // The portal publishes no reduced-transparency, differentiate-without-color,
  // or invert-colors fact.
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_INVERT_COLORS) == 0);
  KWEB_CHECK((capabilities.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) == 0);
  const kweb_preferences_snapshot_result initial = fixture.Snapshot(handle);
  KWEB_CHECK(initial.color_scheme == KWEB_COLOR_SCHEME_DARK);
  KWEB_CHECK(initial.contrast == KWEB_CONTRAST_MORE);
  KWEB_CHECK(initial.reduced_motion == KWEB_PREFERENCE_TRUE);
  KWEB_CHECK(initial.accent_source == KWEB_ACCENT_SOURCE_DESKTOP_PORTAL);
  KWEB_CHECK(initial.accent_red == 51u && initial.accent_green == 102u && initial.accent_blue == 204u);
  KWEB_CHECK(initial.screen_reader == KWEB_PREFERENCE_UNKNOWN);
  // The declared desktop interface is capability-driven: where the schema is
  // installed the session publishes a text scale, otherwise the fact is absent.
  if ((initial.fact_bits & KWEB_PREFERENCE_FACT_TEXT_SCALE) == 0) KWEB_CHECK(initial.text_scale_percent == 0u);

  // 2. One portal change publishes exactly one ordered update.
  double_bus.SetUint32("color-scheme", 2u);
  KWEB_CHECK(double_bus.EmitSettingChanged("color-scheme"));
  kweb_preferences_event changed{};
  changed.struct_size = sizeof(changed);
  changed.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(fixture.AwaitEvent(handle, &changed));
  KWEB_CHECK(changed.kind == KWEB_PREFERENCES_EVENT_CHANGED);
  KWEB_CHECK((changed.changed_fact_bits & KWEB_PREFERENCE_FACT_COLOR_SCHEME) != 0);
  KWEB_CHECK(changed.sequence == initial.sequence + 1u);
  KWEB_CHECK(fixture.Snapshot(handle).color_scheme == KWEB_COLOR_SCHEME_LIGHT);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  // 3. A published fact that stops being published reports the structural change.
  double_bus.SetAccent(1.5, 0.0, 0.0);
  KWEB_CHECK(double_bus.EmitAccentChanged());
  bool observed_accent = false;
  for (int attempt = 0; attempt < 50 && !observed_accent; ++attempt) {
    kweb_preferences_event next{};
    next.struct_size = sizeof(next);
    next.abi_version = KWEB_PREFERENCES_ABI_VERSION;
    if (fixture.AwaitEvent(handle, &next, 100) &&
        (next.changed_fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) != 0) {
      observed_accent = true;
    }
  }
  KWEB_CHECK(observed_accent);
  const kweb_preferences_snapshot_result without_accent = fixture.Snapshot(handle);
  KWEB_CHECK((without_accent.fact_bits & KWEB_PREFERENCE_FACT_ACCENT_COLOR) == 0);
  KWEB_CHECK(without_accent.accent_red == 0u);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);

  // 4. The portal publishes appearance preferences but has no override request.
  kweb_preferences_configuration with_key = fixture.Configuration(KWEB_PREFERENCES_KEY_LINUX_A11Y);
  KWEB_CHECK(kweb_preferences_open(&with_key, &handle) == KWEB_PREFERENCES_STATUS_OK);
  const kweb_preferences_capabilities_result declared = fixture.Capabilities(handle);
  KWEB_CHECK(declared.sensitive_key_bits == KWEB_PREFERENCES_KEY_LINUX_A11Y);
  KWEB_CHECK((declared.published_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0);
  KWEB_CHECK(fixture.Snapshot(handle).screen_reader == KWEB_PREFERENCE_TRUE);
  kweb_preferences_appearance_result system{};
  system.struct_size = sizeof(system);
  system.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_SYSTEM, &system) ==
             KWEB_PREFERENCES_STATUS_OK);
  kweb_preferences_appearance_result dark{};
  dark.struct_size = sizeof(dark);
  dark.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_request_appearance(handle, KWEB_APPEARANCE_DARK, &dark) ==
             KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  // 5. The accessibility bus property change publishes exactly one update.
  double_bus.SetScreenReader(false);
  KWEB_CHECK(double_bus.EmitScreenReaderChanged());
  kweb_preferences_event accessibility{};
  accessibility.struct_size = sizeof(accessibility);
  accessibility.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(fixture.AwaitEvent(handle, &accessibility));
  KWEB_CHECK((accessibility.changed_fact_bits & KWEB_PREFERENCE_FACT_SCREEN_READER) != 0);
  KWEB_CHECK(fixture.Snapshot(handle).screen_reader == KWEB_PREFERENCE_FALSE);
  KWEB_CHECK(fixture.DrainEvents(handle).empty());

  // 6. Closing releases the observers and the bus thread.
  KWEB_CHECK(kweb_preferences_close(handle) == KWEB_PREFERENCES_STATUS_OK);
  kweb_preferences_event late{};
  late.struct_size = sizeof(late);
  late.abi_version = KWEB_PREFERENCES_ABI_VERSION;
  KWEB_CHECK(kweb_preferences_poll_event(handle, &late) == KWEB_PREFERENCES_STATUS_OWNER_CLOSED);
  return g_failures - failures_before;
}

#endif
