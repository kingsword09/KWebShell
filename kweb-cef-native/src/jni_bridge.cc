#include <jni.h>

#include <cstdlib>

#include "engine_jni_bridge.h"

namespace {

constexpr char kBindingsClass[] =
    "io/github/kingsword09/kwebshell/desktop/internal/NativeBindings";

} // namespace

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm == nullptr ||
      vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK ||
      env == nullptr) {
    return JNI_ERR;
  }
  const jclass bindings = env->FindClass(kBindingsClass);
  if (bindings == nullptr) {
    return JNI_ERR;
  }
  const jint registration =
      kwebshell::jni::RegisterEngineNatives(env, bindings);
  env->DeleteLocalRef(bindings);
  return registration == JNI_OK ? JNI_VERSION_1_8 : JNI_ERR;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *, void *) {
  if (!kwebshell::jni::EngineJniCanUnload()) {
    std::abort();
  }
}
