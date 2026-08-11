#ifndef KWEBSHELL_NATIVE_SHUTDOWN_WATCHDOG_H_
#define KWEBSHELL_NATIVE_SHUTDOWN_WATCHDOG_H_

#include <chrono>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

namespace kwebshell {

class ShutdownWatchdog final {
public:
  using TimeoutHandler = std::function<void()>;

  static std::unique_ptr<ShutdownWatchdog>
  Start(std::chrono::milliseconds timeout, TimeoutHandler timeout_handler,
        std::string &error);

  ~ShutdownWatchdog();

  ShutdownWatchdog(const ShutdownWatchdog &) = delete;
  ShutdownWatchdog &operator=(const ShutdownWatchdog &) = delete;

  void Complete();

private:
  ShutdownWatchdog(std::chrono::milliseconds timeout,
                   TimeoutHandler timeout_handler);
  void Run();

  const std::chrono::milliseconds timeout_;
  const TimeoutHandler timeout_handler_;
  std::mutex mutex_;
  std::condition_variable completed_condition_;
  std::thread thread_;
  bool completed_ = false;
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_SHUTDOWN_WATCHDOG_H_
