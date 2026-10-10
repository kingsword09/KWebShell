#include "tray_internal.h"

#if !defined(__APPLE__)
#error "The macOS tray provider must only be compiled on macOS."
#endif

#import <AppKit/AppKit.h>

#include <dispatch/dispatch.h>

#include <cmath>
#include <cstring>
#include <map>
#include <string>

namespace {

kwebshell::tray::State *g_state = nullptr;

}  // namespace

/** Carries the command identity of one native menu item. */
@interface KWebTrayCommandRef : NSObject
@property(nonatomic, copy) NSString *itemId;
@property(nonatomic, copy) NSString *menuId;
@property(nonatomic, copy) NSString *commandId;
@property(nonatomic) unsigned long long treeVersion;
@end

@implementation KWebTrayCommandRef
@end

/** One live status item and its declared activations. */
@interface KWebTrayEntry : NSObject
@property(nonatomic, strong) NSStatusItem *statusItem;
@property(nonatomic) uint32_t activationBits;
@end

@implementation KWebTrayEntry
@end

/** Receives status-item activations and menu selections. */
@interface KWebTrayTarget : NSObject
@property(nonatomic, strong) NSMutableDictionary<NSString *, KWebTrayEntry *> *entries;
- (void)trayActivated:(id)sender;
- (void)trayCommand:(NSMenuItem *)sender;
@end

@implementation KWebTrayTarget

- (void)trayActivated:(id)sender {
  NSStatusBarButton *button = (NSStatusBarButton *)sender;
  NSString *itemId = button.identifier;
  if (itemId == nil || g_state == nullptr) return;
  KWebTrayEntry *entry = self.entries[itemId];
  if (entry == nil) return;
  NSEvent *event = NSApp.currentEvent;
  const uint32_t bits = entry.activationBits;
  if (event != nil && event.type == NSEventTypeRightMouseUp) {
    if ((bits & KWEB_TRAY_ACTIVATION_SECONDARY) != 0) {
      kwebshell::tray::PushActivated(*g_state, itemId.UTF8String, KWEB_TRAY_ACTIVATION_SECONDARY);
    }
    return;
  }
  if (event != nil && event.clickCount >= 2) {
    if ((bits & KWEB_TRAY_ACTIVATION_DOUBLE) != 0) {
      kwebshell::tray::PushActivated(*g_state, itemId.UTF8String, KWEB_TRAY_ACTIVATION_DOUBLE);
    }
    return;
  }
  if ((bits & KWEB_TRAY_ACTIVATION_PRIMARY) != 0) {
    kwebshell::tray::PushActivated(*g_state, itemId.UTF8String, KWEB_TRAY_ACTIVATION_PRIMARY);
  }
}

- (void)trayCommand:(NSMenuItem *)sender {
  KWebTrayCommandRef *reference = sender.representedObject;
  if (reference == nil || g_state == nullptr || reference.commandId.length == 0) return;
  kwebshell::tray::PushMenuCommand(*g_state, reference.itemId.UTF8String, reference.menuId.UTF8String,
                                   reference.commandId.UTF8String, reference.treeVersion);
}

@end

namespace {

/** AppKit owns status items, so every mutation runs on the main queue. */
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

NSImage *BuildIcon(const kwebshell::tray::IconVariant &variant) {
  const NSInteger width = static_cast<NSInteger>(variant.width);
  const NSInteger height = static_cast<NSInteger>(variant.height);
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
  for (size_t pixel = 0; pixel < variant.pixels.size(); pixel += 4) {
    const uint32_t alpha = variant.pixels[pixel + 3];
    destination[pixel + 0] = static_cast<uint8_t>((variant.pixels[pixel + 0] * alpha + 127u) / 255u);
    destination[pixel + 1] = static_cast<uint8_t>((variant.pixels[pixel + 1] * alpha + 127u) / 255u);
    destination[pixel + 2] = static_cast<uint8_t>((variant.pixels[pixel + 2] * alpha + 127u) / 255u);
    destination[pixel + 3] = static_cast<uint8_t>(alpha);
  }
  NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(width, height)];
  [image addRepresentation:bitmap];
  [image setTemplate:(variant.template_icon ? YES : NO)];
  return image;
}

const kwebshell::tray::IconVariant *SelectVariant(const kwebshell::tray::Item &item) {
  const int32_t platform_scale = static_cast<int32_t>(NSScreen.mainScreen.backingScaleFactor);
  const kwebshell::tray::IconVariant *selected = nullptr;
  for (const kwebshell::tray::IconVariant &variant : item.variants) {
    if (platform_scale > 0 && static_cast<int32_t>(variant.scale) <= platform_scale) {
      if (selected == nullptr || variant.scale > selected->scale) selected = &variant;
    }
  }
  if (selected != nullptr) return selected;
  for (const kwebshell::tray::IconVariant &variant : item.variants) {
    if (selected == nullptr || variant.scale < selected->scale) selected = &variant;
  }
  return selected;
}

