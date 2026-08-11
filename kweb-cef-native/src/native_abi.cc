#include "kwebshell/native/abi.h"

#include <condition_variable>
#include <cstdint>
#include <deque>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <system_error>
#include <thread>
#include <utility>

namespace {

constexpr size_t kMaximumTextSize = 1024 * 1024;
constexpr int32_t kMaximumViewportDimension = 32768;
constexpr kweb_session_handle kMaximumSessionHandle =
    static_cast<kweb_session_handle>(std::numeric_limits<int64_t>::max());

bool IsContinuationByte(uint8_t value) { return (value & 0xC0U) == 0x80U; }

bool IsValidUtf8(const char *text, size_t size) {
  size_t index = 0;
  while (index < size) {
    const auto first = static_cast<uint8_t>(text[index]);
    if (first == 0) {
      return false;
    }
    if (first <= 0x7FU) {
      ++index;
      continue;
    }
    if (first >= 0xC2U && first <= 0xDFU) {
      if (index + 1 >= size ||
          !IsContinuationByte(static_cast<uint8_t>(text[index + 1]))) {
        return false;
      }
      index += 2;
      continue;
    }
    if (first >= 0xE0U && first <= 0xEFU) {
      if (index + 2 >= size) {
        return false;
      }
      const auto second = static_cast<uint8_t>(text[index + 1]);
      const auto third = static_cast<uint8_t>(text[index + 2]);
      const bool second_valid =
          IsContinuationByte(second) &&
          !(first == 0xE0U && second < 0xA0U) &&
          !(first == 0xEDU && second > 0x9FU);
      if (!second_valid || !IsContinuationByte(third)) {
        return false;
      }
      index += 3;
      continue;
    }
    if (first >= 0xF0U && first <= 0xF4U) {
      if (index + 3 >= size) {
        return false;
      }
      const auto second = static_cast<uint8_t>(text[index + 1]);
      const auto third = static_cast<uint8_t>(text[index + 2]);
      const auto fourth = static_cast<uint8_t>(text[index + 3]);
      const bool second_valid =
          IsContinuationByte(second) &&
          !(first == 0xF0U && second < 0x90U) &&
          !(first == 0xF4U && second > 0x8FU);
      if (!second_valid || !IsContinuationByte(third) ||
          !IsContinuationByte(fourth)) {
        return false;
      }
      index += 4;
      continue;
    }
    return false;
  }
  return true;
}

enum class CommandType { kNavigate, kResize };

struct Command final {
  CommandType type;
  std::string text;
  int32_t width = 0;
  int32_t height = 0;
};

class Session final {
public:
  Session(kweb_session_handle handle, kweb_event_callback callback,
          void *user_data)
      : handle_(handle), callback_(callback), user_data_(user_data) {}

  ~Session() {
    if (worker_.joinable()) {
      StopForDestruction();
      worker_.join();
    }
  }

  Session(const Session &) = delete;
  Session &operator=(const Session &) = delete;

  kweb_status Start() {
    try {
      worker_ = std::thread(&Session::Run, this);
      worker_id_ = worker_.get_id();
      return KWEB_STATUS_OK;
    } catch (const std::system_error &) {
      return KWEB_STATUS_THREAD_START_FAILED;
    }
  }

  void Activate() {
    {
      std::lock_guard lock(mutex_);
      activated_ = true;
    }
    condition_.notify_one();
  }

  void CancelBeforeActivation() {
    {
      std::lock_guard lock(mutex_);
      cancelled_ = true;
      activated_ = true;
      accepting_commands_ = false;
    }
    condition_.notify_one();
  }

  bool IsWorkerThread() const {
    return std::this_thread::get_id() == worker_id_;
  }

  kweb_status SubmitNavigation(std::string url) {
    std::lock_guard lock(mutex_);
    if (!accepting_commands_) {
      return KWEB_STATUS_SESSION_CLOSING;
    }
    commands_.push_back(
        Command{CommandType::kNavigate, std::move(url), 0, 0});
    condition_.notify_one();
    return KWEB_STATUS_OK;
  }

  kweb_status SubmitResize(int32_t width, int32_t height) {
    std::lock_guard lock(mutex_);
    if (!accepting_commands_) {
      return KWEB_STATUS_SESSION_CLOSING;
    }
    commands_.push_back(Command{CommandType::kResize, {}, width, height});
    condition_.notify_one();
    return KWEB_STATUS_OK;
  }

