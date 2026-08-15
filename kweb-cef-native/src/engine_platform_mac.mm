#import <Cocoa/Cocoa.h>
#import <objc/runtime.h>

#include "engine_platform.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <climits>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <system_error>
#include <thread>

#include <dlfcn.h>

#include "include/cef_api_hash.h"
#include "include/cef_application_mac.h"
#include "include/cef_version.h"
#include "include/cef_version_info.h"
#include "include/wrapper/cef_library_loader.h"
#include "utf8_validation.h"

namespace {

constexpr size_t kMaximumRuntimePathSize = 32768;
constexpr int32_t kNoPendingWorkDelay = INT_MAX;
constexpr int64_t kMaximumPumpDelayMs = 1000 / 30;
constexpr auto kShutdownGraceDelay = std::chrono::milliseconds(999);

std::mutex platform_mutex;
std::filesystem::path configured_runtime_path;
std::atomic<bool> runtime_loaded = false;
std::atomic<bool> cef_active = false;
std::atomic<bool> cef_shutdown_in_progress = false;
std::atomic<bool> cef_shutdown_completed = false;
class ExternalMessagePumpMac;
std::mutex message_pump_mutex;
std::shared_ptr<ExternalMessagePumpMac> message_pump;

std::filesystem::path CanonicalPath(const std::filesystem::path &path) {
  std::error_code error;
  auto canonical = std::filesystem::canonical(path, error);
  return error ? std::filesystem::path() : canonical;
}

bool RuntimeVersionMatches() {
  const char *full_version = cef_version_full();
  const char *api_hash = cef_api_hash(CEF_API_VERSION, 0);
  return full_version != nullptr && api_hash != nullptr &&
         std::string(full_version) == CEF_VERSION &&
         std::string(api_hash) == CEF_API_HASH_PLATFORM;
}

} // namespace

@class KWebCefPumpHandler;

namespace {

class ExternalMessagePumpMac final {
public:
  ExternalMessagePumpMac();
  ~ExternalMessagePumpMac();

  ExternalMessagePumpMac(const ExternalMessagePumpMac &) = delete;
  ExternalMessagePumpMac &operator=(const ExternalMessagePumpMac &) = delete;

  void Schedule(int64_t delay_ms);
  void DrainBeforeShutdown();
  void HandleSchedule(int64_t delay_ms);
  void HandleTimer();
  void Stop();

private:
  void SetTimer(int64_t delay_ms);
  void KillTimer();
  void DoWork();
  bool PerformMessageLoopWork();

  NSThread *owner_thread_;
  NSTimer *timer_ = nil;
  KWebCefPumpHandler *handler_;
  std::atomic<bool> active_ = true;
  bool work_active_ = false;
  bool reentrancy_detected_ = false;
};

} // namespace

@interface KWebCefPumpHandler : NSObject {
@private
  ExternalMessagePumpMac *pump_;
}
- (instancetype)initWithPump:(ExternalMessagePumpMac *)pump;
- (void)scheduleWork:(NSNumber *)delay;
- (void)timerTimeout:(id)sender;
- (void)invalidate;
@end

@implementation KWebCefPumpHandler

- (instancetype)initWithPump:(ExternalMessagePumpMac *)pump {
  self = [super init];
  if (self != nil) {
    pump_ = pump;
  }
  return self;
}

- (void)scheduleWork:(NSNumber *)delay {
  if (pump_ != nullptr) {
    pump_->HandleSchedule([delay longLongValue]);
  }
}

- (void)timerTimeout:(id)sender {
  (void)sender;
  if (pump_ != nullptr) {
    pump_->HandleTimer();
  }
}

- (void)invalidate {
  [NSObject cancelPreviousPerformRequestsWithTarget:self];
  pump_ = nullptr;
}

@end

