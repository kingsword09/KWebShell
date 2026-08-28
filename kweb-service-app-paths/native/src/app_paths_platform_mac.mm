#include "app_paths_platform.h"

#import <Foundation/Foundation.h>

#include <string>

namespace kwebshell::services {
namespace {

bool FromNSString(NSString *value, PlatformPathResult *result,
                  const char *source) {
  if (value == nil || result == nullptr || source == nullptr) {
    return false;
  }
  const char *utf8 = value.UTF8String;
  if (utf8 == nullptr || *utf8 == '\0') {
    return false;
  }
  result->path = PathFromUtf8(utf8);
  result->source = source;
  return true;
}

bool SearchPath(NSSearchPathDirectory directory, PlatformPathResult *result,
                const char *source) {
  NSArray<NSString *> *paths =
      NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, YES);
  return paths.count > 0 && FromNSString(paths.firstObject, result, source);
}

} // namespace

bool ResolvePlatformPath(uint32_t kind, PlatformPathResult *result,
                         uint32_t *status) {
  if (result == nullptr || status == nullptr) {
    return false;
  }
  @autoreleasepool {
    bool resolved = false;
    switch (kind) {
    case KWEB_APP_PATH_HOME:
      resolved = FromNSString(NSHomeDirectory(), result,
                              "macos.Foundation.NSHomeDirectory");
      break;
    case KWEB_APP_PATH_APP_DATA:
      resolved = SearchPath(NSApplicationSupportDirectory, result,
                            "macos.Foundation.NSApplicationSupportDirectory");
      break;
    case KWEB_APP_PATH_APP_CACHE:
      resolved = SearchPath(NSCachesDirectory, result,
                            "macos.Foundation.NSCachesDirectory");
      break;
    case KWEB_APP_PATH_TEMP:
      resolved = FromNSString(NSTemporaryDirectory(), result,
                              "macos.Foundation.NSTemporaryDirectory");
      break;
    case KWEB_APP_PATH_DESKTOP:
      resolved = SearchPath(NSDesktopDirectory, result,
                            "macos.Foundation.NSDesktopDirectory");
      break;
    case KWEB_APP_PATH_DOCUMENTS:
      resolved = SearchPath(NSDocumentDirectory, result,
                            "macos.Foundation.NSDocumentDirectory");
      break;
    case KWEB_APP_PATH_DOWNLOADS:
      resolved = SearchPath(NSDownloadsDirectory, result,
                            "macos.Foundation.NSDownloadsDirectory");
      break;
    case KWEB_APP_PATH_MUSIC:
      resolved = SearchPath(NSMusicDirectory, result,
                            "macos.Foundation.NSMusicDirectory");
      break;
    case KWEB_APP_PATH_PICTURES:
      resolved = SearchPath(NSPicturesDirectory, result,
                            "macos.Foundation.NSPicturesDirectory");
      break;
    case KWEB_APP_PATH_VIDEOS:
      resolved = SearchPath(NSMoviesDirectory, result,
                            "macos.Foundation.NSMoviesDirectory");
      break;
    default:
      *status = KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN;
      return false;
    }
    if (!resolved) {
      *status = KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE;
      return false;
    }
    *status = KWEB_SERVICES_STATUS_OK;
    return true;
  }
}

} // namespace kwebshell::services
