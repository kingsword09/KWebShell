#include "shortcuts_internal.h"

#if !defined(__linux__)
#error "The X11 shortcut provider must only be compiled on Linux."
#endif

#include <X11/Xlib.h>
#include <X11/keysym.h>
#include <X11/XF86keysym.h>

#include <atomic>
#include <thread>
#include <vector>

namespace {

using kwebshell::shortcuts::Registration;
using kwebshell::shortcuts::State;

/** One grabbed combination and the registration it represents. */
struct Entry {
  Registration registration;
  KeyCode keycode = 0;
  unsigned int modifiers = 0;
};

struct NativeState {
  Display *display = nullptr;
  Window root = 0;
  std::vector<Entry> entries;
  std::thread thread;
  std::atomic<bool> running{false};
};

NativeState *g_native = nullptr;
State *g_state = nullptr;
std::atomic<bool> g_grab_error{false};

/** The X11 keysym for one declared named or media key, or 0 when unmapped. */
KeySym KeySymOf(uint32_t key_kind, uint32_t key_code) {
  if (key_kind == KWEB_SHORTCUTS_KEY_MEDIA) {
    switch (key_code) {
      case KWEB_SHORTCUTS_MEDIA_PLAY_PAUSE: return XF86XK_AudioPlay;
      case KWEB_SHORTCUTS_MEDIA_NEXT_TRACK: return XF86XK_AudioNext;
      case KWEB_SHORTCUTS_MEDIA_PREVIOUS_TRACK: return XF86XK_AudioPrev;
      case KWEB_SHORTCUTS_MEDIA_STOP: return XF86XK_AudioStop;
      case KWEB_SHORTCUTS_MEDIA_VOLUME_UP: return XF86XK_AudioRaiseVolume;
      case KWEB_SHORTCUTS_MEDIA_VOLUME_DOWN: return XF86XK_AudioLowerVolume;
      case KWEB_SHORTCUTS_MEDIA_MUTE: return XF86XK_AudioMute;
      default: return 0;
    }
  }
  if (key_code >= 'A' && key_code <= 'Z') return XK_A + (key_code - 'A');
  if (key_code >= '0' && key_code <= '9') return XK_0 + (key_code - '0');
  if (key_code >= KWEB_SHORTCUTS_NAMED_F1 && key_code <= KWEB_SHORTCUTS_NAMED_F24) {
    return XK_F1 + (key_code - KWEB_SHORTCUTS_NAMED_F1);
  }
  switch (key_code) {
    case KWEB_SHORTCUTS_NAMED_SPACE: return XK_space;
    case KWEB_SHORTCUTS_NAMED_TAB: return XK_Tab;
    case KWEB_SHORTCUTS_NAMED_ENTER: return XK_Return;
    case KWEB_SHORTCUTS_NAMED_ESCAPE: return XK_Escape;
    case KWEB_SHORTCUTS_NAMED_BACKSPACE: return XK_BackSpace;
    case KWEB_SHORTCUTS_NAMED_DELETE: return XK_Delete;
    case KWEB_SHORTCUTS_NAMED_INSERT: return XK_Insert;
    case KWEB_SHORTCUTS_NAMED_HOME: return XK_Home;
    case KWEB_SHORTCUTS_NAMED_END: return XK_End;
    case KWEB_SHORTCUTS_NAMED_PAGE_UP: return XK_Page_Up;
    case KWEB_SHORTCUTS_NAMED_PAGE_DOWN: return XK_Page_Down;
    case KWEB_SHORTCUTS_NAMED_ARROW_LEFT: return XK_Left;
    case KWEB_SHORTCUTS_NAMED_ARROW_RIGHT: return XK_Right;
    case KWEB_SHORTCUTS_NAMED_ARROW_UP: return XK_Up;
    case KWEB_SHORTCUTS_NAMED_ARROW_DOWN: return XK_Down;
    case KWEB_SHORTCUTS_NAMED_MINUS: return XK_minus;
    case KWEB_SHORTCUTS_NAMED_EQUALS: return XK_equal;
    case KWEB_SHORTCUTS_NAMED_BRACKET_LEFT: return XK_bracketleft;
    case KWEB_SHORTCUTS_NAMED_BRACKET_RIGHT: return XK_bracketright;
    case KWEB_SHORTCUTS_NAMED_BACKSLASH: return XK_backslash;
    case KWEB_SHORTCUTS_NAMED_SEMICOLON: return XK_semicolon;
    case KWEB_SHORTCUTS_NAMED_QUOTE: return XK_apostrophe;
    case KWEB_SHORTCUTS_NAMED_COMMA: return XK_comma;
    case KWEB_SHORTCUTS_NAMED_PERIOD: return XK_period;
    case KWEB_SHORTCUTS_NAMED_SLASH: return XK_slash;
    case KWEB_SHORTCUTS_NAMED_BACKQUOTE: return XK_grave;
    default: return 0;
  }
}

unsigned int ModifiersOf(uint32_t bits) {
  unsigned int modifiers = 0;
  if ((bits & KWEB_SHORTCUTS_MOD_CONTROL) != 0) modifiers |= ControlMask;
  if ((bits & KWEB_SHORTCUTS_MOD_ALT) != 0) modifiers |= Mod1Mask;
  if ((bits & KWEB_SHORTCUTS_MOD_SHIFT) != 0) modifiers |= ShiftMask;
  if ((bits & KWEB_SHORTCUTS_MOD_META) != 0) modifiers |= Mod4Mask;
  return modifiers;
}

int OnGrabError(Display *, XErrorEvent *event) {
  if (event->error_code == BadAccess) g_grab_error.store(true);
  return 0;
}

/** Grabs a combination under the base and the lock/modifier variants. */
bool GrabCombination(Display *display, Window root, KeyCode keycode, unsigned int modifiers) {
  const unsigned int lock = LockMask;
  const unsigned int num_lock = Mod2Mask;
  const unsigned int variants[4] = {modifiers, modifiers | lock, modifiers | num_lock, modifiers | lock | num_lock};
  g_grab_error.store(false);
  for (unsigned int variant : variants) {
    XGrabKey(display, keycode, variant, root, True, GrabModeAsync, GrabModeAsync);
  }
  XSync(display, False);
  if (g_grab_error.load()) {
    // A rejected combination must not leave the lock-variant grabs behind.
    for (unsigned int variant : variants) {
      XUngrabKey(display, keycode, variant, root);
    }
    XSync(display, False);
    return false;
  }
  return true;
}

void UngrabCombination(Display *display, Window root, KeyCode keycode, unsigned int modifiers) {
  const unsigned int lock = LockMask;
  const unsigned int num_lock = Mod2Mask;
  const unsigned int variants[4] = {modifiers, modifiers | lock, modifiers | num_lock, modifiers | lock | num_lock};
  for (unsigned int variant : variants) {
    XUngrabKey(display, keycode, variant, root);
  }
  XSync(display, False);
}

/** The base modifier mask without the lock modifiers the desktop may add. */
unsigned int NormalizeState(unsigned int state) {
  return state & ~(LockMask | Mod2Mask);
}

void EventLoop() {
  NativeState *native = g_native;
  if (native == nullptr) return;
  while (native->running.load()) {
    while (XPending(native->display) > 0) {
      XEvent event{};
      XNextEvent(native->display, &event);
      if (event.type != KeyPress) continue;
      const unsigned int normalized = NormalizeState(event.xkey.state);
      for (const Entry &entry : native->entries) {
        if (entry.keycode == event.xkey.keycode && entry.modifiers == normalized) {
          if (g_state != nullptr) {
            kwebshell::shortcuts::PublishInvocation(*g_state, entry.registration,
                                                    KWEB_SHORTCUTS_SOURCE_PLATFORM);
          }
          break;
        }
      }
    }
    std::this_thread::sleep_for(std::chrono::milliseconds(5));
  }
}

}  // namespace

