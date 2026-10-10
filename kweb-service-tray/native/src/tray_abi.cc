#include "tray_internal.h"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <set>

namespace {

std::mutex g_registry_mutex;
kwebshell::tray::State g_state;
std::atomic<uint32_t> g_live_count{0};

bool ValidHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  return struct_size >= expected && abi_version == KWEB_TRAY_ABI_VERSION;
}

void ResetCapabilities(kweb_tray_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_TRAY_ABI_VERSION;
}

void ResetBounds(kweb_tray_bounds_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_TRAY_ABI_VERSION;
}

void ResetEvent(kweb_tray_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_TRAY_ABI_VERSION;
}

using kwebshell::tray::Menu;
using kwebshell::tray::MenuNode;

size_t SiblingBlockEnd(const kweb_tray_menu_item *items, uint32_t count, size_t start, uint32_t depth) {
  if (start >= count || depth > 8) return count + 1;
  const kweb_tray_menu_item &node = items[start];
  size_t cursor = start + 1;
  if (node.kind == KWEB_TRAY_MENU_SUBMENU && node.submenu_count > 0) {
    for (uint32_t child = 0; child < node.submenu_count; ++child) {
      const size_t end = SiblingBlockEnd(items, count, cursor, depth + 1);
      if (end > count) return count + 1;
      cursor = end;
    }
  }
  return cursor;
}

bool CountMenuRoots(const kweb_tray_menu_item *items, uint32_t count, size_t *roots_out) {
  size_t cursor = 0;
  size_t roots = 0;
  while (cursor < count) {
    const size_t end = SiblingBlockEnd(items, count, cursor, 1);
    if (end <= cursor || end > count) return false;
    cursor = end;
    roots += 1;
    if (roots > KWEB_TRAY_MAX_TREE_ITEMS) return false;
  }
  if (roots == 0) return false;
  *roots_out = roots;
  return true;
}

bool WalkMenuBlock(const kweb_tray_menu_item *items, uint32_t count, size_t begin, size_t siblings,
                   uint32_t depth, Menu *menu, size_t *next_out) {
  if (siblings == 0 || depth > 8) return false;
  size_t cursor = begin;
  std::vector<size_t> starts;
  kweb_tray_menu_kind previous = KWEB_TRAY_MENU_COMMAND;
  for (size_t index = 0; index < siblings; ++index) {
    if (cursor >= count) return false;
    const kweb_tray_menu_item &node = items[cursor];
    if (!ValidHeader(node.struct_size, node.abi_version, sizeof(kweb_tray_menu_item))) return false;
    if (node.kind < KWEB_TRAY_MENU_COMMAND || node.kind > KWEB_TRAY_MENU_SUBMENU) return false;
    MenuNode parsed;
    parsed.kind = node.kind;
    parsed.enabled = (node.flags & KWEB_TRAY_MENU_FLAG_ENABLED) != 0;
    parsed.visible = (node.flags & KWEB_TRAY_MENU_FLAG_VISIBLE) != 0;
    parsed.checked = (node.flags & KWEB_TRAY_MENU_FLAG_CHECKED) != 0;
    if (!kwebshell::tray::ReadString(node.id, KWEB_TRAY_MAX_ID, &parsed.id)) return false;
    if (!kwebshell::tray::ReadString(node.label, KWEB_TRAY_MAX_LABEL, &parsed.label)) return false;
    if (node.kind == KWEB_TRAY_MENU_SEPARATOR) {
      if (!parsed.id.empty() || !parsed.label.empty() || node.submenu_count != 0 || parsed.checked) return false;
      if (index == 0 || index == siblings - 1 || previous == KWEB_TRAY_MENU_SEPARATOR) return false;
    } else {
      if (!kwebshell::tray::IsIdentifier(parsed.id) || parsed.label.empty()) return false;
      if (parsed.checked && node.kind != KWEB_TRAY_MENU_CHECKBOX && node.kind != KWEB_TRAY_MENU_RADIO) return false;
      if (node.kind != KWEB_TRAY_MENU_SUBMENU && node.submenu_count != 0) return false;
      if (node.kind == KWEB_TRAY_MENU_SUBMENU && node.submenu_count == 0) return false;
    }
    const size_t self_index = menu->items.size();
    if (self_index != cursor) return false;
    menu->items.push_back(parsed);
    cursor += 1;
    starts.push_back(self_index);
    if (node.kind == KWEB_TRAY_MENU_SUBMENU) {
      size_t child_next = 0;
      std::vector<size_t> children;
      size_t child_cursor = cursor;
      for (uint32_t child = 0; child < node.submenu_count; ++child) {
        children.push_back(child_cursor);
        if (!WalkMenuBlock(items, count, child_cursor, 1, depth + 1, menu, &child_next)) return false;
        child_cursor = child_next;
      }
      menu->items[self_index].children = std::move(children);
      cursor = child_cursor;
    }
    previous = node.kind;
  }
  if (next_out != nullptr) *next_out = cursor;
  if (begin == 0) menu->roots = std::move(starts);
  return true;
}

}  // namespace

