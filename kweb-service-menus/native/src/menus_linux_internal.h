#ifndef KWEB_MENUS_LINUX_INTERNAL_H_
#define KWEB_MENUS_LINUX_INTERNAL_H_

#include "menus_internal.h"

#if defined(__linux__)

#include <gio/gio.h>
#include <gtk/gtk.h>

#include <condition_variable>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace kwebshell::menus::linux_provider {

/** One window that publishes a desktop menu object. */
struct DesktopMenuWindow {
  std::string window_id;
  uint64_t xid = 0;
  kweb_menus_owner_kind owner_kind = KWEB_MENUS_OWNER_WINDOW;
  std::string object_path;
  guint registration = 0;
  Tree tree;
  std::map<uint32_t, size_t> index_by_item;
  std::map<uint32_t, std::string> command_by_item;
};

}  // namespace kwebshell::menus::linux_provider

/**
 * The Linux provider state shared by the GTK popup source and the desktop menu
 * source: one GTK thread owns the display connection and the session-bus
 * connection, so both surfaces stay on one thread with one GMainContext.
 */
struct KWebMenusLinuxState {
  std::thread thread;
  GMainContext *context = nullptr;
  GMainLoop *loop = nullptr;
  GDBusConnection *connection = nullptr;
  guint64 revision = 0;
  std::map<std::string, std::shared_ptr<kwebshell::menus::linux_provider::DesktopMenuWindow>> windows;
  std::mutex mutex;
  std::condition_variable ready;
  bool started = false;
  bool available = false;
  bool stopping = false;
};

namespace kwebshell::menus::linux_provider {

/** The process-wide ABI state that desktop menu callbacks report against. */
kwebshell::menus::State *CurrentState();

bool DesktopMenuHostPresent(KWebMenusLinuxState &state);

kweb_menus_status RegisterDesktopMenu(KWebMenusLinuxState &state, const std::string &window_id, uint64_t xid,
                                      kweb_menus_owner_kind owner_kind, const Tree &tree);

kweb_menus_status ClearDesktopMenu(KWebMenusLinuxState &state, const std::string &window_id);

void CloseDesktopMenus(KWebMenusLinuxState &state);

}  // namespace kwebshell::menus::linux_provider

#endif

#endif
