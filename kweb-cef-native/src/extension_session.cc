#include "extension_session.h"

#include <atomic>
#include <cstring>
#include <filesystem>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <system_error>
#include <utility>
#include <vector>

#include "browser_session.h"
#include "engine_platform.h"
#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "kwebshell/native/cef_extension_abi.h"
#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumPathSize = 32768;
constexpr size_t kMaximumVersionSize = 128;
constexpr size_t kMaximumErrorCodeSize = 128;
constexpr size_t kMaximumErrorMessageSize = 1024 * 1024;
constexpr kweb_extension_operation_handle kMaximumOperationHandle =
    static_cast<kweb_extension_operation_handle>(
        std::numeric_limits<int64_t>::max());

static_assert(KWEB_EXTENSION_OPERATION_INSTALL ==
              CEF_KWEB_EXTENSION_OPERATION_INSTALL);
static_assert(KWEB_EXTENSION_OPERATION_UPDATE ==
              CEF_KWEB_EXTENSION_OPERATION_UPDATE);
static_assert(KWEB_EXTENSION_OPERATION_RELOAD ==
              CEF_KWEB_EXTENSION_OPERATION_RELOAD);
static_assert(KWEB_EXTENSION_OPERATION_UNINSTALL ==
              CEF_KWEB_EXTENSION_OPERATION_UNINSTALL);
static_assert(KWEB_EXTENSION_OPERATION_QUERY ==
              CEF_KWEB_EXTENSION_OPERATION_QUERY);
static_assert(KWEB_EXTENSION_OUTCOME_SUCCESS ==
              CEF_KWEB_EXTENSION_OUTCOME_SUCCESS);
static_assert(KWEB_EXTENSION_OUTCOME_REJECTED ==
              CEF_KWEB_EXTENSION_OUTCOME_REJECTED);
static_assert(KWEB_EXTENSION_OUTCOME_AMBIGUOUS ==
              CEF_KWEB_EXTENSION_OUTCOME_AMBIGUOUS);
static_assert(KWEB_EXTENSION_STATE_UNKNOWN == CEF_KWEB_EXTENSION_STATE_UNKNOWN);
static_assert(KWEB_EXTENSION_STATE_ABSENT == CEF_KWEB_EXTENSION_STATE_ABSENT);
static_assert(KWEB_EXTENSION_STATE_ENABLED == CEF_KWEB_EXTENSION_STATE_ENABLED);
static_assert(KWEB_EXTENSION_STATE_DISABLED ==
              CEF_KWEB_EXTENSION_STATE_DISABLED);
static_assert(KWEB_EXTENSION_STATE_TERMINATED ==
              CEF_KWEB_EXTENSION_STATE_TERMINATED);
static_assert(KWEB_EXTENSION_STATE_BLOCKLISTED ==
              CEF_KWEB_EXTENSION_STATE_BLOCKLISTED);
static_assert(KWEB_EXTENSION_STATE_BLOCKED == CEF_KWEB_EXTENSION_STATE_BLOCKED);

std::string PathToUtf8(const std::filesystem::path &path) {
#if defined(_WIN32)
  const std::u8string utf8 = path.u8string();
  return std::string(reinterpret_cast<const char *>(utf8.data()), utf8.size());
#else
  return path.string();
#endif
}

std::filesystem::path PathFromUtf8(std::string_view value) {
#if defined(_WIN32)
  const auto *begin = reinterpret_cast<const char8_t *>(value.data());
  return std::filesystem::path(std::u8string(begin, begin + value.size()));
#else
  return std::filesystem::path(value);
#endif
}

bool IsExtensionId(std::string_view value) {
  if (value.size() != 32) {
    return false;
  }
  for (const char byte : value) {
    if (byte < 'a' || byte > 'p') {
      return false;
    }
  }
  return true;
}

bool IsVersion(std::string_view value) {
  if (value.empty() || value.size() > kMaximumVersionSize ||
      value.front() == '.' || value.back() == '.') {
    return false;
  }
  bool previous_dot = false;
  for (const char byte : value) {
    if (byte == '.') {
      if (previous_dot) {
        return false;
      }
      previous_dot = true;
    } else if (byte >= '0' && byte <= '9') {
      previous_dot = false;
    } else {
      return false;
    }
  }
  return true;
}