namespace kwebshell::tray {

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

bool ReadString(const kweb_tray_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) || (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

bool IsIdentifier(const std::string &value) {
  if (value.empty() || value.size() > KWEB_TRAY_MAX_ID) return false;
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

bool IsResourceId(const std::string &value) {
  if (value.empty() || value.size() > KWEB_TRAY_MAX_RESOURCE) return false;
  if (value.front() == '/' || value.front() == '\\' || value.find('\\') != std::string::npos) return false;
  for (const char character : value) {
    const unsigned char byte = static_cast<unsigned char>(character);
    if (byte < 0x20 || byte == 0x7f) return false;
  }
  size_t start = 0;
  while (start <= value.size()) {
    const size_t next = value.find('/', start);
    const std::string component = value.substr(start, next == std::string::npos ? std::string::npos : next - start);
    if (component.empty() || component == "." || component == ".." || component.back() == '.' ||
        component.back() == ' ') {
      return false;
    }
    if (component.find_first_of("<>:\"|?*") != std::string::npos) return false;
    if (next == std::string::npos) break;
    start = next + 1;
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

kweb_tray_status ParseItem(const kweb_tray_item_spec &spec, Item *output) {
  if (output == nullptr || !ValidHeader(spec.struct_size, spec.abi_version, sizeof(kweb_tray_item_spec))) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  if (spec.activation_bits == 0 || (spec.activation_bits & ~0x7u) != 0) {
    return KWEB_TRAY_STATUS_ACTIVATION_UNSUPPORTED;
  }
  if (spec.icon_variant_count == 0 || spec.icon_variant_count > KWEB_TRAY_MAX_ICON_VARIANTS) {
    return KWEB_TRAY_STATUS_ICON_INVALID;
  }
  Item parsed;
  if (!ReadString(spec.id, KWEB_TRAY_MAX_ID, &parsed.id) || !IsIdentifier(parsed.id)) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  if (!ReadString(spec.tooltip, KWEB_TRAY_MAX_TOOLTIP, &parsed.tooltip)) return KWEB_TRAY_STATUS_TOOLTIP_INVALID;
  if (!parsed.tooltip.empty()) {
    if (parsed.tooltip.find_first_of("\r\n\t") != std::string::npos) return KWEB_TRAY_STATUS_TOOLTIP_INVALID;
    for (const char character : parsed.tooltip) {
      const unsigned char byte = static_cast<unsigned char>(character);
      if (byte < 0x20) return KWEB_TRAY_STATUS_TOOLTIP_INVALID;
    }
  }
  parsed.activation_bits = spec.activation_bits;
  std::set<uint32_t> scales;
  for (uint32_t index = 0; index < spec.icon_variant_count; ++index) {
    const kweb_tray_icon_variant &variant = spec.icon_variants[index];
    if (!ValidHeader(variant.struct_size, variant.abi_version, sizeof(kweb_tray_icon_variant))) {
      return KWEB_TRAY_STATUS_ICON_INVALID;
    }
    if (variant.scale == 0 || variant.scale > KWEB_TRAY_MAX_ICON_SCALE) return KWEB_TRAY_STATUS_ICON_INVALID;
    if (!scales.insert(variant.scale).second) return KWEB_TRAY_STATUS_ICON_INVALID;
    if (variant.template_icon > 1) return KWEB_TRAY_STATUS_ICON_INVALID;
    IconVariant parsed_variant;
    parsed_variant.scale = variant.scale;
    parsed_variant.template_icon = variant.template_icon == 1;
    if (!ReadString(variant.resource_id, KWEB_TRAY_MAX_RESOURCE, &parsed_variant.resource_id) ||
        !IsResourceId(parsed_variant.resource_id)) {
      return KWEB_TRAY_STATUS_ICON_INVALID;
    }
    if (!ReadString(variant.sha256, 64, &parsed_variant.sha256) || !IsSha256(parsed_variant.sha256)) {
      return KWEB_TRAY_STATUS_ICON_INVALID;
    }
    if (variant.width == 0 || variant.height == 0 || variant.width > KWEB_TRAY_MAX_ICON_DIMENSION ||
        variant.height > KWEB_TRAY_MAX_ICON_DIMENSION) {
      return KWEB_TRAY_STATUS_ICON_INVALID;
    }
    const size_t expected = static_cast<size_t>(variant.width) * variant.height * 4u;
    if (expected > KWEB_TRAY_MAX_ICON_BYTES || variant.pixels.size != expected || variant.pixels.data == nullptr) {
      return KWEB_TRAY_STATUS_ICON_INVALID;
    }
    parsed_variant.pixels.assign(variant.pixels.data, variant.pixels.data + variant.pixels.size);
    parsed_variant.width = variant.width;
    parsed_variant.height = variant.height;
    parsed.variants.push_back(std::move(parsed_variant));
  }
  *output = std::move(parsed);
  return KWEB_TRAY_STATUS_OK;
}

bool ParseMenu(const kweb_tray_menu_tree &tree, Menu *output) {
  if (output == nullptr || !ValidHeader(tree.struct_size, tree.abi_version, sizeof(kweb_tray_menu_tree))) {
    return false;
  }
  if (tree.version < 1 || tree.item_count == 0 || tree.item_count > KWEB_TRAY_MAX_TREE_ITEMS ||
      tree.items == nullptr) {
    return false;
  }
  Menu parsed;
  if (!ReadString(tree.menu_id, KWEB_TRAY_MAX_ID, &parsed.menu_id) || !IsIdentifier(parsed.menu_id)) return false;
  parsed.version = tree.version;
  // The flat array is a preorder forest; every top-level block is a sibling of
  // the root walk and the array must be consumed exactly once.
  size_t root_count = 0;
  if (!CountMenuRoots(tree.items, tree.item_count, &root_count)) return false;
  size_t consumed = 0;
  if (!WalkMenuBlock(tree.items, tree.item_count, 0, root_count, 1, &parsed, &consumed)) return false;
  if (consumed != tree.item_count || parsed.items.size() != tree.item_count) return false;
  std::set<std::string> commands;
  for (const MenuNode &node : parsed.items) {
    if (node.kind == KWEB_TRAY_MENU_SEPARATOR) continue;
    if (!commands.insert(node.id).second) return false;
  }
  *output = std::move(parsed);
  return true;
}

void PushActivated(State &state, const std::string &item_id, uint32_t activation_bit) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_TRAY_EVENT_CAPACITY) return;
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  event.kind = KWEB_TRAY_EVENT_ACTIVATED;
  event.activation = activation_bit;
  event.sequence = ++state.sequence;
  CopyBounded(event.item_id, sizeof(event.item_id), item_id);
  state.events.push_back(event);
}

void PushMenuCommand(State &state, const std::string &item_id, const std::string &menu_id,
                     const std::string &command_id, uint64_t tree_version) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_TRAY_EVENT_CAPACITY) return;
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  event.kind = KWEB_TRAY_EVENT_MENU_COMMAND;
  event.sequence = ++state.sequence;
  event.tree_version = tree_version;
  CopyBounded(event.item_id, sizeof(event.item_id), item_id);
  CopyBounded(event.menu_id, sizeof(event.menu_id), menu_id);
  CopyBounded(event.command_id, sizeof(event.command_id), command_id);
  state.events.push_back(event);
}

void PushBalloon(State &state, const std::string &item_id) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_TRAY_EVENT_CAPACITY) return;
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  event.kind = KWEB_TRAY_EVENT_BALLOON;
  event.sequence = ++state.sequence;
  CopyBounded(event.item_id, sizeof(event.item_id), item_id);
  state.events.push_back(event);
}

