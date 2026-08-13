#include "engine_jni_bridge.h"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <optional>
#include <string>
#include <system_error>

#if defined(_WIN32)
#include <windows.h>
#elif defined(__linux__)
#include <X11/Xlib.h>
#include <dlfcn.h>
#else
#include <dlfcn.h>
#endif

#include "jni_string.h"
#include "awt_parent.h"
#include "kwebshell/native/engine_abi.h"

namespace kwebshell::jni {
namespace {

constexpr char kEngineSinkMethod[] = "onNativeEngineEvent";
constexpr char kEngineSinkSignature[] = "(JJI)V";
constexpr char kBrowserSinkMethod[] = "onNativeBrowserEvent";
constexpr char kBrowserSinkSignature[] =
    "(JJJIILjava/lang/String;III)V";

using EngineAbiVersionFunction = uint32_t(KWEB_ABI_CALL *)(void);
using EnginePlatformStartupFunction = kweb_status(KWEB_ABI_CALL *)(const char *,
                                                                   size_t);
using EngineCreateFunction = kweb_status(KWEB_ABI_CALL *)(
    const kweb_engine_config *, kweb_engine_handle *);
using EngineCloseFunction = kweb_status(KWEB_ABI_CALL *)(kweb_engine_handle);
using LiveEngineCountFunction = uint64_t(KWEB_ABI_CALL *)(void);
using BrowserCreateFunction = kweb_status(KWEB_ABI_CALL *)(
    const kweb_browser_config *, kweb_browser_handle *);
using BrowserNavigateFunction = kweb_status(KWEB_ABI_CALL *)(
    kweb_browser_handle, const char *, size_t);
using BrowserResizeFunction = kweb_status(KWEB_ABI_CALL *)(kweb_browser_handle,
                                                           int32_t, int32_t);
using BrowserCloseFunction = kweb_status(KWEB_ABI_CALL *)(kweb_browser_handle);
using BrowserDevToolsFunction = kweb_status(KWEB_ABI_CALL *)(kweb_browser_handle);
using LiveBrowserCountFunction = uint64_t(KWEB_ABI_CALL *)(void);

#if defined(_WIN32)
using LibraryHandle = HMODULE;
constexpr LibraryHandle kInvalidLibraryHandle = nullptr;
#else
using LibraryHandle = void *;
constexpr LibraryHandle kInvalidLibraryHandle = nullptr;
#endif

struct EngineApi final {
  LibraryHandle runtime_library = kInvalidLibraryHandle;
  LibraryHandle engine_library = kInvalidLibraryHandle;
  std::filesystem::path runtime_path;
  std::filesystem::path engine_path;
  EngineAbiVersionFunction abi_version = nullptr;
  EnginePlatformStartupFunction platform_startup = nullptr;
  EngineCreateFunction create = nullptr;
  EngineCloseFunction close = nullptr;
  LiveEngineCountFunction live_count = nullptr;
  BrowserCreateFunction browser_create = nullptr;
  BrowserNavigateFunction browser_navigate = nullptr;
  BrowserResizeFunction browser_resize = nullptr;
  BrowserCloseFunction browser_close = nullptr;
  BrowserDevToolsFunction browser_open_devtools = nullptr;
  BrowserDevToolsFunction browser_close_devtools = nullptr;
  LiveBrowserCountFunction live_browser_count = nullptr;

  bool IsLoaded() const {
    return engine_library != kInvalidLibraryHandle && abi_version != nullptr &&
           platform_startup != nullptr && create != nullptr &&
           close != nullptr && live_count != nullptr &&
           browser_create != nullptr && browser_navigate != nullptr &&
           browser_resize != nullptr && browser_close != nullptr &&
           browser_open_devtools != nullptr &&
           browser_close_devtools != nullptr &&
           live_browser_count != nullptr;
  }
};

struct EngineJniCallbackContext final {
  EngineJniCallbackContext(JavaVM *vm_value, jobject sink_value,
                           jmethodID on_event_value)
      : vm(vm_value), sink(sink_value), on_event(on_event_value) {}