namespace {

ExternalMessagePumpMac::ExternalMessagePumpMac()
    : owner_thread_([NSThread currentThread]),
      handler_([[KWebCefPumpHandler alloc] initWithPump:this]) {}

ExternalMessagePumpMac::~ExternalMessagePumpMac() { Stop(); }

void ExternalMessagePumpMac::Schedule(int64_t delay_ms) {
  if (!active_.load(std::memory_order_acquire)) {
    return;
  }
  NSNumber *delay = [NSNumber numberWithLongLong:delay_ms];
  [handler_
      performSelector:@selector(scheduleWork:)
             onThread:owner_thread_
           withObject:delay
        waitUntilDone:NO
                modes:@[ NSDefaultRunLoopMode, NSEventTrackingRunLoopMode ]];
}

void ExternalMessagePumpMac::DrainBeforeShutdown() {
  KillTimer();
  for (int iteration = 0; iteration < 10; ++iteration) {
    CefDoMessageLoopWork();
  }
}

void ExternalMessagePumpMac::HandleSchedule(int64_t delay_ms) {
  if (!active_.load(std::memory_order_acquire)) {
    return;
  }
  if (delay_ms == kNoPendingWorkDelay && timer_ != nil) {
    return;
  }
  KillTimer();
  if (delay_ms <= 0) {
    DoWork();
    return;
  }
  SetTimer(std::min(delay_ms, kMaximumPumpDelayMs));
}

void ExternalMessagePumpMac::HandleTimer() {
  if (!active_.load(std::memory_order_acquire)) {
    return;
  }
  KillTimer();
  DoWork();
}

void ExternalMessagePumpMac::Stop() {
  bool expected = true;
  if (!active_.compare_exchange_strong(expected, false,
                                       std::memory_order_acq_rel)) {
    return;
  }
  KillTimer();
  [handler_ invalidate];
}

void ExternalMessagePumpMac::SetTimer(int64_t delay_ms) {
  const NSTimeInterval seconds = static_cast<double>(delay_ms) / 1000.0;
  timer_ = [NSTimer timerWithTimeInterval:seconds
                                   target:handler_
                                 selector:@selector(timerTimeout:)
                                 userInfo:nil
                                  repeats:NO];
  NSRunLoop *run_loop = [NSRunLoop currentRunLoop];
  [run_loop addTimer:timer_ forMode:NSRunLoopCommonModes];
  [run_loop addTimer:timer_ forMode:NSEventTrackingRunLoopMode];
}

void ExternalMessagePumpMac::KillTimer() {
  if (timer_ != nil) {
    [timer_ invalidate];
    timer_ = nil;
  }
}

void ExternalMessagePumpMac::DoWork() {
  const bool was_reentrant = PerformMessageLoopWork();
  if (!active_.load(std::memory_order_acquire)) {
    return;
  }
  if (was_reentrant) {
    Schedule(0);
  } else if (timer_ == nil) {
    Schedule(kNoPendingWorkDelay);
  }
}

bool ExternalMessagePumpMac::PerformMessageLoopWork() {
  if (work_active_) {
    reentrancy_detected_ = true;
    return false;
  }
  reentrancy_detected_ = false;
  work_active_ = true;
  CefDoMessageLoopWork();
  work_active_ = false;
  return reentrancy_detected_;
}

} // namespace

@interface NSApplication (KWebShellCefApplication) <CefAppProtocol>
- (BOOL)kweb_isHandlingSendEvent;
- (void)kweb_setHandlingSendEvent:(BOOL)handlingSendEvent;
- (void)kweb_sendEvent:(NSEvent *)event;
@end

@interface NSAutoreleasePool (KWebShellCefShutdown)
- (void)kweb_drain;
@end

@implementation NSAutoreleasePool (KWebShellCefShutdown)

+ (void)load {
  Method original = class_getInstanceMethod(self, @selector(drain));
  Method replacement = class_getInstanceMethod(self, @selector(kweb_drain));
  method_exchangeImplementations(original, replacement);
}

- (void)kweb_drain {
  if (!cef_shutdown_in_progress.load(std::memory_order_acquire) ||
      cef_shutdown_completed.load(std::memory_order_acquire) ||
      ![NSThread isMainThread]) {
    [self kweb_drain];
  }
}

@end

@implementation NSApplication (KWebShellCefApplication)

+ (void)load {
  Method original = class_getInstanceMethod(self, @selector(sendEvent:));
  Method replacement =
      class_getInstanceMethod(self, @selector(kweb_sendEvent:));
  method_exchangeImplementations(original, replacement);
}

- (BOOL)kweb_isHandlingSendEvent {
  return objc_getAssociatedObject(self, @selector(kweb_isHandlingSendEvent)) !=
         nil;
}

