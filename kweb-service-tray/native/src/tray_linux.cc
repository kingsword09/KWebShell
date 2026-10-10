#include "tray_linux_internal.h"

#if !defined(__linux__)
#error "The Linux tray provider must only be compiled on Linux."
#endif

#include <unistd.h>

#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <functional>
#include <thread>

namespace {

const char kWatcherName[] = "org.kde.StatusNotifierWatcher";
const char kWatcherPath[] = "/StatusNotifierWatcher";
const char kWatcherInterface[] = "org.kde.StatusNotifierWatcher";
const char kItemInterface[] = "org.kde.StatusNotifierItem";
const char kMenuInterface[] = "com.canonical.dbusmenu";
const char kItemPath[] = "/StatusNotifierItem";
const char kMenuPath[] = "/MenuBar";
const char kPropertiesInterface[] = "org.freedesktop.DBus.Properties";

const char kItemXml[] =
    "<node>"
    "  <interface name='org.kde.StatusNotifierItem'>"
    "    <property name='Category' type='s' access='read'/>"
    "    <property name='Id' type='s' access='read'/>"
    "    <property name='Title' type='s' access='read'/>"
    "    <property name='Status' type='s' access='read'/>"
    "    <property name='IconName' type='s' access='read'/>"
    "    <property name='IconPixmap' type='a(iiay)' access='read'/>"
    "    <property name='Menu' type='o' access='read'/>"
    "    <property name='ItemIsMenu' type='b' access='read'/>"
    "    <property name='WindowId' type='i' access='read'/>"
    "    <property name='ToolTip' type='(sa(iiay)ss)' access='read'/>"
    "    <method name='Activate'>"
    "      <arg type='i' name='x' direction='in'/>"
    "      <arg type='i' name='y' direction='in'/>"
    "    </method>"
    "    <method name='SecondaryActivate'>"
    "      <arg type='i' name='x' direction='in'/>"
    "      <arg type='i' name='y' direction='in'/>"
    "    </method>"
    "    <method name='ContextMenu'>"
    "      <arg type='i' name='x' direction='in'/>"
    "      <arg type='i' name='y' direction='in'/>"
    "    </method>"
    "    <method name='Scroll'>"
    "      <arg type='i' name='delta' direction='in'/>"
    "      <arg type='s' name='orientation' direction='in'/>"
    "    </method>"
    "  </interface>"
    "</node>";

const char kMenuXml[] =
    "<node>"
    "  <interface name='com.canonical.dbusmenu'>"
    "    <property name='Version' type='u' access='read'/>"
    "    <property name='TextDirection' type='s' access='read'/>"
    "    <property name='Status' type='s' access='read'/>"
    "    <property name='IconThemePath' type='as' access='read'/>"
    "    <method name='GetLayout'>"
    "      <arg type='i' name='parentId' direction='in'/>"
    "      <arg type='i' name='recursionDepth' direction='in'/>"
    "      <arg type='as' name='propertyNames' direction='in'/>"
    "      <arg type='u' name='revision' direction='out'/>"
    "      <arg type='(ia{sv}av)' name='layout' direction='out'/>"
    "    </method>"
    "    <method name='GetGroupProperties'>"
    "      <arg type='ai' name='ids' direction='in'/>"
    "      <arg type='as' name='propertyNames' direction='in'/>"
    "      <arg type='a(ia{sv})' name='properties' direction='out'/>"
    "    </method>"
    "    <method name='Event'>"
    "      <arg type='i' name='id' direction='in'/>"
    "      <arg type='s' name='eventId' direction='in'/>"
    "      <arg type='v' name='data' direction='in'/>"
    "      <arg type='u' name='timestamp' direction='in'/>"
    "    </method>"
    "    <method name='AboutToShow'>"
    "      <arg type='i' name='id' direction='in'/>"
    "      <arg type='b' name='needUpdate' direction='out'/>"
    "    </method>"
    "    <method name='AboutToShowGroup'>"
    "      <arg type='ai' name='ids' direction='in'/>"
    "      <arg type='ai' name='updatesNeeded' direction='out'/>"
    "      <arg type='ai' name='idErrors' direction='out'/>"
    "    </method>"
    "  </interface>"
    "</node>";

const char kPropertiesXml[] =
    "<node>"
    "  <interface name='org.freedesktop.DBus.Properties'>"
    "    <method name='Get'>"
    "      <arg type='s' name='interface_name' direction='in'/>"
    "      <arg type='s' name='property_name' direction='in'/>"
    "      <arg type='v' name='value' direction='out'/>"
    "    </method>"
    "    <method name='GetAll'>"
    "      <arg type='s' name='interface_name' direction='in'/>"
    "      <arg type='a{sv}' name='properties' direction='out'/>"
    "    </method>"
    "    <method name='Set'>"
    "      <arg type='s' name='interface_name' direction='in'/>"
    "      <arg type='s' name='property_name' direction='in'/>"
    "      <arg type='v' name='value' direction='in'/>"
    "    </method>"
    "  </interface>"
    "</node>";

kwebshell::tray::State *g_state = nullptr;

/** Runs one block on the provider's bus thread and waits for it. */
bool RunOnBusThread(KWebTrayLinuxState &state, const std::function<void()> &block) {
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

GDBusNodeInfo *ParseNode(const char *xml) {
  GError *error = nullptr;
  GDBusNodeInfo *node = g_dbus_node_info_new_for_xml(xml, &error);
  if (error != nullptr) g_error_free(error);
  return node;
}

using kwebshell::tray::MenuNode;

/** Assigns the published preorder ids used by the dbusmenu layout. */
void AssignMenuIds(KWebTrayLinuxItem &item, size_t index, uint32_t *next_id) {
  const MenuNode &node = item.menu.items[index];
  const uint32_t id = (*next_id)++;
  item.index_by_item[id] = index;
  item.item_id_by_index[index] = id;
  if (node.kind != KWEB_TRAY_MENU_SEPARATOR) item.command_by_item[id] = node.id;
  for (const size_t child : node.children) AssignMenuIds(item, child, next_id);
}

GVariant *MenuNodeProperties(const MenuNode &node) {
  GVariantBuilder properties;
  g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
  if (node.kind == KWEB_TRAY_MENU_SEPARATOR) {
    g_variant_builder_add(&properties, "{sv}", "type", g_variant_new_string("separator"));
    g_variant_builder_add(&properties, "{sv}", "visible", g_variant_new_boolean(TRUE));
    return g_variant_builder_end(&properties);
  }
  g_variant_builder_add(&properties, "{sv}", "label", g_variant_new_string(node.label.c_str()));
  g_variant_builder_add(&properties, "{sv}", "enabled", g_variant_new_boolean(node.enabled ? TRUE : FALSE));
  g_variant_builder_add(&properties, "{sv}", "visible", g_variant_new_boolean(node.visible ? TRUE : FALSE));
  if (node.kind == KWEB_TRAY_MENU_SUBMENU) {
    g_variant_builder_add(&properties, "{sv}", "children-display", g_variant_new_string("submenu"));
  }
  if (node.kind == KWEB_TRAY_MENU_CHECKBOX || node.kind == KWEB_TRAY_MENU_RADIO) {
    g_variant_builder_add(&properties, "{sv}", "toggle-type",
                          g_variant_new_string(node.kind == KWEB_TRAY_MENU_RADIO ? "radio" : "checkmark"));
    g_variant_builder_add(&properties, "{sv}", "toggle-state",
                          g_variant_new_int32(node.checked ? 1 : 0));
  }
  return g_variant_builder_end(&properties);
}

GVariant *MenuLayout(KWebTrayLinuxItem &item, size_t index, int32_t depth) {
  const MenuNode &node = item.menu.items[index];
  GVariantBuilder layout;
  g_variant_builder_init(&layout, G_VARIANT_TYPE("(ia{sv}av)"));
  g_variant_builder_add(&layout, "i", static_cast<int32_t>(item.item_id_by_index[index]));
  g_variant_builder_add_value(&layout, MenuNodeProperties(node));
  GVariantBuilder children;
  g_variant_builder_init(&children, G_VARIANT_TYPE("av"));
  if (depth != 0) {
    for (const size_t child : node.children) {
      g_variant_builder_add_value(&children, g_variant_new_variant(MenuLayout(item, child, depth - 1)));
    }
  }
  g_variant_builder_add_value(&layout, g_variant_builder_end(&children));
  return g_variant_builder_end(&layout);
}

GVariant *MenuRootLayout(KWebTrayLinuxItem &item, int32_t depth) {
  GVariantBuilder layout;
  g_variant_builder_init(&layout, G_VARIANT_TYPE("(ia{sv}av)"));
  g_variant_builder_add(&layout, "i", 0);
  GVariantBuilder root_properties;
  g_variant_builder_init(&root_properties, G_VARIANT_TYPE("a{sv}"));
  g_variant_builder_add_value(&layout, g_variant_builder_end(&root_properties));
  GVariantBuilder children;
  g_variant_builder_init(&children, G_VARIANT_TYPE("av"));
  if (depth != 0) {
    for (const size_t root : item.menu.roots) {
      g_variant_builder_add_value(&children, g_variant_new_variant(MenuLayout(item, root, depth - 1)));
    }
  }
  g_variant_builder_add_value(&layout, g_variant_builder_end(&children));
  return g_variant_builder_end(&layout);
}

GVariant *ItemProperties(KWebTrayLinuxItem &item) {
  GVariantBuilder properties;
  g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
  g_variant_builder_add(&properties, "{sv}", "Category", g_variant_new_string("ApplicationStatus"));
  g_variant_builder_add(&properties, "{sv}", "Id", g_variant_new_string(item.item_id.c_str()));
  g_variant_builder_add(&properties, "{sv}", "Title",
                        g_variant_new_string(item.tooltip.empty() ? item.item_id.c_str() : item.tooltip.c_str()));
  g_variant_builder_add(&properties, "{sv}", "Status", g_variant_new_string("Active"));
  g_variant_builder_add(&properties, "{sv}", "IconName", g_variant_new_string(""));
  GVariantBuilder pixmaps;
  g_variant_builder_init(&pixmaps, G_VARIANT_TYPE("a(iiay)"));
  for (const kwebshell::tray::IconVariant &variant : item.variants) {
    const std::vector<uint8_t> argb = kwebshell::tray::linux_provider::ArgbaPixmap(variant);
    GVariant *bytes = g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, argb.data(), argb.size(), 1);
    g_variant_builder_add(&pixmaps, "(ii@ay)", static_cast<int32_t>(variant.width),
                          static_cast<int32_t>(variant.height), bytes);
  }
  g_variant_builder_add(&properties, "{sv}", "IconPixmap", g_variant_builder_end(&pixmaps));
  g_variant_builder_add(&properties, "{sv}", "Menu",
                        g_variant_new_object_path(item.has_menu ? kMenuPath : "/"));
  g_variant_builder_add(&properties, "{sv}", "ItemIsMenu", g_variant_new_boolean(FALSE));
  g_variant_builder_add(&properties, "{sv}", "WindowId", g_variant_new_int32(0));
  GVariantBuilder tooltip_pixmaps;
  g_variant_builder_init(&tooltip_pixmaps, G_VARIANT_TYPE("a(iiay)"));
  g_variant_builder_add(&properties, "{sv}", "ToolTip",
                        g_variant_new("(s@a(iiay)ss)", "", g_variant_builder_end(&tooltip_pixmaps),
                                      item.tooltip.c_str(), ""));
  return g_variant_builder_end(&properties);
}

void HandlePropertiesCall(KWebTrayLinuxItem &item, const gchar *interface_name, const gchar *method_name,
                          GVariant *parameters, GDBusMethodInvocation *invocation) {
  const bool menu_object = g_strcmp0(interface_name, kMenuInterface) == 0;
  if (g_strcmp0(method_name, "GetAll") == 0) {
    GVariantBuilder properties;
    g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
    if (menu_object) {
      g_variant_builder_add(&properties, "{sv}", "Version", g_variant_new_uint32(3));
      g_variant_builder_add(&properties, "{sv}", "TextDirection", g_variant_new_string("ltr"));
      g_variant_builder_add(&properties, "{sv}", "Status", g_variant_new_string("normal"));
      GVariantBuilder theme;
      g_variant_builder_init(&theme, G_VARIANT_TYPE("as"));
      g_variant_builder_add(&properties, "{sv}", "IconThemePath", g_variant_builder_end(&theme));
    } else {
      GVariant *item_properties = ItemProperties(item);
      GVariantIter iterator;
      g_variant_iter_init(&iterator, item_properties);
      GVariant *entry = nullptr;
      while ((entry = g_variant_iter_next_value(&iterator)) != nullptr) {
        const gchar *key = nullptr;
        GVariant *value = nullptr;
        g_variant_get(entry, "{sv}", &key, &value);
        g_variant_builder_add(&properties, "{sv}", key, value);
        g_variant_unref(entry);
      }
      g_variant_unref(item_properties);
    }
    g_dbus_method_invocation_return_value(invocation,
                                         g_variant_new("(@a{sv})", g_variant_builder_end(&properties)));
    return;
  }
  if (g_strcmp0(method_name, "Get") == 0) {
    const gchar *requested_interface = nullptr;
    const gchar *property_name = nullptr;
    g_variant_get(parameters, "(&s&s)", &requested_interface, &property_name);
    GVariant *properties = ItemProperties(item);
    GVariant *value = g_variant_lookup_value(properties, property_name, nullptr);
    if (value != nullptr && !menu_object) {
      g_dbus_method_invocation_return_value(invocation,
                                           g_variant_new("(@v)", g_variant_new_variant(value)));
      g_variant_unref(properties);
      return;
    }
    g_variant_unref(properties);
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS,
                                          "The requested tray property is not published.");
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_PROPERTY_READ_ONLY,
                                        "The tray properties are read-only.");
}

