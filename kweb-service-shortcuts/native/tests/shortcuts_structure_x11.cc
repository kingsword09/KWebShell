#include "shortcuts_platform_test.h"

#if defined(__linux__)

#include <X11/Xlib.h>
#include <X11/keysym.h>

#include "shortcuts_tests_fixture.h"

#include <cstring>
#include <string>

namespace {

bool g_test_error = false;

int TestErrorHandler(Display *, XErrorEvent *) {
  g_test_error = true;
  return 0;
}

}  // namespace

/** The real X11 grab, conflict, and activation facts. */
int RunX11StructureTest() {
  const int failures_before = g_failures;
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  const kweb_shortcuts_status opened = kweb_shortcuts_open(&configuration, &handle);
  if (opened != KWEB_SHORTCUTS_STATUS_OK) {
    std::printf("SKIP: the X11 shortcut session is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_shortcuts_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "shortcuts.linux.x11-grab") == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_MEDIA_KEYS) != 0);
  KWEB_CHECK(capabilities.media_key_bits == KWEB_SHORTCUTS_MEDIA_ALL);

  const uint32_t combo = KWEB_SHORTCUTS_MOD_CONTROL | KWEB_SHORTCUTS_MOD_ALT;

  // Another X client already owns the combination, so the provider must report a
  // typed conflict instead of grabbing over it.
  Display *other = XOpenDisplay(nullptr);
  KWEB_CHECK(other != nullptr);
  XErrorHandler previous = nullptr;
  if (other != nullptr) {
    previous = XSetErrorHandler(TestErrorHandler);
    const Window root = RootWindow(other, DefaultScreen(other));
    const KeyCode other_code = XKeysymToKeycode(other, XK_A);
    XGrabKey(other, other_code, ControlMask | Mod1Mask, root, True, GrabModeAsync, GrabModeAsync);
    XSync(other, False);
    KWEB_CHECK(!g_test_error);
    XSetErrorHandler(previous);
  }

  kweb_shortcuts_registration_result result{};
  auto copy = fixture.Request("copy", "text.copy", combo, KWEB_SHORTCUTS_KEY_NAMED,
                              'A');
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_CONFLICT);

  if (other != nullptr) {
    const Window root = RootWindow(other, DefaultScreen(other));
    XUngrabKey(other, XKeysymToKeycode(other, XK_A), ControlMask | Mod1Mask, root);
    XSync(other, False);
  }
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_OK);

  // A delivered root KeyPress drives the provider's activation path exactly once.
  if (other != nullptr) {
    const Window root = RootWindow(other, DefaultScreen(other));
    XKeyEvent event{};
    event.type = KeyPress;
    event.display = other;
    event.window = root;
    event.root = root;
    event.subwindow = None;
    event.time = CurrentTime;
    event.x = 1;
    event.y = 1;
    event.x_root = 1;
    event.y_root = 1;
    event.same_screen = True;
    event.keycode = XKeysymToKeycode(other, XK_A);
    event.state = ControlMask | Mod1Mask;
    XSendEvent(other, root, True, KeyPressMask, reinterpret_cast<XEvent *>(&event));
    XSync(other, False);
    kweb_shortcuts_event delivered{};
    delivered.struct_size = sizeof(delivered);
    delivered.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    const bool invoked = fixture.AwaitEvent(handle, &delivered, 2000);
    KWEB_CHECK(invoked);
    if (invoked) {
      KWEB_CHECK(delivered.kind == KWEB_SHORTCUTS_EVENT_INVOKED);
      KWEB_CHECK(delivered.source == KWEB_SHORTCUTS_SOURCE_PLATFORM);
      KWEB_CHECK(std::strcmp(delivered.command_id, "text.copy") == 0);
    }
  }

  kweb_shortcuts_unregistration_result removed{};
  KWEB_CHECK(fixture.Unregister(handle, "copy", &removed) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(removed.was_registered == 1u);
  if (other != nullptr) XCloseDisplay(other);
  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
  return g_failures - failures_before;
}

#endif