- (void)kweb_setHandlingSendEvent:(BOOL)handlingSendEvent {
  objc_setAssociatedObject(self, @selector(kweb_isHandlingSendEvent),
                           handlingSendEvent ? @YES : nil,
                           OBJC_ASSOCIATION_RETAIN_NONATOMIC);
}

- (void)kweb_sendEvent:(NSEvent *)event {
  if (cef_active.load(std::memory_order_acquire)) {
    CefScopedSendingEvent sending_event;
    [self kweb_sendEvent:event];
    return;
  }
  [self kweb_sendEvent:event];
}

- (BOOL)isHandlingSendEvent {
  return [self kweb_isHandlingSendEvent];
}

- (void)setHandlingSendEvent:(BOOL)handlingSendEvent {
  [self kweb_setHandlingSendEvent:handlingSendEvent];
}

@end

@interface KWebCefInitializeParameters : NSObject {
@public
  CefMainArgs main_args;
  CefSettings settings;
  CefRefPtr<CefApp> application;
  BOOL result;
}
@end

@implementation KWebCefInitializeParameters
@end

@interface KWebCefShutdownParameters : NSObject {
@public
  kwebshell::CefShutdownCompletion completion;
  void *context;
}
@end

@implementation KWebCefShutdownParameters
@end

@interface KWebCefMainThread : NSObject
+ (void)initializeCef:(KWebCefInitializeParameters *)parameters;
+ (void)shutdownCef:(KWebCefShutdownParameters *)parameters;
@end

@implementation KWebCefMainThread

+ (void)initializeCef:(KWebCefInitializeParameters *)parameters {
  auto pump = std::make_shared<ExternalMessagePumpMac>();
  {
    std::lock_guard lock(message_pump_mutex);
    message_pump = pump;
  }
  parameters->result =
      CefInitialize(parameters->main_args, parameters->settings,
                    parameters->application.get(), nullptr);
  if (parameters->result) {
    cef_active.store(true, std::memory_order_release);
    pump->Schedule(0);
  } else {
    pump->Stop();
    std::lock_guard lock(message_pump_mutex);
    message_pump.reset();
  }
}

+ (void)shutdownCef:(KWebCefShutdownParameters *)parameters {
  std::shared_ptr<ExternalMessagePumpMac> pump;
  {
    std::lock_guard lock(message_pump_mutex);
    pump = message_pump;
  }
  pump->DrainBeforeShutdown();
  fprintf(stderr, "KWEBSHELL_NATIVE_ENGINE:before_cef_shutdown\n");
  fflush(stderr);
  cef_active.store(false, std::memory_order_release);
  cef_shutdown_in_progress.store(true, std::memory_order_release);
  CefClearSchemeHandlerFactories();
  CefShutdown();
  fprintf(stderr, "KWEBSHELL_NATIVE_ENGINE:after_cef_shutdown\n");
  fflush(stderr);
  cef_shutdown_completed.store(true, std::memory_order_release);
  pump->Stop();
  {
    std::lock_guard lock(message_pump_mutex);
    message_pump.reset();
  }
  parameters->completion(parameters->context, KWEB_STATUS_OK);
}

@end

