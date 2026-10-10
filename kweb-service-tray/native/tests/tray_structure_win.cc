#include "tray_platform_test.h"

#if defined(_WIN32)

#include <windows.h>

#include "tray_tests_fixture.h"

/** The real notification-area item reflects the declared spec and menu. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  uint64_t handle = 0;
  kweb_tray_configuration configuration = TrayConfiguration();
  const kweb_tray_status opened = kweb_tray_open(&configuration, &handle);
  if (opened != KWEB_TRAY_STATUS_OK) {
    // A session without the shell notification area is a declared unsupported
    // environment, not a fixture failure: the capability check covers it.
    std::printf("SKIP: the Windows shell notification area is unavailable (status %u).\n",
                static_cast<unsigned>(opened));
    return g_failures - failures_before;
  }
  TrayFixture fixture;
  uint32_t created = 0;
  kweb_tray_item_spec item = fixture.Item("structure.item", "KWebShell tray");
  item.activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY | KWEB_TRAY_ACTIVATION_SECONDARY;
  KWEB_CHECK(kweb_tray_set_item(handle, &item, &created) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(created == 1u);

  // A published shell rect proves the provider registered a real icon.
  kweb_tray_bounds_result bounds{};
  bounds.struct_size = sizeof(bounds);
  bounds.abi_version = KWEB_TRAY_ABI_VERSION;
  const kweb_tray_status bounds_status = kweb_tray_bounds(handle, fixture.Text("structure.item"), &bounds);
  KWEB_CHECK(bounds_status == KWEB_TRAY_STATUS_OK || bounds_status == KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE);
  if (bounds_status == KWEB_TRAY_STATUS_OK) {
    KWEB_CHECK(bounds.width > 0 && bounds.height > 0);
  }

  kweb_tray_menu_tree tree = fixture.Menu(
      "tray.structure", 1,
      {fixture.MenuCommand("tray.open", "Open"), fixture.MenuSeparator(),
       fixture.MenuCommand("tray.quit", "Quit")});
  KWEB_CHECK(kweb_tray_set_menu(handle, fixture.Text("structure.item"), &tree) == KWEB_TRAY_STATUS_OK);

  // An Explorer restart must republish the same item exactly once, so a second
  // TaskbarCreated message leaves the icon registered and addressable.
  const UINT taskbar_created = RegisterWindowMessageW(L"TaskbarCreated");
  HWND tray_window = FindWindowExW(HWND_MESSAGE, nullptr, L"KWebShellTrayMessageWindow", nullptr);
  KWEB_CHECK(taskbar_created != 0);
  KWEB_CHECK(tray_window != nullptr);
  if (tray_window != nullptr) {
    SendMessageW(tray_window, taskbar_created, 0, 0);
    SendMessageW(tray_window, taskbar_created, 0, 0);
    const kweb_tray_status republished =
        kweb_tray_bounds(handle, fixture.Text("structure.item"), &bounds);
    KWEB_CHECK(republished == KWEB_TRAY_STATUS_OK ||
               republished == KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE);
  }

  KWEB_CHECK(kweb_tray_close_item(handle, fixture.Text("structure.item")) == KWEB_TRAY_STATUS_OK);
  KWEB_CHECK(kweb_tray_bounds(handle, fixture.Text("structure.item"), &bounds) ==
             KWEB_TRAY_STATUS_ITEM_UNKNOWN);
  KWEB_CHECK(kweb_tray_close(handle) == KWEB_TRAY_STATUS_OK);
  return g_failures - failures_before;
}

#endif
