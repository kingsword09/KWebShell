#ifndef KWEB_CLIPBOARD_INTERNAL_H_
#define KWEB_CLIPBOARD_INTERNAL_H_

#include "kweb_clipboard.h"

#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::clipboard {

struct State {
  std::mutex mutex;
  bool open = false;
  bool owned = false;
  uint64_t sequence = 1;
  uint32_t format_mask = 0;
  uint64_t native_sequence = 0;
  std::array<std::vector<uint8_t>, 4> values;
  void *native_clipboard = nullptr;
};

State *GetState(uint64_t handle);
const char *ProviderId();
kweb_clipboard_status NativeOpen(State &state);
kweb_clipboard_status NativeSnapshot(State &state, kweb_clipboard_snapshot_record &snapshot);
kweb_clipboard_status NativeRead(State &state, kweb_clipboard_format format,
                                 uint8_t *buffer, size_t capacity, size_t *size);
kweb_clipboard_status NativeWrite(State &state, const kweb_clipboard_item *items, size_t count);
kweb_clipboard_status NativeClear(State &state);
kweb_clipboard_status NativeClose(State &state);

bool ValidFormat(kweb_clipboard_format format);
uint32_t FormatBit(kweb_clipboard_format format);
size_t FormatIndex(kweb_clipboard_format format);

}  // namespace kwebshell::clipboard

#endif
