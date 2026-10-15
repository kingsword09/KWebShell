#include "power_internal.h"

#if !defined(__APPLE__)
#error "The macOS power provider must only be compiled on macOS."
#endif

#import <AppKit/AppKit.h>
#import <Foundation/Foundation.h>
#import <IOKit/pwr_mgt/IOPMLib.h>
#import <IOKit/ps/IOPowerSources.h>
#import <IOKit/ps/IOPSKeys.h>
#include <CoreFoundation/CoreFoundation.h>

#include <string>

namespace {

constexpr char kProviderId[] = "power.macos.workspace-iokit";

using kwebshell::power::Facts;
using kwebshell::power::Lease;
using kwebshell::power::State;

/** The observers one opened service owns. */
struct MacState {
  id will_sleep = nil;
  id did_wake = nil;
  id session_resigned = nil;
  id session_active = nil;
  id thermal_changed = nil;
  CFRunLoopSourceRef power_source = nullptr;
};

State *g_state = nullptr;
MacState *g_mac = nullptr;

MacState *StateOf(State &state) { return static_cast<MacState *>(state.platform); }

uint32_t ThermalLevel(NSProcessInfoThermalState state) {
  switch (state) {
    case NSProcessInfoThermalStateFair:
      return KWEB_POWER_THERMAL_FAIR;
    case NSProcessInfoThermalStateSerious:
      return KWEB_POWER_THERMAL_SERIOUS;
    case NSProcessInfoThermalStateCritical:
      return KWEB_POWER_THERMAL_CRITICAL;
    default:
      return KWEB_POWER_THERMAL_NOMINAL;
  }
}

/** Reads the providing power source and, when a battery exists, its percentage. */
void ReadPowerSource(Facts *facts) {
  const CFTypeRef providing = IOPSGetProvidingPowerSourceType(nullptr);
  if (providing == nullptr) return;
  bool known = false;
  bool on_battery = false;
  if (CFStringCompare(static_cast<CFStringRef>(providing), CFSTR(kIOPSBatteryPowerValue), 0) ==
      kCFCompareEqualTo) {
    known = true;
    on_battery = true;
  } else if (CFStringCompare(static_cast<CFStringRef>(providing), CFSTR(kIOPSACPowerValue), 0) ==
             kCFCompareEqualTo) {
    known = true;
    on_battery = false;
  }
  CFRelease(providing);
  if (!known) return;
  facts->fact_bits |= KWEB_POWER_FACT_ON_BATTERY;
  facts->on_battery = on_battery;

  CFTypeRef snapshot = IOPSCopyPowerSourcesInfo();
  if (snapshot == nullptr) return;
  CFArrayRef sources = IOPSCopyPowerSourcesList(snapshot);
  if (sources != nullptr) {
    for (CFIndex index = 0; index < CFArrayGetCount(sources); ++index) {
      CFDictionaryRef description = IOPSGetPowerSourceDescription(
          snapshot, static_cast<CFTypeRef>(CFArrayGetValueAtIndex(sources, index)));
      if (description == nullptr) continue;
      const void *current = CFDictionaryGetValue(description, CFSTR(kIOPSCurrentCapacityKey));
      const void *maximum = CFDictionaryGetValue(description, CFSTR(kIOPSMaxCapacityKey));
      if (current == nullptr || maximum == nullptr) continue;
      int current_value = 0;
      int maximum_value = 0;
      if (!CFNumberGetValue(static_cast<CFNumberRef>(current), kCFNumberIntType, &current_value)) continue;
      if (!CFNumberGetValue(static_cast<CFNumberRef>(maximum), kCFNumberIntType, &maximum_value)) continue;
      if (maximum_value <= 0) continue;
      const long percent = (static_cast<long>(current_value) * 100 + maximum_value / 2) / maximum_value;
      if (percent < 0 || percent > 100) continue;
      facts->fact_bits |= KWEB_POWER_FACT_BATTERY_PERCENT;
      facts->battery_percent = static_cast<uint32_t>(percent);
      break;
    }
    CFRelease(sources);
  }
  CFRelease(snapshot);
}

void ReadThermal(Facts *facts) {
  const uint32_t level = ThermalLevel(NSProcessInfo.processInfo.thermalState);
  facts->fact_bits |= KWEB_POWER_FACT_THERMAL;
  facts->thermal = level;
}

/** The IOKit power-source notification re-reads and publishes only a real change. */
void OnPowerSourceChanged(void *) {
  if (g_state == nullptr) return;
  Facts facts;
  {
    std::lock_guard<std::mutex> lock(g_state->mutex);
    facts = g_state->facts;
  }
  ReadPowerSource(&facts);
  if ((facts.fact_bits & KWEB_POWER_FACT_ON_BATTERY) != 0) {
    kwebshell::power::PublishPowerSource(*g_state, facts.on_battery);
  }
}

}  // namespace

