#include "awt_parent.h"

#include <jawt.h>
#include <jawt_md.h>

namespace kwebshell::jni {

uintptr_t GetAwtNativeParent(JNIEnv *env, jobject component,
                             kweb_status *status_out) {
  if (env == nullptr || component == nullptr || status_out == nullptr) {
    if (status_out != nullptr) {
      *status_out = KWEB_STATUS_INVALID_ARGUMENT;
    }
    return 0;
  }
  JAWT awt{};
  awt.version = JAWT_VERSION_1_4;
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
  uintptr_t result = 0;
  if (info != nullptr && info->platformInfo != nullptr) {
#if defined(_WIN32)
    const auto *platform =
        static_cast<JAWT_Win32DrawingSurfaceInfo *>(info->platformInfo);
    result = reinterpret_cast<uintptr_t>(platform->hwnd);
#elif defined(__linux__)
    const auto *platform =
        static_cast<JAWT_X11DrawingSurfaceInfo *>(info->platformInfo);
    result = static_cast<uintptr_t>(platform->drawable);
#endif
    surface->FreeDrawingSurfaceInfo(info);
  }
  surface->Unlock(surface);
  awt.FreeDrawingSurface(surface);
  *status_out =
      result == 0 ? KWEB_STATUS_PARENT_SURFACE_INVALID : KWEB_STATUS_OK;
  return result;
}

} // namespace kwebshell::jni
