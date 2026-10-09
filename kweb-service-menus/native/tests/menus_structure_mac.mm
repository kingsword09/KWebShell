#include "menus_platform_test.h"

#if defined(__APPLE__)

#import <AppKit/AppKit.h>

#include "menus_tests_fixture.h"

/** The real AppKit main menu reflects the declared tree, not an empty stub. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  Fixture fixture;
  kweb_menus_item submenu = fixture.Base("file", "File");
  submenu.kind = KWEB_MENUS_ITEM_SUBMENU;
  submenu.submenu_count = 2;
  fixture.Raw(submenu);
  fixture.Command("file.open", "Open");
  fixture.Command("file.quit", "Quit");
  kweb_menus_tree tree = fixture.Build();
  KWEB_CHECK(kweb_menus_set_application_menu(handle, &tree) == KWEB_MENUS_STATUS_OK);
  KWEB_CHECK(NSApp.mainMenu != nil);
  KWEB_CHECK(NSApp.mainMenu.numberOfItems == 1);
  NSMenuItem *file_item = [NSApp.mainMenu itemAtIndex:0];
  KWEB_CHECK(file_item != nil && file_item.hasSubmenu);
  KWEB_CHECK(file_item.submenu.numberOfItems == 2);
  KWEB_CHECK([file_item.submenu itemAtIndex:0].title != nil);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
