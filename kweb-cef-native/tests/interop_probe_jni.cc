#include "interop_probe_abi.h"

#include <jni.h>

#include <cstdint>
#include <string_view>

#include "jni_string.h"

namespace {

struct FixedContext {
  JNIEnv *env;
  jobject sink;
  jmethodID receive;
  jthrowable failure = nullptr;
  uint64_t logical_sequence = 0;
};

struct Utf8Context {
  JNIEnv *env;
  jobject sink;
  jmethodID receive;
  jthrowable failure = nullptr;
};

void CaptureFailure(JNIEnv *env, jthrowable *failure) {
  if (!env->ExceptionCheck()) {
    return;
  }
  jthrowable current = env->ExceptionOccurred();
  env->ExceptionClear();
  if (*failure == nullptr) {
    *failure = current;
  } else {
    env->DeleteLocalRef(current);
  }
}

uint64_t KWEB_ABI_CALL ReceiveFixed(void *user_data, uint64_t sequence) {
  auto *context = static_cast<FixedContext *>(user_data);
  const uint64_t delivered =
      context->logical_sequence == 0 ? sequence : context->logical_sequence;
  const jlong result = context->env->CallLongMethod(
      context->sink, context->receive, static_cast<jlong>(delivered));
  CaptureFailure(context->env, &context->failure);
  return context->failure == nullptr ? static_cast<uint64_t>(result)
                                     : KWEB_PROBE_INVALID_LAYOUT_VALUE;
}

uint64_t KWEB_ABI_CALL ReceiveUtf8(void *user_data, const char *text,
                                   size_t size, uint64_t sequence) {
  auto *context = static_cast<Utf8Context *>(user_data);
  const auto characters =
      kwebshell::jni::Utf8ToJavaCharacters(std::string_view(text, size));
  if (!characters) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  jstring value = context->env->NewString(
      characters->data(), static_cast<jsize>(characters->size()));
  CaptureFailure(context->env, &context->failure);
  if (value == nullptr || context->failure != nullptr) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  const jlong result = context->env->CallLongMethod(
      context->sink, context->receive, value, static_cast<jlong>(sequence));
  context->env->DeleteLocalRef(value);
  CaptureFailure(context->env, &context->failure);
  return context->failure == nullptr ? static_cast<uint64_t>(result)
                                     : KWEB_PROBE_INVALID_LAYOUT_VALUE;
}

jmethodID ReceiveMethod(JNIEnv *env, jobject sink, const char *signature) {
  if (sink == nullptr) {
    return nullptr;
  }
  jclass type = env->GetObjectClass(sink);
  if (type == nullptr) {
    return nullptr;
  }
  jmethodID method = env->GetMethodID(type, "receive", signature);
  env->DeleteLocalRef(type);
  return method;
}

bool RethrowCallbackFailure(JNIEnv *env, jthrowable failure) {
  if (failure == nullptr) {
    return false;
  }
  env->Throw(failure);
  env->DeleteLocalRef(failure);
  return true;
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_abiVersion(
    JNIEnv *, jclass) {
  return static_cast<jint>(kweb_probe_abi_version());
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_integerCall(
    JNIEnv *, jclass, jlong handle, jint width, jint height) {
  return static_cast<jlong>(kweb_probe_integer_call(
      static_cast<uint64_t>(handle), width, height));
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_utf8Call(
    JNIEnv *env, jclass, jstring text) {
  const auto utf8 = kwebshell::jni::JavaStringToUtf8(env, text);
  return utf8 ? static_cast<jlong>(
                    kweb_probe_utf8_call(utf8->data(), utf8->size()))
              : static_cast<jlong>(KWEB_PROBE_INVALID_LAYOUT_VALUE);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_fixedUpcall(
    JNIEnv *env, jclass, jobject sink, jint count) {
  const jmethodID receive = ReceiveMethod(env, sink, "(J)J");
  if (receive == nullptr || count <= 0) {
    return static_cast<jlong>(KWEB_PROBE_INVALID_LAYOUT_VALUE);
  }
  FixedContext context{env, sink, receive};
  const uint64_t result = kweb_probe_fixed_upcall(
      ReceiveFixed, &context, static_cast<uint32_t>(count));
  return RethrowCallbackFailure(env, context.failure)
             ? 0
             : static_cast<jlong>(result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_utf8Upcall(
    JNIEnv *env, jclass, jobject sink, jstring text, jint count) {
  const jmethodID receive =
      ReceiveMethod(env, sink, "(Ljava/lang/String;J)J");
  const auto utf8 = kwebshell::jni::JavaStringToUtf8(env, text);
  if (receive == nullptr || !utf8 || count <= 0) {
    return static_cast<jlong>(KWEB_PROBE_INVALID_LAYOUT_VALUE);
  }
  Utf8Context context{env, sink, receive};
  const uint64_t result = kweb_probe_utf8_upcall(
      ReceiveUtf8, &context, utf8->data(), utf8->size(),
      static_cast<uint32_t>(count));
  return RethrowCallbackFailure(env, context.failure)
             ? 0
             : static_cast<jlong>(result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_ownerCycles(
    JNIEnv *env, jclass, jobject sink, jint count) {
  const jmethodID receive = ReceiveMethod(env, sink, "(J)J");
  if (receive == nullptr || count <= 0) {
    return static_cast<jlong>(KWEB_PROBE_INVALID_LAYOUT_VALUE);
  }
  uint64_t result = 0;
  for (jint sequence = 1; sequence <= count; ++sequence) {
    jobject global_sink = env->NewGlobalRef(sink);
    if (global_sink == nullptr) {
      return static_cast<jlong>(KWEB_PROBE_INVALID_LAYOUT_VALUE);
    }
    FixedContext context{env, global_sink, receive, nullptr,
                         static_cast<uint64_t>(sequence)};
    result ^= kweb_probe_owner_cycles(ReceiveFixed, &context, 1);
    env->DeleteGlobalRef(global_sink);
    if (RethrowCallbackFailure(env, context.failure)) {
      return 0;
    }
  }
  return static_cast<jlong>(result);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_kingsword09_kwebshell_interop_probe_JniProbe_liveNativeBytes(
    JNIEnv *, jclass) {
  return static_cast<jlong>(kweb_probe_live_native_bytes());
}
