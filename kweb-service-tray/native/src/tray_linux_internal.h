#ifndef KWEB_TRAY_LINUX_INTERNAL_H_
#define KWEB_TRAY_LINUX_INTERNAL_H_

#include "tray_internal.h"

#if defined(__linux__)

#include <gio/gio.h>

#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

/**
 * One status-notifier item. The protocol resolves an item through the service
 * name registered with the watcher plus the fixed path /StatusNotifierItem, so
 * every item owns its own bus connection and name.
 */
struct KWebTrayLinuxItem {
  std::string item_id;
  std::string bus_name;
  GDBusConnection *connection = nullptr;
  guint bus_owner = 0;
  guint item_registration = 0;
  guint menu_registration = 0;
  bool registered_with_watcher = false;
  bool has_menu = false;
  uint32_t activation_bits = 0;
  std::string tooltip;
  std::vector<kwebshell::tray::IconVariant> variants;
  kwebshell::tray::Menu menu;
  std::map<uint32_t, std::string> command_by_item;
  std::map<uint32_t, size_t> index_by_item;
  std::map<uint32_t, size_t> item_id_by_index;
};

/**
 * The Linux provider state: one bus thread with its own GMainContext that owns
 * a control connection (watcher presence and reconnection) plus one connection
 * per live item.
 */
struct KWebTrayLinuxState {
  std::thread thread;
  GMainContext *context = nullptr;
  GMainLoop *loop = nullptr;
  GDBusConnection *control = nullptr;
  guint watcher_subscription = 0;
  bool watcher_present = false;
  std::map<std::string, std::shared_ptr<KWebTrayLinuxItem>> items;
  std::mutex mutex;
  std::condition_variable ready;
  bool started = false;
  bool stopping = false;
};

namespace kwebshell::tray::linux_provider {

/** The process-wide ABI state the D-Bus handlers report to. */
State *CurrentState();

/** Converts straight RGBA8 into the protocol's network-order ARGB32. */
std::vector<uint8_t> ArgbaPixmap(const IconVariant &variant);

}  // namespace kwebshell::tray::linux_provider

#endif

#endif
