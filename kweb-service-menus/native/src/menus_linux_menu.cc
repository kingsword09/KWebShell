#include "menus_linux_internal.h"

#if !defined(__linux__)
#error "The Linux desktop menu provider must only be compiled on Linux."
#endif

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <map>
#include <memory>
#include <string>
#include <vector>

namespace {

const char kRegistrarBusName[] = "com.canonical.AppMenu.Registrar";
const char kRegistrarPath[] = "/com/canonical/AppMenu/Registrar";
const char kRegistrarInterface[] = "com.canonical.AppMenu.Registrar";
const char kMenuInterface[] = "com.canonical.dbusmenu";

const char kMenuInterfaceXml[] =
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
    "    <signal name='LayoutUpdated'>"
    "      <arg type='u' name='revision'/>"
    "      <arg type='i' name='parent'/>"
    "    </signal>"
    "    <signal name='ItemsPropertiesUpdated'>"
    "      <arg type='a(ia{sv})' name='updatedProps'/>"
    "      <arg type='a(ias)' name='removedProps'/>"
    "    </signal>"
    "  </interface>"
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

using kwebshell::menus::linux_provider::DesktopMenuWindow;

constexpr int32_t kUnlimitedDepth = 32;

/** Assigns the published preorder item ids and both lookup tables. */
void AssignItemIds(const kwebshell::menus::Tree &tree, size_t index, uint32_t *next_id,
                   std::map<uint32_t, size_t> *index_by_item,
                   std::map<uint32_t, std::string> *command_by_item) {
  const kwebshell::menus::Item &node = tree.items[index];
  const uint32_t id = (*next_id)++;
  (*index_by_item)[id] = index;
  if (node.kind != KWEB_MENUS_ITEM_SEPARATOR) (*command_by_item)[id] = node.id;
  for (const size_t child : node.children) {
    AssignItemIds(tree, child, next_id, index_by_item, command_by_item);
  }
}

std::string ItemLabel(const kwebshell::menus::Item &node) {
  std::string label = node.label;
  if (node.mnemonic != 0) {
    for (size_t index = 0; index < label.size(); ++index) {
      if (std::tolower(static_cast<unsigned char>(label[index])) ==
          std::tolower(static_cast<unsigned char>(node.mnemonic))) {
        label.insert(index, 1, '_');
        break;
      }
    }
  }
  return label;
}

GVariant *ItemProperties(const kwebshell::menus::Item &node) {
  GVariantBuilder properties;
  g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
  if (node.kind == KWEB_MENUS_ITEM_SEPARATOR) {
    g_variant_builder_add(&properties, "{sv}", "type", g_variant_new_string("separator"));
    g_variant_builder_add(&properties, "{sv}", "visible", g_variant_new_boolean(TRUE));
    return g_variant_builder_end(&properties);
  }
  g_variant_builder_add(&properties, "{sv}", "label", g_variant_new_string(ItemLabel(node).c_str()));
  g_variant_builder_add(&properties, "{sv}", "enabled", g_variant_new_boolean(node.enabled ? TRUE : FALSE));
  g_variant_builder_add(&properties, "{sv}", "visible", g_variant_new_boolean(node.visible ? TRUE : FALSE));
  if (node.kind == KWEB_MENUS_ITEM_SUBMENU) {
    g_variant_builder_add(&properties, "{sv}", "children-display", g_variant_new_string("submenu"));
  }
  if (node.kind == KWEB_MENUS_ITEM_CHECKBOX || node.kind == KWEB_MENUS_ITEM_RADIO) {
    g_variant_builder_add(&properties, "{sv}", "toggle-type",
                          g_variant_new_string(node.kind == KWEB_MENUS_ITEM_RADIO ? "radio" : "checkmark"));
    g_variant_builder_add(&properties, "{sv}", "toggle-state",
                          g_variant_new_int32(node.checked ? 1 : 0));
  }
  if (node.icon_width != 0 && node.icon_height != 0 && !node.icon_pixels.empty()) {
    const int32_t width = static_cast<int32_t>(node.icon_width);
    const int32_t height = static_cast<int32_t>(node.icon_height);
    GVariant *pixels = g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, node.icon_pixels.data(),
                                                node.icon_pixels.size(), 1);
    g_variant_builder_add(&properties, "{sv}", "icon-data",
                          g_variant_new("(iiibii@ay)", width, height, width * 4, TRUE, 8, 4, pixels));
  }
  return g_variant_builder_end(&properties);
}

