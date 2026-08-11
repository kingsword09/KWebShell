#ifndef KWEBSHELL_NATIVE_EVENT_RECORDER_H_
#define KWEBSHELL_NATIVE_EVENT_RECORDER_H_

#include <filesystem>
#include <fstream>
#include <initializer_list>
#include <map>
#include <mutex>
#include <optional>
#include <string>

namespace kwebshell {

class EventRecorder final {
public:
  explicit EventRecorder(
      const std::optional<std::filesystem::path> &event_log_path);

  EventRecorder(const EventRecorder &) = delete;
  EventRecorder &operator=(const EventRecorder &) = delete;

  bool IsOpen() const;
  const std::string &open_error() const;

  void Record(const std::string &event);
  void Record(const std::string &event,
              const std::map<std::string, std::string> &fields);
  void Fail(const std::string &code,
            const std::map<std::string, std::string> &details = {});

  bool failed() const;
  bool saw_gpu_process() const;
  bool saw_renderer_process() const;
  unsigned int MarkChildProcess(const std::string &process_type);

private:
  static std::string EscapeJson(const std::string &value);

  mutable std::mutex mutex_;
  std::ofstream stream_;
  std::string open_error_;
  unsigned long long sequence_ = 0;
  bool failed_ = false;
  std::map<std::string, unsigned int> child_process_launch_counts_;
};

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_EVENT_RECORDER_H_
