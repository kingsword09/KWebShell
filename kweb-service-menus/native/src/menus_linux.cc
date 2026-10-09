#include "menus_platform.h"

#if !defined(__linux__)
#error "The Linux menus provider must only be compiled on Linux."
#endif

#include <gtk/gtk.h>

#include <cctype>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace {

/**
 * GTK is not thread-safe, so one dedicated thread owns the display connection
 * and its own GMainContext. Every ABI call hands one block to that thread and
 * waits, which keeps menu work independent from the host's event loop.
 */
struct LinuxState {
  std::thread thread;
  GMainContext *context = nullptr;
  GMainLoop *loop = nullptr;
  std::mutex mutex;
  std::condition_variable ready;
  bool started = false;
  bool available = false;
  bool stopping = false;
};

struct PopupSelection {
  std::map<GtkWidget *, std::string> commands;
  GMainLoop *loop = nullptr;
  std::string command_id;
};

kwebshell::menus::State *g_state = nullptr;

/** Escapes the label and marks the published mnemonic for GTK. */
std::string GtkLabel(const std::string &label, uint32_t mnemonic) {
  std::string text;
  text.reserve(label.size() + 2);
  bool marked = false;
  for (const char character : label) {
    if (character == '_') {
      text += "__";
      continue;
    }
    if (!marked && mnemonic != 0 &&
        std::tolower(static_cast<unsigned char>(character)) ==
            std::tolower(static_cast<unsigned char>(mnemonic))) {
      text += '_';
      marked = true;
    }
    text += character;
  }
  return text;
}

const char *const kKeyNames[] = {
    "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q", "R",
    "S", "T", "U", "V", "W", "X", "Y", "Z", "0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
    "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11", "F12",
    "Backspace", "Delete", "Insert", "Home", "End", "PageUp", "PageDown", "Left", "Right", "Up",
    "Down", "Enter", "Esc", "Tab", "Space", "+", "-", "=", ",", ".", ";", "/", "\\", "[", "]", "'",
    "`",
};

std::string AcceleratorText(const kwebshell::menus::Item &item) {
  if (item.accelerator_key == KWEB_MENUS_KEY_NONE) return std::string();
  std::string text;
  if ((item.accelerator_modifiers & (KWEB_MENUS_MODIFIER_PRIMARY | KWEB_MENUS_MODIFIER_CONTROL)) != 0) {
    text += "Ctrl+";
  }
  if ((item.accelerator_modifiers & KWEB_MENUS_MODIFIER_ALT) != 0) text += "Alt+";
  if ((item.accelerator_modifiers & KWEB_MENUS_MODIFIER_SHIFT) != 0) text += "Shift+";
  if ((item.accelerator_modifiers & KWEB_MENUS_MODIFIER_META) != 0) text += "Super+";
  const uint32_t index = item.accelerator_key - 1u;
  if (index < sizeof(kKeyNames) / sizeof(kKeyNames[0])) text += kKeyNames[index];
  return text;
}

GtkWidget *BuildMenuItem(const kwebshell::menus::Tree &tree, size_t index, PopupSelection &selection,
                         GtkWidget **radio_group);

GtkWidget *BuildMenu(const kwebshell::menus::Tree &tree, PopupSelection &selection) {
  GtkWidget *menu = gtk_menu_new();
  if (menu == nullptr) return nullptr;
  GtkWidget *radio_group = nullptr;
  for (const size_t root : tree.roots) {
    gtk_menu_shell_append(GTK_MENU_SHELL(menu), BuildMenuItem(tree, root, selection, &radio_group));
  }
  return menu;
}