/** Builds one `(ia{sv}av)` layout entry, recursing while depth allows. */
GVariant *LayoutFor(const DesktopMenuWindow &window, size_t index, int32_t depth) {
  const kwebshell::menus::Item &node = window.tree.items[index];
  GVariantBuilder layout;
  g_variant_builder_init(&layout, G_VARIANT_TYPE("(ia{sv}av)"));
  const auto id = window.index_by_item.find(0);
  (void)id;
  uint32_t published = 0;
  for (const auto &pair : window.index_by_item) {
    if (pair.second == index) published = pair.first;
  }
  g_variant_builder_add(&layout, "i", static_cast<int32_t>(published));
  g_variant_builder_add_value(&layout, ItemProperties(node));
  GVariantBuilder children;
  g_variant_builder_init(&children, G_VARIANT_TYPE("av"));
  if (depth != 0) {
    for (const size_t child : node.children) {
      g_variant_builder_add_value(&children, g_variant_new_variant(LayoutFor(window, child, depth - 1)));
    }
  }
  g_variant_builder_add_value(&layout, g_variant_builder_end(&children));
  return g_variant_builder_end(&layout);
}

GVariant *RootLayout(const DesktopMenuWindow &window, int32_t depth) {
  GVariantBuilder layout;
  g_variant_builder_init(&layout, G_VARIANT_TYPE("(ia{sv}av)"));
  g_variant_builder_add(&layout, "i", 0);
  GVariantBuilder root_properties;
  g_variant_builder_init(&root_properties, G_VARIANT_TYPE("a{sv}"));
  g_variant_builder_add_value(&layout, g_variant_builder_end(&root_properties));
  GVariantBuilder children;
  g_variant_builder_init(&children, G_VARIANT_TYPE("av"));
  if (depth != 0) {
    for (const size_t root : window.tree.roots) {
      g_variant_builder_add_value(&children, g_variant_new_variant(LayoutFor(window, root, depth - 1)));
    }
  }
  g_variant_builder_add_value(&layout, g_variant_builder_end(&children));
  return g_variant_builder_end(&layout);
}

GVariant *PropertyValue(const char *name) {
  if (g_strcmp0(name, "Version") == 0) return g_variant_new_uint32(3);
  if (g_strcmp0(name, "TextDirection") == 0) return g_variant_new_string("ltr");
  if (g_strcmp0(name, "Status") == 0) return g_variant_new_string("normal");
  if (g_strcmp0(name, "IconThemePath") == 0) {
    GVariantBuilder theme;
    g_variant_builder_init(&theme, G_VARIANT_TYPE("as"));
    return g_variant_builder_end(&theme);
  }
  return nullptr;
}

}  // namespace

