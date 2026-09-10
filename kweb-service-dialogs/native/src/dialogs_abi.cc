#include "dialog_operation.h"

#include <cstring>
#include <limits>
#include <map>
#include <memory>

namespace kwebshell::dialogs {
bool ValidUtf8(const char *data, size_t size) {
  if (size != 0 && data == nullptr) return false;
  for (size_t i = 0; i < size;) {
    const auto lead = static_cast<unsigned char>(data[i++]);
    if (lead == 0) return false;
    if (lead < 0x80) continue;
    unsigned count, low = 0x80, high = 0xbf;
    if (lead >= 0xc2 && lead <= 0xdf) count = 1;
    else if (lead >= 0xe0 && lead <= 0xef) {
      count = 2;
      if (lead == 0xe0) low = 0xa0;
      if (lead == 0xed) high = 0x9f;
    } else if (lead >= 0xf0 && lead <= 0xf4) {
      count = 3;
      if (lead == 0xf0) low = 0x90;
      if (lead == 0xf4) high = 0x8f;
    } else return false;
    if (count > size - i) return false;
    const auto second = static_cast<unsigned char>(data[i++]);
    if (second < low || second > high) return false;
    for (unsigned j = 1; j < count; ++j) {
      const auto c = static_cast<unsigned char>(data[i++]);
      if (c < 0x80 || c > 0xbf) return false;
    }
  }
  return true;
}
} // namespace kwebshell::dialogs

namespace {
using kwebshell::dialogs::Operation;
std::mutex registry_mutex;
std::map<uint64_t, std::shared_ptr<Operation>> registry;
uint64_t next_id = 1;

bool CopyText(kweb_dialog_text text, std::string &out, size_t limit) {
  if (text.size > limit || !kwebshell::dialogs::ValidUtf8(text.data, text.size)) return false;
  if (text.size != 0) out.assign(text.data, text.size);
  return true;
}
bool ValidName(const std::string &name) {
  if (name.empty()) return true;
  if (name == "." || name == ".." || name.back() == '.' || name.back() == ' ') return false;
  for (const unsigned char c : name) {
    if (c < 32 || c == 127 || std::strchr("<>:\"/\\|?*", c) != nullptr) return false;
  }
  return true;
}
bool ValidExtension(const std::string &extension) {
  if (extension.empty() || extension.size() > 32) return false;
  bool component_start = true;
  for (char c : extension) {
    const bool alphanumeric = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    if (component_start && !alphanumeric) return false;
    if (!alphanumeric && c != '.' && c != '+' && c != '_' && c != '-') return false;
    component_start = c == '.';
  }
  return !component_start;
}
} // namespace

extern "C" {
uint32_t kweb_dialog_abi_version() { return KWEB_DIALOG_ABI_VERSION; }

int32_t kweb_dialog_start(const kweb_dialog_request *request, uint64_t *id) {
  if (id == nullptr) return KWEB_DIALOG_INVALID_REQUEST;
  *id = 0;
  if (request == nullptr || request->struct_size != sizeof(*request) ||
      request->abi_version != KWEB_DIALOG_ABI_VERSION || request->mode > 1 ||
      request->owner == 0 || request->extension_count > 1024 ||
      (request->extension_count != 0 && request->extensions == nullptr)) {
    return KWEB_DIALOG_INVALID_REQUEST;
  }
  try {
    auto op = std::make_shared<Operation>();
    op->owner = request->owner;
    op->mode = request->mode;
    if (!CopyText(request->title, op->title, 1024) || op->title.empty() ||
        !CopyText(request->directory, op->directory, 16384) ||
        !CopyText(request->name, op->name, 1024) || !ValidName(op->name)) {
      return KWEB_DIALOG_INVALID_REQUEST;
    }
    for (uint32_t i = 0; i < request->extension_count; ++i) {
      std::string extension;
      if (!CopyText(request->extensions[i], extension, 32) || !ValidExtension(extension)) {
        return KWEB_DIALOG_INVALID_REQUEST;
      }
      op->extensions.push_back(std::move(extension));
    }
    std::lock_guard lock(registry_mutex);
    if (registry.size() >= 64 || next_id == std::numeric_limits<uint64_t>::max()) return KWEB_DIALOG_BUSY;
    for (const auto &[key, existing] : registry) {
      (void)key;
      if (existing->owner == op->owner && !existing->done.load()) return KWEB_DIALOG_BUSY;
    }
    const auto key = next_id++;
    registry.emplace(key, op);
    try {
      op->worker = std::thread([op] {
        try {
          kwebshell::dialogs::RunDialog(*op);
          if (op->state == KWEB_DIALOG_SELECTED &&
              (op->path.empty() || op->path.size() > 16384 ||
               !kwebshell::dialogs::ValidUtf8(op->path.data(), op->path.size()))) {
            op->state = KWEB_DIALOG_FAILED;
            op->failure = KWEB_DIALOG_NATIVE_FAILED;
            op->path.clear();
          }
        } catch (...) {
          op->state = KWEB_DIALOG_FAILED;
          op->failure = KWEB_DIALOG_NATIVE_FAILED;
          op->path.clear();
        }
        op->visible.store(false);
        op->done.store(true);
      });
    } catch (...) {
      registry.erase(key);
      throw;
    }
    *id = key;
    return KWEB_DIALOG_OK;
  } catch (...) {
    return KWEB_DIALOG_NATIVE_FAILED;
  }
}

int32_t kweb_dialog_poll(uint64_t id, kweb_dialog_result *result, char *path, size_t capacity) {
  if (result == nullptr || result->struct_size != sizeof(*result) ||
      result->abi_version != KWEB_DIALOG_ABI_VERSION ||
      (capacity != 0 && path == nullptr)) return KWEB_DIALOG_INVALID_REQUEST;
  std::lock_guard lock(registry_mutex);
  const auto found = registry.find(id);
  if (found == registry.end()) return KWEB_DIALOG_INVALID_HANDLE;
  const auto &op = *found->second;
  result->path_size = 0;
  result->failure = 0;
  if (!op.done.load()) {
    result->state = op.visible.load() ? KWEB_DIALOG_VISIBLE : KWEB_DIALOG_PENDING;
    return KWEB_DIALOG_OK;
  }
  result->state = op.state;
  result->failure = op.failure;
  result->path_size = op.path.size();
  if (op.path.size() > capacity) return KWEB_DIALOG_BUFFER_SMALL;
  if (!op.path.empty()) std::memcpy(path, op.path.data(), op.path.size());
  return KWEB_DIALOG_OK;
}

int32_t kweb_dialog_cancel(uint64_t id) {
  std::lock_guard lock(registry_mutex);
  const auto found = registry.find(id);
  if (found == registry.end()) return KWEB_DIALOG_INVALID_HANDLE;
  found->second->cancel_requested.store(true);
#if defined(_WIN32)
  kwebshell::dialogs::RequestWindowsDialogCancellation(*found->second);
#endif
  return KWEB_DIALOG_OK;
}

int32_t kweb_dialog_release(uint64_t id) {
  std::shared_ptr<Operation> op;
  {
    std::lock_guard lock(registry_mutex);
    const auto found = registry.find(id);
    if (found == registry.end()) return KWEB_DIALOG_INVALID_HANDLE;
    if (!found->second->done.load()) return KWEB_DIALOG_BUSY;
    op = found->second;
    registry.erase(found);
  }
  op->worker.join();
  return KWEB_DIALOG_OK;
}

uint32_t kweb_dialog_live_count() {
  std::lock_guard lock(registry_mutex);
  return static_cast<uint32_t>(registry.size());
}
} // extern "C"