namespace kwebshell::shortcuts::x11_provider {

const char *ProviderId() { return "shortcuts.linux.x11-grab"; }

kweb_shortcuts_status Open(State &state) {
  XInitThreads();
  auto *native = new NativeState();
  native->display = XOpenDisplay(nullptr);
  if (native->display == nullptr) {
    delete native;
    return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  }
  native->root = DefaultRootWindow(native->display);
  XSelectInput(native->display, native->root, KeyPressMask);
  XSetErrorHandler(OnGrabError);
  native->running.store(true);
  native->thread = std::thread(EventLoop);
  state.platform = native;
  g_native = native;
  g_state = &state;
  state.capabilities.provider_id = ProviderId();
  state.capabilities.capability_bits = KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION |
                                       KWEB_SHORTCUTS_CAP_MEDIA_KEYS |
                                       KWEB_SHORTCUTS_CAP_ATOMIC_REPLACEMENT |
                                       KWEB_SHORTCUTS_CAP_INVOCATION_REPORTING;
  state.capabilities.modifier_bits = KWEB_SHORTCUTS_MOD_ALL;
  state.capabilities.media_key_bits = KWEB_SHORTCUTS_MEDIA_ALL;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status Register(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  const KeySym keysym = KeySymOf(registration.key_kind, registration.key_code);
  if (keysym == 0) return KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED;
  const KeyCode keycode = XKeysymToKeycode(native->display, keysym);
  if (keycode == 0) return KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED;
  const unsigned int modifiers = ModifiersOf(registration.modifier_bits);
  if (!GrabCombination(native->display, native->root, keycode, modifiers)) {
    return KWEB_SHORTCUTS_STATUS_CONFLICT;
  }
  Entry entry;
  entry.registration = registration;
  entry.keycode = keycode;
  entry.modifiers = modifiers;
  native->entries.push_back(entry);
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status Unregister(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  for (size_t index = 0; index < native->entries.size(); ++index) {
    if (native->entries[index].registration.id != registration.id) continue;
    UngrabCombination(native->display, native->root, native->entries[index].keycode,
                      native->entries[index].modifiers);
    native->entries.erase(native->entries.begin() + static_cast<long>(index));
    return KWEB_SHORTCUTS_STATUS_OK;
  }
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status Close(State &state) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_OK;
  native->running.store(false);
  if (native->thread.joinable()) native->thread.join();
  for (const Entry &entry : native->entries) {
    UngrabCombination(native->display, native->root, entry.keycode, entry.modifiers);
  }
  native->entries.clear();
  if (g_native == native) g_native = nullptr;
  if (g_state == &state) g_state = nullptr;
  if (native->display != nullptr) XCloseDisplay(native->display);
  delete native;
  state.platform = nullptr;
  return KWEB_SHORTCUTS_STATUS_OK;
}

}  // namespace kwebshell::shortcuts::x11_provider
