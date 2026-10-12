#include "shortcuts_internal.h"

#if !defined(__APPLE__)
#error "The macOS shortcut provider must only be compiled on macOS."
#endif

#import <Carbon/Carbon.h>

#include <cstdint>
#include <string>
#include <vector>

namespace {

constexpr char kProviderId[] = "shortcuts.macos.carbon-hotkey";
constexpr OSType kHotKeySignature = 'KWBS';

using kwebshell::shortcuts::Registration;
using kwebshell::shortcuts::State;

/** One installed hotkey and the registration it represents. */
struct Entry {
  Registration registration;
  uint32_t hotkey_id = 0;
  EventHotKeyRef ref = nullptr;
};

struct NativeState {
  EventHandlerRef handler = nullptr;
  std::vector<Entry> entries;
  uint32_t next_id = 1;
};

State *g_state = nullptr;
NativeState *g_native = nullptr;

/**
 * The Carbon virtual key code for one declared named key. `kVK_ANSI_A` is 0, so the
 * mapping reports validity separately instead of overloading 0 as "unmapped".
 */
bool CarbonKeyCode(uint32_t named, uint32_t *out) {
  uint32_t value = 0;
  if (named >= 'A' && named <= 'Z') {
    static const uint32_t kLetters[26] = {
        0x00, 0x0B, 0x08, 0x02, 0x0E, 0x03, 0x05, 0x04, 0x22, 0x26, 0x28, 0x25, 0x2E,
        0x2D, 0x1F, 0x23, 0x0C, 0x0F, 0x01, 0x11, 0x20, 0x09, 0x0D, 0x07, 0x10, 0x06,
    };
    value = kLetters[named - 'A'];
  } else if (named >= '0' && named <= '9') {
    static const uint32_t kDigits[10] = {0x1D, 0x12, 0x13, 0x14, 0x15, 0x17, 0x16, 0x1A, 0x1C, 0x19};
    value = kDigits[named - '0'];
  } else if (named >= KWEB_SHORTCUTS_NAMED_F1 && named <= KWEB_SHORTCUTS_NAMED_F1 + 19u) {
    static const uint32_t kFunctions[20] = {
        0x7A, 0x78, 0x63, 0x76, 0x60, 0x61, 0x62, 0x64, 0x65, 0x6D,
        0x67, 0x6F, 0x69, 0x6B, 0x71, 0x6A, 0x40, 0x4F, 0x50, 0x5A,
    };
    value = kFunctions[named - KWEB_SHORTCUTS_NAMED_F1];
  } else {
    switch (named) {
      case KWEB_SHORTCUTS_NAMED_SPACE: value = kVK_Space; break;
      case KWEB_SHORTCUTS_NAMED_TAB: value = kVK_Tab; break;
      case KWEB_SHORTCUTS_NAMED_ENTER: value = kVK_Return; break;
      case KWEB_SHORTCUTS_NAMED_ESCAPE: value = kVK_Escape; break;
      case KWEB_SHORTCUTS_NAMED_BACKSPACE: value = kVK_Delete; break;
      case KWEB_SHORTCUTS_NAMED_DELETE: value = kVK_ForwardDelete; break;
      case KWEB_SHORTCUTS_NAMED_INSERT: value = kVK_Help; break;
      case KWEB_SHORTCUTS_NAMED_HOME: value = kVK_Home; break;
      case KWEB_SHORTCUTS_NAMED_END: value = kVK_End; break;
      case KWEB_SHORTCUTS_NAMED_PAGE_UP: value = kVK_PageUp; break;
      case KWEB_SHORTCUTS_NAMED_PAGE_DOWN: value = kVK_PageDown; break;
      case KWEB_SHORTCUTS_NAMED_ARROW_LEFT: value = kVK_LeftArrow; break;
      case KWEB_SHORTCUTS_NAMED_ARROW_RIGHT: value = kVK_RightArrow; break;
      case KWEB_SHORTCUTS_NAMED_ARROW_UP: value = kVK_UpArrow; break;
      case KWEB_SHORTCUTS_NAMED_ARROW_DOWN: value = kVK_DownArrow; break;
      case KWEB_SHORTCUTS_NAMED_MINUS: value = kVK_ANSI_Minus; break;
      case KWEB_SHORTCUTS_NAMED_EQUALS: value = kVK_ANSI_Equal; break;
      case KWEB_SHORTCUTS_NAMED_BRACKET_LEFT: value = kVK_ANSI_LeftBracket; break;
      case KWEB_SHORTCUTS_NAMED_BRACKET_RIGHT: value = kVK_ANSI_RightBracket; break;
      case KWEB_SHORTCUTS_NAMED_BACKSLASH: value = kVK_ANSI_Backslash; break;
      case KWEB_SHORTCUTS_NAMED_SEMICOLON: value = kVK_ANSI_Semicolon; break;
      case KWEB_SHORTCUTS_NAMED_QUOTE: value = kVK_ANSI_Quote; break;
      case KWEB_SHORTCUTS_NAMED_COMMA: value = kVK_ANSI_Comma; break;
      case KWEB_SHORTCUTS_NAMED_PERIOD: value = kVK_ANSI_Period; break;
      case KWEB_SHORTCUTS_NAMED_SLASH: value = kVK_ANSI_Slash; break;
      case KWEB_SHORTCUTS_NAMED_BACKQUOTE: value = kVK_ANSI_Grave; break;
      default: return false;
    }
  }
  *out = value;
  return true;
}

uint32_t CarbonModifiers(uint32_t bits) {
  uint32_t modifiers = 0;
  if ((bits & KWEB_SHORTCUTS_MOD_CONTROL) != 0) modifiers |= controlKey;
  if ((bits & KWEB_SHORTCUTS_MOD_ALT) != 0) modifiers |= optionKey;
  if ((bits & KWEB_SHORTCUTS_MOD_SHIFT) != 0) modifiers |= shiftKey;
  if ((bits & KWEB_SHORTCUTS_MOD_META) != 0) modifiers |= cmdKey;
  return modifiers;
}

OSStatus OnHotKey(EventHandlerCallRef, EventRef event, void *) {
  EventHotKeyID hotkey_id{};
  GetEventParameter(event, kEventParamDirectObject, typeEventHotKeyID, nullptr, sizeof(hotkey_id), nullptr,
                    &hotkey_id);
  if (g_native == nullptr || g_state == nullptr) return noErr;
  for (const Entry &entry : g_native->entries) {
    if (entry.hotkey_id == hotkey_id.id) {
      kwebshell::shortcuts::PublishInvocation(*g_state, entry.registration,
                                              KWEB_SHORTCUTS_SOURCE_PLATFORM);
      break;
    }
  }
  return noErr;
}

}  // namespace

