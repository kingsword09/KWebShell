#include "kweb_menus.h"

#include <cstdio>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

namespace {

int g_failures = 0;

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

kweb_menus_configuration Configuration() {
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

void TestAbiIdentity() {
  KWEB_CHECK(kweb_menus_abi_version() == KWEB_MENUS_ABI_VERSION);
  KWEB_CHECK(std::strlen(kweb_menus_provider_id()) > 0);
  KWEB_CHECK(std::strcmp(kweb_menus_status_name(KWEB_MENUS_STATUS_OK), "ok") == 0);
  KWEB_CHECK(std::strcmp(kweb_menus_status_name(KWEB_MENUS_STATUS_MENU_NOT_DECLARED),
                         "menu-not-declared") == 0);
  KWEB_CHECK(std::strcmp(kweb_menus_status_name(999), "unknown") == 0);
  KWEB_CHECK(kweb_menus_struct_size(KWEB_MENUS_STRUCT_ITEM) == sizeof(kweb_menus_item));
  KWEB_CHECK(kweb_menus_struct_size(KWEB_MENUS_STRUCT_TREE) == sizeof(kweb_menus_tree));
  KWEB_CHECK(kweb_menus_struct_size(KWEB_MENUS_STRUCT_POPUP_REQUEST) == sizeof(kweb_menus_popup_request));
  KWEB_CHECK(kweb_menus_struct_size(KWEB_MENUS_STRUCT_POPUP_RESULT) == sizeof(kweb_menus_popup_result));
  KWEB_CHECK(kweb_menus_struct_size(0) == 0);
  KWEB_CHECK(kweb_menus_live_count() == 0);
}

void TestLifecycle() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(handle == 1);
  KWEB_CHECK(kweb_menus_live_count() == 1);
  uint64_t second = 0;
  KWEB_CHECK(kweb_menus_open(&configuration, &second) == KWEB_MENUS_STATUS_ALREADY_OPEN);
  kweb_menus_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_MENUS_ABI_VERSION;
  KWEB_CHECK(kweb_menus_capabilities(handle, &capabilities) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_SUBMENUS) != 0);
  KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_POPUP_POSITIONING) != 0);
  // A provider that hosts menus in the desktop's shell also owns an
  // application-level menu; the reverse is not required.
  if ((capabilities.flags & KWEB_MENUS_CAP_GLOBAL_MENU_HOST) != 0) {
    KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_APPLICATION_MENU) != 0);
  }
#if defined(__APPLE__)
  KWEB_CHECK((capabilities.flags & KWEB_MENUS_CAP_APPLICATION_MENU) != 0);
  KWEB_CHECK((capabilities.native_roles & (1ull << (KWEB_MENUS_ROLE_ABOUT - 1))) != 0);
#endif
  kweb_menus_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_MENUS_ABI_VERSION;
  KWEB_CHECK(kweb_menus_poll_event(handle, &event) == KWEB_MENUS_STATUS_NO_EVENT);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_live_count() == 0);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OWNER_CLOSED);
  KWEB_CHECK(kweb_menus_capabilities(handle, &capabilities) == KWEB_MENUS_STATUS_OWNER_CLOSED);
}

