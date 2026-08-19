#include "browser_session.h"

#include <algorithm>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <system_error>
#include <utility>

#if defined(_WIN32)
#include <windows.h>
#endif

#include "browser_surface.h"
#include "bridge_protocol.h"
#include "engine_internal.h"
#include "include/base/cef_bind.h"
#include "include/base/cef_callback.h"
#include "include/cef_client.h"
#include "include/cef_cookie.h"
#include "include/cef_parser.h"
#include "include/cef_request_context.h"
#include "include/cef_request_context_handler.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumTextSize = 1024 * 1024;
constexpr int32_t kMaximumViewportDimension = 32768;
constexpr int64_t kCookieFlushTimeoutMs = 30000;
constexpr int64_t kDevToolsOpenTimeoutMs = 30000;
constexpr int kTerminalQuiescenceTasks = 3;
constexpr uint64_t kMaximumBridgeRequestId =
    static_cast<uint64_t>((std::numeric_limits<int64_t>::max)());
constexpr kweb_browser_handle kMaximumBrowserHandle =
    static_cast<kweb_browser_handle>(std::numeric_limits<int64_t>::max());

// Opt-in diagnostics for the browser close chain; enabled with the
// KWEBSHELL_TRACE_CLOSE environment variable.
void TraceCloseStage(kweb_browser_handle browser, const char *stage) {
  static const bool enabled = std::getenv("KWEBSHELL_TRACE_CLOSE") != nullptr;
  if (enabled) {
    std::fprintf(stderr, "KWEBSHELL_CLOSE_TRACE browser=%llu stage=%s",
                 static_cast<unsigned long long>(browser), stage);
#if defined(_WIN32)
    const HANDLE trace_process = ::GetCurrentProcess();
    DWORD handle_count = 0;
    if (::GetProcessHandleCount(trace_process, &handle_count)) {
      std::fprintf(stderr, " handles=%lu", handle_count);
    }
    const DWORD gdi_objects = ::GetGuiResources(trace_process, GR_GDIOBJECTS);
    const DWORD user_objects = ::GetGuiResources(trace_process, GR_USEROBJECTS);
    if (gdi_objects != 0 || user_objects != 0) {
      std::fprintf(stderr, " gdi=%lu user=%lu", gdi_objects, user_objects);
    }
#endif
    std::fprintf(stderr, "\n");
  }
}

// Opt-in diagnostics for the browser creation chain; enabled with the
// KWEBSHELL_TRACE_CREATE environment variable.
void TraceCreateStage(kweb_browser_handle browser, const char *stage) {
  static const bool enabled = std::getenv("KWEBSHELL_TRACE_CREATE") != nullptr;
  if (enabled) {
#if defined(_WIN32)
    const unsigned long ticks =
        static_cast<unsigned long>(::GetTickCount64() % 1000000000ULL);
#else
    const unsigned long ticks = 0;
#endif
    std::fprintf(stderr,
                 "KWEBSHELL_CREATE_TRACE browser=%llu stage=%s ticks=%lu\n",
                 static_cast<unsigned long long>(browser), stage, ticks);
  }
}

std::filesystem::path PathFromUtf8(const char *data, size_t size) {
#if defined(_WIN32)
  const auto *begin = reinterpret_cast<const char8_t *>(data);
  return std::filesystem::path(std::u8string(begin, begin + size));
#else
  return std::filesystem::path(std::string(data, size));
#endif
}

std::string PathToUtf8(const std::filesystem::path &path) {
#if defined(_WIN32)
  const std::u8string utf8 = path.u8string();
  return std::string(reinterpret_cast<const char *>(utf8.data()), utf8.size());
#else
  return path.string();
#endif
}

std::optional<std::filesystem::path>
ValidateProfilePath(kweb_string_view value,
                    const std::filesystem::path &root_cache) {
  if (value.data == nullptr || value.size == 0 || value.size > 32768 ||
      !IsValidUtf8(value.data, value.size)) {
    return std::nullopt;
  }
  try {
    const std::filesystem::path profile = PathFromUtf8(value.data, value.size);
    if (!profile.is_absolute() || profile.filename().empty() ||
        profile.filename() == "." || profile.filename() == "..") {
      return std::nullopt;
    }
    std::error_code error;
    const auto canonical_parent =
        std::filesystem::canonical(profile.parent_path(), error);
    if (error || canonical_parent != root_cache) {
      return std::nullopt;
    }
    const bool profile_exists = std::filesystem::exists(profile, error);
    if (error) {
      return std::nullopt;
    }
    if (profile_exists) {
      if (!std::filesystem::is_directory(profile, error) || error) {
        return std::nullopt;
      }
      const auto canonical_profile = std::filesystem::canonical(profile, error);
      if (error || canonical_profile.parent_path() != root_cache) {
        return std::nullopt;
      }
    }
    std::string profile_name = profile.filename().string();
    for (char &value_byte : profile_name) {
      if (value_byte >= 'A' && value_byte <= 'Z') {
        value_byte = static_cast<char>(value_byte - 'A' + 'a');
      }
    }
    if (profile_name == "default") {
      return std::nullopt;
    }
    return profile.lexically_normal();
  } catch (...) {
    return std::nullopt;
  }
}

std::optional<std::string> ValidateUrl(const char *data, size_t size) {
  if (data == nullptr || size == 0 || size > kMaximumTextSize ||
      !IsValidUtf8(data, size)) {
    return std::nullopt;
  }
  std::string url(data, size);
  CefURLParts parts;
  if (!CefParseURL(url, parts) || CefString(&parts.scheme).empty()) {
    return std::nullopt;
  }
  return url;
}


class BrowserSession;

class DevToolsClient;

class BridgeQueryHandler;

struct ProfileContextEntry;

class SessionRegistry final {
public:
  kweb_status Create(const kweb_browser_config *config,
                     kweb_browser_handle *browser_out);
  kweb_status Navigate(kweb_browser_handle handle, std::string url);
  kweb_status Resize(kweb_browser_handle handle, int32_t width, int32_t height);
  kweb_status Close(kweb_browser_handle handle);
  kweb_status OpenDevTools(kweb_browser_handle handle);
  kweb_status CloseDevTools(kweb_browser_handle handle);
  kweb_status BridgeRespond(kweb_browser_handle handle, uint64_t request_id,
                            std::string response, bool success);
  kweb_status ExtensionContext(kweb_browser_handle handle,
                               kweb_engine_handle *engine_out,
                               std::filesystem::path *profile_path_out) const;
  void Complete(kweb_browser_handle handle, BrowserSession *session);
  uint64_t LiveCount() const;

  // Shared profile request contexts; all access happens on the CEF UI thread.
  std::map<std::filesystem::path, std::shared_ptr<ProfileContextEntry>> &
  ProfileContexts() {
    return profile_contexts_;
  }
  void CompleteProfileContextInitialization(
      const std::shared_ptr<ProfileContextEntry> &entry,
      CefRefPtr<CefRequestContext> context);
  void ReleaseProfileContexts();

private:
  std::shared_ptr<BrowserSession> Lookup(kweb_browser_handle handle) const;

