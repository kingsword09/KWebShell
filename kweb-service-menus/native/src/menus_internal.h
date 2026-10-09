#ifndef KWEB_MENUS_INTERNAL_H_
#define KWEB_MENUS_INTERNAL_H_

#include "kweb_menus.h"

#include <deque>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::menus {

/** One validated node of a declarative menu tree. */
struct Item {
  kweb_menus_item_kind kind = KWEB_MENUS_ITEM_COMMAND;
  bool enabled = true;
  bool visible = true;
  bool checked = false;
  bool template_icon = false;
  kweb_menus_role role = KWEB_MENUS_ROLE_NONE;
  uint32_t mnemonic = 0;
  uint32_t accelerator_modifiers = 0;
  uint32_t accelerator_key = 0;
  std::string id;
  std::string label;
  std::string icon_id;
  std::string icon_sha256;
  std::vector<uint8_t> icon_pixels;
  uint32_t icon_width = 0;
  uint32_t icon_height = 0;
  std::vector<size_t> children;
};

/** One immutable validated menu. Items are stored in flat preorder order. */
struct Tree {
  std::string menu_id;
  uint64_t version = 0;
  std::vector<Item> items;
  std::vector<size_t> roots;
  uint32_t actionable_count = 0;
};

/** The single process-wide menus state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 0;
  uint64_t popup_counter = 0;
  uint32_t open_popups = 0;
  void *platform = nullptr;
  std::deque<kweb_menus_event> events;
  bool has_application_menu = false;
  Tree application_menu;
  std::map<std::string, Tree> window_menus;
  std::map<std::string, Tree> page_menus;
};

/** The result of walking one sibling block of the flat preorder array. */
struct WalkResult {
  size_t next = 0;
  uint32_t actionable = 0;
  std::vector<size_t> starts;
};

const char *ProviderId();
bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_menus_string &value, size_t maximum, std::string *output);
bool ParseTree(const kweb_menus_tree &source, Tree *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

void PushInvoked(State &state, kweb_menus_invocation_source source, kweb_menus_owner_kind owner_kind,
                 const std::string &owner_id, const std::string &popup_id, const std::string &command_id,
                 uint64_t tree_version);
void PushDismissed(State &state, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                   const std::string &popup_id, kweb_menus_dismiss_reason reason);
void PushFailed(State &state, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                const std::string &code);

/**
 * Reports one terminal popup outcome exactly once: pushes the ordered event and
 * fills the caller-visible result. Platform providers call this when the
 * presented popup has closed.
 */
void CompletePopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                   const std::string &popup_id, const std::string &command_id,
                   kweb_menus_dismiss_reason reason, kweb_menus_popup_result *result);

/** Reports one application/window menu bar invocation from a provider. */
void ReportInvocation(State &state, kweb_menus_invocation_source source, kweb_menus_owner_kind owner_kind,
                      const std::string &owner_id, const std::string &command_id, uint64_t tree_version);

/* The platform provider surface implemented by menus_<platform> sources. */
kweb_menus_status NativeOpen(State &state);
kweb_menus_status NativeCapabilities(State &state, kweb_menus_capabilities_result *result);
kweb_menus_status NativeSetApplicationMenu(State &state, const Tree *tree);
kweb_menus_status NativeSetWindowMenu(State &state, const std::string &window_id, uint64_t native_window,
                                     kweb_menus_owner_kind owner_kind, const Tree *tree);
kweb_menus_status NativeShowPopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind,
                                  const std::string &owner_id, kweb_menus_popup_source source,
                                  int32_t screen_x, int32_t screen_y, uint64_t native_window,
                                  const std::string &popup_id, kweb_menus_popup_result *result);
kweb_menus_status NativeClose(State &state);

}  // namespace kwebshell::menus

#endif