void TestTreeValidation() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);

  Fixture duplicate;
  duplicate.Command("file.open", "Open");
  duplicate.Command("file.open", "Open again");
  kweb_menus_tree duplicate_tree = duplicate.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &duplicate_tree) == KWEB_MENUS_STATUS_TREE_INVALID);

  Fixture separator_first;
  separator_first.Separator();
  separator_first.Command("file.open", "Open");
  kweb_menus_tree separator_tree = separator_first.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &separator_tree) == KWEB_MENUS_STATUS_TREE_INVALID);

  Fixture bad_id;
  bad_id.Command("1nvalid", "Invalid");
  kweb_menus_tree bad_id_tree = bad_id.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &bad_id_tree) == KWEB_MENUS_STATUS_TREE_INVALID);

  Fixture bad_icon;
  kweb_menus_item icon_item = bad_icon.Base("file.open", "Open");
  icon_item.icon_id = bad_icon.Text("icons.open");
  icon_item.icon_sha256 = bad_icon.Text(std::string(64, 'a'));
  icon_item.icon_width = 2;
  icon_item.icon_height = 2;
  std::vector<uint8_t> short_pixels(8, 0);
  icon_item.icon_pixels.data = short_pixels.data();
  icon_item.icon_pixels.size = short_pixels.size();
  bad_icon.Raw(icon_item);
  kweb_menus_tree bad_icon_tree = bad_icon.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &bad_icon_tree) == KWEB_MENUS_STATUS_TREE_INVALID);

  // One submenu layer above the ceiling is rejected; the depth is proven by a
  // tree whose leaf sits beyond KWEB_MENUS_MAX_DEPTH nested levels.
  Fixture deep;
  deep.items.resize(1);
  deep.items[0] = deep.Base("deep.leaf", "Leaf");
  for (uint32_t level = 0; level < KWEB_MENUS_MAX_DEPTH; ++level) {
    std::vector<kweb_menus_item> wrapped;
    kweb_menus_item parent = deep.Base("menu." + std::to_string(level), "Menu");
    parent.kind = KWEB_MENUS_ITEM_SUBMENU;
    parent.submenu_count = 1;
    wrapped.push_back(parent);
    wrapped.insert(wrapped.end(), deep.items.begin(), deep.items.end());
    deep.items = wrapped;
  }
  kweb_menus_tree deep_tree = deep.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &deep_tree) == KWEB_MENUS_STATUS_TREE_INVALID);

  kweb_menus_tree empty{};
  empty.struct_size = sizeof(empty);
  empty.abi_version = KWEB_MENUS_ABI_VERSION;
  empty.version = 1;
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &empty) == KWEB_MENUS_STATUS_TREE_INVALID);

  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
}

void TestApplicationMenuVersions() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  kweb_menus_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_MENUS_ABI_VERSION;
  KWEB_CHECK(kweb_menus_capabilities(handle, &capabilities) == KWEB_MENUS_STATUS_OK);

  Fixture first;
  kweb_menus_item submenu = first.Base("file", "File");
  submenu.kind = KWEB_MENUS_ITEM_SUBMENU;
  submenu.submenu_count = 2;
  first.Raw(submenu);
  first.Command("file.open", "Open");
  first.Checkbox("file.autosave", "Autosave", true);
  kweb_menus_tree first_tree = first.Build();
  if ((capabilities.flags & KWEB_MENUS_CAP_APPLICATION_MENU) == 0) {
    // A provider without an application-level bar rejects the call typed and
    // leaves the window menu path to the service fan-out.
    KWEB_CHECK(kweb_menus_set_application_menu(handle, &first_tree) == KWEB_MENUS_STATUS_TARGET_UNSUPPORTED);
    KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
    return;
  }
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &first_tree) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &first_tree) == KWEB_MENUS_STATUS_VERSION_STALE);

  Fixture second;
  kweb_menus_item second_submenu = second.Base("file", "File");
  second_submenu.kind = KWEB_MENUS_ITEM_SUBMENU;
  second_submenu.submenu_count = 2;
  second.Raw(second_submenu);
  second.Command("file.open", "Open file");
  second.Checkbox("file.autosave", "Autosave", true);
  second.version = 2;
  kweb_menus_tree second_tree = second.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &second_tree) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_set_application_menu(handle, nullptr) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
}

void TestWindowMenuBoundary() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  Fixture fixture;
  fixture.Command("view.reload", "Reload");
  kweb_menus_tree tree = fixture.Build();
  const kweb_menus_status status = kweb_menus_set_window_menu(
      handle, fixture.Text("main-window"), 42, KWEB_MENUS_OWNER_WINDOW, &tree);
  const kweb_menus_status clearing =
      kweb_menus_set_window_menu(handle, fixture.Text("main-window"), 42, KWEB_MENUS_OWNER_WINDOW, nullptr);
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("main-window"), 42,
                                        KWEB_MENUS_OWNER_PAGE, nullptr) == KWEB_MENUS_STATUS_INVALID_ARGUMENT);
  KWEB_CHECK(status == KWEB_MENUS_STATUS_TARGET_UNSUPPORTED || status == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(clearing == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("1nvalid"), 42, KWEB_MENUS_OWNER_WINDOW, nullptr) ==
             KWEB_MENUS_STATUS_INVALID_ARGUMENT);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
}