  mutable std::mutex mutex_;
  std::map<kweb_browser_handle, std::shared_ptr<BrowserSession>> sessions_;
  std::map<std::filesystem::path, std::shared_ptr<ProfileContextEntry>>
      profile_contexts_;
  kweb_browser_handle next_handle_ = 1;
};

SessionRegistry &Registry() {
  static SessionRegistry registry;
  return registry;
}

class SessionClient final : public CefClient,
                            public CefDisplayHandler,
                            public CefLifeSpanHandler,
                            public CefLoadHandler,
                            public CefRequestHandler {
public:
  explicit SessionClient(std::weak_ptr<BrowserSession> session)
      : session_(std::move(session)) {}
  ~SessionClient() override;

  CefRefPtr<CefDisplayHandler> GetDisplayHandler() override { return this; }
  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }
  CefRefPtr<CefLoadHandler> GetLoadHandler() override { return this; }
  CefRefPtr<CefRequestHandler> GetRequestHandler() override { return this; }

  void OnAddressChange(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                       const CefString &url) override;
  void OnTitleChange(CefRefPtr<CefBrowser> browser,
                     const CefString &title) override;
  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  bool DoClose(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;
  void OnLoadingStateChange(CefRefPtr<CefBrowser> browser, bool is_loading,
                            bool can_go_back, bool can_go_forward) override;
  void OnLoadEnd(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                 int http_status_code) override;
  void OnLoadError(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                   ErrorCode error_code, const CefString &error_text,
                   const CefString &failed_url) override;
  bool OnBeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                      CefRefPtr<CefRequest> request, bool user_gesture,
                      bool is_redirect) override;
  bool OnProcessMessageReceived(
      CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
      CefProcessId source_process,
      CefRefPtr<CefProcessMessage> message) override;
  void OnRenderProcessTerminated(CefRefPtr<CefBrowser> browser,
                                 TerminationStatus status, int error_code,
                                 const CefString &error_string) override;

private:
  const std::weak_ptr<BrowserSession> session_;
  IMPLEMENT_REFCOUNTING(SessionClient);
};

class DevToolsClient final : public CefClient, public CefLifeSpanHandler {
public:
  explicit DevToolsClient(std::weak_ptr<BrowserSession> session)
      : session_(std::move(session)) {}

  CefRefPtr<CefLifeSpanHandler> GetLifeSpanHandler() override { return this; }

  void OnAfterCreated(CefRefPtr<CefBrowser> browser) override;
  void OnBeforeClose(CefRefPtr<CefBrowser> browser) override;

private:
  const std::weak_ptr<BrowserSession> session_;
  IMPLEMENT_REFCOUNTING(DevToolsClient);
};

class BridgeQueryHandler final
    : public CefMessageRouterBrowserSide::Handler {
public:
  explicit BridgeQueryHandler(std::weak_ptr<BrowserSession> session)
      : session_(std::move(session)) {}

  bool OnQuery(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
               int64_t query_id, const CefString &request, bool persistent,
               CefRefPtr<Callback> callback) override;
  void OnQueryCanceled(CefRefPtr<CefBrowser> browser,
                       CefRefPtr<CefFrame> frame,
                       int64_t query_id) override;

private:
  const std::weak_ptr<BrowserSession> session_;
};

class ProfileContextHandler final : public CefRequestContextHandler {
public:
  explicit ProfileContextHandler(std::weak_ptr<ProfileContextEntry> entry)
      : entry_(std::move(entry)) {}
  void OnRequestContextInitialized(
      CefRefPtr<CefRequestContext> request_context) override;

private:
  const std::weak_ptr<ProfileContextEntry> entry_;
  IMPLEMENT_REFCOUNTING(ProfileContextHandler);
};

class CookieFlushCallback final : public CefCompletionCallback {
public:
  explicit CookieFlushCallback(std::shared_ptr<BrowserSession> session)
      : session_(std::move(session)) {}
  void OnComplete() override;

private:
  std::shared_ptr<BrowserSession> session_;
  IMPLEMENT_REFCOUNTING(CookieFlushCallback);
};

// A CefRequestContext shared by every browser created on one profile cache
// path. Chromium profile storage is expensive to initialize and tear down on
// the same cache path, and repeatedly rebuilding it under churn can stall
// context initialization for tens of seconds; sharing one context per path
// matches Chromium's own profile semantics. Accessed only on the CEF UI
// thread and released at engine close before CefShutdown.
struct ProfileContextEntry {
  explicit ProfileContextEntry(std::filesystem::path profile_path)
      : path(std::move(profile_path)) {}

  const std::filesystem::path path;
  CefRefPtr<CefRequestContext> context;
  bool initialized = false;
  bool failed = false;
  std::vector<std::shared_ptr<BrowserSession>> pending;
};

class BrowserSession final : public std::enable_shared_from_this<BrowserSession> {
public:
  BrowserSession(kweb_engine_handle engine, kweb_browser_handle handle,
                 uintptr_t native_parent, int32_t x, int32_t y, int32_t width,
                 int32_t height, std::filesystem::path profile_path,
                 std::string initial_url, kweb_browser_event_callback callback,
                 void *user_data, std::string bridge_origin,
                 kweb_bridge_event_callback bridge_callback,
                 void *bridge_user_data)
      : engine_(engine), handle_(handle), native_parent_(native_parent), x_(x),
        y_(y), width_(width), height_(height),
        profile_path_(std::move(profile_path)),
        initial_url_(std::move(initial_url)), callback_(callback),
        user_data_(user_data), bridge_origin_(std::move(bridge_origin)),
        bridge_callback_(bridge_callback), bridge_user_data_(bridge_user_data) {}

  kweb_status Start() {
    TraceCreateStage(handle_, "start-requested");
    auto self = shared_from_this();
    if (!CefPostTask(TID_UI, base::BindOnce(
                                 [](std::shared_ptr<BrowserSession> session) {
                                   session->CreateProfileContext();
                                 },
                                 std::move(self)))) {
      return KWEB_STATUS_CEF_UI_TASK_FAILED;
    }
    return KWEB_STATUS_OK;
  }

  void ProfileContextFailed(int32_t status_code, const std::string &code) {
    CEF_REQUIRE_UI_THREAD();
    Fatal(status_code, code);
  }

  kweb_status Navigate(std::string url) {
    if (closing_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_CLOSING;
    }
    if (!ready_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_NOT_READY;
    }
    auto self = shared_from_this();
    return CefPostTask(
               TID_UI,
               base::BindOnce(
                   [](std::shared_ptr<BrowserSession> session,
                      std::string target) {
                     if (!session->closing_.load(std::memory_order_acquire) &&
                         session->browser_) {
                       session->browser_->GetMainFrame()->LoadURL(target);
                     }
                   },
                   std::move(self), std::move(url)))
               ? KWEB_STATUS_OK
               : KWEB_STATUS_CEF_UI_TASK_FAILED;
  }

  kweb_status Resize(int32_t width, int32_t height) {
    if (closing_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_CLOSING;
    }
    if (!ready_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_NOT_READY;
    }
    auto self = shared_from_this();
    return CefPostTask(
               TID_UI,
               base::BindOnce(
                   [](std::shared_ptr<BrowserSession> session, int32_t new_width,
                      int32_t new_height) {
                     if (!session->closing_.load(std::memory_order_acquire)) {
                       session->ApplyResize(new_width, new_height);
                     }
                   },
                   std::move(self), width, height))
               ? KWEB_STATUS_OK
               : KWEB_STATUS_CEF_UI_TASK_FAILED;
  }