std::optional<std::string> CopyString(kweb_string_view value,
                                      size_t maximum_size, bool required) {
  if (value.size > maximum_size || (value.size > 0 && value.data == nullptr) ||
      (required && value.size == 0)) {
    return std::nullopt;
  }
  std::string result(value.data == nullptr ? "" : value.data, value.size);
  if (result.find('\0') != std::string::npos ||
      !IsValidUtf8(result.data(), result.size())) {
    return std::nullopt;
  }
  return result;
}

std::optional<std::filesystem::path>
ValidateManagedDirectory(std::string_view value) {
  try {
    const std::filesystem::path path = PathFromUtf8(value);
    if (!path.is_absolute() || path.filename().empty()) {
      return std::nullopt;
    }
    std::error_code error;
    const auto status = std::filesystem::symlink_status(path, error);
    if (error || !std::filesystem::is_directory(status) ||
        std::filesystem::is_symlink(status)) {
      return std::nullopt;
    }
    const auto canonical = std::filesystem::canonical(path, error);
    if (error || canonical != path.lexically_normal()) {
      return std::nullopt;
    }
    return canonical;
  } catch (...) {
    return std::nullopt;
  }
}

struct ValidatedConfig final {
  kweb_extension_operation_type operation = 0;
  std::string extension_id;
  std::string expected_version;
  std::string extension_path;
  kweb_extension_result_callback callback = nullptr;
  void *user_data = nullptr;
};

std::optional<ValidatedConfig>
ValidateConfig(const kweb_extension_config *config) {
  if (config == nullptr || config->struct_size < sizeof(*config) ||
      config->abi_version != KWEB_ABI_VERSION || config->reserved != 0 ||
      config->callback == nullptr ||
      config->operation < KWEB_EXTENSION_OPERATION_INSTALL ||
      config->operation > KWEB_EXTENSION_OPERATION_QUERY) {
    return std::nullopt;
  }
  const bool package_required =
      config->operation != KWEB_EXTENSION_OPERATION_QUERY;
  auto extension_id = CopyString(config->extension_id, 32, true);
  auto expected_version = CopyString(config->expected_version,
                                     kMaximumVersionSize, package_required);
  auto extension_path =
      CopyString(config->extension_path, kMaximumPathSize, package_required);
  if (!extension_id || !expected_version || !extension_path ||
      !IsExtensionId(*extension_id)) {
    return std::nullopt;
  }
  if (package_required) {
    if (!IsVersion(*expected_version)) {
      return std::nullopt;
    }
    const auto directory = ValidateManagedDirectory(*extension_path);
    if (!directory) {
      return std::nullopt;
    }
    *extension_path = PathToUtf8(*directory);
  } else if (!expected_version->empty() || !extension_path->empty()) {
    return std::nullopt;
  }
  return ValidatedConfig{config->operation,
                         std::move(*extension_id),
                         std::move(*expected_version),
                         std::move(*extension_path),
                         config->callback,
                         config->user_data};
}

template <typename Function> Function ResolveFunction(const char *name) {
  void *symbol = ResolveCefRuntimeSymbol(name);
  if (symbol == nullptr) {
    return nullptr;
  }
  static_assert(sizeof(Function) == sizeof(symbol));
  Function function = nullptr;
  std::memcpy(&function, &symbol, sizeof(function));
  return function;
}

struct RuntimeApi final {
  cef_kweb_extension_abi_fingerprint_fn fingerprint = nullptr;
  cef_kweb_extension_start_fn start = nullptr;
  cef_kweb_extension_cancel_fn cancel = nullptr;
  cef_kweb_extension_live_operation_count_fn live_count = nullptr;

  bool IsComplete() const {
    return fingerprint != nullptr && start != nullptr && cancel != nullptr &&
           live_count != nullptr;
  }
};

