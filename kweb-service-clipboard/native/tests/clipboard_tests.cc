#include "kweb_clipboard.h"

#include <cassert>
#include <cstdint>

int main() {
  assert(kweb_clipboard_abi_version() == KWEB_CLIPBOARD_ABI_VERSION);
  assert(kweb_clipboard_status_name(KWEB_CLIPBOARD_STATUS_OK) != nullptr);
  assert(kweb_clipboard_provider_id() != nullptr);
  assert(kweb_clipboard_live_count() == 0);

  uint64_t handle = 0;
  const auto open_status = kweb_clipboard_open(&handle);
  if (open_status == KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE) return 0;
  assert(open_status == KWEB_CLIPBOARD_STATUS_OK);
  assert(handle == 1);
  assert(kweb_clipboard_live_count() == 1);

  kweb_clipboard_snapshot_record snapshot{
      sizeof(kweb_clipboard_snapshot_record),
      KWEB_CLIPBOARD_ABI_VERSION,
      0,
      0,
      KWEB_CLIPBOARD_OWNERSHIP_UNAVAILABLE,
      0,
  };
  assert(kweb_clipboard_snapshot(handle, &snapshot) == KWEB_CLIPBOARD_STATUS_OK);
  (void)snapshot;
  assert(snapshot.struct_size == sizeof(kweb_clipboard_snapshot_record));
  assert(snapshot.abi_version == KWEB_CLIPBOARD_ABI_VERSION);

  const uint8_t value[] = {'x'};
  kweb_clipboard_item invalid_item{
      0,
      KWEB_CLIPBOARD_ABI_VERSION,
      KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN,
      0,
      value,
      sizeof(value),
  };
  if (kweb_clipboard_write(handle, &invalid_item, 1) != KWEB_CLIPBOARD_STATUS_ABI_MISMATCH) return 1;

  assert(kweb_clipboard_close(handle) == KWEB_CLIPBOARD_STATUS_OK);
  assert(kweb_clipboard_live_count() == 0);
  return 0;
}