  kweb_status Close() {
    TraceCloseStage(handle_, "close-requested");
    bool expected = false;
    if (!closing_.compare_exchange_strong(expected, true,
                                          std::memory_order_acq_rel)) {
      return KWEB_STATUS_BROWSER_CLOSING;
    }
    auto self = shared_from_this();
    if (!CefPostTask(TID_UI,
                     base::BindOnce(
                       [](std::shared_ptr<BrowserSession> session) {
                           session->BeginClose();
                         },
                         std::move(self)))) {
      closing_.store(false, std::memory_order_release);
      return KWEB_STATUS_CEF_UI_TASK_FAILED;
    }
    return KWEB_STATUS_OK;
  }

  kweb_status OpenDevTools() {
    if (closing_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_CLOSING;
    }
    if (!ready_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_NOT_READY;
    }
    bool expected = false;
    if (!devtools_open_requested_.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
      return KWEB_STATUS_DEVTOOLS_ALREADY_OPEN;
    }
    devtools_open_failed_.store(false, std::memory_order_release);
    auto self = shared_from_this();
    if (!CefPostTask(
            TID_UI,
            base::BindOnce(
                [](std::shared_ptr<BrowserSession> session) {
                  session->ShowDevToolsOnUiThread();
                },
                std::move(self)))) {
      devtools_open_requested_.store(false, std::memory_order_release);
      devtools_close_requested_.store(false, std::memory_order_release);
      return KWEB_STATUS_CEF_UI_TASK_FAILED;
    }
    return KWEB_STATUS_OK;
  }

  kweb_status CloseDevTools() {
    if (!devtools_opened_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_DEVTOOLS_NOT_OPEN;
    }
    bool expected = false;
    if (!devtools_close_requested_.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
      return KWEB_STATUS_DEVTOOLS_CLOSING;
    }
    auto self = shared_from_this();
    if (!CefPostTask(
            TID_UI,
            base::BindOnce(
                [](std::shared_ptr<BrowserSession> session) {
                  session->CloseDevToolsOnUiThread();
                },
                std::move(self)))) {
      devtools_close_requested_.store(false, std::memory_order_release);
      return KWEB_STATUS_CEF_UI_TASK_FAILED;
    }
    return KWEB_STATUS_OK;
  }

  kweb_status BridgeRespond(uint64_t request_id, std::string response,
                            bool success) {
    if (request_id == 0) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    if (!IsValidBridgeJson(response)) {
      return KWEB_STATUS_BRIDGE_RESPONSE_INVALID;
    }
    CefRefPtr<CefMessageRouterBrowserSide::Callback> callback;
    {
      std::lock_guard lock(bridge_mutex_);
      const auto found = bridge_requests_.find(request_id);
      if (found == bridge_requests_.end()) {
        return KWEB_STATUS_BRIDGE_REQUEST_NOT_FOUND;
      }
      callback = found->second;
      bridge_requests_.erase(found);
      const auto query = std::find_if(
          bridge_query_ids_.begin(), bridge_query_ids_.end(),
          [request_id](const auto &entry) {
            return entry.second == request_id;
          });
      if (query != bridge_query_ids_.end()) {
        bridge_query_ids_.erase(query);
      }
    }
    if (success) {
      callback->Success(response);
    } else {
      callback->Failure(kBridgeFailureCode, response);
    }
    return KWEB_STATUS_OK;
  }

