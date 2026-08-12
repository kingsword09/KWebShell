#ifndef KWEBSHELL_NATIVE_JNI_STRING_H_
#define KWEBSHELL_NATIVE_JNI_STRING_H_

#include <jni.h>

#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace kwebshell::jni {

std::optional<std::string> JavaStringToUtf8(JNIEnv *env, jstring value);

std::optional<std::vector<jchar>> Utf8ToJavaCharacters(std::string_view utf8);

} // namespace kwebshell::jni

#endif // KWEBSHELL_NATIVE_JNI_STRING_H_
