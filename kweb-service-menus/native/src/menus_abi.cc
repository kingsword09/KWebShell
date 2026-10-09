#include "menus_internal.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <set>

namespace {

std::mutex g_registry_mutex;
kwebshell::menus::State g_state;
std::atomic<uint32_t> g_live_count{0};

bool ValidHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  return struct_size >= expected && abi_version == KWEB_MENUS_ABI_VERSION;
}

bool ValidRole(uint32_t role) {
  return role <= KWEB_MENUS_ROLE_FORWARD;
}

bool ValidKey(uint32_t key) {
  return key <= KWEB_MENUS_MAX_KEY;
}

uint32_t ModifierCount(uint32_t modifiers) {
  uint32_t count = 0;
  for (uint32_t bit = 0; bit < 32; ++bit) {
    if ((modifiers & (1u << bit)) != 0) ++count;
  }
  return count;
}

bool ValidModifiers(uint32_t modifiers) {
  constexpr uint32_t kAllowed = KWEB_MENUS_MODIFIER_PRIMARY | KWEB_MENUS_MODIFIER_CONTROL |
                                KWEB_MENUS_MODIFIER_ALT | KWEB_MENUS_MODIFIER_SHIFT |
                                KWEB_MENUS_MODIFIER_META;
  return (modifiers & ~kAllowed) == 0;
}

bool IsAsciiIdentifier(const std::string &value) {
  if (value.empty() || value.size() > KWEB_MENUS_MAX_ID) return false;
  const char first = value[0];
  if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) return false;
  for (size_t index = 1; index < value.size(); ++index) {
    const char character = value[index];
    const bool accepted = (character >= 'A' && character <= 'Z') || (character >= 'a' && character <= 'z') ||
                          (character >= '0' && character <= '9') || character == '.' || character == '_' ||
                          character == '-';
    if (!accepted) return false;
  }
  return true;
}

bool IsSha256(const std::string &value) {
  if (value.size() != 64) return false;
  for (const char character : value) {
    if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) return false;
  }
  return true;
}

bool HasControlCharacters(const std::string &value) {
  for (const char character : value) {
    const unsigned char byte = static_cast<unsigned char>(character);
    if (byte < 0x20 || byte == 0x7f) return true;
  }
  return false;
}

void ResetCapabilities(kweb_menus_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_MENUS_ABI_VERSION;
}

void ResetPopupResult(kweb_menus_popup_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_MENUS_ABI_VERSION;
}

void ResetEvent(kweb_menus_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_MENUS_ABI_VERSION;
}

using kwebshell::menus::Item;
using kwebshell::menus::Tree;
using kwebshell::menus::WalkResult;

/**
 * Walks one sibling block of the flat preorder array. The provider-independent
 * validation and the immutable tree construction happen in the same pass so an
 * invalid array can never reach a platform API.
 */
