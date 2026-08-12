#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "engine_jni_bridge.h"
#include "jni_string.h"
#include "kwebshell/native/abi.h"

namespace {

constexpr char kBindingsClass[] =
    "io/github/kingsword09/kwebshell/desktop/internal/NativeBindings";
constexpr char kSinkMethod[] = "onNativeEvent";
constexpr char kSinkSignature[] = "(JJILjava/lang/String;II)V";

struct JniCallbackContext final {
  JniCallbackContext(JavaVM *vm_value, jobject sink_value,
                     jmethodID on_event_value)
      : vm(vm_value), sink(sink_value), on_event(on_event_value) {}

  JavaVM *vm;
  jobject sink;
  jmethodID on_event;
  std::atomic<bool> failed = false;
};

std::mutex contexts_mutex;
std::map<kweb_session_handle, std::unique_ptr<JniCallbackContext>> contexts;

void MarkCallbackFailed(JniCallbackContext *context, JNIEnv *env) {
  context->failed.store(true, std::memory_order_release);
  if (env != nullptr && env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void KWEB_ABI_CALL ForwardEvent(void *user_data, const kweb_event *event) {
  auto *context = static_cast<JniCallbackContext *>(user_data);
  if (context == nullptr || event == nullptr ||
      event->struct_size < sizeof(kweb_event) ||
      event->abi_version != KWEB_ABI_VERSION) {
    if (context != nullptr) {
      context->failed.store(true, std::memory_order_release);
    }
    return;
  }

  JNIEnv *env = nullptr;
  bool attached = false;
  const jint environment_status =
      context->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8);
  if (environment_status == JNI_EDETACHED) {
    JavaVMAttachArgs arguments = {
        JNI_VERSION_1_8, const_cast<char *>("KWebShell-native"), nullptr};
    if (context->vm->AttachCurrentThread(reinterpret_cast<void **>(&env),
                                         &arguments) != JNI_OK) {
      context->failed.store(true, std::memory_order_release);
      return;
    }
    attached = true;
  } else if (environment_status != JNI_OK || env == nullptr) {
    context->failed.store(true, std::memory_order_release);
    return;
  }

  try {
    const auto characters = kwebshell::jni::Utf8ToJavaCharacters(
        std::string_view(event->text, event->text_size));
    if (!characters ||
        characters->size() >
            static_cast<size_t>(std::numeric_limits<jsize>::max())) {
      MarkCallbackFailed(context, env);
    } else {
      static constexpr jchar kEmptyText = 0;
      const jchar *character_data =
          characters->empty() ? &kEmptyText : characters->data();
      const jstring text = env->NewString(
          character_data, static_cast<jsize>(characters->size()));
      if (text == nullptr) {
        MarkCallbackFailed(context, env);
      } else {
        env->CallVoidMethod(context->sink, context->on_event,
                            static_cast<jlong>(event->session),
                            static_cast<jlong>(event->sequence),
                            static_cast<jint>(event->type), text,
                            static_cast<jint>(event->width),
                            static_cast<jint>(event->height));
        if (env->ExceptionCheck()) {
          MarkCallbackFailed(context, env);
        }
        env->DeleteLocalRef(text);
      }
    }
  } catch (...) {
    MarkCallbackFailed(context, env);
  }

  if (attached && context->vm->DetachCurrentThread() != JNI_OK) {
    context->failed.store(true, std::memory_order_release);
  }
}

jlong EncodeCreateFailure(kweb_status status) {
  return -static_cast<jlong>(status);
}

jint JNICALL NativeAbiVersion(JNIEnv *, jobject) {
  return static_cast<jint>(kweb_abi_version());
}

jlong JNICALL NativeCreate(JNIEnv *env, jobject, jobject sink) {
  if (sink == nullptr) {
    return EncodeCreateFailure(KWEB_STATUS_INVALID_ARGUMENT);
  }
  try {
    JavaVM *vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr) {
      return EncodeCreateFailure(KWEB_STATUS_INTERNAL_ERROR);
    }
    const jclass sink_class = env->GetObjectClass(sink);
    if (sink_class == nullptr) {
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
      return EncodeCreateFailure(KWEB_STATUS_INVALID_ARGUMENT);
    }
    const jmethodID on_event =
        env->GetMethodID(sink_class, kSinkMethod, kSinkSignature);
    env->DeleteLocalRef(sink_class);
    if (on_event == nullptr) {
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
      return EncodeCreateFailure(KWEB_STATUS_INVALID_ARGUMENT);
    }
    const jobject global_sink = env->NewGlobalRef(sink);
    if (global_sink == nullptr) {
      if (env->ExceptionCheck()) {
        env->ExceptionClear();
      }
      return EncodeCreateFailure(KWEB_STATUS_ALLOCATION_FAILED);
    }

    auto context =
        std::make_unique<JniCallbackContext>(vm, global_sink, on_event);
    const kweb_session_config config = {sizeof(kweb_session_config),
                                        KWEB_ABI_VERSION, &ForwardEvent,
                                        context.get()};
    kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
    const kweb_status status = kweb_session_create(&config, &handle);
    if (status != KWEB_STATUS_OK) {
      env->DeleteGlobalRef(global_sink);
      return EncodeCreateFailure(status);
    }

    try {
      std::lock_guard lock(contexts_mutex);
      contexts.emplace(handle, std::move(context));
    } catch (...) {
      kweb_session_close(handle);
      env->DeleteGlobalRef(global_sink);
      throw;
    }
    return static_cast<jlong>(handle);
  } catch (const std::bad_alloc &) {
    return EncodeCreateFailure(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return EncodeCreateFailure(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeRequestNavigation(JNIEnv *env, jobject, jlong handle,
                                     jstring url) {
  try {
    const auto utf8 = kwebshell::jni::JavaStringToUtf8(env, url);
    if (!utf8) {
      return static_cast<jint>(KWEB_STATUS_INVALID_TEXT_ENCODING);
    }
    return static_cast<jint>(kweb_session_request_navigation(
        static_cast<kweb_session_handle>(handle), utf8->data(), utf8->size()));
  } catch (const std::bad_alloc &) {
    return static_cast<jint>(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return static_cast<jint>(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeResize(JNIEnv *, jobject, jlong handle, jint width,
                          jint height) {
  return static_cast<jint>(kweb_session_resize(
      static_cast<kweb_session_handle>(handle), static_cast<int32_t>(width),
      static_cast<int32_t>(height)));
}

jint JNICALL NativeClose(JNIEnv *env, jobject, jlong handle_value) {
  const auto handle = static_cast<kweb_session_handle>(handle_value);
  std::lock_guard lock(contexts_mutex);
  const auto found = contexts.find(handle);
  if (found == contexts.end()) {
    return static_cast<jint>(KWEB_STATUS_INVALID_HANDLE);
  }
  const kweb_status close_status = kweb_session_close(handle);
  if (close_status != KWEB_STATUS_OK) {
    return static_cast<jint>(close_status);
  }
  const bool callback_failed =
      found->second->failed.load(std::memory_order_acquire);
  env->DeleteGlobalRef(found->second->sink);
  contexts.erase(found);
  return static_cast<jint>(callback_failed ? KWEB_STATUS_CALLBACK_FAILED
                                           : KWEB_STATUS_OK);
}

jlong JNICALL NativeLiveSessionCount(JNIEnv *, jobject) {
  return static_cast<jlong>(kweb_live_session_count());
}

template <typename Function> void *JniFunctionAddress(Function function) {
  static_assert(sizeof(Function) == sizeof(void *));
  void *address = nullptr;
  std::memcpy(&address, &function, sizeof(address));
  return address;
}

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
  JNINativeMethod methods[] = {
      {const_cast<char *>("abiVersion"), const_cast<char *>("()I"),
       JniFunctionAddress(&NativeAbiVersion)},
      {const_cast<char *>("create"),
       const_cast<char *>("(Lio/github/kingsword09/kwebshell/desktop/internal/"
                          "NativeEventSink;)J"),
       JniFunctionAddress(&NativeCreate)},
      {const_cast<char *>("requestNavigation"),
       const_cast<char *>("(JLjava/lang/String;)I"),
       JniFunctionAddress(&NativeRequestNavigation)},
      {const_cast<char *>("resize"), const_cast<char *>("(JII)I"),
       JniFunctionAddress(&NativeResize)},
      {const_cast<char *>("close"), const_cast<char *>("(J)I"),
       JniFunctionAddress(&NativeClose)},
      {const_cast<char *>("liveSessionCount"), const_cast<char *>("()J"),
       JniFunctionAddress(&NativeLiveSessionCount)},
  };
  const jint registration = env->RegisterNatives(
      bindings, methods,
      static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
  const jint engine_registration =
      registration == JNI_OK
          ? kwebshell::jni::RegisterEngineNatives(env, bindings)
          : JNI_ERR;
  env->DeleteLocalRef(bindings);
  return registration == JNI_OK && engine_registration == JNI_OK
             ? JNI_VERSION_1_8
             : JNI_ERR;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
  JNIEnv *env = nullptr;
  if (vm == nullptr ||
      vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_8) != JNI_OK ||
      env == nullptr) {
    return;
  }
  std::lock_guard lock(contexts_mutex);
  for (auto &[handle, context] : contexts) {
    kweb_session_close(handle);
    env->DeleteGlobalRef(context->sink);
  }
  contexts.clear();
  if (!kwebshell::jni::EngineJniCanUnload()) {
    std::abort();
  }
}
