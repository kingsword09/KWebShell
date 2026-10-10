#ifndef KWEB_TRAY_INTERNAL_H_
#define KWEB_TRAY_INTERNAL_H_

#include "kweb_tray.h"

#include <deque>
#include <map>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::tray {

/** One validated RFC 0027 icon variant of a tray item. */
struct IconVariant {
  uint32_t scale = 1;
  bool template_icon = false;
  std::string resource_id;
  std::string sha256;
  std::vector<uint8_t> pixels;
  uint32_t width = 0;
  uint32_t height = 0;
};

/** One validated item declaration. */
struct Item {
  std::string id;
  std::string tooltip;
  uint32_t activation_bits = 0;
  std::vector<IconVariant> variants;
};

/** One validated menu node of the RFC 0017 tree subset. */
struct MenuNode {
  kweb_tray_menu_kind kind = KWEB_TRAY_MENU_COMMAND;
  bool enabled = true;
  bool visible = true;
  bool checked = false;
  std::string id;
  std::string label;
  std::vector<size_t> children;
};

/** One immutable validated menu bound to a live item. */
struct Menu {
  std::string menu_id;
  uint64_t version = 0;
  std::vector<MenuNode> items;
  std::vector<size_t> roots;
};

/** The single process-wide tray state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 0;
  void *platform = nullptr;
  std::deque<kweb_tray_event> events;
  std::map<std::string, Item> items;
  std::map<std::string, Menu> menus;
};

const char *ProviderId();
bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_tray_string &value, size_t maximum, std::string *output);
bool IsIdentifier(const std::string &value);
bool IsResourceId(const std::string &value);
bool IsSha256(const std::string &value);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/**
 * Parses and validates one item declaration before provider dispatch and
 * reports the exact rejection status.
 */
kweb_tray_status ParseItem(const kweb_tray_item_spec &spec, Item *output);

/** Parses and validates one menu tree before provider dispatch. */
bool ParseMenu(const kweb_tray_menu_tree &tree, Menu *output);

void PushActivated(State &state, const std::string &item_id, uint32_t activation_bit);
void PushMenuCommand(State &state, const std::string &item_id, const std::string &menu_id,
                     const std::string &command_id, uint64_t tree_version);
void PushBalloon(State &state, const std::string &item_id);
void PushRemoved(State &state, const std::string &item_id, kweb_tray_removal_reason reason);
void PushFailed(State &state, const std::string &item_id, const std::string &code);

/* The platform provider surface implemented by tray_<platform> sources. */
kweb_tray_status NativeOpen(State &state);
kweb_tray_status NativeCapabilities(State &state, kweb_tray_capabilities_result *result);
kweb_tray_status NativeSetItem(State &state, const Item &item, bool replacing);
kweb_tray_status NativeCloseItem(State &state, const std::string &item_id);
kweb_tray_status NativeSetMenu(State &state, const std::string &item_id, const Menu *menu);
kweb_tray_status NativeBounds(State &state, const std::string &item_id, kweb_tray_bounds_result *result);
kweb_tray_status NativeClose(State &state);

}  // namespace kwebshell::tray

#endif