  kweb_status ExtensionContext(
      kweb_engine_handle *engine_out,
      std::filesystem::path *profile_path_out) const {
    if (engine_out == nullptr || profile_path_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    if (closing_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_CLOSING;
    }
    if (!ready_.load(std::memory_order_acquire)) {
      return KWEB_STATUS_BROWSER_NOT_READY;
    }
    *engine_out = engine_;
    *profile_path_out = profile_path_;
    return KWEB_STATUS_OK;
  }

  bool BridgeQuery(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
                   int64_t query_id, const CefString &request, bool persistent,
                   CefRefPtr<CefMessageRouterBrowserSide::Callback> callback) {
    CEF_REQUIRE_UI_THREAD();
    if (!bridge_callback_ || !browser_ || !browser->IsSame(browser_) ||
        !frame || !frame->IsMain() || persistent || query_id <= 0 ||
        !callback || BridgeOriginFromUrl(frame->GetURL()) != bridge_origin_) {
      return false;
    }
    const std::string payload = request.ToString();
    if (!IsValidBridgeJson(payload)) {
      callback->Failure(
          kBridgeFailureCode,
          "{\"code\":\"bridge.request.invalid\",\"message\":"
          "\"The bridge request is invalid.\"}");
      return true;
    }
    if (next_bridge_request_id_ > kMaximumBridgeRequestId) {
      Fatal(KWEB_STATUS_HANDLE_EXHAUSTED, "bridge-request-id-exhausted");
      return false;
    }
    const uint64_t request_id = next_bridge_request_id_++;
    {
      std::lock_guard lock(bridge_mutex_);
      bridge_requests_.emplace(request_id, callback);
      bridge_query_ids_.emplace(query_id, request_id);
    }
    EmitBridge(KWEB_BRIDGE_EVENT_REQUEST, request_id, payload);
    return true;
  }

  void BridgeQueryCanceled(int64_t query_id) {
    CEF_REQUIRE_UI_THREAD();
    uint64_t request_id = 0;
    {
      std::lock_guard lock(bridge_mutex_);
      const auto found = bridge_query_ids_.find(query_id);
      if (found == bridge_query_ids_.end()) {
        return;
      }
      request_id = found->second;
      bridge_query_ids_.erase(found);
      bridge_requests_.erase(request_id);
    }
    EmitBridge(KWEB_BRIDGE_EVENT_CANCELLED, request_id, {});
  }

  void ProfileInitialized(CefRefPtr<CefRequestContext> context) {
    CEF_REQUIRE_UI_THREAD();
    TraceCreateStage(handle_, "context-initialized");
    if (closing_.load(std::memory_order_acquire)) {
      // The terminal event already fired through the close path; the shared
      // context stays cached for later browsers on this profile.
      TraceCreateStage(handle_, "context-initialized-closing");
      BeginClose();
      return;
    }
    if (!context || context->IsGlobal() ||
        context->GetCachePath().ToString() != PathToUtf8(profile_path_)) {
      ProfileContextFailed(KWEB_STATUS_PROFILE_PATH_INVALID,
                           "profile-context-mismatch");
      return;
    }
    request_context_ = context;
    kweb_status surface_status = KWEB_STATUS_OK;
    surface_ = CreateBrowserSurface(native_parent_, x_, y_, width_, height_,
                                    &surface_status);
    if (!surface_) {
      TraceCreateStage(handle_, "surface-create-failed");
      Fatal(surface_status, "native-parent-unavailable");
      return;
    }
    TraceCreateStage(handle_, "surface-created");
    client_ = new SessionClient(weak_from_this());
    CefWindowInfo window_info;
    window_info.SetAsChild(surface_->parent_handle(),
                           CefRect(0, 0, width_, height_));
    window_info.runtime_style = CEF_RUNTIME_STYLE_ALLOY;
    window_info.windowless_rendering_enabled = false;
    CefBrowserSettings settings;
    settings.background_color = CefColorSetARGB(255, 255, 255, 255);
    CefRefPtr<CefDictionaryValue> extra_info;
    if (bridge_callback_) {
      bridge_handler_ = std::make_unique<BridgeQueryHandler>(weak_from_this());
      bridge_router_ = CefMessageRouterBrowserSide::Create(BridgeRouterConfig());
      if (!bridge_router_ ||
          !bridge_router_->AddHandler(bridge_handler_.get(), true)) {
        Fatal(KWEB_STATUS_BROWSER_CREATE_FAILED, "bridge-router-create-failed");
        return;
      }
      extra_info = CefDictionaryValue::Create();
      extra_info->SetBool(kBridgeEnabledKey, true);
      extra_info->SetString(kBridgeOriginKey, bridge_origin_);
    }
    TraceCreateStage(handle_, "create-browser-called");
    if (!CefBrowserHost::CreateBrowser(window_info, client_, initial_url_,
                                       settings, extra_info, request_context_)) {
      TraceCreateStage(handle_, "create-browser-rejected");
      Fatal(KWEB_STATUS_BROWSER_CREATE_FAILED, "cef-create-rejected");
      return;
    }
    TraceCreateStage(handle_, "create-browser-returned");
  }

  void BrowserCreated(CefRefPtr<CefBrowser> browser) {
    CEF_REQUIRE_UI_THREAD();
    TraceCreateStage(handle_, "browser-created");
    if (closing_.load(std::memory_order_acquire)) {
      TraceCreateStage(handle_, "browser-created-closing");
      browser_ = browser;
      BeginClose();
      return;
    }
    const bool valid_context = request_context_ &&
                               browser->GetHost()->GetRequestContext()->IsSame(
                                   request_context_);
    const bool valid_rendering =
        browser->GetHost()->GetRuntimeStyle() == CEF_RUNTIME_STYLE_ALLOY &&
        !browser->GetHost()->IsWindowRenderingDisabled() &&
        browser->GetHost()->GetWindowHandle() != kNullWindowHandle;
    browser_ = browser;
    surface_->BrowserCreated(browser);
    if (!valid_context || !valid_rendering || !surface_->ValidateParentage()) {
      Fatal(KWEB_STATUS_BROWSER_CREATE_FAILED, "browser-contract-invalid");
      return;
    }
    ready_.store(true, std::memory_order_release);
    Emit(KWEB_BROWSER_EVENT_CREATED, 0, {}, 0, width_, height_);
  }

  void DevToolsCreated(CefRefPtr<DevToolsClient> client,
                       CefRefPtr<CefBrowser> browser) {
    CEF_REQUIRE_UI_THREAD();
    if (devtools_client_.get() != client.get()) {
      if (browser) {
        browser->GetHost()->CloseBrowser(true);
      }
      return;
    }
    if (!browser || !browser->IsPopup() ||
        browser->GetHost()->GetRuntimeStyle() != CEF_RUNTIME_STYLE_CHROME ||
        browser->GetHost()->IsWindowRenderingDisabled() ||
        browser->GetHost()->GetWindowHandle() == kNullWindowHandle) {
      devtools_open_failed_.store(true, std::memory_order_release);
      Emit(KWEB_BROWSER_EVENT_DEVTOOLS_FAILED, 0,
           "devtools-browser-contract-invalid",
           KWEB_STATUS_DEVTOOLS_OPEN_FAILED, 0, 0);
      if (browser) {
        devtools_browser_ = browser;
        devtools_close_requested_.store(true, std::memory_order_release);
        browser->GetHost()->CloseBrowser(true);
      } else {
        ResetDevToolsState();
      }
      return;
    }
    devtools_browser_ = browser;
    if (devtools_open_failed_.load(std::memory_order_acquire)) {
      devtools_close_requested_.store(true, std::memory_order_release);
      browser->GetHost()->CloseBrowser(true);
      return;
    }
    if (closing_.load(std::memory_order_acquire)) {
      devtools_open_failed_.store(true, std::memory_order_release);
      Emit(KWEB_BROWSER_EVENT_DEVTOOLS_FAILED, 0, "browser-closing",
           KWEB_STATUS_BROWSER_CLOSING, 0, 0);
      devtools_close_requested_.store(true, std::memory_order_release);
      browser->GetHost()->CloseBrowser(true);
      return;
    }
    devtools_opened_.store(true, std::memory_order_release);
    Emit(KWEB_BROWSER_EVENT_DEVTOOLS_OPENED, 0, {}, 0, 0, 0);
  }

  void DevToolsClosed(CefRefPtr<DevToolsClient> client) {
    CEF_REQUIRE_UI_THREAD();
    if (devtools_client_.get() != client.get()) {
      return;
    }
    const bool was_opened =
        devtools_opened_.exchange(false, std::memory_order_acq_rel);
    if (was_opened) {
      Emit(KWEB_BROWSER_EVENT_DEVTOOLS_CLOSED, 0, {}, 0, 0, 0);
    }
    ResetDevToolsState();
  }

  void AddressChanged(const std::string &url) {
    Emit(KWEB_BROWSER_EVENT_ADDRESS_CHANGED, 0, url, 0, 0, 0);
  }

  void TitleChanged(const std::string &title) {
    Emit(KWEB_BROWSER_EVENT_TITLE_CHANGED, 0, title, 0, 0, 0);
  }

  void NavigationStarted(const std::string &url, bool user_gesture,
                         bool redirect) {
    uint32_t flags = user_gesture ? KWEB_BROWSER_FLAG_USER_GESTURE : 0;
    flags |= redirect ? KWEB_BROWSER_FLAG_REDIRECT : 0;
    Emit(KWEB_BROWSER_EVENT_NAVIGATION_STARTED, flags, url, 0, 0, 0);
  }

  void LoadingChanged(bool loading, bool can_go_back, bool can_go_forward) {
    uint32_t flags = loading ? KWEB_BROWSER_FLAG_LOADING : 0;
    flags |= can_go_back ? KWEB_BROWSER_FLAG_CAN_GO_BACK : 0;
    flags |= can_go_forward ? KWEB_BROWSER_FLAG_CAN_GO_FORWARD : 0;
    Emit(KWEB_BROWSER_EVENT_LOADING_STATE_CHANGED, flags, {}, 0, 0, 0);
  }

  void LoadEnded(const std::string &url, int status_code) {
    Emit(KWEB_BROWSER_EVENT_LOAD_ENDED, 0, url, status_code, 0, 0);
  }

  void LoadFailed(const std::string &url, int error_code) {
    Emit(KWEB_BROWSER_EVENT_LOAD_FAILED, 0, url, error_code, 0, 0);
  }

  void RendererTerminated(int status, int error_code,
                          const std::string &error) {
    if (bridge_router_ && browser_) {
      bridge_router_->OnRenderProcessTerminated(browser_);
    }
    Fatal(error_code == 0 ? status : error_code,
          error.empty() ? "renderer-terminated" : error);
  }

  bool CompleteBrowserClose(CefRefPtr<CefBrowser> browser) {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, closing_.load(std::memory_order_acquire)
                                  ? "do-close-closing"
                                  : "do-close-open");
    if (!browser) {
      // CEF delivered an unusable browser; take its default destruction path.
      return false;
    }
    if (!browser_ || !browser_->IsSame(browser) || !surface_) {
      Fatal(KWEB_STATUS_INTERNAL_ERROR, "browser-close-state-invalid");
      return true;
    }
    bool handled = false;
    const kweb_status status = surface_->CompleteBrowserClose(&handled);
    if (status != KWEB_STATUS_OK) {
      Fatal(status, "native-child-close-failed");
      return true;
    }
    return handled;
  }

