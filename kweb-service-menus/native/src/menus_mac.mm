#include "menus_platform.h"

#if !defined(__APPLE__)
#error "The macOS menus provider must only be compiled on macOS."
#endif

#import <AppKit/AppKit.h>

#include <dispatch/dispatch.h>

#include <cmath>
#include <cstring>
#include <string>

namespace {

kwebshell::menus::State *g_state = nullptr;

/**
 * AppKit owns the main menu and rejects mutation from another thread. The JVM
 * has no main-thread accessor for a WindowServer application, so the provider
 * hops onto the main queue; a call already on the main thread runs inline
 * because dispatching to the current main queue would deadlock.
 */
template <typename Block>
void OnAppKitThread(Block block) {
  if ([NSThread isMainThread]) {
    block();
    return;
  }
  dispatch_sync(dispatch_get_main_queue(), ^{
    block();
  });
}

struct PopupSelection {
  std::string command_id;
  bool selected = false;
};

/** The AppKit target of every non-role command and one presented popup. */
PopupSelection *g_popup_selection = nullptr;

constexpr uint64_t kAppKitNativeRoles =
    (1ull << (KWEB_MENUS_ROLE_ABOUT - 1)) | (1ull << (KWEB_MENUS_ROLE_SERVICES - 1)) |
    (1ull << (KWEB_MENUS_ROLE_HIDE - 1)) | (1ull << (KWEB_MENUS_ROLE_HIDE_OTHERS - 1)) |
    (1ull << (KWEB_MENUS_ROLE_SHOW_ALL - 1)) | (1ull << (KWEB_MENUS_ROLE_MINIMIZE - 1)) |
    (1ull << (KWEB_MENUS_ROLE_ZOOM - 1)) | (1ull << (KWEB_MENUS_ROLE_TOGGLE_FULL_SCREEN - 1)) |
    (1ull << (KWEB_MENUS_ROLE_BRING_ALL_TO_FRONT - 1)) | (1ull << (KWEB_MENUS_ROLE_WINDOW - 1));

}  // namespace

/** Carries the typed command identity of one native menu item. */
@interface KWebMenuCommandRef : NSObject
@property(nonatomic, copy) NSString *commandId;
@property(nonatomic) unsigned long long treeVersion;
@property(nonatomic) unsigned int ownerKind;
@property(nonatomic, copy) NSString *ownerId;
@end

@implementation KWebMenuCommandRef
@end

/** Receives AppKit menu selections and reports them as typed invocations. */
@interface KWebMenuTarget : NSObject
- (void)kwebMenuCommand:(id)sender;
@end

@implementation KWebMenuTarget

- (void)kwebMenuCommand:(id)sender {
  NSMenuItem *item = (NSMenuItem *)sender;
  KWebMenuCommandRef *reference = item.representedObject;
  if (reference == nil || g_state == nullptr || reference.commandId.length == 0) return;
  const char *command = reference.commandId.UTF8String;
  const char *owner = reference.ownerId != nil ? reference.ownerId.UTF8String : "";
  if (g_popup_selection != nullptr) {
    g_popup_selection->command_id = command;
    g_popup_selection->selected = true;
    return;
  }
  const auto source = reference.ownerKind == KWEB_MENUS_OWNER_WINDOW ? KWEB_MENUS_SOURCE_WINDOW_MENU
                                                                      : KWEB_MENUS_SOURCE_APPLICATION_MENU;
  kwebshell::menus::ReportInvocation(*g_state, source, reference.ownerKind, owner, command,
                                     reference.treeVersion);
}

@end