void AppendChildren(NSMenu *native, const kwebshell::tray::Menu &menu, const std::string &item_id,
                    KWebTrayTarget *target, const std::vector<size_t> &indices, uint32_t depth) {
  if (depth > 8) return;
  for (const size_t index : indices) {
    const kwebshell::tray::MenuNode &node = menu.items[index];
    if (!node.visible) continue;
    if (node.kind == KWEB_TRAY_MENU_SEPARATOR) {
      [native addItem:NSMenuItem.separatorItem];
      continue;
    }
    NSString *label = [NSString stringWithUTF8String:node.label.c_str()];
    NSMenuItem *item = nil;
    if (node.kind == KWEB_TRAY_MENU_SUBMENU) {
      item = [[NSMenuItem alloc] initWithTitle:label action:nil keyEquivalent:@""];
      NSMenu *child = [[NSMenu alloc] initWithTitle:label];
      child.autoenablesItems = NO;
      AppendChildren(child, menu, item_id, target, node.children, depth + 1);
      item.submenu = child;
    } else {
      item = [[NSMenuItem alloc] initWithTitle:label action:@selector(trayCommand:) keyEquivalent:@""];
      KWebTrayCommandRef *reference = [[KWebTrayCommandRef alloc] init];
      reference.itemId = [NSString stringWithUTF8String:item_id.c_str()];
      reference.menuId = [NSString stringWithUTF8String:menu.menu_id.c_str()];
      reference.commandId = [NSString stringWithUTF8String:node.id.c_str()];
      reference.treeVersion = menu.version;
      item.representedObject = reference;
      item.target = target;
      if (node.kind == KWEB_TRAY_MENU_CHECKBOX || node.kind == KWEB_TRAY_MENU_RADIO) {
        item.state = node.checked ? NSControlStateValueOn : NSControlStateValueOff;
      }
    }
    item.enabled = node.enabled ? YES : NO;
    [native addItem:item];
  }
}

NSMenu *BuildTopMenu(const kwebshell::tray::Menu &menu, const std::string &item_id, KWebTrayTarget *target) {
  NSMenu *native = [[NSMenu alloc] initWithTitle:[NSString stringWithUTF8String:menu.menu_id.c_str()]];
  native.autoenablesItems = NO;
  AppendChildren(native, menu, item_id, target, menu.roots, 1);
  return native;
}

typedef struct MacState {
  KWebTrayTarget *target = nil;
} MacState;

MacState *StateOf(kwebshell::tray::State &state) {
  return static_cast<MacState *>(state.platform);
}

}  // namespace

