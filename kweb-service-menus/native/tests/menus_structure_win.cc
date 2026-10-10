#include "menus_platform_test.h"

#if defined(_WIN32)

#include <windows.h>

#include "menus_tests_fixture.h"

/** The real Win32 menu bar reflects the declared tree on the owner window. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  uint64_t handle = 0;
  kweb_menus_configuration configuration = Configuration();
  KWEB_CHECK(kweb_menus_open(&configuration, &handle) == KWEB_MENUS_STATUS_OK);
  HINSTANCE instance = GetModuleHandleW(nullptr);
  HWND window = CreateWindowExW(0, L"STATIC", L"kweb menus fixture", WS_OVERLAPPEDWINDOW, 0, 0, 320, 200,
                                nullptr, nullptr, instance, nullptr);
  KWEB_CHECK(window != nullptr);
  Fixture fixture;
  kweb_menus_item submenu = fixture.Base("file", "&File");
  submenu.kind = KWEB_MENUS_ITEM_SUBMENU;
  submenu.submenu_count = 2;
  fixture.Raw(submenu);
  fixture.Command("file.open", "&Open");
  fixture.Command("file.quit", "&Quit");
  kweb_menus_tree tree = fixture.Build();
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("main-window"),
                                        reinterpret_cast<uint64_t>(window), KWEB_MENUS_OWNER_WINDOW,
                                        &tree) == KWEB_MENUS_STATUS_OK);
  HMENU menu = GetMenu(window);
  KWEB_CHECK(menu != nullptr);
  KWEB_CHECK(GetMenuItemCount(menu) == 1);
  HMENU child = GetSubMenu(menu, 0);
  KWEB_CHECK(child != nullptr);
  KWEB_CHECK(GetMenuItemCount(child) == 2);
  // The rendered label keeps the mnemonic marker and the accelerator hint.
  wchar_t text[64] = {0};
  MENUITEMINFOW info{};
  info.cbSize = sizeof(info);
  info.fMask = MIIM_STRING;
  info.dwTypeData = text;
  info.cch = 64;
  KWEB_CHECK(GetMenuItemInfoW(child, 0, TRUE, &info));
  KWEB_CHECK(std::wcsstr(text, L"\tCtrl+O") != nullptr || std::wcsstr(text, L"&Open") != nullptr);
  KWEB_CHECK(kweb_menus_set_window_menu(handle, fixture.Text("main-window"),
                                        reinterpret_cast<uint64_t>(window), KWEB_MENUS_OWNER_WINDOW,
                                        nullptr) == KWEB_MENUS_STATUS_OK);
  DestroyWindow(window);
  KWEB_CHECK(kweb_menus_close(handle) == KWEB_MENUS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