void HandleItemCall(KWebTrayLinuxItem &item, const gchar *method_name, GDBusMethodInvocation *invocation) {
  kwebshell::tray::State *abi_state = kwebshell::tray::linux_provider::CurrentState();
  if (g_strcmp0(method_name, "Activate") == 0) {
    if (abi_state != nullptr && (item.activation_bits & KWEB_TRAY_ACTIVATION_PRIMARY) != 0) {
      kwebshell::tray::PushActivated(*abi_state, item.item_id, KWEB_TRAY_ACTIVATION_PRIMARY);
    }
    g_dbus_method_invocation_return_value(invocation, nullptr);
    return;
  }
  if (g_strcmp0(method_name, "SecondaryActivate") == 0 || g_strcmp0(method_name, "ContextMenu") == 0) {
    if (abi_state != nullptr && (item.activation_bits & KWEB_TRAY_ACTIVATION_SECONDARY) != 0) {
      kwebshell::tray::PushActivated(*abi_state, item.item_id, KWEB_TRAY_ACTIVATION_SECONDARY);
    }
    g_dbus_method_invocation_return_value(invocation, nullptr);
    return;
  }
  if (g_strcmp0(method_name, "Scroll") == 0) {
    // Scroll is accepted and ignored: it is not part of the declared activation
    // set in v1.
    g_dbus_method_invocation_return_value(invocation, nullptr);
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                        "The tray item does not implement this call.");
}