class RuntimeApiRegistry final {
public:
  kweb_status Resolve(RuntimeApi *api_out) {
    if (api_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    std::lock_guard lock(mutex_);
    if (status_ == KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISSING ||
        status_ == KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISMATCH) {
      return status_;
    }
    if (!api_.IsComplete()) {
      RuntimeApi candidate;
      candidate.fingerprint =
          ResolveFunction<cef_kweb_extension_abi_fingerprint_fn>(
              "cef_kweb_extension_abi_fingerprint");
      candidate.start = ResolveFunction<cef_kweb_extension_start_fn>(
          "cef_kweb_extension_start");
      candidate.cancel = ResolveFunction<cef_kweb_extension_cancel_fn>(
          "cef_kweb_extension_cancel");
      candidate.live_count =
          ResolveFunction<cef_kweb_extension_live_operation_count_fn>(
              "cef_kweb_extension_live_operation_count");
      if (!candidate.IsComplete()) {
        status_ = KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISSING;
        return status_;
      }
      const char *fingerprint = candidate.fingerprint();
      if (fingerprint == nullptr ||
          std::string_view(fingerprint) != CEF_KWEB_EXTENSION_ABI_FINGERPRINT) {
        status_ = KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISMATCH;
        return status_;
      }
      api_ = candidate;
      status_ = KWEB_STATUS_OK;
    }
    *api_out = api_;
    return KWEB_STATUS_OK;
  }

private:
  std::mutex mutex_;
  RuntimeApi api_;
  kweb_status status_ = KWEB_STATUS_OK;
};

RuntimeApiRegistry &RuntimeRegistry() {
  static RuntimeApiRegistry registry;
  return registry;
}

struct RuntimeResult final {
  kweb_extension_outcome_type outcome = KWEB_EXTENSION_OUTCOME_AMBIGUOUS;
  kweb_extension_state_type state = KWEB_EXTENSION_STATE_UNKNOWN;
  std::string extension_id;
  std::string version;
  std::string path;
  std::string error_code;
  std::string error_message;
};

class ExtensionOperation final {
public:
  ExtensionOperation(kweb_extension_operation_handle handle,
                     kweb_engine_handle engine, kweb_browser_handle browser,
                     std::string profile_path, ValidatedConfig config)
      : handle_(handle), engine_(engine), browser_(browser),
        profile_path_(std::move(profile_path)), config_(std::move(config)) {}