bool WalkItems(const kweb_menus_item *items, uint32_t item_count, size_t begin, size_t count, uint32_t depth,
               Tree *tree, WalkResult *result) {
  if (depth > KWEB_MENUS_MAX_DEPTH || count == 0) return false;
  size_t cursor = begin;
  uint32_t actionable = 0;
  kweb_menus_item_kind previous = KWEB_MENUS_ITEM_COMMAND;
  for (size_t index = 0; index < count; ++index) {
    if (cursor >= item_count) return false;
    const kweb_menus_item &item = items[cursor];
    if (!ValidHeader(item.struct_size, item.abi_version, sizeof(kweb_menus_item))) return false;
    if (item.kind < KWEB_MENUS_ITEM_COMMAND || item.kind > KWEB_MENUS_ITEM_SUBMENU) return false;
    if (!ValidRole(item.role) || !ValidKey(item.accelerator_key) || !ValidModifiers(item.accelerator_modifiers)) {
      return false;
    }
    if (item.accelerator_key == KWEB_MENUS_KEY_NONE && item.accelerator_modifiers != 0) return false;
    if (item.accelerator_key != KWEB_MENUS_KEY_NONE &&
        (item.accelerator_modifiers == 0 ||
         ModifierCount(item.accelerator_modifiers) > KWEB_MENUS_MAX_ACCELERATOR_MODIFIERS)) {
      return false;
    }
    Item parsed;
    parsed.kind = item.kind;
    parsed.enabled = (item.flags & KWEB_MENUS_ITEM_FLAG_ENABLED) != 0;
    parsed.visible = (item.flags & KWEB_MENUS_ITEM_FLAG_VISIBLE) != 0;
    parsed.checked = (item.flags & KWEB_MENUS_ITEM_FLAG_CHECKED) != 0;
    parsed.template_icon = (item.flags & KWEB_MENUS_ITEM_FLAG_TEMPLATE_ICON) != 0;
    parsed.role = item.role;
    parsed.mnemonic = item.mnemonic;
    parsed.accelerator_modifiers = item.accelerator_modifiers;
    parsed.accelerator_key = item.accelerator_key;
    if (!kwebshell::menus::ReadString(item.id, KWEB_MENUS_MAX_ID, &parsed.id)) return false;
    if (!kwebshell::menus::ReadString(item.label, KWEB_MENUS_MAX_LABEL, &parsed.label)) return false;
    if (!kwebshell::menus::ReadString(item.icon_id, KWEB_MENUS_MAX_ID, &parsed.icon_id)) return false;
    if (!kwebshell::menus::ReadString(item.icon_sha256, 64, &parsed.icon_sha256)) return false;
    if (item.kind == KWEB_MENUS_ITEM_SEPARATOR) {
      if (!parsed.id.empty() || !parsed.label.empty() || item.submenu_count != 0 || parsed.checked ||
          !parsed.icon_id.empty() || parsed.role != KWEB_MENUS_ROLE_NONE) {
        return false;
      }
      if (index == 0 || index == count - 1 || previous == KWEB_MENUS_ITEM_SEPARATOR) return false;
    } else {
      if (!IsAsciiIdentifier(parsed.id) || parsed.label.empty() || HasControlCharacters(parsed.label)) return false;
      if (parsed.checked && item.kind != KWEB_MENUS_ITEM_CHECKBOX && item.kind != KWEB_MENUS_ITEM_RADIO) {
        return false;
      }
      if (!parsed.icon_id.empty()) {
        if (!IsAsciiIdentifier(parsed.icon_id) || !IsSha256(parsed.icon_sha256)) return false;
        if (item.icon_width == 0 || item.icon_height == 0 ||
            item.icon_width > KWEB_MENUS_MAX_ICON_DIMENSION || item.icon_height > KWEB_MENUS_MAX_ICON_DIMENSION) {
          return false;
        }
        const size_t expected = static_cast<size_t>(item.icon_width) * item.icon_height * 4u;
        if (expected > KWEB_MENUS_MAX_ICON_BYTES || item.icon_pixels.size != expected ||
            item.icon_pixels.data == nullptr) {
          return false;
        }
        parsed.icon_pixels.assign(item.icon_pixels.data, item.icon_pixels.data + item.icon_pixels.size);
        parsed.icon_width = item.icon_width;
        parsed.icon_height = item.icon_height;
      } else if (item.icon_width != 0 || item.icon_height != 0 || item.icon_pixels.size != 0) {
        return false;
      }
      if (item.kind != KWEB_MENUS_ITEM_SUBMENU && item.submenu_count != 0) return false;
    }
    if (tree->items.size() != cursor) return false;
    const size_t self_index = tree->items.size();
    tree->items.push_back(parsed);
    cursor += 1;
    result->starts.push_back(self_index);
    if (item.kind == KWEB_MENUS_ITEM_SUBMENU) {
      if (item.submenu_count == 0) return false;
      WalkResult child;
      if (!WalkItems(items, item_count, cursor, item.submenu_count, depth + 1, tree, &child)) return false;
      tree->items[self_index].children = std::move(child.starts);
      cursor = child.next;
      actionable += child.actionable;
    } else {
      actionable += 1;
    }
    previous = item.kind;
  }
  result->next = cursor;
  result->actionable = actionable;
  return true;
}