void HandleMenuCall(KWebTrayLinuxItem &item, const gchar *method_name, GVariant *parameters,
                    GDBusMethodInvocation *invocation) {
  if (g_strcmp0(method_name, "GetLayout") == 0) {
    gint32 parent_id = 0;
    gint32 recursion_depth = -1;
    g_autoptr(GVariant) names = nullptr;
    g_variant_get(parameters, "(ii@as)", &parent_id, &recursion_depth, &names);
    const int32_t depth = recursion_depth < 0 ? 32 : recursion_depth;
    GVariant *layout = nullptr;
    if (parent_id == 0) {
      layout = MenuRootLayout(item, depth);
    } else {
      const auto found = item.index_by_item.find(static_cast<uint32_t>(parent_id));
      if (found == item.index_by_item.end()) {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS,
                                              "The requested menu item is not published.");
        return;
      }
      layout = MenuLayout(item, found->second, depth);
    }
    g_dbus_method_invocation_return_value(invocation,
                                         g_variant_new("(u@(ia{sv}av))", 1u, layout));
    return;
  }
  if (g_strcmp0(method_name, "GetGroupProperties") == 0) {
    g_autoptr(GVariant) ids = nullptr;
    g_autoptr(GVariant) names = nullptr;
    g_variant_get(parameters, "(@ai@as)", &ids, &names);
    GVariantBuilder properties;
    g_variant_builder_init(&properties, G_VARIANT_TYPE("a(ia{sv})"));
    const gsize count = g_variant_n_children(ids);
    for (gsize index = 0; index < count; ++index) {
      gint32 id = 0;
      g_autoptr(GVariant) value = g_variant_get_child_value(ids, index);
      g_variant_get(value, "i", &id);
      const auto found = item.index_by_item.find(static_cast<uint32_t>(id));
      if (found == item.index_by_item.end()) continue;
      g_variant_builder_add(&properties, "(i@a{sv})", id, MenuNodeProperties(item.menu.items[found->second]));
    }
    g_dbus_method_invocation_return_value(invocation,
                                         g_variant_new("(@a(ia{sv}))", g_variant_builder_end(&properties)));
    return;
  }
  if (g_strcmp0(method_name, "Event") == 0) {
    gint32 id = 0;
    const gchar *event_id = nullptr;
    g_autoptr(GVariant) data = nullptr;
    guint32 timestamp = 0;
    g_variant_get(parameters, "(i&s@vu)", &id, &event_id, &data, &timestamp);
    kwebshell::tray::State *abi_state = kwebshell::tray::linux_provider::CurrentState();
    if (g_strcmp0(event_id, "clicked") == 0 && abi_state != nullptr) {
      const auto command = item.command_by_item.find(static_cast<uint32_t>(id));
      if (command != item.command_by_item.end()) {
        kwebshell::tray::PushMenuCommand(*abi_state, item.item_id, item.menu.menu_id, command->second,
                                         item.menu.version);
      }
    }
    g_dbus_method_invocation_return_value(invocation, nullptr);
    return;
  }
  if (g_strcmp0(method_name, "AboutToShow") == 0) {
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(b)", FALSE));
    return;
  }
  if (g_strcmp0(method_name, "AboutToShowGroup") == 0) {
    GVariantBuilder empty;
    g_variant_builder_init(&empty, G_VARIANT_TYPE("ai"));
    GVariant *updates = g_variant_builder_end(&empty);
    GVariantBuilder errors;
    g_variant_builder_init(&errors, G_VARIANT_TYPE("ai"));
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(@ai@ai)", updates, g_variant_builder_end(&errors)));
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                        "The tray menu does not implement this call.");
}