void PushRemoved(State &state, const std::string &item_id, kweb_tray_removal_reason reason) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_TRAY_EVENT_CAPACITY) return;
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  event.kind = KWEB_TRAY_EVENT_REMOVED;
  event.removal_reason = reason;
  event.sequence = ++state.sequence;
  CopyBounded(event.item_id, sizeof(event.item_id), item_id);
  state.events.push_back(event);
}

void PushFailed(State &state, const std::string &item_id, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  if (!state.open || state.events.size() >= KWEB_TRAY_EVENT_CAPACITY) return;
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  event.kind = KWEB_TRAY_EVENT_FAILED;
  event.sequence = ++state.sequence;
  CopyBounded(event.item_id, sizeof(event.item_id), item_id);
  CopyBounded(event.code, sizeof(event.code), code);
  state.events.push_back(event);
}

}  // namespace kwebshell::tray

extern "C" {

uint32_t KWEB_TRAY_CALL kweb_tray_abi_version(void) {
  return KWEB_TRAY_ABI_VERSION;
}

uint32_t KWEB_TRAY_CALL kweb_tray_struct_size(uint32_t which) {
  switch (which) {
    case KWEB_TRAY_STRUCT_CONFIGURATION: return static_cast<uint32_t>(sizeof(kweb_tray_configuration));
    case KWEB_TRAY_STRUCT_ICON_VARIANT: return static_cast<uint32_t>(sizeof(kweb_tray_icon_variant));
    case KWEB_TRAY_STRUCT_ITEM_SPEC: return static_cast<uint32_t>(sizeof(kweb_tray_item_spec));
    case KWEB_TRAY_STRUCT_MENU_ITEM: return static_cast<uint32_t>(sizeof(kweb_tray_menu_item));
    case KWEB_TRAY_STRUCT_MENU_TREE: return static_cast<uint32_t>(sizeof(kweb_tray_menu_tree));
    case KWEB_TRAY_STRUCT_CAPABILITIES: return static_cast<uint32_t>(sizeof(kweb_tray_capabilities_result));
    case KWEB_TRAY_STRUCT_BOUNDS: return static_cast<uint32_t>(sizeof(kweb_tray_bounds_result));
    case KWEB_TRAY_STRUCT_EVENT: return static_cast<uint32_t>(sizeof(kweb_tray_event));
    default: return 0;
  }
}

const char *KWEB_TRAY_CALL kweb_tray_status_name(kweb_tray_status status) {
  switch (status) {
    case KWEB_TRAY_STATUS_OK: return "ok";
    case KWEB_TRAY_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_TRAY_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_TRAY_STATUS_ITEM_EXISTS: return "item-exists";
    case KWEB_TRAY_STATUS_ITEM_UNKNOWN: return "item-unknown";
    case KWEB_TRAY_STATUS_ITEM_LIMIT: return "item-limit";
    case KWEB_TRAY_STATUS_ICON_INVALID: return "icon-invalid";
    case KWEB_TRAY_STATUS_ICON_UNSUPPORTED: return "icon-unsupported";
    case KWEB_TRAY_STATUS_TOOLTIP_INVALID: return "tooltip-invalid";
    case KWEB_TRAY_STATUS_TOOLTIP_UNSUPPORTED: return "tooltip-unsupported";
    case KWEB_TRAY_STATUS_ACTIVATION_UNSUPPORTED: return "activation-unsupported";
    case KWEB_TRAY_STATUS_MENU_INVALID: return "menu-invalid";
    case KWEB_TRAY_STATUS_MENU_UNSUPPORTED: return "menu-unsupported";
    case KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE: return "bounds-unavailable";
    case KWEB_TRAY_STATUS_NATIVE_FAILED: return "native-failed";
    case KWEB_TRAY_STATUS_NO_EVENT: return "no-event";
    case KWEB_TRAY_STATUS_OWNER_CLOSED: return "owner-closed";
    case KWEB_TRAY_STATUS_ALREADY_OPEN: return "already-open";
    default: return "unknown";
  }
}

const char *KWEB_TRAY_CALL kweb_tray_provider_id(void) {
  return kwebshell::tray::ProviderId();
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_open(const kweb_tray_configuration *configuration,
                                                uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr ||
      !ValidHeader(configuration->struct_size, configuration->abi_version, sizeof(kweb_tray_configuration))) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::tray::ReadString(configuration->application_id, KWEB_TRAY_MAX_ID, &application_id) ||
      !kwebshell::tray::IsIdentifier(application_id) ||
      !kwebshell::tray::ReadString(configuration->package_identity, KWEB_TRAY_MAX_ID, &package_identity)) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_TRAY_STATUS_ALREADY_OPEN;
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.sequence = 0;
    g_state.events.clear();
    g_state.items.clear();
    g_state.menus.clear();
    g_state.open = true;
  }
  const kweb_tray_status status = kwebshell::tray::NativeOpen(g_state);
  if (status != KWEB_TRAY_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.open = false;
    return status;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_capabilities(uint64_t handle,
                                                        kweb_tray_capabilities_result *result) {
  if (handle != 1 || result == nullptr ||
      !ValidHeader(result->struct_size, result->abi_version, sizeof(kweb_tray_capabilities_result))) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  ResetCapabilities(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  return kwebshell::tray::NativeCapabilities(g_state, result);
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_set_item(uint64_t handle, const kweb_tray_item_spec *spec,
                                                    uint32_t *created_out) {
  if (handle != 1 || spec == nullptr) return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  kwebshell::tray::Item parsed;
  const kweb_tray_status parsed_status = kwebshell::tray::ParseItem(*spec, &parsed);
  if (parsed_status != KWEB_TRAY_STATUS_OK) return parsed_status;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  const bool replacing = g_state.items.count(parsed.id) != 0;
  if (!replacing && g_state.items.size() >= KWEB_TRAY_MAX_ITEMS) return KWEB_TRAY_STATUS_ITEM_LIMIT;
  const kweb_tray_status status = kwebshell::tray::NativeSetItem(g_state, parsed, replacing);
  if (status != KWEB_TRAY_STATUS_OK) return status;
  g_state.items[parsed.id] = std::move(parsed);
  if (created_out != nullptr) *created_out = replacing ? 0u : 1u;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_close_item(uint64_t handle, kweb_tray_string item_id) {
  if (handle != 1) return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  std::string id;
  if (!kwebshell::tray::ReadString(item_id, KWEB_TRAY_MAX_ID, &id) || !kwebshell::tray::IsIdentifier(id)) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
    if (g_state.items.count(id) == 0) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
    const kweb_tray_status status = kwebshell::tray::NativeCloseItem(g_state, id);
    if (status != KWEB_TRAY_STATUS_OK) return status;
    g_state.items.erase(id);
    g_state.menus.erase(id);
  }
  // The ordered removal event reports the explicit close exactly once.
  kwebshell::tray::PushRemoved(g_state, id, KWEB_TRAY_REMOVAL_CLOSED);
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_set_menu(uint64_t handle, kweb_tray_string item_id,
                                                    const kweb_tray_menu_tree *tree) {
  if (handle != 1) return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  std::string id;
  if (!kwebshell::tray::ReadString(item_id, KWEB_TRAY_MAX_ID, &id) || !kwebshell::tray::IsIdentifier(id)) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  kwebshell::tray::Menu parsed;
  if (tree != nullptr && !kwebshell::tray::ParseMenu(*tree, &parsed)) return KWEB_TRAY_STATUS_MENU_INVALID;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  if (g_state.items.count(id) == 0) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  if (tree != nullptr) {
    const auto existing = g_state.menus.find(id);
    if (existing != g_state.menus.end() && parsed.version <= existing->second.version) {
      return KWEB_TRAY_STATUS_MENU_INVALID;
    }
  }
  const kweb_tray_status status =
      kwebshell::tray::NativeSetMenu(g_state, id, tree == nullptr ? nullptr : &parsed);
  if (status != KWEB_TRAY_STATUS_OK) return status;
  if (tree == nullptr) {
    g_state.menus.erase(id);
  } else {
    g_state.menus[id] = std::move(parsed);
  }
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_bounds(uint64_t handle, kweb_tray_string item_id,
                                                  kweb_tray_bounds_result *result) {
  if (handle != 1 || result == nullptr ||
      !ValidHeader(result->struct_size, result->abi_version, sizeof(kweb_tray_bounds_result))) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  std::string id;
  if (!kwebshell::tray::ReadString(item_id, KWEB_TRAY_MAX_ID, &id) || !kwebshell::tray::IsIdentifier(id)) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  ResetBounds(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  if (g_state.items.count(id) == 0) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  return kwebshell::tray::NativeBounds(g_state, id, result);
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_poll_event(uint64_t handle, kweb_tray_event *event) {
  if (handle != 1 || event == nullptr ||
      !ValidHeader(event->struct_size, event->abi_version, sizeof(kweb_tray_event))) {
    return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  }
  ResetEvent(event);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_TRAY_STATUS_NO_EVENT;
  *event = g_state.events.front();
  g_state.events.pop_front();
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status KWEB_TRAY_CALL kweb_tray_close(uint64_t handle) {
  if (handle != 1) return KWEB_TRAY_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_TRAY_STATUS_OWNER_CLOSED;
  const kweb_tray_status status = kwebshell::tray::NativeClose(g_state);
  g_state.open = false;
  g_state.events.clear();
  g_state.items.clear();
  g_state.menus.clear();
  g_live_count.fetch_sub(1);
  return status;
}

uint32_t KWEB_TRAY_CALL kweb_tray_live_count(void) {
  return g_live_count.load();
}

}  // extern "C"
