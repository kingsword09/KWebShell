#include "kwebshell/native/shutdown_watchdog.h"

#include <system_error>
#include <utility>

namespace kwebshell {

std::unique_ptr<ShutdownWatchdog>
ShutdownWatchdog::Start(std::chrono::milliseconds timeout,
                        TimeoutHandler timeout_handler, std::string &error) {
  error.clear();
  if (timeout <= std::chrono::milliseconds::zero()) {
    error = "Shutdown watchdog timeout must be positive.";
    return nullptr;
  }
  if (!timeout_handler) {
    error = "Shutdown watchdog timeout handler is required.";
    return nullptr;
  }

  auto watchdog = std::unique_ptr<ShutdownWatchdog>(
      new ShutdownWatchdog(timeout, std::move(timeout_handler)));
  try {
    watchdog->thread_ = std::thread(&ShutdownWatchdog::Run, watchdog.get());
  } catch (const std::system_error &thread_error) {
    error = "Unable to start shutdown watchdog thread: " +
            std::string(thread_error.what());
    return nullptr;
  }
  return watchdog;
}

ShutdownWatchdog::ShutdownWatchdog(std::chrono::milliseconds timeout,
                                   TimeoutHandler timeout_handler)
    : timeout_(timeout), timeout_handler_(std::move(timeout_handler)) {}

ShutdownWatchdog::~ShutdownWatchdog() { Complete(); }

void ShutdownWatchdog::Complete() {
  {
    std::lock_guard lock(mutex_);
    completed_ = true;
  }
  completed_condition_.notify_one();
  if (thread_.joinable()) {
    thread_.join();
  }
}

void ShutdownWatchdog::Run() {
  std::unique_lock lock(mutex_);
  if (completed_condition_.wait_for(lock, timeout_,
                                    [this] { return completed_; })) {
    return;
  }
  lock.unlock();
  timeout_handler_();
}

} // namespace kwebshell