namespace kwebshell::tray {

const char *ProviderId() {
  return "tray.macos.appkit";
}

kweb_tray_status NativeOpen(State &state) {
  NSApplication *application = NSApplication.sharedApplication;
  if (application == nil) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  KWebTrayTarget *target = [[KWebTrayTarget alloc] init];
  target.entries = [NSMutableDictionary dictionary];
  auto *mac = new MacState();
  mac->target = target;
  state.platform = mac;
  g_state = &state;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeCapabilities(State &, kweb_tray_capabilities_result *result) {
  result->flags = KWEB_TRAY_CAP_MENU | KWEB_TRAY_CAP_TOOLTIP | KWEB_TRAY_CAP_TEMPLATE_ICON |
                  KWEB_TRAY_CAP_ICON_VARIANTS | KWEB_TRAY_CAP_BOUNDS;
  result->activation_bits = KWEB_TRAY_ACTIVATION_PRIMARY | KWEB_TRAY_ACTIVATION_SECONDARY |
                            KWEB_TRAY_ACTIVATION_DOUBLE;
  CopyBounded(result->provider_id, sizeof(result->provider_id), ProviderId());
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeSetItem(State &state, const Item &item, bool) {
  MacState *mac = StateOf(state);
  if (mac == nullptr || mac->target == nil) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  const std::string item_id = item.id;
  const std::string tooltip = item.tooltip;
  Item snapshot = item;
  kweb_tray_status status = KWEB_TRAY_STATUS_OK;
  OnAppKitThread([&] {
    NSString *identifier = [NSString stringWithUTF8String:item_id.c_str()];
    KWebTrayEntry *entry = mac->target.entries[identifier];
    if (entry == nil) {
      NSStatusItem *status_item =
          [NSStatusBar.systemStatusBar statusItemWithLength:NSVariableStatusItemLength];
      if (status_item == nil) {
        status = KWEB_TRAY_STATUS_NATIVE_FAILED;
        return;
      }
      entry = [[KWebTrayEntry alloc] init];
      entry.statusItem = status_item;
      entry.statusItem.button.identifier = identifier;
      entry.statusItem.button.target = mac->target;
      entry.statusItem.button.action = @selector(trayActivated:);
      [entry.statusItem.button sendActionOn:(NSEventMaskLeftMouseUp | NSEventMaskRightMouseUp)];
      mac->target.entries[identifier] = entry;
    }
    const IconVariant *variant = SelectVariant(snapshot);
    if (variant == nullptr) {
      status = KWEB_TRAY_STATUS_ICON_INVALID;
      return;
    }
    NSImage *image = BuildIcon(*variant);
    if (image == nil) {
      status = KWEB_TRAY_STATUS_NATIVE_FAILED;
      return;
    }
    entry.statusItem.button.image = image;
    entry.activationBits = snapshot.activation_bits;
    if (!tooltip.empty()) {
      entry.statusItem.button.toolTip = [NSString stringWithUTF8String:tooltip.c_str()];
    } else {
      entry.statusItem.button.toolTip = nil;
    }
  });
  return status;
}

kweb_tray_status NativeCloseItem(State &state, const std::string &item_id) {
  MacState *mac = StateOf(state);
  if (mac == nullptr || mac->target == nil) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  bool removed = false;
  OnAppKitThread([&] {
    NSString *identifier = [NSString stringWithUTF8String:item_id.c_str()];
    KWebTrayEntry *entry = mac->target.entries[identifier];
    if (entry == nil) return;
    entry.statusItem.menu = nil;
    [NSStatusBar.systemStatusBar removeStatusItem:entry.statusItem];
    [mac->target.entries removeObjectForKey:identifier];
    removed = true;
  });
  if (!removed) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeSetMenu(State &state, const std::string &item_id, const Menu *menu) {
  MacState *mac = StateOf(state);
  if (mac == nullptr || mac->target == nil) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  bool applied = true;
  OnAppKitThread([&] {
    NSString *identifier = [NSString stringWithUTF8String:item_id.c_str()];
    KWebTrayEntry *entry = mac->target.entries[identifier];
    if (entry == nil) {
      applied = false;
      return;
    }
    entry.statusItem.menu = menu == nullptr ? nil : BuildTopMenu(*menu, item_id, mac->target);
  });
  if (!applied) return KWEB_TRAY_STATUS_ITEM_UNKNOWN;
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeBounds(State &state, const std::string &item_id, kweb_tray_bounds_result *result) {
  MacState *mac = StateOf(state);
  if (mac == nullptr || mac->target == nil) return KWEB_TRAY_STATUS_NATIVE_UNAVAILABLE;
  bool available = false;
  NSRect frame = NSMakeRect(0, 0, 0, 0);
  OnAppKitThread([&] {
    NSString *identifier = [NSString stringWithUTF8String:item_id.c_str()];
    KWebTrayEntry *entry = mac->target.entries[identifier];
    NSStatusBarButton *button = entry.statusItem.button;
    if (button == nil || button.window == nil) return;
    frame = [button.window convertRectToScreen:button.frame];
    available = (frame.size.width > 0 && frame.size.height > 0);
  });
  if (!available) return KWEB_TRAY_STATUS_BOUNDS_UNAVAILABLE;
  result->x = static_cast<int32_t>(std::lround(frame.origin.x));
  result->y = static_cast<int32_t>(std::lround(frame.origin.y));
  result->width = static_cast<int32_t>(std::lround(frame.size.width));
  result->height = static_cast<int32_t>(std::lround(frame.size.height));
  return KWEB_TRAY_STATUS_OK;
}

kweb_tray_status NativeClose(State &state) {
  MacState *mac = StateOf(state);
  if (mac == nullptr) return KWEB_TRAY_STATUS_OK;
  OnAppKitThread([&] {
    for (NSString *identifier in mac->target.entries.allKeys) {
      KWebTrayEntry *entry = mac->target.entries[identifier];
      entry.statusItem.menu = nil;
      [NSStatusBar.systemStatusBar removeStatusItem:entry.statusItem];
    }
    [mac->target.entries removeAllObjects];
  });
  mac->target = nil;
  state.platform = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete mac;
  return KWEB_TRAY_STATUS_OK;
}

}  // namespace kwebshell::tray