  void BeforeClose() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, "before-close");
    before_close_observed_ = true;
    ready_.store(false, std::memory_order_release);
    if (bridge_router_ && browser_) {
      bridge_router_->OnBeforeClose(browser_);
      bridge_router_->RemoveHandler(bridge_handler_.get());
      bridge_handler_.reset();
      bridge_router_ = nullptr;
    }
    const bool wait_for_devtools =
        devtools_open_requested_.load(std::memory_order_acquire);
    if (wait_for_devtools) {
      source_close_waiting_for_devtools_ = true;
      devtools_close_requested_.store(true, std::memory_order_release);
      if (devtools_browser_) {
        devtools_browser_->GetHost()->CloseBrowser(true);
      }
    }
    if (surface_) {
      const kweb_status status = surface_->BrowserDestroyed();
      if (status != KWEB_STATUS_OK) {
        EmitFatalOnce(status, "native-surface-release-failed");
      }
    }
    browser_ = nullptr;
    client_ = nullptr;
    request_context_ = nullptr;
    MaybeScheduleTerminalCompletion();
  }

  void SourceClientDestroyed() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, "source-client-destroyed");
    source_client_destroyed_ = true;
    MaybeScheduleTerminalCompletion();
  }

  void FlushCompleted() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, "flush-completed");
    if (!flush_started_ || flush_completed_) {
      Fatal(KWEB_STATUS_INTERNAL_ERROR, "cookie-flush-state-invalid");
      return;
    }
    flush_completed_ = true;
    CloseBrowser();
  }

  bool HandleProcessMessage(CefRefPtr<CefBrowser> browser,
                            CefRefPtr<CefFrame> frame,
                            CefProcessId source_process,
                            CefRefPtr<CefProcessMessage> message) {
    CEF_REQUIRE_UI_THREAD();
    return bridge_router_ && bridge_router_->OnProcessMessageReceived(
                                 browser, frame, source_process, message);
  }

  void BeforeBrowse(CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame) {
    CEF_REQUIRE_UI_THREAD();
    if (bridge_router_) {
      bridge_router_->OnBeforeBrowse(browser, frame);
    }
  }

