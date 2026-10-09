#ifndef KWEB_MENUS_TESTS_FIXTURE_H_
#define KWEB_MENUS_TESTS_FIXTURE_H_

#include "kweb_menus.h"

#include <cstdio>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

/** One shared assertion counter so every translation unit reports one total. */
inline int g_failures = 0;

#define KWEB_CHECK(condition)                                                                    \
  do {                                                                                           \
    if (!(condition)) {                                                                          \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition);                            \
      g_failures += 1;                                                                           \
    }                                                                                            \
  } while (false)

/**
 * Owns every string an ABI structure points at. The pool is a deque so each
 * stored string keeps its address while later items are appended.
 */
struct Fixture {
  std::deque<std::string> pool;
  std::vector<kweb_menus_item> items;
  std::string menu_id = "app.menu";
  uint64_t version = 1;

  kweb_menus_string Text(const std::string &value) {
    pool.push_back(value);
    kweb_menus_string result{};
    result.data = reinterpret_cast<const uint8_t *>(pool.back().data());
    result.size = pool.back().size();
    return result;
  }

  kweb_menus_item Base(const std::string &id, const std::string &label) {
    kweb_menus_item item{};
    item.struct_size = sizeof(item);
    item.abi_version = KWEB_MENUS_ABI_VERSION;
    item.kind = KWEB_MENUS_ITEM_COMMAND;
    item.flags = KWEB_MENUS_ITEM_FLAG_ENABLED | KWEB_MENUS_ITEM_FLAG_VISIBLE;
    item.role = KWEB_MENUS_ROLE_NONE;
    item.id = Text(id);
    item.label = Text(label);
    return item;
  }

  void Command(const std::string &id, const std::string &label) {
    items.push_back(Base(id, label));
  }

  void Checkbox(const std::string &id, const std::string &label, bool checked) {
    kweb_menus_item item = Base(id, label);
    item.kind = KWEB_MENUS_ITEM_CHECKBOX;
    if (checked) item.flags |= KWEB_MENUS_ITEM_FLAG_CHECKED;
    items.push_back(item);
  }

  void Separator() {
    kweb_menus_item item{};
    item.struct_size = sizeof(item);
    item.abi_version = KWEB_MENUS_ABI_VERSION;
    item.kind = KWEB_MENUS_ITEM_SEPARATOR;
    items.push_back(item);
  }

  void Raw(const kweb_menus_item &item) { items.push_back(item); }

  kweb_menus_tree Build() {
    kweb_menus_tree tree{};
    tree.struct_size = sizeof(tree);
    tree.abi_version = KWEB_MENUS_ABI_VERSION;
    tree.version = version;
    tree.item_count = static_cast<uint32_t>(items.size());
    tree.menu_id = Text(menu_id);
    tree.items = items.data();
    return tree;
  }
};

inline kweb_menus_configuration Configuration() {
  static const char kApplicationId[] = "io.github.kingsword09.kwebshell.fixture";
  static const char kPackageIdentity[] = "kwebshell-fixture";
  kweb_menus_configuration configuration{};
  configuration.struct_size = sizeof(configuration);
  configuration.abi_version = KWEB_MENUS_ABI_VERSION;
  configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
  configuration.application_id.size = sizeof(kApplicationId) - 1u;
  configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
  configuration.package_identity.size = sizeof(kPackageIdentity) - 1u;
  return configuration;
}


#endif