void HandleCall(GDBusConnection *, const gchar *sender, const gchar *object_path,
                const gchar *interface_name, const gchar *method_name, GVariant *parameters,
                GDBusMethodInvocation *invocation, gpointer user_data) {
  (void)sender;
  auto *item = static_cast<KWebTrayLinuxItem *>(user_data);
  if (item == nullptr) {
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_OBJECT,
                                          "The tray item is not published.");
    return;
  }
  if (g_strcmp0(interface_name, kPropertiesInterface) == 0) {
    HandlePropertiesCall(*item, g_strcmp0(object_path, kMenuPath) == 0 ? kMenuInterface : kItemInterface,
                        method_name, parameters, invocation);
    return;
  }
  if (g_strcmp0(interface_name, kMenuInterface) == 0) {
    HandleMenuCall(*item, method_name, parameters, invocation);
    return;
  }
  if (g_strcmp0(interface_name, kItemInterface) == 0) {
    HandleItemCall(*item, method_name, invocation);
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_INTERFACE,
                                        "The tray item does not implement this interface.");
}

const GDBusInterfaceVTable kCallVTable = {HandleCall, nullptr, nullptr, {nullptr}};

bool WatcherPresent(KWebTrayLinuxState &state) {
  if (state.control == nullptr) return false;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      state.control, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "NameHasOwner",
      g_variant_new("(s)", kWatcherName), G_VARIANT_TYPE("(b)"), G_DBUS_CALL_FLAGS_NONE, 2000, nullptr,
      &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  gboolean owned = FALSE;
  g_variant_get(reply, "(b)", &owned);
  g_variant_unref(reply);
  return owned == TRUE;
}

