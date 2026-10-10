#ifndef KWEB_TRAY_TESTS_FIXTURE_H_
#define KWEB_TRAY_TESTS_FIXTURE_H_

#include "kweb_tray.h"

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

/** Owns every string and pixel buffer an ABI structure points at. */
struct TrayFixture {
  std::deque<std::string> pool;
  std::deque<std::vector<uint8_t>> pixels;
  std::deque<std::vector<kweb_tray_menu_item>> menu_items;

  kweb_tray_string Text(const std::string &value) {
    pool.push_back(value);
    kweb_tray_string result{};
    result.data = reinterpret_cast<const uint8_t *>(pool.back().data());
    result.size = pool.back().size();
    return result;
  }

  kweb_tray_string Pixels(uint32_t width, uint32_t height) {
    pixels.push_back(std::vector<uint8_t>(static_cast<size_t>(width) * height * 4u, 0xff));
    kweb_tray_string result{};
    result.data = pixels.back().data();
    result.size = pixels.back().size();
    return result;
  }

  kweb_tray_icon_variant Variant(const std::string &resource, uint32_t scale, uint32_t width) {
    kweb_tray_icon_variant variant{};
    variant.struct_size = sizeof(variant);
    variant.abi_version = KWEB_TRAY_ABI_VERSION;
    variant.scale = scale;
    variant.template_icon = 0;
    variant.resource_id = Text(resource);
    variant.sha256 = Text(std::string(64, 'a'));
    variant.pixels = Pixels(width, width);
    variant.width = width;
    variant.height = width;
    return variant;
  }

  kweb_tray_item_spec Item(const std::string &id, const std::string &tooltip = "") {
    kweb_tray_item_spec spec{};
    spec.struct_size = sizeof(spec);
    spec.abi_version = KWEB_TRAY_ABI_VERSION;
    spec.activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY;
    spec.icon_variant_count = 1;
    spec.id = Text(id);
    spec.tooltip = Text(tooltip);
    spec.icon_variants[0] = Variant("icons/tray.png", 1, 2);
    return spec;
  }

  kweb_tray_menu_item MenuCommand(const std::string &id, const std::string &label) {
    kweb_tray_menu_item item{};
    item.struct_size = sizeof(item);
    item.abi_version = KWEB_TRAY_ABI_VERSION;
    item.kind = KWEB_TRAY_MENU_COMMAND;
    item.flags = KWEB_TRAY_MENU_FLAG_ENABLED | KWEB_TRAY_MENU_FLAG_VISIBLE;
    item.id = Text(id);
    item.label = Text(label);
    return item;
  }

  kweb_tray_menu_item MenuSeparator() {
    kweb_tray_menu_item item{};
    item.struct_size = sizeof(item);
    item.abi_version = KWEB_TRAY_ABI_VERSION;
    item.kind = KWEB_TRAY_MENU_SEPARATOR;
    return item;
  }

  kweb_tray_menu_tree Menu(const std::string &menu_id, uint64_t version,
                           std::vector<kweb_tray_menu_item> items) {
    menu_items.push_back(std::move(items));
    kweb_tray_menu_tree tree{};
    tree.struct_size = sizeof(tree);
    tree.abi_version = KWEB_TRAY_ABI_VERSION;
    tree.version = version;
    tree.item_count = static_cast<uint32_t>(menu_items.back().size());
    tree.menu_id = Text(menu_id);
    tree.items = menu_items.back().data();
    return tree;
  }
};

inline kweb_tray_configuration TrayConfiguration() {
  static const char kApplicationId[] = "io.github.kingsword09.kwebshell.tray.fixture";
  static const char kPackageIdentity[] = "kwebshell-tray-fixture";
  kweb_tray_configuration configuration{};
  configuration.struct_size = sizeof(configuration);
  configuration.abi_version = KWEB_TRAY_ABI_VERSION;
  configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
  configuration.application_id.size = sizeof(kApplicationId) - 1u;
  configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
  configuration.package_identity.size = sizeof(kPackageIdentity) - 1u;
  return configuration;
}

#endif