GtkWidget *BuildMenuItem(const kwebshell::menus::Tree &tree, size_t index, PopupSelection &selection,
                         GtkWidget **radio_group) {
  const kwebshell::menus::Item &node = tree.items[index];
  if (node.kind == KWEB_MENUS_ITEM_SEPARATOR) return gtk_separator_menu_item_new();
  const std::string label = GtkLabel(node.label, node.mnemonic);
  GtkWidget *item = nullptr;
  if (node.kind == KWEB_MENUS_ITEM_SUBMENU) {
    item = gtk_menu_item_new_with_mnemonic(label.c_str());
    GtkWidget *child = gtk_menu_new();
    GtkWidget *child_radio_group = nullptr;
    for (const size_t child_index : node.children) {
      gtk_menu_shell_append(GTK_MENU_SHELL(child),
                            BuildMenuItem(tree, child_index, selection, &child_radio_group));
    }
    gtk_menu_item_set_submenu(GTK_MENU_ITEM(item), child);
  } else if (node.kind == KWEB_MENUS_ITEM_RADIO) {
    GSList *group = *radio_group != nullptr ? gtk_radio_menu_item_get_group(GTK_RADIO_MENU_ITEM(*radio_group))
                                            : nullptr;
    item = gtk_radio_menu_item_new_with_mnemonic(group, label.c_str());
    gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(item), node.checked ? TRUE : FALSE);
    if (*radio_group == nullptr) *radio_group = item;
  } else if (node.kind == KWEB_MENUS_ITEM_CHECKBOX) {
    item = gtk_check_menu_item_new_with_mnemonic(label.c_str());
    gtk_check_menu_item_set_active(GTK_CHECK_MENU_ITEM(item), node.checked ? TRUE : FALSE);
    *radio_group = nullptr;
  } else {
    item = gtk_menu_item_new();
  }
  if (item == nullptr) return gtk_separator_menu_item_new();
  gtk_widget_set_sensitive(item, node.enabled ? TRUE : FALSE);
  gtk_widget_set_visible(item, node.visible ? TRUE : FALSE);
  if (node.kind == KWEB_MENUS_ITEM_COMMAND || node.kind == KWEB_MENUS_ITEM_CHECKBOX ||
      node.kind == KWEB_MENUS_ITEM_RADIO) {
    GdkPixbuf *pixbuf = nullptr;
    if (node.icon_width != 0 && node.icon_height != 0 && !node.icon_pixels.empty()) {
      // Published pixels are straight RGBA8, which is exactly GdkPixbuf RGB+A.
      pixbuf = gdk_pixbuf_new(GDK_COLORSPACE_RGB, TRUE, 8, static_cast<int>(node.icon_width),
                              static_cast<int>(node.icon_height));
      if (pixbuf != nullptr) {
        std::memcpy(gdk_pixbuf_get_pixels(pixbuf), node.icon_pixels.data(), node.icon_pixels.size());
      }
    }
    const std::string accelerator = AcceleratorText(node);
    GtkWidget *box = gtk_box_new(GTK_ORIENTATION_HORIZONTAL, 6);
    if (pixbuf != nullptr) {
      GtkWidget *image = gtk_image_new_from_pixbuf(pixbuf);
      g_object_unref(pixbuf);
      if (image != nullptr) gtk_box_pack_start(GTK_BOX(box), image, FALSE, FALSE, 0);
    }
    gtk_box_pack_start(GTK_BOX(box), gtk_label_new_with_mnemonic(label.c_str()), TRUE, TRUE, 0);
    if (!accelerator.empty()) {
      GtkWidget *shortcut = gtk_label_new(accelerator.c_str());
      gtk_style_context_add_class(gtk_widget_get_style_context(shortcut), "dim-label");
      gtk_box_pack_end(GTK_BOX(box), shortcut, FALSE, FALSE, 0);
    }
    gtk_container_add(GTK_CONTAINER(item), box);
    gtk_widget_show_all(box);
    selection.commands[item] = node.id;
  }
  return item;
}

void OnActivate(GtkMenuItem *item, gpointer user_data) {
  auto *selection = static_cast<PopupSelection *>(user_data);
  if (selection == nullptr) return;
  const auto found = selection->commands.find(GTK_WIDGET(item));
  if (found != selection->commands.end()) selection->command_id = found->second;
}

void OnSelectionDone(GtkMenuShell *, gpointer user_data) {
  auto *selection = static_cast<PopupSelection *>(user_data);
  if (selection != nullptr && selection->loop != nullptr) g_main_loop_quit(selection->loop);
}