private:
  void ShowDevToolsOnUiThread() {
    CEF_REQUIRE_UI_THREAD();
    if (closing_.load(std::memory_order_acquire) || !browser_) {
      devtools_open_failed_.store(true, std::memory_order_release);
      Emit(KWEB_BROWSER_EVENT_DEVTOOLS_FAILED, 0, "browser-closing",
           KWEB_STATUS_BROWSER_CLOSING, 0, 0);
      devtools_open_requested_.store(false, std::memory_order_release);
      return;
    }
    CefRefPtr<DevToolsClient> client =
        new DevToolsClient(weak_from_this());
    devtools_client_ = client;
    CefWindowInfo window_info;
    ConfigureDevToolsWindow(window_info, native_parent_, 1200, 800);
    CefBrowserSettings settings;
    settings.background_color = CefColorSetARGB(255, 255, 255, 255);
    browser_->GetHost()->ShowDevTools(window_info, client, settings,
                                      CefPoint());
    CefPostDelayedTask(
        TID_UI,
        base::BindOnce(
            [](std::shared_ptr<BrowserSession> session,
               CefRefPtr<DevToolsClient> requested_client) {
              session->HandleDevToolsOpenTimeout(requested_client);
            },
            shared_from_this(), std::move(client)),
        kDevToolsOpenTimeoutMs);
  }

  void HandleDevToolsOpenTimeout(CefRefPtr<DevToolsClient> client) {
    CEF_REQUIRE_UI_THREAD();
    if (devtools_client_.get() != client.get() ||
        !devtools_open_requested_.load(std::memory_order_acquire) ||
        devtools_opened_.load(std::memory_order_acquire)) {
      return;
    }
    Emit(KWEB_BROWSER_EVENT_DEVTOOLS_FAILED, 0, "devtools-open-timeout",
         KWEB_STATUS_DEVTOOLS_OPEN_FAILED, 0, 0);
    devtools_open_failed_.store(true, std::memory_order_release);
    if (browser_ && browser_->GetHost()->HasDevTools()) {
      devtools_close_requested_.store(true, std::memory_order_release);
      browser_->GetHost()->CloseDevTools();
      return;
    }
    devtools_client_ = nullptr;
    devtools_open_requested_.store(false, std::memory_order_release);
    devtools_close_requested_.store(false, std::memory_order_release);
    if (source_close_waiting_for_devtools_) {
      source_close_waiting_for_devtools_ = false;
      MaybeScheduleTerminalCompletion();
    }
  }

  void CloseDevToolsOnUiThread() {
    CEF_REQUIRE_UI_THREAD();
    if (browser_ && devtools_opened_.load(std::memory_order_acquire)) {
      browser_->GetHost()->CloseDevTools();
      return;
    }
  }

  void ResetDevToolsState() {
    CEF_REQUIRE_UI_THREAD();
    devtools_browser_ = nullptr;
    devtools_client_ = nullptr;
    devtools_opened_.store(false, std::memory_order_release);
    devtools_open_requested_.store(false, std::memory_order_release);
    devtools_close_requested_.store(false, std::memory_order_release);
    devtools_open_failed_.store(false, std::memory_order_release);
    if (source_close_waiting_for_devtools_) {
      source_close_waiting_for_devtools_ = false;
      MaybeScheduleTerminalCompletion();
    }
  }

  void CreateProfileContext() {
    CEF_REQUIRE_UI_THREAD();
    TraceCreateStage(handle_, "context-create-begin");
    auto &cache = Registry().ProfileContexts();
    auto found = cache.find(profile_path_);
    if (found != cache.end() && found->second->failed) {
      // The previous initialization of this profile failed; retry with a
      // fresh context instead of failing every later session on the path.
      cache.erase(found);
      found = cache.end();
    }
    std::shared_ptr<ProfileContextEntry> entry;
    if (found != cache.end()) {
      entry = found->second;
    } else {
      entry = std::make_shared<ProfileContextEntry>(profile_path_);
      CefRequestContextSettings settings;
#if defined(_WIN32)
      CefString(&settings.cache_path) = profile_path_.wstring();
#else
      CefString(&settings.cache_path) = profile_path_.string();
#endif
      settings.persist_session_cookies = true;
      entry->context = CefRequestContext::CreateContext(
          settings, new ProfileContextHandler(entry));
      if (!entry->context) {
        TraceCreateStage(handle_, "context-create-failed");
        Fatal(KWEB_STATUS_BROWSER_CREATE_FAILED, "profile-context-create-failed");
        return;
      }
      TraceCreateStage(handle_, "context-create-returned");
      cache.emplace(profile_path_, entry);
    }
    if (entry->initialized) {
      TraceCreateStage(handle_, "context-reused");
      ProfileInitialized(entry->context);
      return;
    }
    TraceCreateStage(handle_, "context-pending");
    entry->pending.push_back(shared_from_this());
  }

  void ApplyResize(int32_t width, int32_t height) {
    CEF_REQUIRE_UI_THREAD();
    if (!surface_) {
      Fatal(KWEB_STATUS_PARENT_SURFACE_INVALID, "surface-missing-on-resize");
      return;
    }
    int32_t actual_width = 0;
    int32_t actual_height = 0;
    const kweb_status status =
        surface_->Resize(width, height, &actual_width, &actual_height);
    if (status != KWEB_STATUS_OK) {
      Fatal(status, "native-resize-verification-failed");
      return;
    }
    width_ = actual_width;
    height_ = actual_height;
    Emit(KWEB_BROWSER_EVENT_RESIZED, 0, {}, 0, actual_width, actual_height);
  }

  void BeginClose() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, "begin-close");
    closing_.store(true, std::memory_order_release);
    ready_.store(false, std::memory_order_release);
    if (!request_context_) {
      CompleteTerminal();
      return;
    }
    if (flush_started_) {
      return;
    }
    CefRefPtr<CefCookieManager> cookie_manager =
        request_context_->GetCookieManager(nullptr);
    if (!cookie_manager) {
      Fatal(KWEB_STATUS_INTERNAL_ERROR, "cookie-manager-missing");
      return;
    }
    flush_started_ = true;
    CefPostDelayedTask(
        TID_UI,
        base::BindOnce(
            [](std::shared_ptr<BrowserSession> session) {
              if (!session->flush_completed_) {
                session->Fatal(KWEB_STATUS_INTERNAL_ERROR,
                               "cookie-flush-timeout");
              }
            },
            shared_from_this()),
        kCookieFlushTimeoutMs);
    if (!cookie_manager->FlushStore(
            new CookieFlushCallback(shared_from_this()))) {
      Fatal(KWEB_STATUS_INTERNAL_ERROR, "cookie-flush-rejected");
    }
  }

  void CloseBrowser() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, browser_ ? "close-browser-live" : "close-browser-gone");
    if (browser_) {
      const kweb_status status = surface_->RequestBrowserClose();
      if (status != KWEB_STATUS_OK) {
        EmitFatalOnce(status, "native-child-close-failed");
      }
      return;
    }
    if (surface_) {
      surface_.reset();
    }
    client_ = nullptr;
    request_context_ = nullptr;
    CompleteTerminal();
  }

  void Fatal(int32_t status_code, std::string code) {
    CEF_REQUIRE_UI_THREAD();
    EmitFatalOnce(status_code, std::move(code));
    closing_.store(true, std::memory_order_release);
    if (flush_started_ && !flush_completed_) {
      CloseBrowser();
      return;
    }
    BeginClose();
  }

  void EmitFatalOnce(int32_t status_code, std::string code) {
    bool expected = false;
    if (fatal_emitted_.compare_exchange_strong(expected, true,
                                               std::memory_order_acq_rel)) {
      std::fprintf(stderr,
                   "KWEBSHELL_NATIVE_FATAL browser=%llu status=%d code=%s\n",
                   static_cast<unsigned long long>(handle_), status_code,
                   code.c_str());
      Emit(KWEB_BROWSER_EVENT_FATAL_ERROR, 0, code, status_code, 0, 0);
    }
  }

  void ScheduleTerminalCompletion() {
    CEF_REQUIRE_UI_THREAD();
    TraceCloseStage(handle_, "terminal-scheduled");
    bool expected = false;
    if (!terminal_completion_scheduled_.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
      return;
    }
    PostTerminalCompletionTask(kTerminalQuiescenceTasks);
  }

  void MaybeScheduleTerminalCompletion() {
    CEF_REQUIRE_UI_THREAD();
    if (before_close_observed_ && source_client_destroyed_ &&
        !source_close_waiting_for_devtools_) {
      ScheduleTerminalCompletion();
    }
  }

  void PostTerminalCompletionTask(int remaining_tasks) {
    CEF_REQUIRE_UI_THREAD();
    if (remaining_tasks == 0) {
      CompleteTerminal();
      return;
    }
    auto self = shared_from_this();
    if (!CefPostTask(
            TID_UI,
            base::BindOnce(
                [](std::shared_ptr<BrowserSession> session, int remaining) {
                  session->PostTerminalCompletionTask(remaining);
                },
                std::move(self), remaining_tasks - 1))) {
      EmitFatalOnce(KWEB_STATUS_CEF_UI_TASK_FAILED,
                    "terminal-completion-task-rejected");
      CompleteTerminal();
    }
  }

  void CompleteTerminal() {
    TraceCloseStage(handle_, "terminal-complete");
    bool expected = false;
    if (!terminal_emitted_.compare_exchange_strong(
            expected, true, std::memory_order_acq_rel)) {
      return;
    }
    surface_.reset();
    auto keep_alive = shared_from_this();
    Registry().Complete(handle_, keep_alive.get());
    keep_alive->Emit(KWEB_BROWSER_EVENT_CLOSED, 0, {}, 0, 0, 0);
  }

  void Emit(kweb_browser_event_type type, uint32_t flags,
            const std::string &text, int32_t status_code, int32_t width,
            int32_t height) {
    const kweb_string_view text_view = {text.data(), text.size()};
    const kweb_browser_event event = {
        sizeof(kweb_browser_event), KWEB_ABI_VERSION, type, flags,
        engine_,                    handle_,          ++sequence_,
        text_view,                  status_code,      width,
        height,                     0};
    callback_(user_data_, &event);
  }

  void EmitBridge(kweb_bridge_event_type type, uint64_t request_id,
                  const std::string &payload) {
    const kweb_bridge_event event = {
        sizeof(kweb_bridge_event), KWEB_ABI_VERSION, type, 0, engine_, handle_,
        request_id, {payload.data(), payload.size()}};
    bridge_callback_(bridge_user_data_, &event);
  }

  const kweb_engine_handle engine_;
  const kweb_browser_handle handle_;
  const uintptr_t native_parent_;
  const int32_t x_;
  const int32_t y_;
  int32_t width_;
  int32_t height_;
  const std::filesystem::path profile_path_;
  const std::string initial_url_;
  const kweb_browser_event_callback callback_;
  void *const user_data_;
  const std::string bridge_origin_;
  const kweb_bridge_event_callback bridge_callback_;
  void *const bridge_user_data_;
  std::unique_ptr<BrowserSurface> surface_;
  CefRefPtr<SessionClient> client_;
  CefRefPtr<CefRequestContext> request_context_;
  CefRefPtr<CefBrowser> browser_;
  CefRefPtr<DevToolsClient> devtools_client_;
  CefRefPtr<CefBrowser> devtools_browser_;
  std::unique_ptr<BridgeQueryHandler> bridge_handler_;
  CefRefPtr<CefMessageRouterBrowserSide> bridge_router_;
  std::mutex bridge_mutex_;
  std::map<uint64_t, CefRefPtr<CefMessageRouterBrowserSide::Callback>>
      bridge_requests_;
  std::map<int64_t, uint64_t> bridge_query_ids_;
  uint64_t next_bridge_request_id_ = 1;
  uint64_t sequence_ = 0;
  std::atomic<bool> ready_ = false;
  std::atomic<bool> closing_ = false;
  std::atomic<bool> fatal_emitted_ = false;
  std::atomic<bool> terminal_emitted_ = false;
  std::atomic<bool> terminal_completion_scheduled_ = false;
  std::atomic<bool> devtools_open_requested_ = false;
  std::atomic<bool> devtools_opened_ = false;
  std::atomic<bool> devtools_close_requested_ = false;
  std::atomic<bool> devtools_open_failed_ = false;
  bool source_close_waiting_for_devtools_ = false;
  bool before_close_observed_ = false;
  bool source_client_destroyed_ = false;
  bool flush_started_ = false;
  bool flush_completed_ = false;
};