bool BlockEnd(const kweb_menus_item *items, uint32_t item_count, size_t start, uint32_t depth, size_t *end) {
  if (start >= item_count || depth > KWEB_MENUS_MAX_DEPTH + 1u) return false;
  size_t cursor = start + 1;
  const kweb_menus_item &item = items[start];
  if (item.kind == KWEB_MENUS_ITEM_SUBMENU && item.submenu_count > 0) {
    if (item.submenu_count > KWEB_MENUS_MAX_ITEMS) return false;
    for (uint32_t child = 0; child < item.submenu_count; ++child) {
      size_t child_end = 0;
      if (!BlockEnd(items, item_count, cursor, depth + 1u, &child_end)) return false;
      cursor = child_end;
    }
  }
  *end = cursor;
  return true;
}

bool CountRoots(const kweb_menus_item *items, uint32_t item_count, uint32_t *roots) {
  size_t cursor = 0;
  uint32_t count = 0;
  while (cursor < item_count) {
    size_t end = 0;
    if (!BlockEnd(items, item_count, cursor, 1u, &end) || end <= cursor) return false;
    cursor = end;
    count += 1;
    if (count > KWEB_MENUS_MAX_ITEMS) return false;
  }
  *roots = count;
  return count > 0;
}

bool HasDuplicateCommandsOrAccelerators(const Tree &tree) {
  std::set<std::string> command_ids;
  std::set<std::pair<uint32_t, uint32_t>> accelerators;
  for (const Item &item : tree.items) {
    if (item.kind == KWEB_MENUS_ITEM_SEPARATOR) continue;
    if (!command_ids.insert(item.id).second) return true;
    if (item.accelerator_key != KWEB_MENUS_KEY_NONE) {
      if (!accelerators.insert({item.accelerator_modifiers, item.accelerator_key}).second) return true;
    }
  }
  return false;
}

}  // namespace

namespace kwebshell::menus {

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  if (capacity == 0) return;
  const size_t count = std::min(capacity - 1u, value.size());
  std::memcpy(target, value.data(), count);
  target[count] = '\0';
}

bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr && size != 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first <= 0x7f) { ++index; continue; }
    if (first >= 0xc2 && first <= 0xdf) continuation = 1;
    else if (first == 0xe0) { continuation = 2; second_min = 0xa0; }
    else if (first >= 0xe1 && first <= 0xec) continuation = 2;
    else if (first == 0xed) { continuation = 2; second_max = 0x9f; }
    else if (first >= 0xee && first <= 0xef) continuation = 2;
    else if (first == 0xf0) { continuation = 3; second_min = 0x90; }
    else if (first >= 0xf1 && first <= 0xf3) continuation = 3;
    else if (first == 0xf4) { continuation = 3; second_max = 0x8f; }
    else return false;
    if (index + continuation >= size) return false;
    const unsigned char second = data[index + 1];
    if (second < second_min || second > second_max) return false;
    for (size_t offset = 2; offset <= continuation; ++offset) {
      const unsigned char value = data[index + offset];
      if (value < 0x80 || value > 0xbf) return false;
    }
    index += continuation + 1;
  }
  return true;
}

