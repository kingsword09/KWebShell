#include "preferences_internal.h"

#if !defined(__APPLE__)
#error "The macOS system-preferences provider must only be compiled on macOS."
#endif

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#include <dispatch/dispatch.h>

#include <cmath>

namespace {

using kwebshell::preferences::Facts;
using kwebshell::preferences::State;

/** The AppKit observer tokens owned by the opened service. */
struct MacState {
  id appearance_observer = nil;
  id accessibility_observer = nil;
  id theme_observer = nil;
};

MacState *StateOf(State &state) { return static_cast<MacState *>(state.platform); }

/** AppKit owns the application appearance, so every read and mutation runs on the main queue. */
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

uint32_t TriState(BOOL value) { return value ? KWEB_PREFERENCE_TRUE : KWEB_PREFERENCE_FALSE; }

uint32_t Channel(CGFloat component) {
  const long value = std::lround(component * 255.0);
  if (value < 0) return 0;
  if (value > 255) return 255;
  return static_cast<uint32_t>(value);
}

/** Matches the effective appearance against the Aqua and high-contrast names. */
uint32_t ColorSchemeOf(NSAppearance *appearance) {
  if (appearance == nil) return 0;
  NSAppearanceName name = [appearance
      bestMatchFromAppearancesWithNames:@[
        NSAppearanceNameAqua,
        NSAppearanceNameDarkAqua,
        NSAppearanceNameAccessibilityHighContrastAqua,
        NSAppearanceNameAccessibilityHighContrastDarkAqua,
      ]];
  if (name == nil) return 0;
  if ([name isEqualToString:NSAppearanceNameDarkAqua] ||
      [name isEqualToString:NSAppearanceNameAccessibilityHighContrastDarkAqua]) {
    return KWEB_COLOR_SCHEME_DARK;
  }
  return KWEB_COLOR_SCHEME_LIGHT;
}

}  // namespace

/** Observes the appearance key path and reports the change to the portable layer. */
@interface KWebPreferencesObserver : NSObject
@property(nonatomic, assign) kwebshell::preferences::State *state;
@end

@implementation KWebPreferencesObserver
- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change
                       context:(void *)context {
  (void)keyPath;
  (void)object;
  (void)change;
  (void)context;
  if (self.state != nullptr) kwebshell::preferences::PushChanged(*self.state);
}
@end

