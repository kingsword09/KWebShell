#ifndef KWEBSHELL_NATIVE_ENGINE_JNI_BRIDGE_H_
#define KWEBSHELL_NATIVE_ENGINE_JNI_BRIDGE_H_

#include <jni.h>

namespace kwebshell::jni {

jint RegisterEngineNatives(JNIEnv *env, jclass bindings);

bool EngineJniCanUnload();

} // namespace kwebshell::jni

#endif // KWEBSHELL_NATIVE_ENGINE_JNI_BRIDGE_H_