bool ReadString(const kweb_menus_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) || (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

bool ParseTree(const kweb_menus_tree &source, Tree *output) {
  if (output == nullptr || !ValidHeader(source.struct_size, source.abi_version, sizeof(kweb_menus_tree))) {
    return false;
  }
  if (source.version < 1 || source.item_count == 0 || source.item_count > KWEB_MENUS_MAX_ITEMS ||
      source.items == nullptr) {
    return false;
  }
  Tree parsed;
  if (!ReadString(source.menu_id, KWEB_MENUS_MAX_ID, &parsed.menu_id) ||
      !IsAsciiIdentifier(parsed.menu_id)) {
    return false;
  }
  parsed.version = source.version;
  parsed.items.reserve(source.item_count);
  uint32_t root_count = 0;
  if (!CountRoots(source.items, source.item_count, &root_count)) return false;
  WalkResult walk;
  if (!WalkItems(source.items, source.item_count, 0, root_count, 1, &parsed, &walk)) return false;
  if (walk.next != source.item_count || parsed.items.size() != source.item_count || walk.actionable == 0) {
    return false;
  }
  if (HasDuplicateCommandsOrAccelerators(parsed)) return false;
  parsed.actionable_count = walk.actionable;
  *output = std::move(parsed);
  return true;
}

void PushInvoked(State &state, kweb_menus_invocation_source source, kweb_menus_owner_kind owner_kind,
                 const std::string &owner_id, const std::string &popup_id, const std::string &command_id,
                 uint64_t tree_version) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_MENUS_EVENT_CAPACITY) return;
  kweb_menus_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_MENUS_ABI_VERSION;
  event.kind = KWEB_MENUS_EVENT_INVOKED;
  event.source = source;
  event.owner_kind = owner_kind;
  event.sequence = ++state.sequence;
  event.tree_version = tree_version;
  CopyBounded(event.owner_id, sizeof(event.owner_id), owner_id);
  CopyBounded(event.popup_id, sizeof(event.popup_id), popup_id);
  CopyBounded(event.command_id, sizeof(event.command_id), command_id);
  state.events.push_back(event);
}

void PushDismissed(State &state, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                   const std::string &popup_id, kweb_menus_dismiss_reason reason) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_MENUS_EVENT_CAPACITY) return;
  kweb_menus_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_MENUS_ABI_VERSION;
  event.kind = KWEB_MENUS_EVENT_DISMISSED;
  event.dismiss_reason = reason;
  event.owner_kind = owner_kind;
  event.sequence = ++state.sequence;
  CopyBounded(event.owner_id, sizeof(event.owner_id), owner_id);
  CopyBounded(event.popup_id, sizeof(event.popup_id), popup_id);
  state.events.push_back(event);
}

void PushFailed(State &state, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_MENUS_EVENT_CAPACITY) return;
  kweb_menus_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_MENUS_ABI_VERSION;
  event.kind = KWEB_MENUS_EVENT_FAILED;
  event.owner_kind = owner_kind;
  event.sequence = ++state.sequence;
  CopyBounded(event.owner_id, sizeof(event.owner_id), owner_id);
  CopyBounded(event.code, sizeof(event.code), code);
  state.events.push_back(event);
}

void CompletePopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind, const std::string &owner_id,
                   const std::string &popup_id, const std::string &command_id,
                   kweb_menus_dismiss_reason reason, kweb_menus_popup_result *result) {
  if (result != nullptr) {
    result->struct_size = sizeof(*result);
    result->abi_version = KWEB_MENUS_ABI_VERSION;
    result->tree_version = tree.version;
    CopyBounded(result->popup_id, sizeof(result->popup_id), popup_id);
    if (!command_id.empty()) {
      result->outcome = KWEB_MENUS_OUTCOME_INVOKED;
      CopyBounded(result->command_id, sizeof(result->command_id), command_id);
    } else {
      result->outcome = KWEB_MENUS_OUTCOME_DISMISSED;
      result->dismiss_reason = reason;
    }
  }
  if (!command_id.empty()) {
    PushInvoked(state, KWEB_MENUS_SOURCE_POPUP, owner_kind, owner_id, popup_id, command_id, tree.version);
  } else {
    PushDismissed(state, owner_kind, owner_id, popup_id, reason);
  }
}

void ReportInvocation(State &state, kweb_menus_invocation_source source, kweb_menus_owner_kind owner_kind,
                      const std::string &owner_id, const std::string &command_id, uint64_t tree_version) {
  PushInvoked(state, source, owner_kind, owner_id, std::string(), command_id, tree_version);
}

}  // namespace kwebshell::menus