namespace kwebshell {

kweb_status EnginePlatformStartup(const char *cef_runtime_path_utf8,
                                  size_t cef_runtime_path_size) {
  if (cef_runtime_path_utf8 == nullptr || cef_runtime_path_size == 0) {
    return KWEB_STATUS_PATH_REQUIRED;
  }
  if (cef_runtime_path_size > kMaximumRuntimePathSize) {
    return KWEB_STATUS_TEXT_TOO_LARGE;
  }
  if (!IsValidUtf8(cef_runtime_path_utf8, cef_runtime_path_size)) {
    return KWEB_STATUS_INVALID_TEXT_ENCODING;
  }
  try {
    std::filesystem::path requested(
        std::string(cef_runtime_path_utf8, cef_runtime_path_size));
    if (!requested.is_absolute()) {
      return KWEB_STATUS_PATH_NOT_ABSOLUTE;
    }
    const auto canonical = CanonicalPath(requested);
    if (canonical.empty()) {
      return KWEB_STATUS_PATH_NOT_FOUND;
    }

    std::lock_guard lock(platform_mutex);
    if (runtime_loaded.load(std::memory_order_acquire)) {
      return canonical == configured_runtime_path
                 ? KWEB_STATUS_OK
                 : KWEB_STATUS_CEF_RUNTIME_MISMATCH;
    }
    const std::string runtime_path = requested.string();
    if (!cef_load_library(runtime_path.c_str())) {
      return KWEB_STATUS_CEF_RUNTIME_LOAD_FAILED;
    }
    runtime_loaded.store(true, std::memory_order_release);
    if (!RuntimeVersionMatches()) {
      runtime_loaded.store(false, std::memory_order_release);
      cef_unload_library();
      return KWEB_STATUS_CEF_RUNTIME_MISMATCH;
    }
    configured_runtime_path = canonical;
    return KWEB_STATUS_OK;
  } catch (...) {
    return KWEB_STATUS_INTERNAL_ERROR;
  }
}

bool EnginePlatformRuntimeMatches(
    const std::filesystem::path &cef_runtime_path) {
  const auto canonical = CanonicalPath(cef_runtime_path);
  std::lock_guard lock(platform_mutex);
  return runtime_loaded.load(std::memory_order_acquire) && !canonical.empty() &&
         canonical == configured_runtime_path;
}

void *ResolveCefRuntimeSymbol(const char *name) {
  if (name == nullptr || *name == '\0') {
    return nullptr;
  }
  std::lock_guard lock(platform_mutex);
  if (!runtime_loaded.load(std::memory_order_acquire) ||
      configured_runtime_path.empty()) {
    return nullptr;
  }
  void *library =
      ::dlopen(configured_runtime_path.c_str(), RTLD_NOW | RTLD_NOLOAD);
  if (library == nullptr) {
    return nullptr;
  }
  void *symbol = ::dlsym(library, name);
  ::dlclose(library);
  return symbol;
}

bool InitializeCefOnPlatform(const CefMainArgs &main_args,
                             const CefSettings &settings,
                             CefRefPtr<CefApp> application) {
  @autoreleasepool {
    if (NSApp == nil) {
      return false;
    }
    KWebCefInitializeParameters *parameters =
        [[KWebCefInitializeParameters alloc] init];
    parameters->main_args = main_args;
    parameters->settings = settings;
    parameters->application = application;
    parameters->result = NO;
    if ([NSThread isMainThread]) {
      [KWebCefMainThread initializeCef:parameters];
    } else {
      [KWebCefMainThread performSelectorOnMainThread:@selector(initializeCef:)
                                          withObject:parameters
                                       waitUntilDone:YES];
    }
    return parameters->result == YES;
  }
}

void ConfigureEngineCommandLineOnPlatform(
    const CefString &process_type, CefRefPtr<CefCommandLine> command_line) {
  if (!process_type.empty()) {
    return;
  }
  command_line->AppendSwitch("disable-in-process-stack-traces");
  command_line->AppendSwitch("use-mock-keychain");
  command_line->AppendSwitchWithValue("remote-debugging-address",
                                      "127.0.0.1");
  if (command_line->HasSwitch("disable-in-process-stack-traces") &&
      command_line->HasSwitch("use-mock-keychain")) {
    fprintf(stderr, "KWEBSHELL_NATIVE_ENGINE:macos_browser_policy_applied\n");
    fflush(stderr);
  }
}

void CleanupPlatformAfterCefInitializeFailure() {
  cef_active.store(false, std::memory_order_release);
}

kweb_status ShutdownCefOnPlatform(CefShutdownCompletion completion,
                                  void *context) {
  KWebCefShutdownParameters *parameters =
      [[KWebCefShutdownParameters alloc] init];
  parameters->completion = completion;
  parameters->context = context;
  try {
    std::thread([parameters] {
      std::this_thread::sleep_for(kShutdownGraceDelay);
      [KWebCefMainThread performSelectorOnMainThread:@selector(shutdownCef:)
                                          withObject:parameters
                                       waitUntilDone:NO];
    }).detach();
  } catch (const std::system_error &) {
    return KWEB_STATUS_THREAD_START_FAILED;
  }
  return KWEB_STATUS_OK;
}

bool ScheduleCefMessagePumpWorkOnPlatform(int64_t delay_ms) {
  std::shared_ptr<ExternalMessagePumpMac> pump;
  {
    std::lock_guard lock(message_pump_mutex);
    pump = message_pump;
  }
  if (!pump) {
    return false;
  }
  pump->Schedule(delay_ms);
  return true;
}

} // namespace kwebshell