SessionClient::~SessionClient() {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    session->SourceClientDestroyed();
  }
}

void SessionClient::OnAddressChange(CefRefPtr<CefBrowser> browser,
                                    CefRefPtr<CefFrame> frame,
                                    const CefString &url) {
  CEF_REQUIRE_UI_THREAD();
  if (frame->IsMain()) {
    if (auto session = session_.lock()) {
      session->AddressChanged(url.ToString());
    }
  }
}

void SessionClient::OnTitleChange(CefRefPtr<CefBrowser> browser,
                                  const CefString &title) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (auto session = session_.lock()) {
    session->TitleChanged(title.ToString());
  }
}

void SessionClient::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    session->BrowserCreated(browser);
  }
}

bool SessionClient::DoClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    return session->CompleteBrowserClose(browser);
  }
  return true;
}

void SessionClient::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (auto session = session_.lock()) {
    session->BeforeClose();
  }
}

void SessionClient::OnLoadingStateChange(CefRefPtr<CefBrowser> browser,
                                         bool is_loading, bool can_go_back,
                                         bool can_go_forward) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (auto session = session_.lock()) {
    session->LoadingChanged(is_loading, can_go_back, can_go_forward);
  }
}

void SessionClient::OnLoadEnd(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame, int http_status_code) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (frame->IsMain()) {
    if (auto session = session_.lock()) {
      session->LoadEnded(frame->GetURL().ToString(), http_status_code);
    }
  }
}

void SessionClient::OnLoadError(CefRefPtr<CefBrowser> browser,
                                CefRefPtr<CefFrame> frame, ErrorCode error_code,
                                const CefString &error_text,
                                const CefString &failed_url) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  (void)error_text;
  if (frame->IsMain() && error_code != ERR_ABORTED) {
    if (auto session = session_.lock()) {
      session->LoadFailed(failed_url.ToString(), error_code);
    }
  }
}

bool SessionClient::OnBeforeBrowse(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefRequest> request,
                                   bool user_gesture, bool is_redirect) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    session->BeforeBrowse(browser, frame);
  }
  if (frame->IsMain()) {
    if (auto session = session_.lock()) {
      session->NavigationStarted(request->GetURL().ToString(), user_gesture,
                                 is_redirect);
    }
  }
  return false;
}

bool SessionClient::OnProcessMessageReceived(
    CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
    CefProcessId source_process, CefRefPtr<CefProcessMessage> message) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    return session->HandleProcessMessage(browser, frame, source_process,
                                         message);
  }
  return false;
}

void SessionClient::OnRenderProcessTerminated(
    CefRefPtr<CefBrowser> browser, TerminationStatus status, int error_code,
    const CefString &error_string) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (auto session = session_.lock()) {
    session->RendererTerminated(status, error_code, error_string.ToString());
  }
}

void DevToolsClient::OnAfterCreated(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    CefRefPtr<DevToolsClient> client(this);
    CefPostTask(
        TID_UI,
        base::BindOnce(
            [](std::shared_ptr<BrowserSession> owner,
               CefRefPtr<DevToolsClient> callback_client,
               CefRefPtr<CefBrowser> created) {
              owner->DevToolsCreated(callback_client, created);
            },
            std::move(session), std::move(client), std::move(browser)));
  } else if (browser) {
    browser->GetHost()->CloseBrowser(true);
  }
}

void DevToolsClient::OnBeforeClose(CefRefPtr<CefBrowser> browser) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  if (auto session = session_.lock()) {
    session->DevToolsClosed(CefRefPtr<DevToolsClient>(this));
  }
}

bool BridgeQueryHandler::OnQuery(CefRefPtr<CefBrowser> browser,
                                 CefRefPtr<CefFrame> frame, int64_t query_id,
                                 const CefString &request, bool persistent,
                                 CefRefPtr<Callback> callback) {
  CEF_REQUIRE_UI_THREAD();
  if (auto session = session_.lock()) {
    return session->BridgeQuery(browser, frame, query_id, request, persistent,
                                callback);
  }
  return false;
}

void BridgeQueryHandler::OnQueryCanceled(CefRefPtr<CefBrowser> browser,
                                         CefRefPtr<CefFrame> frame,
                                         int64_t query_id) {
  CEF_REQUIRE_UI_THREAD();
  (void)browser;
  (void)frame;
  if (auto session = session_.lock()) {
    session->BridgeQueryCanceled(query_id);
  }
}

void ProfileContextHandler::OnRequestContextInitialized(
    CefRefPtr<CefRequestContext> request_context) {
  CEF_REQUIRE_UI_THREAD();
  const auto entry = entry_.lock();
  if (entry) {
    Registry().CompleteProfileContextInitialization(entry,
                                                    std::move(request_context));
  }
}

void CookieFlushCallback::OnComplete() {
  CEF_REQUIRE_UI_THREAD();
  auto session = std::move(session_);
  session->FlushCompleted();
}

kweb_status SessionRegistry::Create(const kweb_browser_config *config,
                                    kweb_browser_handle *browser_out) {
  if (config == nullptr || browser_out == nullptr) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  *browser_out = KWEB_INVALID_BROWSER_HANDLE;
  if (config->struct_size < sizeof(kweb_browser_config) ||
      config->abi_version != KWEB_ABI_VERSION) {
    return KWEB_STATUS_ABI_MISMATCH;
  }
  if (config->callback == nullptr || config->native_parent == 0) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  const bool bridge_enabled = config->bridge_callback != nullptr;
  if (bridge_enabled != (config->bridge_origin.data != nullptr &&
                         config->bridge_origin.size != 0)) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  if (config->width <= 0 || config->height <= 0 ||
      config->width > kMaximumViewportDimension ||
      config->height > kMaximumViewportDimension) {
    return KWEB_STATUS_INVALID_DIMENSIONS;
  }
  std::filesystem::path root_cache;
  const kweb_status engine_status =
      ValidateEngineForBrowser(config->engine, &root_cache);
  if (engine_status != KWEB_STATUS_OK) {
    return engine_status;
  }
  const auto profile = ValidateProfilePath(config->profile_path, root_cache);
  if (!profile) {
    return KWEB_STATUS_PROFILE_PATH_INVALID;
  }
  const auto url =
      ValidateUrl(config->initial_url.data, config->initial_url.size);
  if (!url) {
    return KWEB_STATUS_NAVIGATION_INVALID;
  }
  std::string bridge_origin;
  if (bridge_enabled) {
    const auto validated_origin = ValidateBridgeOrigin(config->bridge_origin);
    if (!validated_origin) {
      return KWEB_STATUS_BRIDGE_ORIGIN_INVALID;
    }
    bridge_origin = *validated_origin;
  }

  std::shared_ptr<BrowserSession> session;
  kweb_browser_handle handle = KWEB_INVALID_BROWSER_HANDLE;
  {
    std::lock_guard lock(mutex_);
    if (next_handle_ > kMaximumBrowserHandle) {
      return KWEB_STATUS_HANDLE_EXHAUSTED;
    }
    handle = next_handle_++;
    session = std::make_shared<BrowserSession>(
        config->engine, handle, config->native_parent, config->x, config->y,
        config->width, config->height, *profile, *url, config->callback,
        config->user_data, std::move(bridge_origin), config->bridge_callback,
        config->bridge_user_data);
    sessions_.emplace(handle, session);
  }
  const kweb_status start_status = session->Start();
  if (start_status != KWEB_STATUS_OK) {
    std::lock_guard lock(mutex_);
    sessions_.erase(handle);
    return start_status;
  }
  *browser_out = handle;
  return KWEB_STATUS_OK;
}