void TestPageMenusAndPopupValidation() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  Fixture fixture;
  fixture.menu_id = "editor.context";
  fixture.Command("editor.copy", "Copy");
  fixture.Separator();
  fixture.Command("editor.paste", "Paste");
  kweb_menus_tree tree = fixture.Build();
  KWEB_CHECK(kweb_menus_declare_page_menu(handle, fixture.Text("page-token"), &tree) ==
             KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(kweb_menus_declare_page_menu(handle, fixture.Text("page-token"), &tree) ==
             KWEB_MENUS_STATUS_VERSION_STALE);

  kweb_menus_popup_result result{};
  result.struct_size = sizeof(result);
  result.abi_version = KWEB_MENUS_ABI_VERSION;
  kweb_menus_popup_request request{};
  request.struct_size = sizeof(request);
  request.abi_version = KWEB_MENUS_ABI_VERSION;
  request.menu_id = fixture.Text("editor.missing");
  request.owner_kind = KWEB_MENUS_OWNER_PAGE;
  request.owner_id = fixture.Text("page-token");
  request.source = KWEB_MENUS_POPUP_SOURCE_RENDERER;
  request.screen_x = 10;
  request.screen_y = 20;
  request.native_window = 7;
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_MENU_NOT_DECLARED);
  request.menu_id = fixture.Text("editor.context");
  request.screen_x = 40000;
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_ANCHOR_INVALID);
  request.screen_x = 10;
  request.owner_kind = KWEB_MENUS_OWNER_WINDOW;
  request.owner_id = fixture.Text("main-window");
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_WINDOW_UNKNOWN);
  request.owner_kind = KWEB_MENUS_OWNER_PAGE;
  request.owner_id = fixture.Text("page-token");
  request.native_window = 0;
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_ANCHOR_INVALID);
  request.native_window = 7;
  request.owner_kind = KWEB_MENUS_OWNER_APPLICATION;
  request.owner_id = kweb_menus_string{};
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_MENU_NOT_DECLARED);

  KWEB_CHECK(kweb_menus_clear_page_menu(handle, fixture.Text("page-token"), fixture.Text("editor.context")) ==
             KWEB_MENUS_STATUS_OK);
  request.owner_kind = KWEB_MENUS_OWNER_PAGE;
  request.owner_id = fixture.Text("page-token");
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_MENU_NOT_DECLARED);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
}

void TestRejectedPresentationQueuesNoEvent() {
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  Fixture fixture;
  fixture.menu_id = "page.menu";
  fixture.Command("file.open", "Open");
  kweb_menus_tree tree = fixture.Build();
  KWEB_CHECK(kweb_menus_declare_page_menu(handle, fixture.Text("page-token"), &tree) ==
             KWEB_MENUS_STATUS_OK);

  kweb_menus_popup_result result{};
  result.struct_size = sizeof(result);
  result.abi_version = KWEB_MENUS_ABI_VERSION;
  kweb_menus_popup_request request{};
  request.struct_size = sizeof(request);
  request.abi_version = KWEB_MENUS_ABI_VERSION;
  // The declared tree uses a different identifier, so presentation is rejected
  // before any native popup exists and never blocks on a real menu.
  request.menu_id = fixture.Text("app.other");
  request.owner_kind = KWEB_MENUS_OWNER_APPLICATION;
  request.source = KWEB_MENUS_POPUP_SOURCE_HOST;
  request.screen_x = 5;
  request.screen_y = 6;
  request.native_window = 3;
  KWEB_CHECK(kweb_menus_show_popup(handle, &request, &result) == KWEB_MENUS_STATUS_MENU_NOT_DECLARED);
  kweb_menus_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_MENUS_ABI_VERSION;
  KWEB_CHECK(kweb_menus_poll_event(handle, &event) == KWEB_MENUS_STATUS_NO_EVENT);

  kweb_menus_popup_request invalid = request;
  invalid.abi_version = 99;
  KWEB_CHECK(kweb_menus_show_popup(handle, &invalid, &result) == KWEB_MENUS_STATUS_INVALID_ARGUMENT);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
}

}  // namespace

int main() {
  TestAbiIdentity();
  TestLifecycle();
  TestTreeValidation();
  TestApplicationMenuVersions();
  TestWindowMenuBoundary();
  TestPageMenusAndPopupValidation();
  TestRejectedPresentationQueuesNoEvent();
  if (g_failures != 0) {
    std::printf("%d menus native checks failed.\n", g_failures);
    return 1;
  }
  std::printf("kweb menus native checks passed.\n");
  return 0;
}