bool RegisterWithWatcher(KWebTrayLinuxState &state, KWebTrayLinuxItem &item) {
  if (state.control == nullptr) return false;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      state.control, kWatcherName, kWatcherPath, kWatcherInterface, "RegisterStatusNotifierItem",
      g_variant_new("(s)", item.bus_name.c_str()), nullptr, G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    item.registered_with_watcher = false;
    return false;
  }
  g_variant_unref(reply);
  item.registered_with_watcher = true;
  return true;
}

void OnWatcherNameOwnerChanged(GDBusConnection *, const gchar *sender_name, const gchar *, const gchar *,
                               const gchar *, GVariant *parameters, gpointer user_data) {
  (void)sender_name;
  auto *state = static_cast<KWebTrayLinuxState *>(user_data);
  const gchar *name = nullptr;
  const gchar *old_owner = nullptr;
  const gchar *new_owner = nullptr;
  g_variant_get(parameters, "(&s&s&s)", &name, &old_owner, &new_owner);
  if (g_strcmp0(name, kWatcherName) != 0) return;
  kwebshell::tray::State *abi_state = kwebshell::tray::linux_provider::CurrentState();
  const bool connected = new_owner != nullptr && new_owner[0] != '\0';
  if (!connected) {
    state->watcher_present = false;
    for (const auto &pair : state->items) {
      pair.second->registered_with_watcher = false;
      if (abi_state != nullptr) kwebshell::tray::PushFailed(*abi_state, pair.first, "tray.host-lost");
    }
    return;
  }
  state->watcher_present = true;
  for (auto &pair : state->items) {
    if (!pair.second->registered_with_watcher) RegisterWithWatcher(*state, *pair.second);
  }
}