namespace kwebshell::shortcuts {

kweb_shortcuts_status NativeOpen(State &state) {
  auto *native = new NativeState();
  EventTypeSpec spec{kEventClassKeyboard, kEventHotKeyPressed};
  const OSStatus installed =
      InstallApplicationEventHandler(&OnHotKey, 1, &spec, nullptr, &native->handler);
  if (installed != noErr) {
    delete native;
    return KWEB_SHORTCUTS_STATUS_NATIVE_FAILED;
  }
  state.platform = native;
  g_native = native;
  g_state = &state;
  state.capabilities.provider_id = kProviderId;
  // Carbon global hotkeys cover the named key classes. Media keys require an
  // accessibility trust the service does not assume, so they stay a typed absence.
  state.capabilities.capability_bits = KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION |
                                       KWEB_SHORTCUTS_CAP_ATOMIC_REPLACEMENT |
                                       KWEB_SHORTCUTS_CAP_INVOCATION_REPORTING;
  state.capabilities.modifier_bits = KWEB_SHORTCUTS_MOD_ALL;
  state.capabilities.media_key_bits = 0;
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeRegister(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  uint32_t key_code = 0;
  if (!CarbonKeyCode(registration.key_code, &key_code)) {
    return KWEB_SHORTCUTS_STATUS_ACCELERATOR_UNSUPPORTED;
  }
  Entry entry;
  entry.registration = registration;
  entry.hotkey_id = native->next_id++;
  EventHotKeyID hotkey_id{kHotKeySignature, entry.hotkey_id};
  const OSStatus registered = RegisterEventHotKey(key_code, CarbonModifiers(registration.modifier_bits),
                                                  hotkey_id, GetApplicationEventTarget(), 0, &entry.ref);
  if (registered == eventHotKeyExistsErr) return KWEB_SHORTCUTS_STATUS_CONFLICT;
  if (registered != noErr) return KWEB_SHORTCUTS_STATUS_NATIVE_FAILED;
  native->entries.push_back(entry);
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeUnregister(State &state, const Registration &registration) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE;
  for (size_t index = 0; index < native->entries.size(); ++index) {
    if (native->entries[index].registration.id != registration.id) continue;
    if (native->entries[index].ref != nullptr) UnregisterEventHotKey(native->entries[index].ref);
    native->entries.erase(native->entries.begin() + static_cast<long>(index));
    return KWEB_SHORTCUTS_STATUS_OK;
  }
  return KWEB_SHORTCUTS_STATUS_OK;
}

kweb_shortcuts_status NativeClose(State &state) {
  NativeState *native = static_cast<NativeState *>(state.platform);
  if (native == nullptr) return KWEB_SHORTCUTS_STATUS_OK;
  for (const Entry &entry : native->entries) {
    if (entry.ref != nullptr) UnregisterEventHotKey(entry.ref);
  }
  if (native->handler != nullptr) RemoveEventHandler(native->handler);
  native->handler = nullptr;
  if (g_native == native) g_native = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete native;
  state.platform = nullptr;
  return KWEB_SHORTCUTS_STATUS_OK;
}

}  // namespace kwebshell::shortcuts