namespace {

SEL RoleSelector(kweb_menus_role role) {
  switch (role) {
    case KWEB_MENUS_ROLE_ABOUT: return @selector(orderFrontStandardAboutPanel:);
    case KWEB_MENUS_ROLE_HIDE: return @selector(hide:);
    case KWEB_MENUS_ROLE_HIDE_OTHERS: return @selector(hideOtherApplications:);
    case KWEB_MENUS_ROLE_SHOW_ALL: return @selector(unhideAllApplications:);
    case KWEB_MENUS_ROLE_MINIMIZE: return @selector(performMiniaturize:);
    case KWEB_MENUS_ROLE_ZOOM: return @selector(performZoom:);
    case KWEB_MENUS_ROLE_TOGGLE_FULL_SCREEN: return @selector(toggleFullScreen:);
    case KWEB_MENUS_ROLE_BRING_ALL_TO_FRONT: return @selector(arrangeInFront:);
    default: return nullptr;
  }
}

NSString *AcceleratorCharacter(uint32_t key) {
  if (key >= KWEB_MENUS_KEY_A && key <= KWEB_MENUS_KEY_Z) {
    const unichar character = static_cast<unichar>('a' + (key - KWEB_MENUS_KEY_A));
    return [NSString stringWithCharacters:&character length:1];
  }
  if (key >= 1u && key <= 26u) {
    const unichar character = static_cast<unichar>('a' + (key - 1u));
    return [NSString stringWithCharacters:&character length:1];
  }
  const uint32_t digit_first = 27u;
  if (key >= digit_first && key <= digit_first + 9u) {
    const unichar character = static_cast<unichar>('0' + (key - digit_first));
    return [NSString stringWithCharacters:&character length:1];
  }
  const uint32_t function_first = 37u;
  if (key >= function_first && key <= function_first + 11u) {
    const unichar character = static_cast<unichar>(NSF1FunctionKey + (key - function_first));
    return [NSString stringWithCharacters:&character length:1];
  }
  unichar character = 0;
  switch (key) {
    case 49u: character = NSBackspaceCharacter; break;
    case 50u: character = NSDeleteCharacter; break;
    case 51u: character = NSInsertFunctionKey; break;
    case 52u: character = NSHomeFunctionKey; break;
    case 53u: character = NSEndFunctionKey; break;
    case 54u: character = NSPageUpFunctionKey; break;
    case 55u: character = NSPageDownFunctionKey; break;
    case 56u: character = NSLeftArrowFunctionKey; break;
    case 57u: character = NSRightArrowFunctionKey; break;
    case 58u: character = NSUpArrowFunctionKey; break;
    case 59u: character = NSDownArrowFunctionKey; break;
    case 60u: character = NSEnterCharacter; break;
    case 61u: character = 27u; break;
    case 62u: character = 9u; break;
    case 63u: character = ' '; break;
    case 64u: character = '+'; break;
    case 65u: character = '-'; break;
    case 66u: character = '='; break;
    case 67u: character = ','; break;
    case 68u: character = '.'; break;
    case 69u: character = ';'; break;
    case 70u: character = '/'; break;
    case 71u: character = '\\'; break;
    case 72u: character = '['; break;
    case 73u: character = ']'; break;
    case 74u: character = '\''; break;
    case 75u: character = '`'; break;
    default: return nil;
  }
  return [NSString stringWithCharacters:&character length:1];
}

NSEventModifierFlags ModifierFlags(uint32_t modifiers) {
  NSEventModifierFlags flags = 0;
  if ((modifiers & KWEB_MENUS_MODIFIER_PRIMARY) != 0) flags |= NSEventModifierFlagCommand;
  if ((modifiers & KWEB_MENUS_MODIFIER_META) != 0) flags |= NSEventModifierFlagCommand;
  if ((modifiers & KWEB_MENUS_MODIFIER_CONTROL) != 0) flags |= NSEventModifierFlagControl;
  if ((modifiers & KWEB_MENUS_MODIFIER_ALT) != 0) flags |= NSEventModifierFlagOption;
  if ((modifiers & KWEB_MENUS_MODIFIER_SHIFT) != 0) flags |= NSEventModifierFlagShift;
  return flags;
}

NSImage *IconImage(const kwebshell::menus::Item &item) {
  if (item.icon_width == 0 || item.icon_height == 0 || item.icon_pixels.empty()) return nil;
  const NSInteger width = static_cast<NSInteger>(item.icon_width);
  const NSInteger height = static_cast<NSInteger>(item.icon_height);
  NSBitmapImageRep *bitmap = [[NSBitmapImageRep alloc]
      initWithBitmapDataPlanes:nullptr
                    pixelsWide:width
                    pixelsHigh:height
                 bitsPerSample:8
               samplesPerPixel:4
                      hasAlpha:YES
                      isPlanar:NO
                colorSpaceName:NSDeviceRGBColorSpace
                   bytesPerRow:width * 4
                  bitsPerPixel:32];
  if (bitmap == nil) return nil;
  // AppKit bitmap representations are premultiplied; the ABI carries straight RGBA8.
  uint8_t *destination = bitmap.bitmapData;
  for (size_t pixel = 0; pixel < item.icon_pixels.size(); pixel += 4) {
    const uint32_t alpha = item.icon_pixels[pixel + 3];
    destination[pixel + 0] = static_cast<uint8_t>((item.icon_pixels[pixel + 0] * alpha + 127u) / 255u);
    destination[pixel + 1] = static_cast<uint8_t>((item.icon_pixels[pixel + 1] * alpha + 127u) / 255u);
    destination[pixel + 2] = static_cast<uint8_t>((item.icon_pixels[pixel + 2] * alpha + 127u) / 255u);
    destination[pixel + 3] = static_cast<uint8_t>(alpha);
  }
  NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(width, height)];
  [image addRepresentation:bitmap];
  [image setTemplate:(item.template_icon ? YES : NO)];
  return image;
}

