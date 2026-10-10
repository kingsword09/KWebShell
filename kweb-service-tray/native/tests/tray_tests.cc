#include "tray_platform_test.h"
#include "tray_tests_fixture.h"

#include <algorithm>
#include <map>
#include <string>

namespace {

/** Every check is provider-independent ABI behavior with a real provider. */
void TestIdentity() {
  KWEB_CHECK(kweb_tray_abi_version() == KWEB_TRAY_ABI_VERSION);
  KWEB_CHECK(std::strlen(kweb_tray_provider_id()) > 0);
  KWEB_CHECK(std::strcmp(kweb_tray_status_name(KWEB_TRAY_STATUS_OK), "ok") == 0);
  KWEB_CHECK(std::strcmp(kweb_tray_status_name(KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE), "bounds-unavailable") == 0);
  KWEB_CHECK(std::strcmp(kweb_tray_status_name(999), "unknown") == 0);
  KWEB_CHECK(kweb_tray_struct_size(KWEB_TRAY_STRUCT_ITEM_SPEC) == sizeof(kweb_tray_item_spec));
  KWEB_CHECK(kweb_tray_struct_size(KWEB_TRAY_STRUCT_MENU_TREE) == sizeof(kweb_tray_menu_tree));
  KWEB_CHECK(kweb_tray_struct_size(KWEB_TRAY_STRUCT_EVENT) == sizeof(kweb_tray_event));
  KWEB_CHECK(kweb_tray_struct_size(0) == 0);
  KWEB_CHECK(kweb_tray_live_count() == 0);
}

void TestLifecycleAndCapabilities() {
  uint64_t handle = 0;
  kweb_tray_configuration configuration = TrayConfiguration();
  KWEB_CHECK(kweb_tray_open(&configuration, &handle) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(handle == 1);
  KWEB_CHECK(kweb_tray_live_count() == 1);
  uint64_t second = 0;
  KWEB_CHECK(kweb_tray_open(&configuration, &second) == KWEB_TRAY_STATUS_ALREADY_OPEN);
  kweb_tray_capabilities_result capabilities{};
  capabilities.struct_size = sizeof(capabilities);
  capabilities.abi_version = KWEB_TRAY_ABI_VERSION;
  KWEB_CHECK(kweb_tray_capabilities(handle, &capabilities) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK((capabilities.flags & KWEB_TRAY_CAP_MENU) != 0);
  KWEB_CHECK((capabilities.activation_bits & KWEB_TRAY_ACTIVATION_PRIMARY) != 0);
  KWEB_CHECK(std::strlen(capabilities.provider_id) > 0);
  kweb_tray_event event{};
  event.struct_size = sizeof(event);
  event.abi_version = KWEB_TRAY_ABI_VERSION;
  KWEB_CHECK(kweb_tray_poll_event(handle, &event) == KWEB_TRAY_STATUS_NO_EVENT);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_live_count() == 0);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OWNER_CLOSED);
  KWEB_CHECK(kweb_tray_capabilities(handle, &capabilities) == KWEB_TRAY_STATUS_OWNER_CLOSED);
}

void TestItemValidation() {
  uint64_t handle = 0;
  kweb_tray_configuration configuration = TrayConfiguration();
  KWEB_CHECK(kweb_tray_open(&configuration, &handle) == KWEB_TRAY_STATUS_OK);
  TrayFixture fixture;
  uint32_t created = 0;

  kweb_tray_item_spec invalid_id = fixture.Item("1nvalid");
  KWEB_CHECK(kweb_tray_set_item(handle, &invalid_id, &created) == KWEB_TRAY_STATUS_INVALID_ARGUMENT);
  kweb_tray_item_spec no_activation = fixture.Item("status.item");
  no_activation.activation_bits = 0;
  KWEB_CHECK(kweb_tray_set_item(handle, &no_activation, &created) == KWEB_TRAY_STATUS_ACTIVATION_UNSUPPORTED);
  kweb_tray_item_spec long_tooltip = fixture.Item("status.item", std::string(80, 't'));
  KWEB_CHECK(kweb_tray_set_item(handle, &long_tooltip, &created) == KWEB_TRAY_STATUS_TOOLTIP_INVALID);
  kweb_tray_item_spec no_icon = fixture.Item("status.item");
  no_icon.icon_variant_count = 0;
  KWEB_CHECK(kweb_tray_set_item(handle, &no_icon, &created) == KWEB_TRAY_STATUS_ICON_INVALID);
  kweb_tray_item_spec duplicate_scale = fixture.Item("status.item");
  duplicate_scale.icon_variant_count = 2;
  duplicate_scale.icon_variants[1] = fixture.Variant("icons/tray.png", 1, 2);
  KWEB_CHECK(kweb_tray_set_item(handle, &duplicate_scale, &created) == KWEB_TRAY_STATUS_ICON_INVALID);
  kweb_tray_item_spec bad_resource = fixture.Item("status.item");
  bad_resource.icon_variants[0] = fixture.Variant("../tray.png", 1, 2);
  KWEB_CHECK(kweb_tray_set_item(handle, &bad_resource, &created) == KWEB_TRAY_STATUS_ICON_INVALID);
  kweb_tray_item_spec short_pixels = fixture.Item("status.item");
  short_pixels.icon_variants[0].pixels.size = 4;
  KWEB_CHECK(kweb_tray_set_item(handle, &short_pixels, &created) == KWEB_TRAY_STATUS_ICON_INVALID);

  // A provider whose declared host is absent (for example a Linux session
  // without a status-notifier watcher) rejects items with the typed
  // unavailable status; validation above still runs because it happens before
  // provider dispatch, and the platform structure test owns the host path.
  kweb_tray_item_spec valid = fixture.Item("status.item", "KWebShell");
  const kweb_tray_status first_status = kweb_tray_set_item(handle, &valid, &created);
  if (first_status == KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE) {
    std::printf("SKIP: the declared tray host is unavailable (status %u).\n",
                static_cast<unsigned>(first_status));
    KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
    return;
  }
  KWEB_CHECK(first_status == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(created == 1u);
  KWEB_CHECK(kweb_tray_set_item(handle, &valid, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(created == 0u);

  kweb_tray_item_spec second = fixture.Item("status.second");
  kweb_tray_item_spec third = fixture.Item("status.third");
  kweb_tray_item_spec fourth = fixture.Item("status.fourth");
  kweb_tray_item_spec fifth = fixture.Item("status.fifth");
  KWEB_CHECK(kweb_tray_set_item(handle, &second, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_set_item(handle, &third, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_set_item(handle, &fourth, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_set_item(handle, &fifth, &created) == KWEB_TRAY_STATUS_ITEM_LIMIT);

  kweb_tray_bounds_result bounds{};
  bounds.struct_size = sizeof(bounds);
  bounds.abi_version = KWEB_TRAY_ABI_VERSION;
  const kweb_tray_status bounds_status = kweb_tray_bounds(handle, fixture.Text("status.item"), &bounds);
  KWEB_CHECK(bounds_status == KWEB_TRAY_STATUS_OK ||
             bounds_status == KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE ||
             bounds_status == KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE);
  if (bounds_status == KWEB_TRAY_STATUS_OK) {
    KWEB_CHECK(bounds.width > 0 && bounds.height > 0);
  }
  KWEB_CHECK(kweb_tray_bounds(handle, fixture.Text("status.missing"), &bounds) == KWEB_TRAY_STATUS_ITEM_UNKNOWN);

  KWEB_CHECK(kweb_tray_close_item(handle, fixture.Text("status.item")) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_close_item(handle, fixture.Text("status.item")) == KWEB_TRAY_STATUS_ITEM_UNKNOWN);
  kweb_tray_event removed{};
  removed.struct_size = sizeof(removed);
  removed.abi_version = KWEB_TRAY_ABI_VERSION;
  KWEB_CHECK(kweb_tray_poll_event(handle, &removed) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(removed.kind == KWEB_TRAY_EVENT_REMOVED);
  KWEB_CHECK(removed.removal_reason == KWEB_TRAY_REMOVAL_CLOSED);
  KWEB_CHECK(std::strcmp(removed.item_id, "status.item") == 0);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
}

void TestMenuOwnership() {
  uint64_t handle = 0;
  kweb_tray_configuration configuration = TrayConfiguration();
  KWEB_CHECK(kweb_tray_open(&configuration, &handle) == KWEB_TRAY_STATUS_OK);
  TrayFixture fixture;
  uint32_t created = 0;
  kweb_tray_item_spec item = fixture.Item("status.item");
  const kweb_tray_status created_status = kweb_tray_set_item(handle, &item, &created);
  if (created_status == KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE) {
    std::printf("SKIP: the declared tray host is unavailable for the menu checks.\n");
    KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
    return;
  }
  KWEB_CHECK(created_status == KWEB_TRAY_STATUS_OK);

  kweb_tray_menu_tree tree = fixture.Menu(
      "tray.menu", 1,
      {fixture.MenuCommand("tray.open", "Open"), fixture.MenuSeparator(),
       fixture.MenuCommand("tray.quit", "Quit")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &tree) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &tree) == KWEB_TRAY_STATUS_MENU_INVALID);

  kweb_tray_menu_tree stale = fixture.Menu("tray.menu", 1, {fixture.MenuCommand("tray.open", "Open")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &stale) == KWEB_TRAY_STATUS_MENU_INVALID);

  kweb_tray_menu_tree newer =
      fixture.Menu("tray.menu", 2, {fixture.MenuCommand("tray.open", "Open")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &newer) == KWEB_TRAY_STATUS_OK);

  kweb_tray_menu_tree leading_separator =
      fixture.Menu("tray.bad", 1, {fixture.MenuSeparator(), fixture.MenuCommand("tray.open", "Open")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &leading_separator) ==
             KWEB_TRAY_STATUS_MENU_INVALID);

  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.missing"), &newer) == KWEB_TRAY_STATUS_ITEM_UNKNOWN);
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), nullptr) == KWEB_TRAY_STATUS_OK);

  // Closing the item clears its menu; a recreated item starts without one.
  KWEB_CHECK(kweb_tray_close_item(handle, fixture.Text("status.item")) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_set_item(handle, &item, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(created == 1u);
  kweb_tray_menu_tree fresh = fixture.Menu("tray.menu", 1, {fixture.MenuCommand("tray.open", "Open")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("status.item"), &fresh) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
}

}  // namespace

int main() {
  TestIdentity();
  TestLifecycleAndCapabilities();
  TestItemValidation();
  TestMenuOwnership();
  g_failures += RunPlatformNativeStructureTest();
  if (g_failures != 0) {
    std::printf("%d tray native checks failed.\n", g_failures);
    return 1;
  }
  std::printf("kweb tray native checks passed.\n");
  return 0;
}
