#ifndef KWEB_DIALOG_OPERATION_H
#define KWEB_DIALOG_OPERATION_H

#include "kweb_dialogs.h"
#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace kwebshell::dialogs {
struct Operation {
  uint64_t owner = 0;
  uint32_t mode = 0;
  std::string title, directory, name;
  std::vector<std::string> extensions;
  std::atomic<bool> cancel_requested{false};
  std::atomic<uintptr_t> dialog_window{0};
  std::atomic<bool> visible{false};
  std::atomic<bool> done{false};
  uint32_t state = KWEB_DIALOG_PENDING;
  uint32_t failure = 0;
  std::string path;
  std::thread worker;
};

/* Runs on its own worker. Implementations marshal to the actual platform UI. */
void RunDialog(Operation &operation);
bool ValidUtf8(const char *data, size_t size);
#if defined(_WIN32)
void RequestWindowsDialogCancellation(Operation &operation);
#endif
} // namespace kwebshell::dialogs
#endif