/** Hands one block to the GTK thread and waits until it has run. */
bool RunOnGtkThread(LinuxState &state, const std::function<void()> &block) {
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.started || state.stopping || state.context == nullptr) return false;
  }
  std::mutex completion_mutex;
  std::condition_variable completion;
  bool done = false;
  auto *work = new std::function<void()>(
      [&block, &done, &completion_mutex, &completion] {
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

}  // namespace

namespace kwebshell::menus {

const char *ProviderId() {
  return "menus.linux.gtk";
}

kweb_menus_status NativeOpen(State &state) {
  auto linux_state = std::make_unique<LinuxState>();
  std::unique_lock<std::mutex> lock(linux_state->mutex);
  linux_state->thread = std::thread([linux_state = linux_state.get()] {
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GMainLoop *loop = g_main_loop_new(context, FALSE);
    const gboolean available = gtk_init_check(nullptr, nullptr) == TRUE;
    {
      std::lock_guard<std::mutex> guard(linux_state->mutex);
      linux_state->context = context;
      linux_state->loop = loop;
      linux_state->available = available;
      linux_state->started = true;
    }
    linux_state->ready.notify_all();
    if (available) g_main_loop_run(loop);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
  });
  linux_state->ready.wait(lock, [linux_state = linux_state.get()] { return linux_state->started; });
  if (!linux_state->available) {
    lock.unlock();
    linux_state->thread.join();
    return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  }
  lock.unlock();
  state.platform = linux_state.release();
  g_state = &state;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeCapabilities(State &, kweb_menus_capabilities_result *result) {
  result->flags = KWEB_MENUS_CAP_PAGE_MENU | KWEB_MENUS_CAP_SUBMENUS | KWEB_MENUS_CAP_CHECKBOX_ITEMS |
                  KWEB_MENUS_CAP_RADIO_ITEMS | KWEB_MENUS_CAP_ITEM_ICONS | KWEB_MENUS_CAP_MNEMONICS |
                  KWEB_MENUS_CAP_ACCELERATOR_DISPLAY | KWEB_MENUS_CAP_POPUP_POSITIONING;
  result->native_roles = 0;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeSetApplicationMenu(State &, const Tree *tree) {
  // The Linux v1 provider publishes popup menus only. Desktop menu hosting is a
  // declared follow-up objective; nothing silently substitutes an in-window bar.
  if (tree == nullptr) return KWEB_MENUS_STATUS_OK;
  return KWEB_MENUS_STATUS_TARGET_UNSUPPORTED;
}

kweb_menus_status NativeSetWindowMenu(State &, const std::string &, uint64_t, kweb_menus_owner_kind,
                                     const Tree *tree) {
  if (tree == nullptr) return KWEB_MENUS_STATUS_OK;
  return KWEB_MENUS_STATUS_TARGET_UNSUPPORTED;
}

kweb_menus_status NativeShowPopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind,
                                  const std::string &owner_id, kweb_menus_popup_source, int32_t screen_x,
                                  int32_t screen_y, uint64_t native_window, const std::string &popup_id,
                                  kweb_menus_popup_result *result) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  if (linux_state == nullptr) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  PopupSelection selection;
  bool presented = false;
  const bool submitted = RunOnGtkThread(*linux_state, [&] {
    GtkWidget *menu = BuildMenu(tree, selection);
    if (menu == nullptr) return;
    GMainLoop *loop = g_main_loop_new(linux_state->context, FALSE);
    selection.loop = loop;
    for (const auto &entry : selection.commands) {
      g_signal_connect(entry.first, "activate", G_CALLBACK(OnActivate), &selection);
    }
    g_signal_connect(menu, "selection-done", G_CALLBACK(OnSelectionDone), &selection);
    gtk_widget_show_all(menu);
    GdkWindow *root = gdk_get_default_root_window();
    GdkRectangle anchor = {screen_x, screen_y, 1, 1};
    gtk_menu_popup_at_rect(GTK_MENU(menu), root, &anchor, GDK_GRAVITY_NORTH_WEST, GDK_GRAVITY_NORTH_WEST,
                           nullptr);
    presented = true;
    g_main_loop_run(loop);
    gtk_widget_destroy(menu);
    g_main_loop_unref(loop);
    selection.loop = nullptr;
  });
  (void)native_window;
  if (!submitted || !presented) {
    CompletePopup(state, tree, owner_kind, owner_id, popup_id, std::string(), KWEB_MENUS_DISMISS_USER, result);
    return KWEB_MENUS_STATUS_NATIVE_FAILED;
  }
  CompletePopup(state, tree, owner_kind, owner_id, popup_id, selection.command_id, KWEB_MENUS_DISMISS_USER,
                result);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeClose(State &state) {
  auto *linux_state = static_cast<LinuxState *>(state.platform);
  if (linux_state != nullptr) {
    std::function<void()> stop = [linux_state] {
      if (linux_state->loop != nullptr) g_main_loop_quit(linux_state->loop);
    };
    RunOnGtkThread(*linux_state, stop);
    {
      std::lock_guard<std::mutex> lock(linux_state->mutex);
      linux_state->stopping = true;
    }
    if (linux_state->thread.joinable()) linux_state->thread.join();
    state.platform = nullptr;
    if (g_state == &state) g_state = nullptr;
    delete linux_state;
  }
  return KWEB_MENUS_STATUS_OK;
}

}  // namespace kwebshell::menus
