#import <Cocoa/Cocoa.h>
#import <QuartzCore/CALayer.h>

#include "awt_parent.h"

#include <jawt.h>
#include <jawt_md.h>

namespace kwebshell::jni {
namespace {

NSView *FindViewWithLayer(NSView *view, CALayer *layer) {
  if ([view layer] == layer) {
    return view;
  }
  for (NSView *child in [view subviews]) {
    NSView *match = FindViewWithLayer(child, layer);
    if (match != nil) {
      return match;
    }
  }
  return nil;
}

} // namespace

uintptr_t GetAwtNativeParent(JNIEnv *env, jobject component,
                             kweb_status *status_out) {
  if (env == nullptr || component == nullptr || status_out == nullptr) {
    if (status_out != nullptr) {
      *status_out = KWEB_STATUS_INVALID_ARGUMENT;
    }
    return 0;
  }
  JAWT awt{};
  awt.version = JAWT_VERSION_1_7;
  if (JAWT_GetAWT(env, &awt) == JNI_FALSE) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return 0;
  }
  JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
  if (surface == nullptr) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return 0;
  }
  const jint lock = surface->Lock(surface);
  if ((lock & JAWT_LOCK_ERROR) != 0) {
    awt.FreeDrawingSurface(surface);
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return 0;
  }
  JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
  CALayer *window_layer = nil;
  if (info != nullptr && info->platformInfo != nullptr) {
    id<JAWT_SurfaceLayers> layers =
        (__bridge id<JAWT_SurfaceLayers>)info->platformInfo;
    window_layer = [layers windowLayer];
    surface->FreeDrawingSurfaceInfo(info);
  }
  surface->Unlock(surface);
  awt.FreeDrawingSurface(surface);

  if (window_layer == nil) {
    *status_out = KWEB_STATUS_PARENT_SURFACE_INVALID;
    return 0;
  }
  __block NSWindow *parent = nil;
  void (^lookup)(void) = ^{
    for (NSWindow *window in [NSApp windows]) {
      if (FindViewWithLayer([window contentView], window_layer) != nil) {
        parent = window;
        break;
      }
    }
  };
  if ([NSThread isMainThread]) {
    lookup();
  } else {
    dispatch_sync(dispatch_get_main_queue(), lookup);
  }
  *status_out =
      parent == nil ? KWEB_STATUS_PARENT_SURFACE_INVALID : KWEB_STATUS_OK;
  return reinterpret_cast<uintptr_t>((__bridge void *)parent);
}

} // namespace kwebshell::jni