  kweb_extension_operation_handle handle() const { return handle_; }
  kweb_engine_handle engine() const { return engine_; }
  kweb_browser_handle browser() const { return browser_; }
  const std::string &profile_path() const { return profile_path_; }
  const ValidatedConfig &config() const { return config_; }
  bool runtime_started() const {
    return runtime_started_.load(std::memory_order_acquire);
  }
  void mark_runtime_started() {
    runtime_started_.store(true, std::memory_order_release);
  }

private:
  const kweb_extension_operation_handle handle_;
  const kweb_engine_handle engine_;
  const kweb_browser_handle browser_;
  const std::string profile_path_;
  const ValidatedConfig config_;
  std::atomic<bool> runtime_started_ = false;
};

bool IsMutation(kweb_extension_operation_type operation) {
  return operation != KWEB_EXTENSION_OPERATION_QUERY;
}

std::optional<std::string>
CopyRuntimeString(cef_kweb_extension_string_view value, size_t maximum_size) {
  if (value.size > maximum_size || (value.size > 0 && value.data == nullptr)) {
    return std::nullopt;
  }
  std::string result(value.data == nullptr ? "" : value.data, value.size);
  if (result.find('\0') != std::string::npos ||
      !IsValidUtf8(result.data(), result.size())) {
    return std::nullopt;
  }
  return result;
}

bool IsKnownState(kweb_extension_state_type state) {
  return state >= KWEB_EXTENSION_STATE_ABSENT &&
         state <= KWEB_EXTENSION_STATE_BLOCKED;
}

std::optional<RuntimeResult>
ValidateRuntimeResult(const ExtensionOperation &operation,
                      const cef_kweb_extension_result *result) {
  if (result == nullptr || result->struct_size != sizeof(*result) ||
      result->abi_version != CEF_KWEB_EXTENSION_ABI_VERSION ||
      result->operation_id != operation.handle() ||
      result->operation != operation.config().operation ||
      result->reserved != 0 ||
      result->outcome < KWEB_EXTENSION_OUTCOME_SUCCESS ||
      result->outcome > KWEB_EXTENSION_OUTCOME_AMBIGUOUS ||
      result->state > KWEB_EXTENSION_STATE_BLOCKED) {
    return std::nullopt;
  }
  auto extension_id = CopyRuntimeString(result->extension_id, 32);
  auto version = CopyRuntimeString(result->version, kMaximumVersionSize);
  auto path = CopyRuntimeString(result->path, kMaximumPathSize);
  auto error_code =
      CopyRuntimeString(result->error_code, kMaximumErrorCodeSize);
  auto error_message =
      CopyRuntimeString(result->error_message, kMaximumErrorMessageSize);
  if (!extension_id || !version || !path || !error_code || !error_message ||
      *extension_id != operation.config().extension_id) {
    return std::nullopt;
  }
  const bool success = result->outcome == KWEB_EXTENSION_OUTCOME_SUCCESS;
  if (success != error_code->empty()) {
    return std::nullopt;
  }
  if (success) {
    switch (operation.config().operation) {
    case KWEB_EXTENSION_OPERATION_INSTALL:
    case KWEB_EXTENSION_OPERATION_UPDATE:
    case KWEB_EXTENSION_OPERATION_RELOAD:
      if (result->state != KWEB_EXTENSION_STATE_ENABLED ||
          *version != operation.config().expected_version ||
          *path != operation.config().extension_path) {
        return std::nullopt;
      }
      break;
    case KWEB_EXTENSION_OPERATION_UNINSTALL:
      if (result->state != KWEB_EXTENSION_STATE_ABSENT || !version->empty() ||
          !path->empty()) {
        return std::nullopt;
      }
      break;
    case KWEB_EXTENSION_OPERATION_QUERY:
      if (!IsKnownState(result->state) ||
          (result->state == KWEB_EXTENSION_STATE_ABSENT
               ? (!version->empty() || !path->empty())
               : (version->empty() || path->empty()))) {
        return std::nullopt;
      }
      break;
    default:
      return std::nullopt;
    }
  }
  return RuntimeResult{result->outcome,          result->state,
                       std::move(*extension_id), std::move(*version),
                       std::move(*path),         std::move(*error_code),
                       std::move(*error_message)};
}

kweb_string_view StringView(const std::string &value) {
  return {value.empty() ? nullptr : value.data(), value.size()};
}

kweb_status MapRuntimeStatus(cef_kweb_extension_status_t status) {
  switch (status) {
  case CEF_KWEB_EXTENSION_STATUS_OK:
    return KWEB_STATUS_OK;
  case CEF_KWEB_EXTENSION_STATUS_INVALID_ARGUMENT:
    return KWEB_STATUS_EXTENSION_OPERATION_INVALID;
  case CEF_KWEB_EXTENSION_STATUS_ABI_MISMATCH:
    return KWEB_STATUS_EXTENSION_RUNTIME_ABI_MISMATCH;
  case CEF_KWEB_EXTENSION_STATUS_WRONG_THREAD:
    return KWEB_STATUS_WRONG_THREAD;
  case CEF_KWEB_EXTENSION_STATUS_DUPLICATE_OPERATION:
    return KWEB_STATUS_EXTENSION_OPERATION_ACTIVE;
  case CEF_KWEB_EXTENSION_STATUS_OPERATION_NOT_FOUND:
    return KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND;
  case CEF_KWEB_EXTENSION_STATUS_INTERNAL_ERROR:
    return KWEB_STATUS_INTERNAL_ERROR;
  default:
    return KWEB_STATUS_EXTENSION_RESULT_INVALID;
  }
}

class ExtensionOperationRegistry final {
public:
  kweb_status Start(kweb_browser_handle browser,
                    const kweb_extension_config *config,
                    kweb_extension_operation_handle *operation_out);
  kweb_status Cancel(kweb_extension_operation_handle operation);
  kweb_status CancelForBrowser(kweb_browser_handle browser);
  uint64_t LiveCount() const;