NSMenuItem *BuildItem(const kwebshell::menus::Tree &tree, size_t index, uint32_t owner_kind,
                      NSString *owner_id, KWebMenuTarget *target) {
  const kwebshell::menus::Item &node = tree.items[index];
  if (node.kind == KWEB_MENUS_ITEM_SEPARATOR) return NSMenuItem.separatorItem;
  NSString *label = [NSString stringWithUTF8String:node.label.c_str()];
  NSString *key = node.accelerator_key != KWEB_MENUS_KEY_NONE ? AcceleratorCharacter(node.accelerator_key) : nil;
  const bool native_role = node.role != KWEB_MENUS_ROLE_NONE &&
                           (kAppKitNativeRoles & (1ull << (node.role - 1u))) != 0;
  SEL selector = native_role ? RoleSelector(node.role) : @selector(kwebMenuCommand:);
  NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:label action:selector keyEquivalent:(key != nil ? key : @"")];
  if (key != nil) item.keyEquivalentModifierMask = ModifierFlags(node.accelerator_modifiers);
  item.enabled = node.enabled ? YES : NO;
  item.hidden = node.visible ? NO : YES;
  if (node.checked) item.state = NSControlStateValueOn;
  NSImage *icon = IconImage(node);
  if (icon != nil) item.image = icon;
  if (native_role) {
    item.target = nil;
  } else {
    KWebMenuCommandRef *reference = [[KWebMenuCommandRef alloc] init];
    reference.commandId = [NSString stringWithUTF8String:node.id.c_str()];
    reference.treeVersion = tree.version;
    reference.ownerKind = owner_kind;
    reference.ownerId = owner_id;
    item.representedObject = reference;
    item.target = target;
  }
  if (node.kind == KWEB_MENUS_ITEM_SUBMENU) {
    NSMenu *submenu = [[NSMenu alloc] initWithTitle:label];
    submenu.autoenablesItems = NO;
    for (const size_t child : node.children) {
      [submenu addItem:BuildItem(tree, child, owner_kind, owner_id, target)];
    }
    item.submenu = submenu;
    if (node.role == KWEB_MENUS_ROLE_SERVICES) {
      NSApp.servicesMenu = submenu;
    } else if (node.role == KWEB_MENUS_ROLE_WINDOW) {
      NSApp.windowsMenu = submenu;
    } else if (node.role == KWEB_MENUS_ROLE_HELP) {
      NSApp.helpMenu = submenu;
    }
  }
  return item;
}

NSMenu *BuildMenu(const kwebshell::menus::Tree &tree, uint32_t owner_kind, NSString *owner_id,
                  KWebMenuTarget *target) {
  NSMenu *menu = [[NSMenu alloc] initWithTitle:[NSString stringWithUTF8String:tree.menu_id.c_str()]];
  menu.autoenablesItems = NO;
  for (const size_t index : tree.roots) {
    [menu addItem:BuildItem(tree, index, owner_kind, owner_id, target)];
  }
  return menu;
}

}  // namespace