namespace kwebshell::power {

kweb_power_status NativeOpen(State &state) {
  auto *mac = new MacState();
  state.platform = mac;
  g_state = &state;
  g_mac = mac;
  NSNotificationCenter *workspace = NSWorkspace.sharedWorkspace.notificationCenter;
  mac->will_sleep = [workspace addObserverForName:NSWorkspaceWillSleepNotification
                                           object:nil
                                            queue:nil
                                       usingBlock:^(NSNotification *note) {
                                         (void)note;
                                         if (g_state != nullptr) PublishSuspended(*g_state);
                                       }];
  mac->did_wake = [workspace addObserverForName:NSWorkspaceDidWakeNotification
                                         object:nil
                                          queue:nil
                                     usingBlock:^(NSNotification *note) {
                                       (void)note;
                                       if (g_state != nullptr) PublishResumed(*g_state);
                                     }];
  mac->session_resigned =
      [workspace addObserverForName:NSWorkspaceSessionDidResignActiveNotification
                             object:nil
                              queue:nil
                         usingBlock:^(NSNotification *note) {
                           (void)note;
                           if (g_state != nullptr) PublishLocked(*g_state);
                         }];
  mac->session_active =
      [workspace addObserverForName:NSWorkspaceSessionDidBecomeActiveNotification
                             object:nil
                              queue:nil
                         usingBlock:^(NSNotification *note) {
                           (void)note;
                           if (g_state != nullptr) PublishUnlocked(*g_state);
                         }];
  mac->thermal_changed =
      [NSNotificationCenter.defaultCenter addObserverForName:NSProcessInfoThermalStateDidChangeNotification
                                                      object:nil
                                                       queue:nil
                                                  usingBlock:^(NSNotification *note) {
                                                    (void)note;
                                                    if (g_state != nullptr) {
                                                      PublishThermal(*g_state, ThermalLevel(NSProcessInfo.processInfo.thermalState));
                                                    }
                                                  }];
  mac->power_source = IOPSNotificationCreateRunLoopSource(OnPowerSourceChanged, nullptr);
  if (mac->power_source != nullptr) {
    CFRunLoopAddSource(CFRunLoopGetMain(), mac->power_source, kCFRunLoopDefaultMode);
  }
  state.capabilities.provider_id = kProviderId;
  state.capabilities.capability_bits = KWEB_POWER_CAP_SUSPEND_EVENTS | KWEB_POWER_CAP_LOCK_EVENTS |
                                       KWEB_POWER_CAP_POWER_SOURCE_STATE | KWEB_POWER_CAP_THERMAL_STATE |
                                       KWEB_POWER_CAP_SYSTEM_SLEEP_BLOCKER |
                                       KWEB_POWER_CAP_DISPLAY_SLEEP_BLOCKER | KWEB_POWER_CAP_LEASE_RENEWAL;
  state.capabilities.max_leases = KWEB_POWER_MAX_LEASES;
  state.capabilities.max_leases_per_type = 4;
  state.capabilities.min_lease_seconds = 1;
  state.capabilities.max_lease_seconds = 14400;
  Facts facts;
  ReadPowerSource(&facts);
  ReadThermal(&facts);
  state.facts = facts;
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeReadFacts(State &state, Facts *facts) {
  if (StateOf(state) == nullptr || facts == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  ReadPowerSource(facts);
  ReadThermal(facts);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeAcquire(State &state, const Lease &lease, uint64_t *token) {
  if (StateOf(state) == nullptr || token == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  const CFStringRef type = lease.type == KWEB_POWER_TYPE_SYSTEM_SLEEP
                               ? kIOPMAssertionTypePreventUserIdleSystemSleep
                               : kIOPMAssertionTypePreventUserIdleDisplaySleep;
  IOPMAssertionID assertion = 0;
  const IOReturn created = IOPMAssertionCreateWithName(
      type, kIOPMAssertionLevelOn, CFSTR("KWebShell application power lease"), &assertion);
  if (created != kIOReturnSuccess) return KWEB_POWER_STATUS_NATIVE_FAILED;
  *token = static_cast<uint64_t>(assertion);
  return KWEB_POWER_STATUS_OK;
}

kweb_power_status NativeRelease(State &state, const Lease &lease) {
  if (StateOf(state) == nullptr) return KWEB_POWER_STATUS_PLATFORM_UNAVAILABLE;
  if (lease.token == 0) return KWEB_POWER_STATUS_OK;
  const IOReturn released = IOPMAssertionRelease(static_cast<IOPMAssertionID>(lease.token));
  return released == kIOReturnSuccess ? KWEB_POWER_STATUS_OK : KWEB_POWER_STATUS_NATIVE_FAILED;
}

kweb_power_status NativeClose(State &state) {
  MacState *mac = StateOf(state);
  if (mac == nullptr) return KWEB_POWER_STATUS_OK;
  NSNotificationCenter *workspace = NSWorkspace.sharedWorkspace.notificationCenter;
  if (mac->will_sleep != nil) [workspace removeObserver:mac->will_sleep];
  if (mac->did_wake != nil) [workspace removeObserver:mac->did_wake];
  if (mac->session_resigned != nil) [workspace removeObserver:mac->session_resigned];
  if (mac->session_active != nil) [workspace removeObserver:mac->session_active];
  if (mac->thermal_changed != nil) [NSNotificationCenter.defaultCenter removeObserver:mac->thermal_changed];
  if (mac->power_source != nullptr) {
    CFRunLoopRemoveSource(CFRunLoopGetMain(), mac->power_source, kCFRunLoopDefaultMode);
    CFRelease(mac->power_source);
    mac->power_source = nullptr;
  }
  if (g_mac == mac) g_mac = nullptr;
  if (g_state == &state) g_state = nullptr;
  delete mac;
  state.platform = nullptr;
  return KWEB_POWER_STATUS_OK;
}

}  // namespace kwebshell::power
