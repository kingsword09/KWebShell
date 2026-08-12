#ifndef KWEBSHELL_NATIVE_AWT_PARENT_H_
#define KWEBSHELL_NATIVE_AWT_PARENT_H_

#include <cstdint>

#include <jni.h>

#include "kwebshell/native/base_abi.h"

namespace kwebshell::jni {

uintptr_t GetAwtNativeParent(JNIEnv *env, jobject component,
                             kweb_status *status_out);

} // namespace kwebshell::jni

#endif // KWEBSHELL_NATIVE_AWT_PARENT_H_
