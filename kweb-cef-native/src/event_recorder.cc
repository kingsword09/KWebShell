#include "kwebshell/native/event_recorder.h"

#include <iomanip>
#include <iostream>
#include <sstream>

namespace kwebshell {

EventRecorder::EventRecorder(
    const std::optional<std::filesystem::path> &event_log_path) {
  if (!event_log_path) {
    return;
  }

  std::error_code directory_error;
  const auto parent = event_log_path->parent_path();
  if (!parent.empty()) {
    std::filesystem::create_directories(parent, directory_error);
  }
  if (directory_error) {
    open_error_ =
        "Unable to create event log directory: " + directory_error.message();
    return;
  }

  stream_.open(*event_log_path, std::ios::out | std::ios::trunc);
  if (!stream_.is_open()) {
    open_error_ = "Unable to open event log: " + event_log_path->string();
  }
}

bool EventRecorder::IsOpen() const {
  std::lock_guard lock(mutex_);
  return open_error_.empty();
}

const std::string &EventRecorder::open_error() const { return open_error_; }

void EventRecorder::Record(const std::string &event) { Record(event, {}); }

void EventRecorder::Record(const std::string &event,
                           const std::map<std::string, std::string> &fields) {
  std::lock_guard lock(mutex_);
  std::ostringstream line;
  line << "{\"sequence\":" << ++sequence_ << ",\"event\":\""
       << EscapeJson(event) << '"';
  for (const auto &[key, value] : fields) {
    line << ",\"" << EscapeJson(key) << "\":\"" << EscapeJson(value) << '"';
  }
  line << '}';

  if (stream_.is_open()) {
    stream_ << line.str() << '\n';
    stream_.flush();
  }
  std::cerr << line.str() << std::endl;
}

void EventRecorder::Fail(const std::string &code,
                         const std::map<std::string, std::string> &details) {
  {
    std::lock_guard lock(mutex_);
    failed_ = true;
  }
  auto fields = details;
  fields.emplace("code", code);
  Record("error", fields);
}

bool EventRecorder::failed() const {
  std::lock_guard lock(mutex_);
  return failed_;
}

bool EventRecorder::saw_gpu_process() const {
  std::lock_guard lock(mutex_);
  const auto found = child_process_launch_counts_.find("gpu-process");
  return found != child_process_launch_counts_.end() && found->second > 0;
}

bool EventRecorder::saw_renderer_process() const {
  std::lock_guard lock(mutex_);
  const auto found = child_process_launch_counts_.find("renderer");
  return found != child_process_launch_counts_.end() && found->second > 0;
}

unsigned int EventRecorder::MarkChildProcess(const std::string &process_type) {
  unsigned int launch_count = 0;
  {
    std::lock_guard lock(mutex_);
    launch_count = ++child_process_launch_counts_[process_type];
  }
  Record(
      "child_process_launch",
      {{"type", process_type}, {"launch_count", std::to_string(launch_count)}});
  return launch_count;
}

std::string EventRecorder::EscapeJson(const std::string &value) {
  std::ostringstream escaped;
  for (const unsigned char character : value) {
    switch (character) {
    case '"':
      escaped << "\\\"";
      break;
    case '\\':
      escaped << "\\\\";
      break;
    case '\b':
      escaped << "\\b";
      break;
    case '\f':
      escaped << "\\f";
      break;
    case '\n':
      escaped << "\\n";
      break;
    case '\r':
      escaped << "\\r";
      break;
    case '\t':
      escaped << "\\t";
      break;
    default:
      if (character < 0x20) {
        escaped << "\\u" << std::hex << std::setw(4) << std::setfill('0')
                << static_cast<int>(character) << std::dec;
      } else {
        escaped << character;
      }
    }
  }
  return escaped.str();
}

} // namespace kwebshell