/** Creates one item connection, owns its bus name, and exports both objects. */
kweb_tray_status CreateItemConnection(KWebTrayLinuxState &state, KWebTrayLinuxItem &item) {
  (void)state;
  GError *error = nullptr;
  g_autofree gchar *address = g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SESSION, nullptr, &error);
  if (address == nullptr) {
    if (error != nullptr) g_error_free(error);
    return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  }
  item.connection = g_dbus_connection_new_for_address_sync(
      address,
      static_cast<GDBusConnectionFlags>(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT |
                                       G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION),
      nullptr, nullptr, &error);
  if (item.connection == nullptr) {
    if (error != nullptr) g_error_free(error);
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  item.bus_owner = g_bus_own_name_on_connection(item.connection, item.bus_name.c_str(),
                                               G_BUS_NAME_OWNER_FLAGS_NONE, nullptr, nullptr, nullptr,
                                               nullptr);
  GDBusNodeInfo *item_node = ParseNode(kItemXml);
  GDBusNodeInfo *menu_node = ParseNode(kMenuXml);
  GDBusNodeInfo *properties_node = ParseNode(kPropertiesXml);
  if (item_node == nullptr || menu_node == nullptr || properties_node == nullptr) {
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  item.item_registration = g_dbus_connection_register_object(
      item.connection, kItemPath, item_node->interfaces[0], &kCallVTable, &item, nullptr, &error);
  if (item.item_registration == 0) {
    if (error != nullptr) g_error_free(error);
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  item.menu_registration = g_dbus_connection_register_object(
      item.connection, kMenuPath, menu_node->interfaces[0], &kCallVTable, &item, nullptr, &error);
  if (item.menu_registration == 0) {
    if (error != nullptr) g_error_free(error);
    return KWEB_TRAY_STATUS_NATIVE_FAILED;
  }
  (void)properties_node;
  return KWEB_TRAY_STATUS_OK;
}

void EmitItemChanged(KWebTrayLinuxItem &item) {
  if (item.connection == nullptr) return;
  GError *error = nullptr;
  GVariant *changed = g_variant_new("(s@a{sv})", kItemInterface, ItemProperties(item));
  g_dbus_connection_emit_signal(item.connection, nullptr, kItemPath, kPropertiesInterface,
                                "PropertiesChanged", changed, &error);
  if (error != nullptr) g_error_free(error);
}

kweb_tray_status ReleaseItem(KWebTrayLinuxState &state, KWebTrayLinuxItem &item) {
  if (item.connection != nullptr) {
    if (item.menu_registration != 0) {
      g_dbus_connection_unregister_object(item.connection, item.menu_registration);
      item.menu_registration = 0;
    }
    if (item.item_registration != 0) {
      g_dbus_connection_unregister_object(item.connection, item.item_registration);
      item.item_registration = 0;
    }
  }
  if (item.bus_owner != 0) {
    g_bus_unown_name(item.bus_owner);
    item.bus_owner = 0;
  }
  if (item.connection != nullptr) {
    g_object_unref(item.connection);
    item.connection = nullptr;
  }
  (void)state;
  return KWEB_TRAY_STATUS_OK;
}

}  // namespace

namespace kwebshell::tray::linux_provider {

State *CurrentState() {
  return g_state;
}

std::vector<uint8_t> ArgbaPixmap(const IconVariant &variant) {
  std::vector<uint8_t> argb(variant.pixels.size());
  for (size_t pixel = 0; pixel < variant.pixels.size(); pixel += 4) {
    // The status-notifier protocol publishes ARGB32 in network byte order.
    argb[pixel + 0] = variant.pixels[pixel + 3];
    argb[pixel + 1] = variant.pixels[pixel + 0];
    argb[pixel + 2] = variant.pixels[pixel + 1];
    argb[pixel + 3] = variant.pixels[pixel + 2];
  }
  return argb;
}

}  // namespace kwebshell::tray::linux_provider

namespace kwebshell::tray {

const char *ProviderId() {
  return "tray.linux.status-notifier";
}

kweb_tray_status NativeOpen(State &state) {
  auto linux_state = std::make_unique<KWebTrayLinuxState>();
  std::unique_lock<std::mutex> lock(linux_state->mutex);
  linux_state->thread = std::thread([linux_state = linux_state.get()] {
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GMainLoop *loop = g_main_loop_new(context, FALSE);
    GDBusConnection *control = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, nullptr);
    if (control != nullptr) {
      linux_state->watcher_subscription = g_dbus_connection_signal_subscribe(
          control, "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
          "/org/freedesktop/DBus", kWatcherName, G_DBUS_SIGNAL_FLAGS_NONE, OnWatcherNameOwnerChanged,
          linux_state, nullptr);
    }
    {
      std::lock_guard<std::mutex> guard(linux_state->mutex);
      linux_state->context = context;
      linux_state->loop = loop;
      linux_state->control = control;
      linux_state->started = true;
    }
    linux_state->ready.notify_all();
    if (control != nullptr) g_main_loop_run(loop);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
  });
  linux_state->ready.wait(lock, [linux_state = linux_state.get()] { return linux_state->started; });
  if (linux_state->control == nullptr) {
    lock.unlock();
    if (linux_state->thread.joinable()) linux_state->thread.join();
    return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  }
  lock.unlock();
  state.platform = linux_state.release();
  g_state = &state;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeCapabilities(State &state, kweb_tray_capabilities_result *result) {
  auto *linux_state = static_cast<KWebTrayLinuxState *>(state.platform);
  result->flags = KWEB_TRAY_CAP_MENU | KWEB_TRAY_CAP_TOOLTIP | KWEB_TRAY_CAP_ICON_VARIANTS;
  result->activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY | KWEB_TRAY_ACTIVATION_SECONDARY;
  CopyBounded(result->provider_id, sizeof(result->provider_id), ProviderId());
  if (linux_state == nullptr) return KWEB_TRAY_STATUS_OK;
  bool present = false;
  RunOnBusThread(*linux_state, [&] { present = WatcherPresent(*linux_state); });
  linux_state->watcher_present = present;
  if (present) {
    result->flags |= KWEB_TRAY_CAP_WATCHER_RECONNECT;
    for (auto &pair : linux_state->items) {
      if (!pair.second->registered_with_watcher) RegisterWithWatcher(*linux_state, *pair.second);
    }
  }
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeSetItem(State &state, const Item &item, bool) {
  auto *linux_state = static_cast<KWebTrayLinuxState *>(state.platform);
  if (linux_state == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  kweb_tray_status status = KWEB_TRAY_STATUS_NATIVE_FAILED;
  RunOnBusThread(*linux_state, [&] {
    if (!WatcherPresent(*linux_state)) {
      status = KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
      return;
    }
    const auto existing = linux_state->items.find(item.id);
    if (existing != linux_state->items.end()) {
      KWebTrayLinuxItem &live = *existing->second;
      live.activation_bits = item.activation_bits;
      live.variants = item.variants;
      live.tooltip = item.tooltip;
      EmitItemChanged(live);
      status = KWEB_TRAY_STATUS_OK;
      return;
    }
    auto entry = std::make_shared<KWebTrayLinuxItem>();
    entry->item_id = item.id;
    entry->activation_bits = item.activation_bits;
    entry->variants = item.variants;
    entry->tooltip = item.tooltip;
    std::string suffix = item.id;
    for (char &character : suffix) {
      const bool accepted = (character >= 'a' && character <= 'z') ||
                            (character >= 'A' && character <= 'Z') ||
                            (character >= '0' && character <= '9') || character == '_' ||
                            character == '.' || character == '-';
      if (!accepted) character = '_';
    }
    entry->bus_name = "org.kde.StatusNotifierItem-" + std::to_string(::getpid()) + "-" + suffix;
    status = CreateItemConnection(*linux_state, *entry);
    if (status != KWEB_TRAY_STATUS_OK) {
      ReleaseItem(*linux_state, *entry);
      return;
    }
    if (!RegisterWithWatcher(*linux_state, *entry)) {
      ReleaseItem(*linux_state, *entry);
      status = KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
      return;
    }
    linux_state->items[item.id] = entry;
    status = KWEB_TRAY_STATUS_OK;
  });
  return status;
}

kweb_tray_status NativeCloseItem(State &state, const std::string &item_id) {
  auto *linux_state = static_cast<KWebTrayLinuxState *>(state.platform);
  if (linux_state == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  kweb_tray_status status = KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  RunOnBusThread(*linux_state, [&] {
    const auto found = linux_state->items.find(item_id);
    if (found == linux_state->items.end()) return;
    ReleaseItem(*linux_state, *found->second);
    linux_state->items.erase(found);
    status = KWEB_TRAY_STATUS_OK;
  });
  return status;
}

kweb_tray_status NativeSetMenu(State &state, const std::string &item_id, const Menu *menu) {
  auto *linux_state = static_cast<KWebTrayLinuxState *>(state.platform);
  if (linux_state == nullptr) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  kweb_tray_status status = KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  RunOnBusThread(*linux_state, [&] {
    const auto found = linux_state->items.find(item_id);
    if (found == linux_state->items.end()) return;
    KWebTrayLinuxItem &live = *found->second;
    live.command_by_item.clear();
    live.index_by_item.clear();
    live.item_id_by_index.clear();
    if (menu == nullptr) {
      live.has_menu = false;
      live.menu = Menu();
    } else {
      live.menu = *menu;
      live.has_menu = true;
      uint32_t next_id = 1;
      for (const size_t root : live.menu.roots) AssignMenuIds(live, root, &next_id);
    }
    EmitItemChanged(live);
    status = KWEB_TRAY_STATUS_OK;
  });
  return status;
}

kweb_tray_status NativeBounds(State &, const std::string &, kweb_tray_bounds_result *) {
  // The status-notifier protocol does not publish item geometry.
  return KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE;
}

kweb_tray_status NativeClose(State &state) {
  auto *linux_state = static_cast<KWebTrayLinuxState *>(state.platform);
  if (linux_state != nullptr) {
    RunOnBusThread(*linux_state, [&] {
      std::vector<std::string> ids;
      ids.reserve(linux_state->items.size());
      for (const auto &pair : linux_state->items) ids.push_back(pair.first);
      for (const std::string &id : ids) {
        const auto found = linux_state->items.find(id);
        if (found == linux_state->items.end()) continue;
        ReleaseItem(*linux_state, *found->second);
        linux_state->items.erase(found);
      }
      if (linux_state->watcher_subscription != 0 && linux_state->control != nullptr) {
        g_dbus_connection_signal_unsubscribe(linux_state->control, linux_state->watcher_subscription);
        linux_state->watcher_subscription = 0;
      }
      if (linux_state->control != nullptr) {
        g_object_unref(linux_state->control);
        linux_state->control = nullptr;
      }
      if (linux_state->loop != nullptr) g_main_loop_quit(linux_state->loop);
    });
    {
      std::lock_guard<std::mutex> lock(linux_state->mutex);
      linux_state->stopping = true;
    }
    if (linux_state->thread.joinable()) linux_state->thread.join();
    state.platform = nullptr;
    if (g_state == &state) g_state = nullptr;
    delete linux_state;
  }
  return KWEB_TRAY_STATUS_OK;
}

}  // namespace kwebshell::tray