  kweb_status Close() {
    if (IsWorkerThread()) {
      return KWEB_STATUS_REENTRANT_CLOSE;
    }
    {
      std::lock_guard lock(mutex_);
      if (accepting_commands_) {
        accepting_commands_ = false;
        close_requested_ = true;
      }
    }
    condition_.notify_one();
    if (worker_.joinable()) {
      worker_.join();
    }
    return KWEB_STATUS_OK;
  }

private:
  void StopForDestruction() {
    {
      std::lock_guard lock(mutex_);
      if (!activated_) {
        cancelled_ = true;
        activated_ = true;
        accepting_commands_ = false;
      } else if (accepting_commands_) {
        accepting_commands_ = false;
        close_requested_ = true;
      }
    }
    condition_.notify_one();
  }

  void Run() {
    {
      std::unique_lock lock(mutex_);
      condition_.wait(lock, [this] { return activated_; });
      if (cancelled_) {
        return;
      }
    }

    Emit(KWEB_EVENT_SESSION_OPENED, {}, 0, 0);
    for (;;) {
      Command command{CommandType::kResize, {}, 0, 0};
      bool should_close = false;
      {
        std::unique_lock lock(mutex_);
        condition_.wait(
            lock, [this] { return !commands_.empty() || close_requested_; });
        if (commands_.empty()) {
          should_close = true;
        } else {
          command = std::move(commands_.front());
          commands_.pop_front();
        }
      }

      if (should_close) {
        Emit(KWEB_EVENT_SESSION_CLOSED, {}, 0, 0);
        return;
      }
      if (command.type == CommandType::kNavigate) {
        Emit(KWEB_EVENT_NAVIGATION_REQUESTED, command.text, 0, 0);
      } else {
        Emit(KWEB_EVENT_VIEWPORT_CHANGED, {}, command.width, command.height);
      }
    }
  }

  void Emit(kweb_event_type type, const std::string &text, int32_t width,
            int32_t height) {
    const kweb_event event = {
        sizeof(kweb_event), KWEB_ABI_VERSION, type, 0, handle_, ++sequence_,
        text.data(), text.size(), width, height};
    callback_(user_data_, &event);
  }

  const kweb_session_handle handle_;
  const kweb_event_callback callback_;
  void *const user_data_;
  std::mutex mutex_;
  std::condition_variable condition_;
  std::deque<Command> commands_;
  std::thread worker_;
  std::thread::id worker_id_;
  uint64_t sequence_ = 0;
  bool activated_ = false;
  bool cancelled_ = false;
  bool accepting_commands_ = true;
  bool close_requested_ = false;
};

struct SessionEntry final {
  std::shared_ptr<Session> session;
  bool close_in_progress = false;
};

class SessionRegistry final {
public:
  kweb_status Create(const kweb_session_config *config,
                     kweb_session_handle *session_out) {
    if (config == nullptr || session_out == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }
    *session_out = KWEB_INVALID_SESSION_HANDLE;
    if (config->struct_size < sizeof(kweb_session_config) ||
        config->abi_version != KWEB_ABI_VERSION) {
      return KWEB_STATUS_ABI_MISMATCH;
    }
    if (config->callback == nullptr) {
      return KWEB_STATUS_INVALID_ARGUMENT;
    }

    std::shared_ptr<Session> session;
    kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
    {
      std::lock_guard lock(mutex_);
      if (next_handle_ > kMaximumSessionHandle) {
        return KWEB_STATUS_HANDLE_EXHAUSTED;
      }
      handle = next_handle_++;
      session =
          std::make_shared<Session>(handle, config->callback, config->user_data);
      const kweb_status start_status = session->Start();
      if (start_status != KWEB_STATUS_OK) {
        return start_status;
      }
      try {
        sessions_.emplace(handle, SessionEntry{session, false});
      } catch (...) {
        session->CancelBeforeActivation();
        throw;
      }
    }
    session->Activate();
    *session_out = handle;
    return KWEB_STATUS_OK;
  }

  kweb_status RequestNavigation(kweb_session_handle handle, std::string url) {
    std::shared_ptr<Session> session;
    const kweb_status lookup_status = LookupForCommand(handle, &session);
    if (lookup_status != KWEB_STATUS_OK) {
      return lookup_status;
    }
    return session->SubmitNavigation(std::move(url));
  }

  kweb_status Resize(kweb_session_handle handle, int32_t width,
                     int32_t height) {
    std::shared_ptr<Session> session;
    const kweb_status lookup_status = LookupForCommand(handle, &session);
    if (lookup_status != KWEB_STATUS_OK) {
      return lookup_status;
    }
    return session->SubmitResize(width, height);
  }