  JavaVM *vm;
  jobject sink;
  jmethodID on_event;
  std::atomic<kweb_engine_handle> handle = KWEB_INVALID_ENGINE_HANDLE;
  std::atomic<bool> failed = false;
  std::atomic<bool> terminal_event = false;
  std::atomic<bool> sink_released = false;
};

struct BrowserJniCallbackContext final {
  BrowserJniCallbackContext(JavaVM *vm_value, jobject sink_value,
                            jmethodID on_event_value)
      : vm(vm_value), sink(sink_value), on_event(on_event_value) {}

  JavaVM *vm;
  jobject sink;
  jmethodID on_event;
  std::atomic<kweb_browser_handle> handle = KWEB_INVALID_BROWSER_HANDLE;
  std::atomic<bool> failed = false;
  std::atomic<bool> terminal_event = false;
  std::atomic<bool> sink_released = false;
};

std::mutex api_mutex;
EngineApi engine_api;
std::mutex engine_contexts_mutex;
std::map<EngineJniCallbackContext *, std::shared_ptr<EngineJniCallbackContext>>
    engine_contexts;
std::mutex browser_contexts_mutex;
std::map<BrowserJniCallbackContext *, std::shared_ptr<BrowserJniCallbackContext>>
    browser_contexts;

void ReleaseEngineContext(
    JNIEnv *env, const std::shared_ptr<EngineJniCallbackContext> &context) {
  bool expected = false;
  if (!context->sink_released.compare_exchange_strong(
          expected, true, std::memory_order_acq_rel)) {
    return;
  }
  env->DeleteGlobalRef(context->sink);
  std::lock_guard lock(engine_contexts_mutex);
  const auto found = engine_contexts.find(context.get());
  if (found != engine_contexts.end() && found->second == context) {
    engine_contexts.erase(found);
  }
}

void ReleaseBrowserContext(
    JNIEnv *env, const std::shared_ptr<BrowserJniCallbackContext> &context) {
  bool expected = false;
  if (!context->sink_released.compare_exchange_strong(
          expected, true, std::memory_order_acq_rel)) {
    return;
  }
  env->DeleteGlobalRef(context->sink);
  std::lock_guard lock(browser_contexts_mutex);
  const auto found = browser_contexts.find(context.get());
  if (found != browser_contexts.end() && found->second == context) {
    browser_contexts.erase(found);
  }
}

std::optional<std::filesystem::path>
CanonicalLibraryPath(const std::string &utf8) {
  try {
#if defined(_WIN32)
    const auto *begin = reinterpret_cast<const char8_t *>(utf8.data());
    std::filesystem::path path(std::u8string(begin, begin + utf8.size()));
#else
    std::filesystem::path path(utf8);
#endif
    if (!path.is_absolute()) {
      return std::nullopt;
    }
    std::error_code error;
    if (!std::filesystem::is_regular_file(path, error) || error) {
      return std::nullopt;
    }
    auto canonical = std::filesystem::canonical(path, error);
    if (error) {
      return std::nullopt;
    }
    return canonical;
  } catch (...) {
    return std::nullopt;
  }
}

#if defined(_WIN32)
std::wstring Utf8PathToWide(const std::string &utf8) {
  const auto *begin = reinterpret_cast<const char8_t *>(utf8.data());
  return std::filesystem::path(std::u8string(begin, begin + utf8.size()))
      .wstring();
}
#endif

#if !defined(__APPLE__)
LibraryHandle LoadRuntimeLibrary(const std::string &runtime_path) {
#if defined(_WIN32)
  const std::wstring path = Utf8PathToWide(runtime_path);
  return ::LoadLibraryExW(path.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
#else
  return ::dlopen(runtime_path.c_str(), RTLD_NOW | RTLD_GLOBAL);
#endif
}
#endif

LibraryHandle LoadEngineLibrary(const std::string &engine_path) {
#if defined(_WIN32)
  const std::wstring path = Utf8PathToWide(engine_path);
  return ::LoadLibraryExW(path.c_str(), nullptr, LOAD_WITH_ALTERED_SEARCH_PATH);
#else
  return ::dlopen(engine_path.c_str(), RTLD_NOW | RTLD_LOCAL);
#endif
}

#if !defined(__APPLE__)
void UnloadLibrary(LibraryHandle library) {
  if (library == kInvalidLibraryHandle) {
    return;
  }
#if defined(_WIN32)
  ::FreeLibrary(library);
#else
  ::dlclose(library);
#endif
}
#endif

template <typename Function>
Function ResolveFunction(LibraryHandle library, const char *name) {
#if defined(_WIN32)
  const auto symbol = ::GetProcAddress(library, name);
#else
  const auto symbol = ::dlsym(library, name);
#endif
  if (symbol == nullptr) {
    return nullptr;
  }
  static_assert(sizeof(Function) == sizeof(symbol));
  Function function = nullptr;
  std::memcpy(&function, &symbol, sizeof(function));
  return function;
}

void ResetEngineApi(EngineApi *api) {
#if !defined(__APPLE__)
  UnloadLibrary(api->engine_library);
  UnloadLibrary(api->runtime_library);
#endif
  *api = EngineApi{};
}

kweb_status LoadEngineApi(const std::string &engine_path_utf8,
                          const std::string &runtime_path_utf8) {
  const auto canonical_engine = CanonicalLibraryPath(engine_path_utf8);
  const auto canonical_runtime = CanonicalLibraryPath(runtime_path_utf8);
  if (!canonical_engine || !canonical_runtime) {
    return KWEB_STATUS_PATH_NOT_FOUND;
  }

  std::lock_guard lock(api_mutex);
  if (engine_api.IsLoaded()) {
    if (engine_api.engine_path != *canonical_engine) {
      return KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED;
    }
    return engine_api.runtime_path == *canonical_runtime
               ? KWEB_STATUS_OK
               : KWEB_STATUS_CEF_RUNTIME_MISMATCH;
  }

  EngineApi candidate;
#if defined(__linux__)
  if (::XInitThreads() == 0) {
    return KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED;
  }
#endif
#if !defined(__APPLE__)
  candidate.runtime_library = LoadRuntimeLibrary(runtime_path_utf8);
  if (candidate.runtime_library == kInvalidLibraryHandle) {
    return KWEB_STATUS_CEF_RUNTIME_LOAD_FAILED;
  }
#endif
  candidate.engine_library = LoadEngineLibrary(engine_path_utf8);
  if (candidate.engine_library == kInvalidLibraryHandle) {
    ResetEngineApi(&candidate);
    return KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED;
  }
  candidate.abi_version = ResolveFunction<EngineAbiVersionFunction>(
      candidate.engine_library, "kweb_engine_abi_version");
  candidate.platform_startup = ResolveFunction<EnginePlatformStartupFunction>(
      candidate.engine_library, "kweb_engine_platform_startup");
  candidate.create = ResolveFunction<EngineCreateFunction>(
      candidate.engine_library, "kweb_engine_create");
  candidate.close = ResolveFunction<EngineCloseFunction>(
      candidate.engine_library, "kweb_engine_close");
  candidate.live_count = ResolveFunction<LiveEngineCountFunction>(
      candidate.engine_library, "kweb_live_engine_count");
  candidate.browser_create = ResolveFunction<BrowserCreateFunction>(
      candidate.engine_library, "kweb_browser_create");
  candidate.browser_navigate = ResolveFunction<BrowserNavigateFunction>(
      candidate.engine_library, "kweb_browser_navigate");
  candidate.browser_resize = ResolveFunction<BrowserResizeFunction>(
      candidate.engine_library, "kweb_browser_resize");
  candidate.browser_close = ResolveFunction<BrowserCloseFunction>(
      candidate.engine_library, "kweb_browser_close");
  candidate.browser_open_devtools = ResolveFunction<BrowserDevToolsFunction>(
      candidate.engine_library, "kweb_browser_open_devtools");
  candidate.browser_close_devtools = ResolveFunction<BrowserDevToolsFunction>(
      candidate.engine_library, "kweb_browser_close_devtools");
  candidate.live_browser_count = ResolveFunction<LiveBrowserCountFunction>(
      candidate.engine_library, "kweb_live_browser_count");
  if (candidate.abi_version == nullptr ||
      candidate.platform_startup == nullptr || candidate.create == nullptr ||
      candidate.close == nullptr || candidate.live_count == nullptr ||
      candidate.browser_create == nullptr ||
      candidate.browser_navigate == nullptr ||
      candidate.browser_resize == nullptr ||
      candidate.browser_close == nullptr ||
      candidate.browser_open_devtools == nullptr ||
      candidate.browser_close_devtools == nullptr ||
      candidate.live_browser_count == nullptr) {
    ResetEngineApi(&candidate);
    return KWEB_STATUS_ENGINE_SYMBOL_MISSING;
  }
  if (candidate.abi_version() != KWEB_ABI_VERSION) {
    ResetEngineApi(&candidate);
    return KWEB_STATUS_ABI_MISMATCH;
  }
  const kweb_status startup_status = candidate.platform_startup(
      runtime_path_utf8.data(), runtime_path_utf8.size());
  if (startup_status != KWEB_STATUS_OK) {
    ResetEngineApi(&candidate);
    return startup_status;
  }
  candidate.engine_path = *canonical_engine;
  candidate.runtime_path = *canonical_runtime;
  engine_api = candidate;
  return KWEB_STATUS_OK;
}

EngineApi SnapshotEngineApi() {
  std::lock_guard lock(api_mutex);
  return engine_api;
}

void MarkEngineCallbackFailed(EngineJniCallbackContext *context, JNIEnv *env) {
  context->failed.store(true, std::memory_order_release);
  if (env != nullptr && env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void MarkBrowserCallbackFailed(BrowserJniCallbackContext *context,
                               JNIEnv *env) {
  context->failed.store(true, std::memory_order_release);
  if (env != nullptr && env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
  }
}

void KWEB_ABI_CALL ForwardEngineEvent(void *user_data,
                                      const kweb_engine_event *event) {
  auto *context = static_cast<EngineJniCallbackContext *>(user_data);
  if (context == nullptr || event == nullptr ||
      event->struct_size < sizeof(kweb_engine_event) ||
      event->abi_version != KWEB_ABI_VERSION || event->engine == 0 ||
      event->sequence == 0) {
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
    JavaVMAttachArgs arguments = {JNI_VERSION_1_8,
                                  const_cast<char *>("KWebShell-engine-native"),
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

  env->CallVoidMethod(
      context->sink, context->on_event, static_cast<jlong>(event->engine),
      static_cast<jlong>(event->sequence), static_cast<jint>(event->type));
  if (env->ExceptionCheck()) {
    MarkEngineCallbackFailed(context, env);
  }
  std::shared_ptr<EngineJniCallbackContext> terminal_context;
  if (event->type == KWEB_ENGINE_EVENT_CLOSED) {
    context->terminal_event.store(true, std::memory_order_release);
    std::lock_guard lock(engine_contexts_mutex);
    const auto found = engine_contexts.find(context);
    if (found != engine_contexts.end() &&
        context->handle.load(std::memory_order_acquire) == event->engine) {
      terminal_context = found->second;
    } else {
      context->failed.store(true, std::memory_order_release);
    }
  }
  if (terminal_context) {
    ReleaseEngineContext(env, terminal_context);
  }
  if (attached && context->vm->DetachCurrentThread() != JNI_OK) {
    context->failed.store(true, std::memory_order_release);
  }
}

void KWEB_ABI_CALL ForwardBrowserEvent(void *user_data,
                                       const kweb_browser_event *event) {
  auto *context = static_cast<BrowserJniCallbackContext *>(user_data);
  if (context == nullptr || event == nullptr ||
      event->struct_size < sizeof(kweb_browser_event) ||
      event->abi_version != KWEB_ABI_VERSION || event->engine == 0 ||
      event->browser == 0 || event->sequence == 0 ||
      (event->text.data == nullptr && event->text.size != 0)) {
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
        JNI_VERSION_1_8, const_cast<char *>("KWebShell-browser-native"),
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
        std::string_view(event->text.data, event->text.size));
    if (!characters ||
        characters->size() >
            static_cast<size_t>((std::numeric_limits<jsize>::max)())) {
      MarkBrowserCallbackFailed(context, env);
    } else {
      static constexpr jchar kEmptyText = 0;
      const jchar *data =
          characters->empty() ? &kEmptyText : characters->data();
      jstring text =
          env->NewString(data, static_cast<jsize>(characters->size()));
      if (text == nullptr) {
        MarkBrowserCallbackFailed(context, env);
      } else {
        env->CallVoidMethod(
            context->sink, context->on_event,
            static_cast<jlong>(event->engine),
            static_cast<jlong>(event->browser),
            static_cast<jlong>(event->sequence),
            static_cast<jint>(event->type), static_cast<jint>(event->flags),
            text, static_cast<jint>(event->status_code),
            static_cast<jint>(event->width),
            static_cast<jint>(event->height));
        if (env->ExceptionCheck()) {
          MarkBrowserCallbackFailed(context, env);
        }
        env->DeleteLocalRef(text);
      }
    }
  } catch (...) {
    MarkBrowserCallbackFailed(context, env);
  }

  std::shared_ptr<BrowserJniCallbackContext> terminal_context;
  if (event->type == KWEB_BROWSER_EVENT_CLOSED) {
    context->terminal_event.store(true, std::memory_order_release);
    std::lock_guard lock(browser_contexts_mutex);
    const auto found = browser_contexts.find(context);
    if (found != browser_contexts.end() &&
        context->handle.load(std::memory_order_acquire) == event->browser) {
      terminal_context = found->second;
    } else {
      context->failed.store(true, std::memory_order_release);
    }
  }
  if (terminal_context) {
    ReleaseBrowserContext(env, terminal_context);
  }
  if (attached && context->vm->DetachCurrentThread() != JNI_OK) {
    context->failed.store(true, std::memory_order_release);
  }
}

jlong EncodeCreateFailure(kweb_status status) {
  return -static_cast<jlong>(status);
}

jint JNICALL NativeLoadEngineLibrary(JNIEnv *env, jobject, jstring engine_path,
                                     jstring runtime_path) {
  try {
    const auto engine_utf8 = JavaStringToUtf8(env, engine_path);
    const auto runtime_utf8 = JavaStringToUtf8(env, runtime_path);
    if (!engine_utf8 || !runtime_utf8) {
      return static_cast<jint>(KWEB_STATUS_INVALID_TEXT_ENCODING);
    }
    return static_cast<jint>(LoadEngineApi(*engine_utf8, *runtime_utf8));
  } catch (const std::bad_alloc &) {
    return static_cast<jint>(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return static_cast<jint>(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeEngineAbiVersion(JNIEnv *, jobject) {
  const EngineApi api = SnapshotEngineApi();
  return api.IsLoaded() ? static_cast<jint>(api.abi_version()) : 0;
}

jlong JNICALL NativeEngineCreate(JNIEnv *env, jobject, jobject sink,
                                 jstring cef_runtime_path,
                                 jstring browser_subprocess_path,
                                 jstring resources_path, jstring locales_path,
                                 jstring root_cache_path, jstring log_path,
                                 jint remote_debugging_port) {
  if (sink == nullptr) {
    return EncodeCreateFailure(KWEB_STATUS_INVALID_ARGUMENT);
  }
  try {
    const EngineApi api = SnapshotEngineApi();
    if (!api.IsLoaded()) {
      return EncodeCreateFailure(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
    }
    const auto runtime = JavaStringToUtf8(env, cef_runtime_path);
    const auto subprocess = JavaStringToUtf8(env, browser_subprocess_path);
    const auto resources = JavaStringToUtf8(env, resources_path);
    const auto locales = JavaStringToUtf8(env, locales_path);
    const auto root_cache = JavaStringToUtf8(env, root_cache_path);
    const auto log = JavaStringToUtf8(env, log_path);
    if (!runtime || !subprocess || !resources || !locales || !root_cache ||
        !log) {
      return EncodeCreateFailure(KWEB_STATUS_INVALID_TEXT_ENCODING);
    }

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
        env->GetMethodID(sink_class, kEngineSinkMethod, kEngineSinkSignature);
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
        std::make_shared<EngineJniCallbackContext>(vm, global_sink, on_event);
    try {
      std::lock_guard lock(engine_contexts_mutex);
      engine_contexts.emplace(context.get(), context);
    } catch (...) {
      env->DeleteGlobalRef(global_sink);
      throw;
    }
    const kweb_engine_config configuration = {
        sizeof(kweb_engine_config),
        KWEB_ABI_VERSION,
        &ForwardEngineEvent,
        context.get(),
        {runtime->data(), runtime->size()},
        {subprocess->data(), subprocess->size()},
        {resources->data(), resources->size()},
        {locales->data(), locales->size()},
        {root_cache->data(), root_cache->size()},
        {log->data(), log->size()},
        remote_debugging_port,
        0,
    };
    kweb_engine_handle handle = KWEB_INVALID_ENGINE_HANDLE;
    const kweb_status status = api.create(&configuration, &handle);
    if (status != KWEB_STATUS_OK) {
      ReleaseEngineContext(env, context);
      return EncodeCreateFailure(status);
    }
    context->handle.store(handle, std::memory_order_release);
    return static_cast<jlong>(handle);
  } catch (const std::bad_alloc &) {
    return EncodeCreateFailure(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return EncodeCreateFailure(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeEngineClose(JNIEnv *env, jobject, jlong handle_value) {
  const EngineApi api = SnapshotEngineApi();
  if (!api.IsLoaded()) {
    return static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
  }
  const auto handle = static_cast<kweb_engine_handle>(handle_value);
  std::shared_ptr<EngineJniCallbackContext> context;
  {
    std::lock_guard lock(engine_contexts_mutex);
    const auto found = std::find_if(
        engine_contexts.begin(), engine_contexts.end(),
        [handle](const auto &entry) {
          return entry.second->handle.load(std::memory_order_acquire) == handle;
        });
    if (found == engine_contexts.end()) {
      return static_cast<jint>(KWEB_STATUS_INVALID_HANDLE);
    }
    context = found->second;
  }
  const kweb_status close_status = api.close(handle);
  if (close_status != KWEB_STATUS_OK &&
      close_status != KWEB_STATUS_PLATFORM_INITIALIZATION_FAILED) {
    return static_cast<jint>(close_status);
  }
  if (context->terminal_event.load(std::memory_order_acquire)) {
    ReleaseEngineContext(env, context);
  }
  const bool callback_failed = context->failed.load(std::memory_order_acquire);
  if (callback_failed) {
    return static_cast<jint>(KWEB_STATUS_CALLBACK_FAILED);
  }
  return static_cast<jint>(close_status);
}

jlong JNICALL NativeLiveEngineCount(JNIEnv *, jobject) {
  const EngineApi api = SnapshotEngineApi();
  if (!api.IsLoaded()) {
    return -1;
  }
  const uint64_t count = api.live_count();
  if (count > static_cast<uint64_t>((std::numeric_limits<jlong>::max)())) {
    return -1;
  }
  return static_cast<jlong>(count);
}

jlong JNICALL NativeBrowserCreate(JNIEnv *env, jobject, jlong engine,
                                  jobject sink, jobject component,
                                  jstring profile_path, jstring initial_url,
                                  jint x, jint y, jint width, jint height) {
  if (sink == nullptr || component == nullptr) {
    return EncodeCreateFailure(KWEB_STATUS_INVALID_ARGUMENT);
  }
  try {
    const EngineApi api = SnapshotEngineApi();
    if (!api.IsLoaded()) {
      return EncodeCreateFailure(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
    }
    const auto profile = JavaStringToUtf8(env, profile_path);
    const auto url = JavaStringToUtf8(env, initial_url);
    if (!profile || !url) {
      return EncodeCreateFailure(KWEB_STATUS_INVALID_TEXT_ENCODING);
    }
    kweb_status parent_status = KWEB_STATUS_OK;
    const uintptr_t native_parent =
        GetAwtNativeParent(env, component, &parent_status);
    if (parent_status != KWEB_STATUS_OK) {
      return EncodeCreateFailure(parent_status);
    }
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
        env->GetMethodID(sink_class, kBrowserSinkMethod, kBrowserSinkSignature);
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
        std::make_shared<BrowserJniCallbackContext>(vm, global_sink, on_event);
    try {
      std::lock_guard lock(browser_contexts_mutex);
      browser_contexts.emplace(context.get(), context);
    } catch (...) {
      env->DeleteGlobalRef(global_sink);
      throw;
    }
    const kweb_browser_config configuration = {
        sizeof(kweb_browser_config),
        KWEB_ABI_VERSION,
        static_cast<kweb_engine_handle>(engine),
        0,
        native_parent,
        x,
        y,
        width,
        height,
        {profile->data(), profile->size()},
        {url->data(), url->size()},
        &ForwardBrowserEvent,
        context.get(),
    };
    kweb_browser_handle handle = KWEB_INVALID_BROWSER_HANDLE;
    const kweb_status status = api.browser_create(&configuration, &handle);
    if (status != KWEB_STATUS_OK) {
      ReleaseBrowserContext(env, context);
      return EncodeCreateFailure(status);
    }
    context->handle.store(handle, std::memory_order_release);
    return static_cast<jlong>(handle);
  } catch (const std::bad_alloc &) {
    return EncodeCreateFailure(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return EncodeCreateFailure(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeBrowserNavigate(JNIEnv *env, jobject, jlong handle,
                                   jstring url) {
  try {
    const auto value = JavaStringToUtf8(env, url);
    if (!value) {
      return static_cast<jint>(KWEB_STATUS_INVALID_TEXT_ENCODING);
    }
    const EngineApi api = SnapshotEngineApi();
    return api.IsLoaded()
               ? static_cast<jint>(api.browser_navigate(
                     static_cast<kweb_browser_handle>(handle), value->data(),
                     value->size()))
               : static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
  } catch (const std::bad_alloc &) {
    return static_cast<jint>(KWEB_STATUS_ALLOCATION_FAILED);
  } catch (...) {
    return static_cast<jint>(KWEB_STATUS_INTERNAL_ERROR);
  }
}

jint JNICALL NativeBrowserResize(JNIEnv *, jobject, jlong handle, jint width,
                                 jint height) {
  const EngineApi api = SnapshotEngineApi();
  return api.IsLoaded()
             ? static_cast<jint>(api.browser_resize(
                   static_cast<kweb_browser_handle>(handle), width, height))
             : static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
}

jint JNICALL NativeBrowserClose(JNIEnv *, jobject, jlong handle) {
  const EngineApi api = SnapshotEngineApi();
  return api.IsLoaded()
             ? static_cast<jint>(api.browser_close(
                   static_cast<kweb_browser_handle>(handle)))
             : static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
}

jint JNICALL NativeBrowserOpenDevTools(JNIEnv *, jobject, jlong handle) {
  const EngineApi api = SnapshotEngineApi();
  return api.IsLoaded()
             ? static_cast<jint>(api.browser_open_devtools(
                   static_cast<kweb_browser_handle>(handle)))
             : static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
}

jint JNICALL NativeBrowserCloseDevTools(JNIEnv *, jobject, jlong handle) {
  const EngineApi api = SnapshotEngineApi();
  return api.IsLoaded()
             ? static_cast<jint>(api.browser_close_devtools(
                   static_cast<kweb_browser_handle>(handle)))
             : static_cast<jint>(KWEB_STATUS_ENGINE_LIBRARY_LOAD_FAILED);
}

jlong JNICALL NativeLiveBrowserCount(JNIEnv *, jobject) {
  const EngineApi api = SnapshotEngineApi();
  if (!api.IsLoaded()) {
    return -1;
  }
  const uint64_t count = api.live_browser_count();
  return count <= static_cast<uint64_t>((std::numeric_limits<jlong>::max)())
             ? static_cast<jlong>(count)
             : -1;
}

template <typename Function> void *JniFunctionAddress(Function function) {
  static_assert(sizeof(Function) == sizeof(void *));
  void *address = nullptr;
  std::memcpy(&address, &function, sizeof(address));
  return address;
}

} // namespace

jint RegisterEngineNatives(JNIEnv *env, jclass bindings) {
  JNINativeMethod methods[] = {
      {const_cast<char *>("loadEngineLibrary"),
       const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;)I"),
       JniFunctionAddress(&NativeLoadEngineLibrary)},
      {const_cast<char *>("engineAbiVersion"), const_cast<char *>("()I"),
       JniFunctionAddress(&NativeEngineAbiVersion)},
      {const_cast<char *>("engineCreate"),
       const_cast<char *>(
           "(Lio/github/kingsword09/kwebshell/desktop/internal/"
           "NativeEngineEventSink;Ljava/lang/String;Ljava/lang/String;"
           "Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
           "Ljava/lang/String;I)J"),
       JniFunctionAddress(&NativeEngineCreate)},
      {const_cast<char *>("engineClose"), const_cast<char *>("(J)I"),
       JniFunctionAddress(&NativeEngineClose)},
      {const_cast<char *>("liveEngineCount"), const_cast<char *>("()J"),
       JniFunctionAddress(&NativeLiveEngineCount)},
      {const_cast<char *>("browserCreate"),
       const_cast<char *>(
           "(JLio/github/kingsword09/kwebshell/desktop/internal/"
           "NativeBrowserEventSink;Ljava/awt/Component;Ljava/lang/String;"
           "Ljava/lang/String;IIII)J"),
       JniFunctionAddress(&NativeBrowserCreate)},
      {const_cast<char *>("browserNavigate"),
       const_cast<char *>("(JLjava/lang/String;)I"),
       JniFunctionAddress(&NativeBrowserNavigate)},
      {const_cast<char *>("browserResize"), const_cast<char *>("(JII)I"),
       JniFunctionAddress(&NativeBrowserResize)},
      {const_cast<char *>("browserClose"), const_cast<char *>("(J)I"),
       JniFunctionAddress(&NativeBrowserClose)},
      {const_cast<char *>("browserOpenDevTools"), const_cast<char *>("(J)I"),
       JniFunctionAddress(&NativeBrowserOpenDevTools)},
      {const_cast<char *>("browserCloseDevTools"), const_cast<char *>("(J)I"),
       JniFunctionAddress(&NativeBrowserCloseDevTools)},
      {const_cast<char *>("liveBrowserCount"), const_cast<char *>("()J"),
       JniFunctionAddress(&NativeLiveBrowserCount)},
  };
  return env->RegisterNatives(
      bindings, methods,
      static_cast<jint>(sizeof(methods) / sizeof(methods[0])));
}

bool EngineJniCanUnload() {
  std::scoped_lock lock(engine_contexts_mutex, browser_contexts_mutex);
  return engine_contexts.empty() && browser_contexts.empty();
}

} // namespace kwebshell::jni