namespace kwebshell::menus::linux_provider {

namespace {

std::shared_ptr<DesktopMenuWindow> WindowFor(KWebMenusLinuxState &state, const gchar *object_path) {
  for (const auto &pair : state.windows) {
    if (pair.second->object_path == object_path) return pair.second;
  }
  return nullptr;
}

void HandleMenuCall(KWebMenusLinuxState &state, DesktopMenuWindow &window, const gchar *method_name,
                    GVariant *parameters, GDBusMethodInvocation *invocation) {
  if (g_strcmp0(method_name, "GetLayout") == 0) {
    gint32 parent_id = 0;
    gint32 recursion_depth = -1;
    g_autoptr(GVariant) names = nullptr;
    g_variant_get(parameters, "(ii@as)", &parent_id, &recursion_depth, &names);
    const int32_t depth = recursion_depth < 0 ? kUnlimitedDepth : recursion_depth;
    GVariant *layout = nullptr;
    if (parent_id == 0) {
      layout = RootLayout(window, depth);
    } else {
      const auto found = window.index_by_item.find(static_cast<uint32_t>(parent_id));
      if (found == window.index_by_item.end()) {
        g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS,
                                              "The requested menu item is not published.");
        return;
      }
      layout = LayoutFor(window, found->second, depth);
    }
    g_dbus_method_invocation_return_value(
        invocation, g_variant_new("(u@(ia{sv}av))", static_cast<guint32>(state.revision), layout));
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
      const auto found = window.index_by_item.find(static_cast<uint32_t>(id));
      if (found == window.index_by_item.end()) continue;
      g_variant_builder_add(&properties, "(i@a{sv})", id, ItemProperties(window.tree.items[found->second]));
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
    kwebshell::menus::State *state_ptr = kwebshell::menus::linux_provider::CurrentState();
    if (g_strcmp0(event_id, "clicked") == 0 && state_ptr != nullptr) {
      const auto command = window.command_by_item.find(static_cast<uint32_t>(id));
      if (command != window.command_by_item.end()) {
        kwebshell::menus::ReportInvocation(
            *state_ptr,
            window.owner_kind == KWEB_MENUS_OWNER_APPLICATION ? KWEB_MENUS_SOURCE_APPLICATION_MENU
                                                               : KWEB_MENUS_SOURCE_WINDOW_MENU,
            window.owner_kind, window.window_id, command->second, window.tree.version);
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
                                        "The menus object does not implement this call.");
}

void HandlePropertiesCall(DesktopMenuWindow &window, const gchar *method_name, GVariant *parameters,
                          GDBusMethodInvocation *invocation) {
  (void)window;
  if (g_strcmp0(method_name, "GetAll") == 0) {
    GVariantBuilder properties;
    g_variant_builder_init(&properties, G_VARIANT_TYPE("a{sv}"));
    g_variant_builder_add(&properties, "{sv}", "Version", g_variant_new_uint32(3));
    g_variant_builder_add(&properties, "{sv}", "TextDirection", g_variant_new_string("ltr"));
    g_variant_builder_add(&properties, "{sv}", "Status", g_variant_new_string("normal"));
    GVariantBuilder theme;
    g_variant_builder_init(&theme, G_VARIANT_TYPE("as"));
    g_variant_builder_add(&properties, "{sv}", "IconThemePath", g_variant_builder_end(&theme));
    g_dbus_method_invocation_return_value(invocation,
                                         g_variant_new("(@a{sv})", g_variant_builder_end(&properties)));
    return;
  }
  if (g_strcmp0(method_name, "Get") == 0) {
    const gchar *interface_name = nullptr;
    const gchar *property_name = nullptr;
    g_variant_get(parameters, "(&s&s)", &interface_name, &property_name);
    GVariant *value = PropertyValue(property_name);
    if (value == nullptr) {
      g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS,
                                            "The requested property is not published.");
      return;
    }
    g_dbus_method_invocation_return_value(invocation, g_variant_new("(@v)", g_variant_new_variant(value)));
    return;
  }
  if (g_strcmp0(method_name, "Set") == 0) {
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_PROPERTY_READ_ONLY,
                                          "The menus properties are read-only.");
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_METHOD,
                                        "The menus properties object does not implement this call.");
}

void HandleMethodCall(GDBusConnection *, const gchar *sender, const gchar *object_path,
                      const gchar *interface_name, const gchar *method_name, GVariant *parameters,
                      GDBusMethodInvocation *invocation, gpointer user_data) {
  (void)sender;
  auto *state = static_cast<KWebMenusLinuxState *>(user_data);
  auto window = WindowFor(*state, object_path);
  if (window == nullptr) {
    g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_OBJECT,
                                          "The menus object is not registered.");
    return;
  }
  if (g_strcmp0(interface_name, kMenuInterface) == 0) {
    HandleMenuCall(*state, *window, method_name, parameters, invocation);
    return;
  }
  if (g_strcmp0(interface_name, "org.freedesktop.DBus.Properties") == 0) {
    HandlePropertiesCall(*window, method_name, parameters, invocation);
    return;
  }
  g_dbus_method_invocation_return_error(invocation, G_DBUS_ERROR, G_DBUS_ERROR_UNKNOWN_INTERFACE,
                                        "The menus object does not implement this interface.");
}

const GDBusInterfaceVTable kMenuVTable = {HandleMethodCall, nullptr, nullptr, {nullptr}};

GDBusNodeInfo *MenuNodeInfo() {
  static GDBusNodeInfo *node_info = nullptr;
  if (node_info == nullptr) {
    GError *error = nullptr;
    node_info = g_dbus_node_info_new_for_xml(kMenuInterfaceXml, &error);
    if (error != nullptr) g_error_free(error);
  }
  return node_info;
}

}  // namespace

