#include <jni.h>

#include <atomic>
#include <cstdint>
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

bool IsHighSurrogate(uint32_t value) {
  return value >= 0xD800U && value <= 0xDBFFU;
}

bool IsLowSurrogate(uint32_t value) {
  return value >= 0xDC00U && value <= 0xDFFFU;
}

void AppendUtf8(uint32_t code_point, std::string *output) {
  if (code_point <= 0x7FU) {
    output->push_back(static_cast<char>(code_point));
  } else if (code_point <= 0x7FFU) {
    output->push_back(static_cast<char>(0xC0U | (code_point >> 6U)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  } else if (code_point <= 0xFFFFU) {
    output->push_back(static_cast<char>(0xE0U | (code_point >> 12U)));
    output->push_back(
        static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  } else {
    output->push_back(static_cast<char>(0xF0U | (code_point >> 18U)));
    output->push_back(
        static_cast<char>(0x80U | ((code_point >> 12U) & 0x3FU)));
    output->push_back(
        static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  }
}

std::optional<std::string> JavaStringToUtf8(JNIEnv *env, jstring value) {
  if (value == nullptr) {
    return std::nullopt;
  }
  const jsize length = env->GetStringLength(value);
  const jchar *characters = env->GetStringChars(value, nullptr);
  if (characters == nullptr) {
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    return std::nullopt;
  }

  std::optional<std::string> result;
  try {
    std::string utf8;
    utf8.reserve(static_cast<size_t>(length) * 3U);
    bool valid = true;
    for (jsize index = 0; index < length && valid; ++index) {
      uint32_t code_point = characters[index];
      if (IsHighSurrogate(code_point)) {
        if (index + 1 >= length ||
            !IsLowSurrogate(characters[index + 1])) {
          valid = false;
          break;
        }
        const uint32_t low = characters[++index];
        code_point =
            0x10000U + ((code_point - 0xD800U) << 10U) + (low - 0xDC00U);
      } else if (IsLowSurrogate(code_point) || code_point == 0) {
        valid = false;
        break;
      }
      AppendUtf8(code_point, &utf8);
    }
    if (valid) {
      result = std::move(utf8);
    }
  } catch (...) {
    env->ReleaseStringChars(value, characters);
    throw;
  }
  env->ReleaseStringChars(value, characters);
  return result;
}

bool IsContinuationByte(uint8_t value) { return (value & 0xC0U) == 0x80U; }

std::optional<std::vector<jchar>> Utf8ToJavaCharacters(std::string_view utf8) {
  std::vector<jchar> output;
  output.reserve(utf8.size());
  size_t index = 0;
  while (index < utf8.size()) {
    const auto first = static_cast<uint8_t>(utf8[index]);
    uint32_t code_point = 0;
    size_t length = 0;
    if (first <= 0x7FU && first != 0) {
      code_point = first;
      length = 1;
    } else if (first >= 0xC2U && first <= 0xDFU &&
               index + 1 < utf8.size() &&
               IsContinuationByte(static_cast<uint8_t>(utf8[index + 1]))) {
      code_point = ((first & 0x1FU) << 6U) |
                   (static_cast<uint8_t>(utf8[index + 1]) & 0x3FU);
      length = 2;
    } else if (first >= 0xE0U && first <= 0xEFU &&
               index + 2 < utf8.size()) {
      const auto second = static_cast<uint8_t>(utf8[index + 1]);
      const auto third = static_cast<uint8_t>(utf8[index + 2]);
      if (!IsContinuationByte(second) || !IsContinuationByte(third) ||
          (first == 0xE0U && second < 0xA0U) ||
          (first == 0xEDU && second > 0x9FU)) {
        return std::nullopt;
      }
      code_point = ((first & 0x0FU) << 12U) | ((second & 0x3FU) << 6U) |
                   (third & 0x3FU);
      length = 3;
    } else if (first >= 0xF0U && first <= 0xF4U &&
               index + 3 < utf8.size()) {
      const auto second = static_cast<uint8_t>(utf8[index + 1]);
      const auto third = static_cast<uint8_t>(utf8[index + 2]);
      const auto fourth = static_cast<uint8_t>(utf8[index + 3]);
      if (!IsContinuationByte(second) || !IsContinuationByte(third) ||
          !IsContinuationByte(fourth) ||
          (first == 0xF0U && second < 0x90U) ||
          (first == 0xF4U && second > 0x8FU)) {
        return std::nullopt;
      }
      code_point = ((first & 0x07U) << 18U) | ((second & 0x3FU) << 12U) |
                   ((third & 0x3FU) << 6U) | (fourth & 0x3FU);
      length = 4;
    } else {
      return std::nullopt;
    }

    if (code_point <= 0xFFFFU) {
      output.push_back(static_cast<jchar>(code_point));
    } else {
      const uint32_t adjusted = code_point - 0x10000U;
      output.push_back(static_cast<jchar>(0xD800U + (adjusted >> 10U)));
      output.push_back(static_cast<jchar>(0xDC00U + (adjusted & 0x3FFU)));
    }
    index += length;
  }
  return output;
}

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
  const jint environment_status = context->vm->GetEnv(
      reinterpret_cast<void **>(&env), JNI_VERSION_1_8);
  if (environment_status == JNI_EDETACHED) {
    JavaVMAttachArgs arguments = {JNI_VERSION_1_8,
                                  const_cast<char *>("KWebShell-native"),
                                  nullptr};
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
    const auto characters = Utf8ToJavaCharacters(
        std::string_view(event->text, event->text_size));
    if (!characters || characters->size() >
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
    const kweb_session_config config = {
        sizeof(kweb_session_config), KWEB_ABI_VERSION, &ForwardEvent,
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
    const auto utf8 = JavaStringToUtf8(env, url);
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
       const_cast<char *>(
           "(Lio/github/kingsword09/kwebshell/desktop/internal/NativeEventSink;)J"),
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
  env->DeleteLocalRef(bindings);
  return registration == JNI_OK ? JNI_VERSION_1_8 : JNI_ERR;
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
}