  void StartOnUi(kweb_extension_operation_handle handle);
  void CancelOnUi(kweb_extension_operation_handle handle,
                  std::string error_code, std::string error_message);
  void CompleteFromRuntime(ExtensionOperation *callback_operation,
                           const cef_kweb_extension_result *result);

private:
  using MutationKey = std::pair<std::string, std::string>;

  std::shared_ptr<ExtensionOperation>
  Lookup(kweb_extension_operation_handle handle) const;
  void Complete(kweb_extension_operation_handle handle, RuntimeResult result);
  void CompleteInvalid(kweb_extension_operation_handle handle,
                       std::string error_code, std::string error_message);
  void RemoveLocked(const std::shared_ptr<ExtensionOperation> &operation);

  mutable std::mutex mutex_;
  std::map<kweb_extension_operation_handle, std::shared_ptr<ExtensionOperation>>
      operations_;
  std::map<MutationKey, kweb_extension_operation_handle> mutations_;
  std::map<kweb_extension_operation_handle, bool> cancel_requested_;
  kweb_extension_operation_handle next_handle_ = 1;
};

ExtensionOperationRegistry &OperationRegistry() {
  static ExtensionOperationRegistry registry;
  return registry;
}

void KWEB_CEF_CALLBACK
ReceiveRuntimeResult(void *user_data, const cef_kweb_extension_result *result) {
  auto *operation = static_cast<ExtensionOperation *>(user_data);
  if (operation == nullptr) {
    return;
  }
  const auto handle = operation->handle();
  try {
    OperationRegistry().CompleteFromRuntime(operation, result);
  } catch (...) {
    OperationRegistry().CancelOnUi(
        handle, "adapter-callback-failed",
        "The CEF extension adapter callback could not be processed.");
  }
}

kweb_status ExtensionOperationRegistry::Start(
    kweb_browser_handle browser, const kweb_extension_config *config,
    kweb_extension_operation_handle *operation_out) {
  if (operation_out == nullptr) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  *operation_out = KWEB_INVALID_EXTENSION_OPERATION_HANDLE;
  const auto validated = ValidateConfig(config);
  if (!validated) {
    return config != nullptr && config->abi_version != KWEB_ABI_VERSION
               ? KWEB_STATUS_ABI_MISMATCH
               : KWEB_STATUS_EXTENSION_OPERATION_INVALID;
  }
  kweb_engine_handle engine = KWEB_INVALID_ENGINE_HANDLE;
  std::filesystem::path profile_path;
  const kweb_status browser_status =
      GetBrowserExtensionContext(browser, &engine, &profile_path);
  if (browser_status != KWEB_STATUS_OK) {
    return browser_status;
  }
  RuntimeApi api;
  const kweb_status runtime_status = RuntimeRegistry().Resolve(&api);
  if (runtime_status != KWEB_STATUS_OK) {
    return runtime_status;
  }
  (void)api;

  const std::string profile = PathToUtf8(profile_path);
  kweb_extension_operation_handle handle =
      KWEB_INVALID_EXTENSION_OPERATION_HANDLE;
  std::shared_ptr<ExtensionOperation> operation;
  {
    std::lock_guard lock(mutex_);
    if (next_handle_ > kMaximumOperationHandle) {
      return KWEB_STATUS_HANDLE_EXHAUSTED;
    }
    const MutationKey mutation_key{profile, validated->extension_id};
    if (IsMutation(validated->operation) && mutations_.contains(mutation_key)) {
      return KWEB_STATUS_EXTENSION_OPERATION_ACTIVE;
    }
    handle = next_handle_++;
    operation = std::make_shared<ExtensionOperation>(
        handle, engine, browser, profile, std::move(*validated));
    operations_.emplace(handle, operation);
    if (IsMutation(operation->config().operation)) {
      mutations_.emplace(mutation_key, handle);
    }
  }
  if (!CefPostTask(TID_UI,
                   base::BindOnce(&ExtensionOperationRegistry::StartOnUi,
                                  base::Unretained(this), handle))) {
    std::lock_guard lock(mutex_);
    RemoveLocked(operation);
    return KWEB_STATUS_CEF_UI_TASK_FAILED;
  }
  *operation_out = handle;
  return KWEB_STATUS_OK;
}

kweb_status
ExtensionOperationRegistry::Cancel(kweb_extension_operation_handle handle) {
  std::shared_ptr<ExtensionOperation> operation;
  {
    std::lock_guard lock(mutex_);
    const auto found = operations_.find(handle);
    if (handle == KWEB_INVALID_EXTENSION_OPERATION_HANDLE ||
        found == operations_.end()) {
      return KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND;
    }
    if (cancel_requested_.contains(handle)) {
      return KWEB_STATUS_EXTENSION_OPERATION_ACTIVE;
    }
    cancel_requested_.emplace(handle, true);
    operation = found->second;
  }
  if (!CefPostTask(
          TID_UI,
          base::BindOnce(&ExtensionOperationRegistry::CancelOnUi,
                         base::Unretained(this), handle, "operation-cancelled",
                         "The extension operation was cancelled before its "
                         "result was known."))) {
    std::lock_guard lock(mutex_);
    cancel_requested_.erase(handle);
    return KWEB_STATUS_CEF_UI_TASK_FAILED;
  }
  return KWEB_STATUS_OK;
}

kweb_status
ExtensionOperationRegistry::CancelForBrowser(kweb_browser_handle browser) {
  std::vector<kweb_extension_operation_handle> handles;
  {
    std::lock_guard lock(mutex_);
    for (const auto &[handle, operation] : operations_) {
      if (operation->browser() == browser &&
          !cancel_requested_.contains(handle)) {
        handles.push_back(handle);
      }
    }
  }
  for (const auto handle : handles) {
    const kweb_status status = Cancel(handle);
    if (status != KWEB_STATUS_OK &&
        status != KWEB_STATUS_EXTENSION_OPERATION_NOT_FOUND &&
        status != KWEB_STATUS_EXTENSION_OPERATION_ACTIVE) {
      return status;
    }
  }
  return KWEB_STATUS_OK;
}

uint64_t ExtensionOperationRegistry::LiveCount() const {
  std::lock_guard lock(mutex_);
  return static_cast<uint64_t>(operations_.size());
}

void ExtensionOperationRegistry::StartOnUi(
    kweb_extension_operation_handle handle) {
  CEF_REQUIRE_UI_THREAD();
  auto operation = Lookup(handle);
  if (!operation) {
    return;
  }
  RuntimeApi api;
  if (RuntimeRegistry().Resolve(&api) != KWEB_STATUS_OK) {
    CompleteInvalid(handle, "adapter-unavailable",
                    "The CEF extension adapter became unavailable.");
    return;
  }
  const auto &config = operation->config();
  const cef_kweb_extension_request request = {
      sizeof(cef_kweb_extension_request),
      CEF_KWEB_EXTENSION_ABI_VERSION,
      handle,
      config.operation,
      0,
      {operation->profile_path().data(), operation->profile_path().size()},
      {config.extension_id.data(), config.extension_id.size()},
      {config.expected_version.empty() ? nullptr
                                       : config.expected_version.data(),
       config.expected_version.size()},
      {config.extension_path.empty() ? nullptr : config.extension_path.data(),
       config.extension_path.size()},
      &ReceiveRuntimeResult,
      operation.get(),
  };
  operation->mark_runtime_started();
  const auto adapter_status = api.start(&request);
  if (adapter_status != CEF_KWEB_EXTENSION_STATUS_OK) {
    CompleteInvalid(handle, "adapter-start-rejected",
                    kweb_status_name(MapRuntimeStatus(adapter_status)));
  }
}

void ExtensionOperationRegistry::CancelOnUi(
    kweb_extension_operation_handle handle, std::string error_code,
    std::string error_message) {
  CEF_REQUIRE_UI_THREAD();
  auto operation = Lookup(handle);
  if (!operation) {
    return;
  }
  if (operation->runtime_started()) {
    RuntimeApi api;
    if (RuntimeRegistry().Resolve(&api) == KWEB_STATUS_OK) {
      const auto status = api.cancel(handle);
      if (status == CEF_KWEB_EXTENSION_STATUS_OK) {
        return;
      }
      if (status == CEF_KWEB_EXTENSION_STATUS_OPERATION_NOT_FOUND &&
          !Lookup(handle)) {
        return;
      }
      error_code = "adapter-cancel-rejected";
      error_message = kweb_status_name(MapRuntimeStatus(status));
    }
  }
  Complete(handle, RuntimeResult{KWEB_EXTENSION_OUTCOME_AMBIGUOUS,
                                 KWEB_EXTENSION_STATE_UNKNOWN,
                                 operation->config().extension_id,
                                 {},
                                 {},
                                 std::move(error_code),
                                 std::move(error_message)});
}

void ExtensionOperationRegistry::CompleteFromRuntime(
    ExtensionOperation *callback_operation,
    const cef_kweb_extension_result *result) {
  CEF_REQUIRE_UI_THREAD();
  auto operation = Lookup(callback_operation->handle());
  if (!operation || operation.get() != callback_operation) {
    return;
  }
  auto validated = ValidateRuntimeResult(*operation, result);
  if (!validated) {
    CompleteInvalid(operation->handle(), "adapter-result-invalid",
                    "The CEF extension adapter returned an invalid result.");
    return;
  }
  Complete(operation->handle(), std::move(*validated));
}

std::shared_ptr<ExtensionOperation> ExtensionOperationRegistry::Lookup(
    kweb_extension_operation_handle handle) const {
  std::lock_guard lock(mutex_);
  const auto found = operations_.find(handle);
  return found == operations_.end() ? nullptr : found->second;
}

void ExtensionOperationRegistry::Complete(
    kweb_extension_operation_handle handle, RuntimeResult result) {
  std::shared_ptr<ExtensionOperation> operation;
  {
    std::lock_guard lock(mutex_);
    const auto found = operations_.find(handle);
    if (found == operations_.end()) {
      return;
    }
    operation = found->second;
    RemoveLocked(operation);
  }
  const kweb_extension_result event = {
      sizeof(kweb_extension_result),
      KWEB_ABI_VERSION,
      operation->handle(),
      operation->config().operation,
      result.outcome,
      result.state,
      0,
      operation->engine(),
      operation->browser(),
      StringView(result.extension_id),
      StringView(result.version),
      StringView(result.path),
      StringView(result.error_code),
      StringView(result.error_message),
  };
  operation->config().callback(operation->config().user_data, &event);
}

void ExtensionOperationRegistry::CompleteInvalid(
    kweb_extension_operation_handle handle, std::string error_code,
    std::string error_message) {
  auto operation = Lookup(handle);
  if (!operation) {
    return;
  }
  Complete(handle, RuntimeResult{KWEB_EXTENSION_OUTCOME_AMBIGUOUS,
                                 KWEB_EXTENSION_STATE_UNKNOWN,
                                 operation->config().extension_id,
                                 {},
                                 {},
                                 std::move(error_code),
                                 std::move(error_message)});
}

void ExtensionOperationRegistry::RemoveLocked(
    const std::shared_ptr<ExtensionOperation> &operation) {
  operations_.erase(operation->handle());
  cancel_requested_.erase(operation->handle());
  if (IsMutation(operation->config().operation)) {
    mutations_.erase(
        {operation->profile_path(), operation->config().extension_id});
  }
}

template <typename Function> kweb_status GuardStatus(Function function) {
  try {
    return function();
  } catch (const std::bad_alloc &) {
    return KWEB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return KWEB_STATUS_INTERNAL_ERROR;
  }
}

} // namespace

kweb_status
StartExtensionOperation(kweb_browser_handle browser,
                        const kweb_extension_config *config,
                        kweb_extension_operation_handle *operation_out) {
  return GuardStatus([&] {
    return OperationRegistry().Start(browser, config, operation_out);
  });
}

kweb_status
CancelExtensionOperation(kweb_extension_operation_handle operation) {
  return GuardStatus([&] { return OperationRegistry().Cancel(operation); });
}

kweb_status CancelExtensionOperationsForBrowser(kweb_browser_handle browser) {
  return GuardStatus(
      [&] { return OperationRegistry().CancelForBrowser(browser); });
}

uint64_t LiveExtensionOperationCount() {
  try {
    return OperationRegistry().LiveCount();
  } catch (...) {
    return std::numeric_limits<uint64_t>::max();
  }
}

} // namespace kwebshell
