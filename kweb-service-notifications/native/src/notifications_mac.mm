#include "notifications_platform.h"

#if !defined(__APPLE__)
#error "The macOS notification provider must only be compiled on macOS."
#endif

#import <Foundation/Foundation.h>
#import <UserNotifications/UserNotifications.h>
#import <UserNotifications/UNError.h>

#include <dispatch/dispatch.h>

#include <cstring>
#include <string>

@interface KWebNotificationDelegate : NSObject <UNUserNotificationCenterDelegate>
@property(nonatomic, assign) kwebshell::notifications::State *state;
@end

namespace {
struct MacState;

struct MacState {
  UNUserNotificationCenter *center = nil;
  __strong KWebNotificationDelegate *delegate = nil;
};

std::string ToUtf8(NSString *value) {
  return value == nil ? std::string() : std::string([value UTF8String]);
}

void WaitForPermission(
    UNUserNotificationCenter *center,
    bool request,
    kweb_notifications_permission_status *status,
    bool *completed) {
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  // Blocks retain this storage if the native prompt outlives our deadline.
  // Never let an asynchronous completion write through caller stack pointers.
  __block kweb_notifications_permission_status observed = KWEB_NOTIFICATIONS_PERMISSION_UNAVAILABLE;
  if (request) {
    [center requestAuthorizationWithOptions:(UNAuthorizationOptionAlert | UNAuthorizationOptionSound)
                          completionHandler:^(BOOL granted, NSError *error) {
      if (error != nil) {
        NSLog(@"KWebNotifications requestAuthorization failed (domain=%@ code=%ld)",
              error.domain, (long)error.code);
      }
      observed = granted ? KWEB_NOTIFICATIONS_PERMISSION_GRANTED : KWEB_NOTIFICATIONS_PERMISSION_DENIED;
      dispatch_semaphore_signal(semaphore);
    }];
  } else {
    [center getNotificationSettingsWithCompletionHandler:^(UNNotificationSettings *settings) {
      switch (settings.authorizationStatus) {
        case UNAuthorizationStatusAuthorized:
        case UNAuthorizationStatusProvisional:
          observed = KWEB_NOTIFICATIONS_PERMISSION_GRANTED;
          break;
        case UNAuthorizationStatusDenied:
          observed = KWEB_NOTIFICATIONS_PERMISSION_DENIED;
          break;
        case UNAuthorizationStatusNotDetermined:
          observed = KWEB_NOTIFICATIONS_PERMISSION_NOT_DETERMINED;
          break;
      }
      dispatch_semaphore_signal(semaphore);
    }];
  }
  const auto deadline = dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC);
  *completed = dispatch_semaphore_wait(semaphore, deadline) == 0;
  if (*completed) *status = observed;
}

}  // namespace

@implementation KWebNotificationDelegate

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
       willPresentNotification:(UNNotification *)notification
         withCompletionHandler:(void (^)(UNNotificationPresentationOptions options))completionHandler {
  (void)center;
  (void)notification;
  completionHandler(UNNotificationPresentationOptionBanner | UNNotificationPresentationOptionList |
                    UNNotificationPresentationOptionSound);
}

- (void)userNotificationCenter:(UNUserNotificationCenter *)center
 didReceiveNotificationResponse:(UNNotificationResponse *)response
          withCompletionHandler:(void (^)(void))completionHandler {
  (void)center;
  auto *state = self.state;
  if (state == nullptr) {
    completionHandler();
    return;
  }
  const std::string id = ToUtf8(response.notification.request.identifier);
  const std::string action = ToUtf8(response.actionIdentifier);
  if (action == ToUtf8(UNNotificationDismissActionIdentifier)) {
    kwebshell::notifications::PushClosed(*state, id, KWEB_NOTIFICATIONS_CLOSE_USER_DISMISSED);
  } else {
    std::string reply;
    if ([response isKindOfClass:[UNTextInputNotificationResponse class]]) {
      reply = ToUtf8([(UNTextInputNotificationResponse *)response userText]);
    }
    const std::string actionId = action == ToUtf8(UNNotificationDefaultActionIdentifier) ? "default" : action;
    kwebshell::notifications::PushAction(*state, id, actionId, reply);
  }
  completionHandler();
}