bool DesktopMenuHostPresent(KWebMenusLinuxState &state) {
  if (state.connection == nullptr) return false;
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      state.connection, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
      "NameHasOwner", g_variant_new("(s)", kRegistrarBusName), G_VARIANT_TYPE("(b)"),
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    return false;
  }
  gboolean owned = FALSE;
  g_variant_get(reply, "(b)", &owned);
  g_variant_unref(reply);
  return owned == TRUE;
}

kweb_menus_status RegisterDesktopMenu(KWebMenusLinuxState &state, const std::string &window_id, uint64_t xid,
                                      kweb_menus_owner_kind owner_kind, const Tree &tree) {
  if (state.connection == nullptr) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  if (!DesktopMenuHostPresent(state)) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  GDBusNodeInfo *node_info = MenuNodeInfo();
  if (node_info == nullptr) return KWEB_MENUS_STATUS_NATIVE_FAILED;
  // D-Bus object paths allow only [A-Za-z0-9_] per element.
  std::string sanitized = window_id;
  for (char &character : sanitized) {
    const bool accepted = (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z') ||
                          (character >= '0' && character <= '9') || character == '_';
    if (!accepted) character = '_';
  }
  auto window = std::make_shared<DesktopMenuWindow>();
  window->window_id = window_id;
  window->xid = xid;
  window->owner_kind = owner_kind;
  window->tree = tree;
  window->object_path = "/io/kwebshell/menus/" + sanitized + "_" + std::to_string(xid);
  uint32_t next_id = 1;
  for (const size_t root : tree.roots) {
    AssignItemIds(tree, root, &next_id, &window->index_by_item, &window->command_by_item);
  }
  GError *error = nullptr;
  const guint registration = g_dbus_connection_register_object(
      state.connection, window->object_path.c_str(), node_info->interfaces[0], &kMenuVTable, &state, nullptr,
      &error);
  if (registration == 0) {
    if (error != nullptr) g_error_free(error);
    return KWEB_MENUS_STATUS_NATIVE_FAILED;
  }
  window->registration = registration;
  GVariant *reply = g_dbus_connection_call_sync(
      state.connection, kRegistrarBusName, kRegistrarPath, kRegistrarInterface, "RegisterWindow",
      g_variant_new("(uo)", static_cast<guint32>(xid), window->object_path.c_str()), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    g_dbus_connection_unregister_object(state.connection, registration);
    return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  }
  g_variant_unref(reply);
  const auto existing = state.windows.find(window_id);
  if (existing != state.windows.end()) {
    // A replacement tree reuses the published object path; the previous
    // registration is released so exactly one object per window stays live.
    if (state.connection != nullptr && existing->second->registration != 0) {
      g_dbus_connection_unregister_object(state.connection, existing->second->registration);
    }
    state.windows.erase(existing);
  }
  state.revision += 1;
  state.windows[window_id] = window;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status ClearDesktopMenu(KWebMenusLinuxState &state, const std::string &window_id) {
  const auto existing = state.windows.find(window_id);
  if (existing == state.windows.end()) return KWEB_MENUS_STATUS_OK;
  auto window = existing->second;
  if (state.connection != nullptr) {
    GError *error = nullptr;
    GVariant *reply = g_dbus_connection_call_sync(
        state.connection, kRegistrarBusName, kRegistrarPath, kRegistrarInterface, "UnregisterWindow",
        g_variant_new("(u)", static_cast<guint32>(window->xid)), nullptr, G_DBUS_CALL_FLAGS_NONE, 2000, nullptr,
        &error);
    if (reply != nullptr) {
      g_variant_unref(reply);
    } else if (error != nullptr) {
      g_error_free(error);
    }
    if (window->registration != 0) {
      g_dbus_connection_unregister_object(state.connection, window->registration);
      window->registration = 0;
    }
  }
  state.windows.erase(window_id);
  state.revision += 1;
  return KWEB_MENUS_STATUS_OK;
}

void CloseDesktopMenus(KWebMenusLinuxState &state) {
  std::vector<std::string> ids;
  ids.reserve(state.windows.size());
  for (const auto &pair : state.windows) ids.push_back(pair.first);
  for (const std::string &id : ids) ClearDesktopMenu(state, id);
  if (state.connection != nullptr) {
    g_object_unref(state.connection);
    state.connection = nullptr;
  }
}

}  // namespace kwebshell::menus::linux_provider