namespace kwebshell::preferences {

const char *ProviderId() { return "preferences.macos.appkit"; }

kweb_preferences_status NativeOpen(State &state) {
  auto *mac = new MacState();
  state.platform = mac;
  state.target_key_bit = KWEB_PREFERENCES_KEY_MACOS_VOICEOVER;
  OnAppKitThread([&state, mac] {
    // A headless process still owns an NSApplication once AppKit is initialized.
    [NSApplication sharedApplication];
    auto *observer = [[KWebPreferencesObserver alloc] init];
    observer.state = &state;
    [NSApp addObserver:observer
            forKeyPath:@"effectiveAppearance"
               options:NSKeyValueObservingOptionNew
               context:nullptr];
    mac->appearance_observer = observer;
    mac->accessibility_observer =
        [NSNotificationCenter.defaultCenter
            addObserverForName:NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification
                        object:nil
                         queue:NSOperationQueue.mainQueue
                    usingBlock:^(NSNotification *note) {
                      (void)note;
                      PushChanged(state);
                    }];
    mac->theme_observer =
        [NSDistributedNotificationCenter.defaultCenter
            addObserverForName:@"AppleInterfaceThemeChangedNotification"
                        object:nil
                         queue:NSOperationQueue.mainQueue
                    usingBlock:^(NSNotification *note) {
                      (void)note;
                      PushChanged(state);
                    }];
  });
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeReadFacts(State &state, Facts *facts) {
  if (facts == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  Facts result;
  OnAppKitThread([&result, &state] {
    NSWorkspace *workspace = NSWorkspace.sharedWorkspace;
    result.color_scheme = ColorSchemeOf(NSApp.effectiveAppearance);
    result.contrast = workspace.accessibilityDisplayShouldIncreaseContrast ? KWEB_CONTRAST_MORE
                                                                          : KWEB_CONTRAST_NONE;
    result.reduced_motion = TriState(workspace.accessibilityDisplayShouldReduceMotion);
    result.reduced_transparency = TriState(workspace.accessibilityDisplayShouldReduceTransparency);
    result.differentiate_without_color = TriState(workspace.accessibilityDisplayShouldDifferentiateWithoutColor);
    result.invert_colors = TriState(workspace.accessibilityDisplayShouldInvertColors);
    NSColor *accent = [NSColor.controlAccentColor colorUsingColorSpace:NSColorSpace.sRGBColorSpace];
    if (accent != nil) {
      result.accent_source = KWEB_ACCENT_SOURCE_CONTROL_ACCENT;
      result.accent_red = Channel(accent.redComponent);
      result.accent_green = Channel(accent.greenComponent);
      result.accent_blue = Channel(accent.blueComponent);
    }
    const uint32_t declared_keys = state.sensitive_key_bits & state.target_key_bit;
    if (declared_keys != 0) {
      // The assistive-technology fact is read only under its declared key.
      result.screen_reader = TriState(workspace.isVoiceOverEnabled);
    }
    // macOS publishes no global text scale, so that fact stays absent.
    result.published_bits = KWEB_PREFERENCE_FACT_COLOR_SCHEME | KWEB_PREFERENCE_FACT_CONTRAST |
                            KWEB_PREFERENCE_FACT_REDUCED_MOTION |
                            KWEB_PREFERENCE_FACT_REDUCED_TRANSPARENCY |
                            KWEB_PREFERENCE_FACT_DIFFERENTIATE_WITHOUT_COLOR |
                            KWEB_PREFERENCE_FACT_INVERT_COLORS;
    if (result.accent_source != 0) result.published_bits |= KWEB_PREFERENCE_FACT_ACCENT_COLOR;
    if (result.screen_reader != KWEB_PREFERENCE_UNKNOWN) {
      result.published_bits |= KWEB_PREFERENCE_FACT_SCREEN_READER;
    }
    // The application appearance is delivered as an ordered change, and every
    // published fact is observed through an AppKit notification.
    result.live_bits = result.published_bits | KWEB_PREFERENCE_FACT_APPEARANCE_SOURCE;
  });
  *facts = result;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeRequestAppearance(State &state, uint32_t source, uint32_t *effective) {
  (void)state;
  if (effective == nullptr) return KWEB_PREFERENCES_STATUS_INVALID_ARGUMENT;
  NSAppearance *target = nil;
  switch (source) {
    case KWEB_APPEARANCE_SYSTEM:
      target = nil;
      break;
    case KWEB_APPEARANCE_LIGHT:
      target = [NSAppearance appearanceNamed:NSAppearanceNameAqua];
      break;
    case KWEB_APPEARANCE_DARK:
      target = [NSAppearance appearanceNamed:NSAppearanceNameDarkAqua];
      break;
    default:
      return KWEB_PREFERENCES_STATUS_APPEARANCE_INVALID;
  }
  if (source != KWEB_APPEARANCE_SYSTEM && target == nil) {
    return KWEB_PREFERENCES_STATUS_APPEARANCE_UNSUPPORTED;
  }
  OnAppKitThread([target] {
    NSApp.appearance = target;
  });
  *effective = source;
  return KWEB_PREFERENCES_STATUS_OK;
}

kweb_preferences_status NativeClose(State &state) {
  MacState *mac = StateOf(state);
  if (mac == nullptr) return KWEB_PREFERENCES_STATUS_OK;
  OnAppKitThread([mac, &state] {
    if (mac->appearance_observer != nil) {
      [NSApp removeObserver:mac->appearance_observer forKeyPath:@"effectiveAppearance"];
      mac->appearance_observer = nil;
    }
    if (mac->accessibility_observer != nil) {
      [NSNotificationCenter.defaultCenter removeObserver:mac->accessibility_observer];
      mac->accessibility_observer = nil;
    }
    if (mac->theme_observer != nil) {
      [NSDistributedNotificationCenter.defaultCenter removeObserver:mac->theme_observer];
      mac->theme_observer = nil;
    }
    // The process-local appearance override ends with the service.
    NSApp.appearance = nil;
    (void)state;
  });
  delete mac;
  state.platform = nullptr;
  return KWEB_PREFERENCES_STATUS_OK;
}

}  // namespace kwebshell::preferences