  kweb_status Close(kweb_session_handle handle) {
    std::shared_ptr<Session> session;
    {
      std::lock_guard lock(mutex_);
      const auto found = sessions_.find(handle);
      if (found == sessions_.end()) {
        return KWEB_STATUS_INVALID_HANDLE;
      }
      if (found->second.session->IsWorkerThread()) {
        return KWEB_STATUS_REENTRANT_CLOSE;
      }
      if (found->second.close_in_progress) {
        return KWEB_STATUS_SESSION_CLOSING;
      }
      found->second.close_in_progress = true;
      session = found->second.session;
    }

    const kweb_status close_status = session->Close();
    {
      std::lock_guard lock(mutex_);
      const auto found = sessions_.find(handle);
      if (found != sessions_.end() && found->second.session == session) {
        if (close_status == KWEB_STATUS_OK) {
          sessions_.erase(found);
        } else {
          found->second.close_in_progress = false;
        }
      }
    }
    return close_status;
  }

  uint64_t LiveCount() const {
    std::lock_guard lock(mutex_);
    return static_cast<uint64_t>(sessions_.size());
  }

private:
  kweb_status LookupForCommand(kweb_session_handle handle,
                               std::shared_ptr<Session> *session_out) {
    if (handle == KWEB_INVALID_SESSION_HANDLE || session_out == nullptr) {
      return KWEB_STATUS_INVALID_HANDLE;
    }
    std::lock_guard lock(mutex_);
    const auto found = sessions_.find(handle);
    if (found == sessions_.end()) {
      return KWEB_STATUS_INVALID_HANDLE;
    }
    if (found->second.close_in_progress) {
      return KWEB_STATUS_SESSION_CLOSING;
    }
    *session_out = found->second.session;
    return KWEB_STATUS_OK;
  }

  mutable std::mutex mutex_;
  std::map<kweb_session_handle, SessionEntry> sessions_;
  kweb_session_handle next_handle_ = 1;
};

SessionRegistry &Registry() {
  static SessionRegistry registry;
  return registry;
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

extern "C" {

uint32_t KWEB_ABI_CALL kweb_abi_version(void) { return KWEB_ABI_VERSION; }

const char *KWEB_ABI_CALL kweb_status_name(kweb_status status) {
  switch (status) {
  case KWEB_STATUS_OK:
    return "ok";
  case KWEB_STATUS_INVALID_ARGUMENT:
    return "invalid-argument";
  case KWEB_STATUS_ABI_MISMATCH:
    return "abi-mismatch";
  case KWEB_STATUS_ALLOCATION_FAILED:
    return "allocation-failed";
  case KWEB_STATUS_THREAD_START_FAILED:
    return "thread-start-failed";
  case KWEB_STATUS_HANDLE_EXHAUSTED:
    return "handle-exhausted";
  case KWEB_STATUS_INVALID_HANDLE:
    return "invalid-handle";
  case KWEB_STATUS_SESSION_CLOSING:
    return "session-closing";
  case KWEB_STATUS_INVALID_TEXT_ENCODING:
    return "invalid-text-encoding";
  case KWEB_STATUS_TEXT_TOO_LARGE:
    return "text-too-large";
  case KWEB_STATUS_INVALID_DIMENSIONS:
    return "invalid-dimensions";
  case KWEB_STATUS_REENTRANT_CLOSE:
    return "reentrant-close";
  case KWEB_STATUS_CALLBACK_FAILED:
    return "callback-failed";
  case KWEB_STATUS_INTERNAL_ERROR:
    return "internal-error";
  default:
    return "unknown-status";
  }
}

kweb_status KWEB_ABI_CALL
kweb_session_create(const kweb_session_config *config,
                    kweb_session_handle *session_out) {
  return GuardStatus(
      [&] { return Registry().Create(config, session_out); });
}

kweb_status KWEB_ABI_CALL
kweb_session_request_navigation(kweb_session_handle session,
                                const char *url_utf8, size_t url_size) {
  if (url_utf8 == nullptr || url_size == 0) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  if (url_size > kMaximumTextSize) {
    return KWEB_STATUS_TEXT_TOO_LARGE;
  }
  if (!IsValidUtf8(url_utf8, url_size)) {
    return KWEB_STATUS_INVALID_TEXT_ENCODING;
  }
  return GuardStatus([&] {
    return Registry().RequestNavigation(
        session, std::string(url_utf8, url_size));
  });
}

kweb_status KWEB_ABI_CALL kweb_session_resize(kweb_session_handle session,
                                               int32_t width,
                                               int32_t height) {
  if (width <= 0 || height <= 0 || width > kMaximumViewportDimension ||
      height > kMaximumViewportDimension) {
    return KWEB_STATUS_INVALID_DIMENSIONS;
  }
  return GuardStatus([&] { return Registry().Resize(session, width, height); });
}

kweb_status KWEB_ABI_CALL kweb_session_close(kweb_session_handle session) {
  return GuardStatus([&] { return Registry().Close(session); });
}

uint64_t KWEB_ABI_CALL kweb_live_session_count(void) {
  try {
    return Registry().LiveCount();
  } catch (...) {
    return std::numeric_limits<uint64_t>::max();
  }
}

} // extern "C"
