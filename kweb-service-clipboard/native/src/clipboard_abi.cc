#include "clipboard_internal.h"

#include <atomic>
#include <cstring>

namespace {
std::mutex g_registry_mutex;
kwebshell::clipboard::State g_state;
std::atomic<uint32_t> g_live_count{0};
}

namespace kwebshell::clipboard {

State *GetState(uint64_t handle) {
  return handle == 1 && g_state.open ? &g_state : nullptr;
}

bool ValidFormat(kweb_clipboard_format format) {
  return format >= KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN &&
         format <= KWEB_CLIPBOARD_FORMAT_URI_LIST;
}

uint32_t FormatBit(kweb_clipboard_format format) {
  return 1u << (format - 1u);
}

size_t FormatIndex(kweb_clipboard_format format) {
  return static_cast<size_t>(format - 1u);
}

}  // namespace kwebshell::clipboard

extern "C" {

uint32_t kweb_clipboard_abi_version(void) {
  return KWEB_CLIPBOARD_ABI_VERSION;
}

const char *kweb_clipboard_status_name(kweb_clipboard_status status) {
  switch (status) {
    case KWEB_CLIPBOARD_STATUS_OK: return "ok";
    case KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_CLIPBOARD_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE: return "read-unavailable";
    case KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE: return "write-unavailable";
    case KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN: return "write-outcome-unknown";
    case KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED: return "format-unsupported";
    case KWEB_CLIPBOARD_STATUS_BUFFER_SMALL: return "buffer-small";
    case KWEB_CLIPBOARD_STATUS_NATIVE_FAILED: return "native-failed";
    default: return "unknown";
  }
}

const char *kweb_clipboard_provider_id(void) {
  return kwebshell::clipboard::ProviderId();
}

kweb_clipboard_status kweb_clipboard_open(uint64_t *handle) {
  if (handle == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (g_state.open) return KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE;
  const auto status = kwebshell::clipboard::NativeOpen(g_state);
  if (status != KWEB_CLIPBOARD_STATUS_OK) return status;
  g_state.open = true;
  g_live_count.store(1);
  *handle = 1;
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status kweb_clipboard_snapshot(
    uint64_t handle, kweb_clipboard_snapshot_record *snapshot) {
  if (snapshot == nullptr || snapshot->struct_size < sizeof(kweb_clipboard_snapshot_record) ||
      snapshot->abi_version != KWEB_CLIPBOARD_ABI_VERSION) {
    return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  auto *state = kwebshell::clipboard::GetState(handle);
  if (state == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(state->mutex);
  return kwebshell::clipboard::NativeSnapshot(*state, *snapshot);
}

kweb_clipboard_status kweb_clipboard_read(
    uint64_t handle, kweb_clipboard_format format, uint8_t *buffer,
    size_t capacity, size_t *size) {
  if (size == nullptr || !kwebshell::clipboard::ValidFormat(format)) {
    return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  auto *state = kwebshell::clipboard::GetState(handle);
  if (state == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(state->mutex);
  return kwebshell::clipboard::NativeRead(*state, format, buffer, capacity, size);
}

kweb_clipboard_status kweb_clipboard_write(
    uint64_t handle, const kweb_clipboard_item *items, size_t count) {
  if (items == nullptr || count == 0 || count > 4) {
    return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  }
  uint32_t format_mask = 0;
  for (size_t index = 0; index < count; ++index) {
    const auto &item = items[index];
    if (item.struct_size < sizeof(kweb_clipboard_item) ||
        item.abi_version != KWEB_CLIPBOARD_ABI_VERSION) {
      return KWEB_CLIPBOARD_STATUS_ABI_MISMATCH;
    }
    if (!kwebshell::clipboard::ValidFormat(item.format) ||
        (item.data == nullptr && item.size != 0)) {
      return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
    }
    const auto bit = kwebshell::clipboard::FormatBit(item.format);
    if ((format_mask & bit) != 0) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
    format_mask |= bit;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  auto *state = kwebshell::clipboard::GetState(handle);
  if (state == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(state->mutex);
  return kwebshell::clipboard::NativeWrite(*state, items, count);
}

kweb_clipboard_status kweb_clipboard_clear(uint64_t handle) {
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  auto *state = kwebshell::clipboard::GetState(handle);
  if (state == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(state->mutex);
  return kwebshell::clipboard::NativeClear(*state);
}

kweb_clipboard_status kweb_clipboard_close(uint64_t handle) {
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  auto *state = kwebshell::clipboard::GetState(handle);
  if (state == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> lock(state->mutex);
  const auto status = kwebshell::clipboard::NativeClose(*state);
  if (status == KWEB_CLIPBOARD_STATUS_OK) {
    state->open = false;
    g_live_count.store(0);
  }
  return status;
}

uint32_t kweb_clipboard_live_count(void) {
  return g_live_count.load();
}

}  // extern "C"