extern "C" {

uint32_t KWEB_MENUS_CALL kweb_menus_abi_version(void) {
  return KWEB_MENUS_ABI_VERSION;
}

uint32_t KWEB_MENUS_CALL kweb_menus_struct_size(uint32_t which) {
  switch (which) {
    case KWEB_MENUS_STRUCT_CONFIGURATION: return static_cast<uint32_t>(sizeof(kweb_menus_configuration));
    case KWEB_MENUS_STRUCT_ITEM: return static_cast<uint32_t>(sizeof(kweb_menus_item));
    case KWEB_MENUS_STRUCT_TREE: return static_cast<uint32_t>(sizeof(kweb_menus_tree));
    case KWEB_MENUS_STRUCT_POPUP_REQUEST: return static_cast<uint32_t>(sizeof(kweb_menus_popup_request));
    case KWEB_MENUS_STRUCT_POPUP_RESULT: return static_cast<uint32_t>(sizeof(kweb_menus_popup_result));
    case KWEB_MENUS_STRUCT_CAPABILITIES: return static_cast<uint32_t>(sizeof(kweb_menus_capabilities_result));
    case KWEB_MENUS_STRUCT_EVENT: return static_cast<uint32_t>(sizeof(kweb_menus_event));
    default: return 0;
  }
}

const char *KWEB_MENUS_CALL kweb_menus_status_name(kweb_menus_status status) {
  switch (status) {
    case KWEB_MENUS_STATUS_OK: return "ok";
    case KWEB_MENUS_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_MENUS_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_MENUS_STATUS_TREE_INVALID: return "tree-invalid";
    case KWEB_MENUS_STATUS_TREE_TOO_LARGE: return "tree-too-large";
    case KWEB_MENUS_STATUS_TREE_DEPTH_EXCEEDED: return "tree-depth-exceeded";
    case KWEB_MENUS_STATUS_COMMAND_DUPLICATE: return "command-duplicate";
    case KWEB_MENUS_STATUS_ACCELERATOR_INVALID: return "accelerator-invalid";
    case KWEB_MENUS_STATUS_ACCELERATOR_DUPLICATE: return "accelerator-duplicate";
    case KWEB_MENUS_STATUS_VERSION_STALE: return "version-stale";
    case KWEB_MENUS_STATUS_WINDOW_UNKNOWN: return "window-unknown";
    case KWEB_MENUS_STATUS_PAGE_UNKNOWN: return "page-unknown";
    case KWEB_MENUS_STATUS_MENU_NOT_DECLARED: return "menu-not-declared";
    case KWEB_MENUS_STATUS_POPUP_LIMIT: return "popup-limit";
    case KWEB_MENUS_STATUS_ANCHOR_INVALID: return "anchor-invalid";
    case KWEB_MENUS_STATUS_TARGET_UNSUPPORTED: return "target-unsupported";
    case KWEB_MENUS_STATUS_ICON_UNSUPPORTED: return "icon-unsupported";
    case KWEB_MENUS_STATUS_NATIVE_FAILED: return "native-failed";
    case KWEB_MENUS_STATUS_NO_EVENT: return "no-event";
    case KWEB_MENUS_STATUS_OWNER_CLOSED: return "owner-closed";
    case KWEB_MENUS_STATUS_ALREADY_OPEN: return "already-open";
    default: return "unknown";
  }
}

const char *KWEB_MENUS_CALL kweb_menus_provider_id(void) {
  return kwebshell::menus::ProviderId();
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_open(const kweb_menus_configuration *configuration,
                                                  uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr ||
      !ValidHeader(configuration->struct_size, configuration->abi_version, sizeof(kweb_menus_configuration))) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::menus::ReadString(configuration->application_id, KWEB_MENUS_MAX_ID, &application_id) ||
      !IsAsciiIdentifier(application_id) ||
      !kwebshell::menus::ReadString(configuration->package_identity, KWEB_MENUS_MAX_LABEL, &package_identity)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_MENUS_STATUS_ALREADY_OPEN;
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.sequence = 0;
    g_state.popup_counter = 0;
    g_state.open_popups = 0;
    g_state.events.clear();
    g_state.has_application_menu = false;
    g_state.application_menu = Tree();
    g_state.window_menus.clear();
    g_state.page_menus.clear();
    g_state.open = true;
  }
  const kweb_menus_status status = kwebshell::menus::NativeOpen(g_state);
  if (status != KWEB_MENUS_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.open = false;
    return status;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_capabilities(uint64_t handle,
                                                          kweb_menus_capabilities_result *result) {
  if (handle != 1 || result == nullptr ||
      !ValidHeader(result->struct_size, result->abi_version, sizeof(kweb_menus_capabilities_result))) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  ResetCapabilities(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  return kwebshell::menus::NativeCapabilities(g_state, result);
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_set_application_menu(uint64_t handle,
                                                                 const kweb_menus_tree *tree) {
  if (handle != 1) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  Tree parsed;
  if (tree != nullptr && !kwebshell::menus::ParseTree(*tree, &parsed)) return KWEB_MENUS_STATUS_TREE_INVALID;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  if (tree != nullptr && g_state.has_application_menu && parsed.version <= g_state.application_menu.version) {
    return KWEB_MENUS_STATUS_VERSION_STALE;
  }
  const kweb_menus_status status =
      kwebshell::menus::NativeSetApplicationMenu(g_state, tree == nullptr ? nullptr : &parsed);
  if (status != KWEB_MENUS_STATUS_OK) return status;
  if (tree == nullptr) {
    g_state.has_application_menu = false;
    g_state.application_menu = Tree();
  } else {
    g_state.has_application_menu = true;
    g_state.application_menu = std::move(parsed);
  }
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_set_window_menu(uint64_t handle, kweb_menus_string window_id,
                                                             uint64_t native_window,
                                                             const kweb_menus_tree *tree) {
  if (handle != 1) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  std::string id;
  if (!kwebshell::menus::ReadString(window_id, KWEB_MENUS_MAX_ID, &id) || !IsAsciiIdentifier(id)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  Tree parsed;
  if (tree != nullptr && !kwebshell::menus::ParseTree(*tree, &parsed)) return KWEB_MENUS_STATUS_TREE_INVALID;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  const auto existing = g_state.window_menus.find(id);
  if (tree != nullptr && existing != g_state.window_menus.end() && parsed.version <= existing->second.version) {
    return KWEB_MENUS_STATUS_VERSION_STALE;
  }
  const kweb_menus_status status =
      kwebshell::menus::NativeSetWindowMenu(g_state, id, native_window, tree == nullptr ? nullptr : &parsed);
  if (status != KWEB_MENUS_STATUS_OK) return status;
  if (tree == nullptr) {
    g_state.window_menus.erase(id);
  } else {
    g_state.window_menus[id] = std::move(parsed);
  }
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_declare_page_menu(uint64_t handle, kweb_menus_string page_token,
                                                               const kweb_menus_tree *tree) {
  if (handle != 1 || tree == nullptr) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  std::string token;
  if (!kwebshell::menus::ReadString(page_token, KWEB_MENUS_MAX_ID, &token) || !IsAsciiIdentifier(token)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  Tree parsed;
  if (!kwebshell::menus::ParseTree(*tree, &parsed)) return KWEB_MENUS_STATUS_TREE_INVALID;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  const std::string key = token + "\n" + parsed.menu_id;
  const auto existing = g_state.page_menus.find(key);
  if (existing != g_state.page_menus.end() && parsed.version <= existing->second.version) {
    return KWEB_MENUS_STATUS_VERSION_STALE;
  }
  g_state.page_menus[key] = std::move(parsed);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_clear_page_menu(uint64_t handle, kweb_menus_string page_token,
                                                              kweb_menus_string menu_id) {
  if (handle != 1) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  std::string token;
  std::string menu;
  if (!kwebshell::menus::ReadString(page_token, KWEB_MENUS_MAX_ID, &token) || !IsAsciiIdentifier(token) ||
      !kwebshell::menus::ReadString(menu_id, KWEB_MENUS_MAX_ID, &menu) || !IsAsciiIdentifier(menu)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  g_state.page_menus.erase(token + "\n" + menu);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_show_popup(uint64_t handle, const kweb_menus_popup_request *request,
                                                        kweb_menus_popup_result *result) {
  if (handle != 1 || request == nullptr || result == nullptr ||
      !ValidHeader(request->struct_size, request->abi_version, sizeof(kweb_menus_popup_request))) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  std::string menu_id;
  std::string owner_id;
  if (!kwebshell::menus::ReadString(request->menu_id, KWEB_MENUS_MAX_ID, &menu_id) ||
      !IsAsciiIdentifier(menu_id) ||
      !kwebshell::menus::ReadString(request->owner_id, KWEB_MENUS_MAX_ID, &owner_id)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  if (request->owner_kind < KWEB_MENUS_OWNER_APPLICATION || request->owner_kind > KWEB_MENUS_OWNER_PAGE) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  if (request->owner_kind == KWEB_MENUS_OWNER_APPLICATION) {
    if (!owner_id.empty()) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  } else if (!IsAsciiIdentifier(owner_id)) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  if (request->source < KWEB_MENUS_POPUP_SOURCE_HOST || request->source > KWEB_MENUS_POPUP_SOURCE_PAGE_CONTEXT) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  if (request->screen_x < -32768 || request->screen_x > 32767 || request->screen_y < -32768 ||
      request->screen_y > 32767) {
    return KWEB_MENUS_STATUS_ANCHOR_INVALID;
  }
  if (request->owner_kind != KWEB_MENUS_OWNER_APPLICATION && request->native_window == 0) {
    return KWEB_MENUS_STATUS_ANCHOR_INVALID;
  }

  Tree tree;
  std::string popup_id;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
    switch (request->owner_kind) {
      case KWEB_MENUS_OWNER_APPLICATION:
        if (!g_state.has_application_menu || g_state.application_menu.menu_id != menu_id) {
          return KWEB_MENUS_STATUS_MENU_NOT_DECLARED;
        }
        tree = g_state.application_menu;
        break;
      case KWEB_MENUS_OWNER_WINDOW: {
        const auto found = g_state.window_menus.find(owner_id);
        if (found == g_state.window_menus.end()) return KWEB_MENUS_STATUS_WINDOW_UNKNOWN;
        if (found->second.menu_id != menu_id) return KWEB_MENUS_STATUS_MENU_NOT_DECLARED;
        tree = found->second;
        break;
      }
      case KWEB_MENUS_OWNER_PAGE: {
        const auto found = g_state.page_menus.find(owner_id + "\n" + menu_id);
        if (found == g_state.page_menus.end()) return KWEB_MENUS_STATUS_MENU_NOT_DECLARED;
        tree = found->second;
        break;
      }
      default:
        return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
    }
    if (g_state.open_popups >= KWEB_MENUS_MAX_OPEN_POPUPS) return KWEB_MENUS_STATUS_POPUP_LIMIT;
    g_state.open_popups += 1;
    g_state.popup_counter += 1;
    popup_id = "popup-" + std::to_string(g_state.popup_counter);
  }

  ResetPopupResult(result);
  result->tree_version = tree.version;
  kwebshell::menus::CopyBounded(result->popup_id, sizeof(result->popup_id), popup_id);
  const kweb_menus_status status = kwebshell::menus::NativeShowPopup(
      g_state, tree, request->owner_kind, owner_id, request->source, request->screen_x, request->screen_y,
      request->native_window, popup_id, result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open_popups > 0) g_state.open_popups -= 1;
  }
  return status;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_poll_event(uint64_t handle, kweb_menus_event *event) {
  if (handle != 1 || event == nullptr ||
      !ValidHeader(event->struct_size, event->abi_version, sizeof(kweb_menus_event))) {
    return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  }
  ResetEvent(event);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_MENUS_STATUS_NO_EVENT;
  *event = g_state.events.front();
  g_state.events.pop_front();
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status KWEB_MENUS_CALL kweb_menus_close(uint64_t handle) {
  if (handle != 1) return KWEB_MENUS_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_MENUS_STATUS_OWNER_CLOSED;
  const kweb_menus_status status = kwebshell::menus::NativeClose(g_state);
  g_state.open = false;
  g_state.events.clear();
  g_state.window_menus.clear();
  g_state.page_menus.clear();
  g_state.has_application_menu = false;
  g_state.application_menu = Tree();
  g_live_count.fetch_sub(1);
  return status;
}

uint32_t KWEB_MENUS_CALL kweb_menus_live_count(void) {
  return g_live_count.load();
}

}  // extern "C"