@end

namespace kwebshell::notifications {

const char *ProviderId() { return "macos.UNUserNotificationCenter"; }

void NativePump(State &) {}

kweb_notifications_status NativeOpen(State &state) {
  if (state.package_identity.empty()) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  NSString *bundleIdentifier = [NSBundle mainBundle].bundleIdentifier;
  NSString *bundlePath = [NSBundle mainBundle].bundlePath;
  if (bundleIdentifier == nil || ToUtf8(bundleIdentifier) != state.package_identity ||
      bundlePath == nil || (![bundlePath.pathExtension isEqualToString:@"app"] &&
                            [bundlePath rangeOfString:@".app/"].location == NSNotFound)) {
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  }
  auto *mac_state = new MacState();
  void (^installDelegate)(void) = ^{
    mac_state->center = [UNUserNotificationCenter currentNotificationCenter];
    mac_state->delegate = [KWebNotificationDelegate new];
    mac_state->delegate.state = &state;
    mac_state->center.delegate = mac_state->delegate;
  };
  if (NSThread.isMainThread) {
    installDelegate();
  } else {
    dispatch_sync(dispatch_get_main_queue(), installDelegate);
  }
  if (mac_state->center == nil || mac_state->center.delegate != mac_state->delegate) {
    delete mac_state;
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  }
  state.platform = mac_state;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativePermission(
    State &state, bool request, kweb_notifications_permission_result *result) {
  auto *mac_state = static_cast<MacState *>(state.platform);
  if (mac_state == nullptr || mac_state->center == nil) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  bool completed = false;
  kweb_notifications_permission_status status = KWEB_NOTIFICATIONS_PERMISSION_UNAVAILABLE;
  WaitForPermission(mac_state->center, request, &status, &completed);
  if (!completed) return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  result->status = status;
  std::strncpy(result->provider, ProviderId(), KWEB_NOTIFICATIONS_MAX_PROVIDER - 1u);
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeCapabilities(
    State &, kweb_notifications_capabilities_result *result) {
  result->flags = KWEB_NOTIFICATIONS_CAP_ACTIONS |
                  KWEB_NOTIFICATIONS_CAP_REPLIES |
                  KWEB_NOTIFICATIONS_CAP_REPLACEMENT |
                  KWEB_NOTIFICATIONS_CAP_TIMEOUT |
                  KWEB_NOTIFICATIONS_CAP_ACTIVATION;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeShow(State &state, const kweb_notifications_request &request) {
  auto *mac_state = static_cast<MacState *>(state.platform);
  if (mac_state == nullptr || mac_state->center == nil) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  std::string id;
  std::string title;
  std::string body;
  if (!ReadString(request.id, KWEB_NOTIFICATIONS_MAX_ID, &id) ||
      !ReadString(request.title, KWEB_NOTIFICATIONS_MAX_TITLE, &title) ||
      !ReadString(request.body, KWEB_NOTIFICATIONS_MAX_BODY, &body)) {
    return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
  }
  UNMutableNotificationContent *content = [UNMutableNotificationContent new];
  content.title = [NSString stringWithUTF8String:title.c_str()];
  content.body = [NSString stringWithUTF8String:body.c_str()];
  content.sound = [UNNotificationSound defaultSound];
  NSString *categoryIdentifier = [NSString stringWithFormat:@"kweb.notification.%s", id.c_str()];
  NSMutableArray<UNNotificationAction *> *nativeActions = [NSMutableArray array];
  for (uint32_t index = 0; index < request.action_count; ++index) {
    std::string actionId;
    std::string actionTitle;
    std::string placeholder;
    if (!ReadString(request.actions[index].id, KWEB_NOTIFICATIONS_MAX_ACTION_ID, &actionId) ||
        !ReadString(request.actions[index].title, KWEB_NOTIFICATIONS_MAX_ACTION_TITLE, &actionTitle) ||
        !ReadString(request.actions[index].reply_placeholder, KWEB_NOTIFICATIONS_MAX_REPLY, &placeholder)) {
      return KWEB_NOTIFICATIONS_STATUS_INVALID_ARGUMENT;
    }
    NSString *identifier = [NSString stringWithUTF8String:actionId.c_str()];
    NSString *label = [NSString stringWithUTF8String:actionTitle.c_str()];
    UNNotificationAction *action = nil;
    if (request.actions[index].kind == KWEB_NOTIFICATIONS_ACTION_REPLY) {
      action = [UNTextInputNotificationAction actionWithIdentifier:identifier
                                                               title:label
                                                             options:UNNotificationActionOptionForeground
                                                textInputButtonTitle:label
                                           textInputPlaceholder:[NSString stringWithUTF8String:placeholder.c_str()]];
    } else {
      action = [UNNotificationAction actionWithIdentifier:identifier title:label options:UNNotificationActionOptionForeground];
    }
    [nativeActions addObject:action];
  }
  UNNotificationCategory *category = [UNNotificationCategory categoryWithIdentifier:categoryIdentifier
                                                                                actions:nativeActions
                                                                      intentIdentifiers:@[]
                                                                                options:UNNotificationCategoryOptionCustomDismissAction];
  [mac_state->center setNotificationCategories:[NSSet setWithObject:category]];
  content.categoryIdentifier = categoryIdentifier;
  UNNotificationRequest *notificationRequest = [UNNotificationRequest requestWithIdentifier:
      [NSString stringWithUTF8String:id.c_str()] content:content trigger:nil];
  dispatch_semaphore_t semaphore = dispatch_semaphore_create(0);
  __block NSError *requestError = nil;
  [mac_state->center addNotificationRequest:notificationRequest withCompletionHandler:^(NSError *error) {
    requestError = error;
    dispatch_semaphore_signal(semaphore);
  }];
  const auto deadline = dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC);
  if (dispatch_semaphore_wait(semaphore, deadline) != 0) return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  if (requestError != nil) {
    // NSError domain/code are actionable provider diagnostics and do not expose
    // notification content or other user data.
    NSLog(@"KWebNotifications addNotificationRequest failed (domain=%@ code=%ld)",
          requestError.domain, (long)requestError.code);
    if ([requestError.domain isEqualToString:UNErrorDomain] &&
        requestError.code == UNErrorCodeNotificationsNotAllowed) {
      return KWEB_NOTIFICATIONS_STATUS_PERMISSION_DENIED;
    }
    return KWEB_NOTIFICATIONS_STATUS_NATIVE_FAILED;
  }
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeCloseNotification(State &state, const std::string &id) {
  auto *mac_state = static_cast<MacState *>(state.platform);
  if (mac_state == nullptr || mac_state->center == nil) return KWEB_NOTIFICATIONS_STATUS_NATIVE_UNAVAILABLE;
  NSString *identifier = [NSString stringWithUTF8String:id.c_str()];
  [mac_state->center removePendingNotificationRequestsWithIdentifiers:@[identifier]];
  [mac_state->center removeDeliveredNotificationsWithIdentifiers:@[identifier]];
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

kweb_notifications_status NativeClose(State &state) {
  auto *mac_state = static_cast<MacState *>(state.platform);
  if (mac_state == nullptr) return KWEB_NOTIFICATIONS_STATUS_OK;
  void (^clearDelegate)(void) = ^{
    if (mac_state->center.delegate == mac_state->delegate) {
      mac_state->center.delegate = nil;
    }
    mac_state->delegate.state = nullptr;
  };
  if (NSThread.isMainThread) {
    clearDelegate();
  } else {
    dispatch_sync(dispatch_get_main_queue(), clearDelegate);
  }
  state.platform = nullptr;
  delete mac_state;
  return KWEB_NOTIFICATIONS_STATUS_OK;
}

}  // namespace kwebshell::notifications