namespace kwebshell::menus {

const char *ProviderId() {
  return "menus.macos.appkit";
}

kweb_menus_status NativeOpen(State &state) {
  NSApplication *application = NSApplication.sharedApplication;
  if (application == nil) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  KWebMenuTarget *target = [[KWebMenuTarget alloc] init];
  state.platform = (__bridge_retained void *)target;
  g_state = &state;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeCapabilities(State &, kweb_menus_capabilities_result *result) {
  result->flags = KWEB_MENUS_CAP_APPLICATION_MENU | KWEB_MENUS_CAP_PAGE_MENU | KWEB_MENUS_CAP_SUBMENUS |
                  KWEB_MENUS_CAP_CHECKBOX_ITEMS | KWEB_MENUS_CAP_RADIO_ITEMS | KWEB_MENUS_CAP_ITEM_ICONS |
                  KWEB_MENUS_CAP_ACCELERATOR_DISPLAY | KWEB_MENUS_CAP_ACCELERATOR_ACTIVATION |
                  KWEB_MENUS_CAP_POPUP_POSITIONING;
  result->native_roles = kAppKitNativeRoles;
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeSetApplicationMenu(State &state, const Tree *tree) {
  // AppKit menu mutation runs on the AWT event thread, which is the AppKit
  // user-interface thread for a Compose Desktop application.
  KWebMenuTarget *target = (__bridge KWebMenuTarget *)state.platform;
  if (target == nil) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  BOOL built = YES;
  OnAppKitThread([&] {
    if (tree == nullptr) {
      NSApp.mainMenu = [[NSMenu alloc] initWithTitle:@""];
      return;
    }
    NSMenu *menu = BuildMenu(*tree, KWEB_MENUS_OWNER_APPLICATION, @"", target);
    if (menu == nil) {
      built = NO;
      return;
    }
    NSApp.mainMenu = menu;
  });
  return built ? KWEB_MENUS_STATUS_OK : KWEB_MENUS_STATUS_NATIVE_FAILED;
}

kweb_menus_status NativeSetWindowMenu(State &, const std::string &, uint64_t, const Tree *tree) {
  if (tree == nullptr) return KWEB_MENUS_STATUS_OK;
  return KWEB_MENUS_STATUS_TARGET_UNSUPPORTED;
}

kweb_menus_status NativeShowPopup(State &state, const Tree &tree, kweb_menus_owner_kind owner_kind,
                                  const std::string &owner_id, kweb_menus_popup_source, int32_t screen_x,
                                  int32_t screen_y, uint64_t, const std::string &popup_id,
                                  kweb_menus_popup_result *result) {
  KWebMenuTarget *target = (__bridge KWebMenuTarget *)state.platform;
  if (target == nil) return KWEB_MENUS_STATUS_NATIVE_UNAVAILABLE;
  NSString *owner = [NSString stringWithUTF8String:owner_id.c_str()];
  PopupSelection selection;
  BOOL presented = YES;
  OnAppKitThread([&] {
    NSMenu *menu = BuildMenu(tree, owner_kind, owner, target);
    if (menu == nil) {
      presented = NO;
      return;
    }
    g_popup_selection = &selection;
    const NSRect main_screen = NSScreen.mainScreen != nil ? NSScreen.mainScreen.frame
                                                          : NSMakeRect(0, 0, 0, 0);
    const NSPoint location = NSMakePoint(static_cast<CGFloat>(screen_x),
                                        main_screen.size.height - static_cast<CGFloat>(screen_y));
    [menu popUpMenuPositioningItem:nil atLocation:location inView:nil];
    g_popup_selection = nullptr;
  });
  if (!presented) return KWEB_MENUS_STATUS_NATIVE_FAILED;
  CompletePopup(state, tree, owner_kind, owner_id, popup_id, selection.command_id,
                KWEB_MENUS_DISMISS_USER, result);
  return KWEB_MENUS_STATUS_OK;
}

kweb_menus_status NativeClose(State &state) {
  if (state.platform != nullptr) {
    NSApp.mainMenu = [[NSMenu alloc] initWithTitle:@""];
    void *platform = state.platform;
    state.platform = nullptr;
    if (g_state == &state) g_state = nullptr;
    KWebMenuTarget *target = (__bridge_transfer KWebMenuTarget *)platform;
    (void)target;
  }
  return KWEB_MENUS_STATUS_OK;
}

}  // namespace kwebshell::menus