void SessionRegistry::CompleteProfileContextInitialization(
    const std::shared_ptr<ProfileContextEntry> &entry,
    CefRefPtr<CefRequestContext> context) {
  CEF_REQUIRE_UI_THREAD();
  auto pending = std::move(entry->pending);
  entry->pending.clear();
  const bool valid =
      context && !context->IsGlobal() &&
      context->GetCachePath().ToString() == PathToUtf8(entry->path);
  if (!valid) {
    entry->context = nullptr;
    entry->failed = true;
    for (const auto &session : pending) {
      session->ProfileContextFailed(KWEB_STATUS_PROFILE_PATH_INVALID,
                                    "profile-context-mismatch");
    }
    return;
  }
  entry->context = std::move(context);
  entry->initialized = true;
  for (const auto &session : pending) {
    session->ProfileInitialized(entry->context);
  }
}

void SessionRegistry::ReleaseProfileContexts() {
  CEF_REQUIRE_UI_THREAD();
  profile_contexts_.clear();
}

std::shared_ptr<BrowserSession>
SessionRegistry::Lookup(kweb_browser_handle handle) const {
  std::lock_guard lock(mutex_);
  const auto found = sessions_.find(handle);
  return found == sessions_.end() ? nullptr : found->second;
}

kweb_status SessionRegistry::Navigate(kweb_browser_handle handle,
                                      std::string url) {
  auto session = Lookup(handle);
  return session ? session->Navigate(std::move(url))
                 : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::Resize(kweb_browser_handle handle, int32_t width,
                                    int32_t height) {
  auto session = Lookup(handle);
  return session ? session->Resize(width, height) : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::Close(kweb_browser_handle handle) {
  auto session = Lookup(handle);
  return session ? session->Close() : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::OpenDevTools(kweb_browser_handle handle) {
  auto session = Lookup(handle);
  return session ? session->OpenDevTools() : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::CloseDevTools(kweb_browser_handle handle) {
  auto session = Lookup(handle);
  return session ? session->CloseDevTools() : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::BridgeRespond(kweb_browser_handle handle,
                                           uint64_t request_id,
                                           std::string response,
                                           bool success) {
  auto session = Lookup(handle);
  return session ? session->BridgeRespond(request_id, std::move(response),
                                          success)
                 : KWEB_STATUS_INVALID_HANDLE;
}

kweb_status SessionRegistry::ExtensionContext(
    kweb_browser_handle handle, kweb_engine_handle *engine_out,
    std::filesystem::path *profile_path_out) const {
  auto session = Lookup(handle);
  return session ? session->ExtensionContext(engine_out, profile_path_out)
                 : KWEB_STATUS_INVALID_HANDLE;
}

void SessionRegistry::Complete(kweb_browser_handle handle,
                               BrowserSession *session) {
  std::lock_guard lock(mutex_);
  const auto found = sessions_.find(handle);
  if (found != sessions_.end() && found->second.get() == session) {
    sessions_.erase(found);
  }
}

uint64_t SessionRegistry::LiveCount() const {
  std::lock_guard lock(mutex_);
  return static_cast<uint64_t>(sessions_.size());
}

template <typename Operation> kweb_status GuardStatus(Operation operation) {
  try {
    return operation();
  } catch (const std::bad_alloc &) {
    return KWEB_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    return KWEB_STATUS_INTERNAL_ERROR;
  }
}

} // namespace

kweb_status OpenDevToolsSession(kweb_browser_handle browser) {
  return GuardStatus([&] { return Registry().OpenDevTools(browser); });
}

kweb_status CloseDevToolsSession(kweb_browser_handle browser) {
  return GuardStatus([&] { return Registry().CloseDevTools(browser); });
}

kweb_status RespondToBridgeSession(kweb_browser_handle browser,
                                   uint64_t request_id,
                                   const char *response_utf8,
                                   size_t response_size, bool success) {
  if (response_utf8 == nullptr || response_size == 0 ||
      response_size > kMaximumTextSize ||
      !IsValidUtf8(response_utf8, response_size)) {
    return KWEB_STATUS_BRIDGE_RESPONSE_INVALID;
  }
  return GuardStatus([&] {
    return Registry().BridgeRespond(
        browser, request_id, std::string(response_utf8, response_size), success);
  });
}

kweb_status CreateBrowserSession(const kweb_browser_config *config,
                                 kweb_browser_handle *browser_out) {
  return GuardStatus([&] { return Registry().Create(config, browser_out); });
}

kweb_status NavigateBrowserSession(kweb_browser_handle browser,
                                   const char *url_utf8, size_t url_size) {
  const auto url = ValidateUrl(url_utf8, url_size);
  if (!url) {
    return KWEB_STATUS_NAVIGATION_INVALID;
  }
  return GuardStatus([&] { return Registry().Navigate(browser, *url); });
}

kweb_status ResizeBrowserSession(kweb_browser_handle browser, int32_t width,
                                 int32_t height) {
  if (width <= 0 || height <= 0 || width > kMaximumViewportDimension ||
      height > kMaximumViewportDimension) {
    return KWEB_STATUS_INVALID_DIMENSIONS;
  }
  return GuardStatus([&] { return Registry().Resize(browser, width, height); });
}

kweb_status CloseBrowserSession(kweb_browser_handle browser) {
  return GuardStatus([&] { return Registry().Close(browser); });
}

uint64_t LiveBrowserSessionCount() {
  try {
    return Registry().LiveCount();
  } catch (...) {
    return std::numeric_limits<uint64_t>::max();
  }
}

void ReleaseEngineProfileContexts() {
  Registry().ReleaseProfileContexts();
}

kweb_status GetBrowserExtensionContext(
    kweb_browser_handle browser, kweb_engine_handle *engine_out,
    std::filesystem::path *profile_path_out) {
  return GuardStatus([&] {
    return Registry().ExtensionContext(browser, engine_out, profile_path_out);
  });
}

} // namespace kwebshell
