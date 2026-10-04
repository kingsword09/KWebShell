#include "kweb_clipboard.h"

#include <cassert>
#include <cstdint>

#if defined(_WIN32)
#include <windows.h>

#include <chrono>
#include <cstdio>
#include <future>
#include <thread>

namespace {
using namespace std::chrono_literals;

// Own the real clipboard from a different window/thread. A zero duration
// keeps the lock until destruction; no clipboard API is mocked here.
class ClipboardLock {
 public:
  explicit ClipboardLock(std::chrono::milliseconds duration = 0ms) {
    std::promise<bool> ready;
    auto acquired = ready.get_future();
    thread_ = std::thread([ready = std::move(ready), release = release_.get_future(), duration]() mutable {
      const HWND window = CreateWindowExW(0, L"STATIC", L"KWebClipboard contention fixture",
                                           0, 0, 0, 0, 0, HWND_MESSAGE, nullptr, nullptr, nullptr);
      assert(window != nullptr);
      const auto deadline = std::chrono::steady_clock::now() + 2s;
      bool locked = false;
      do {
        locked = OpenClipboard(window) != FALSE;
        if (!locked) std::this_thread::sleep_for(5ms);
      } while (!locked && std::chrono::steady_clock::now() < deadline);
      ready.set_value(locked);
      if (locked) {
        if (duration == 0ms) release.wait();
        else (void)release.wait_for(duration);
        assert(CloseClipboard() != FALSE);
      }
      assert(DestroyWindow(window) != FALSE);
    });
    assert(acquired.get());
  }

  ~ClipboardLock() {
    release_.set_value();
    thread_.join();
  }

 private:
  std::promise<void> release_;
  std::thread thread_;
};

template <typename Operation>
void VerifyBoundedFailure(Operation operation, kweb_clipboard_status expected) {
  const auto start = std::chrono::steady_clock::now();
  assert(operation() == expected);
  const auto elapsed = std::chrono::steady_clock::now() - start;
  assert(elapsed >= 200ms && elapsed < 2s);
}

void VerifyWin32Contention(uint64_t handle) {
  const uint8_t original[] = {'x'};
  kweb_clipboard_item item{sizeof(kweb_clipboard_item), KWEB_CLIPBOARD_ABI_VERSION,
                           KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN, 0, original, sizeof(original)};
  assert(kweb_clipboard_write(handle, &item, 1) == KWEB_CLIPBOARD_STATUS_OK);
  uint8_t output[8]{};
  size_t size = 0;
  const auto read = [&] {
    return kweb_clipboard_read(handle, KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN, output, sizeof(output), &size);
  };
  {
    ClipboardLock lock(100ms);
    assert(read() == KWEB_CLIPBOARD_STATUS_OK);
    assert(size == 1 && output[0] == original[0]);
  }
  {
    ClipboardLock lock;
    kweb_clipboard_snapshot_record snapshot{};
    snapshot.struct_size = sizeof(snapshot);
    snapshot.abi_version = KWEB_CLIPBOARD_ABI_VERSION;
    VerifyBoundedFailure(read, KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE);
    VerifyBoundedFailure([&] { return kweb_clipboard_snapshot(handle, &snapshot); },
                          KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE);
    const uint8_t replacement[] = {'y'};
    item.data = replacement;
    VerifyBoundedFailure([&] { return kweb_clipboard_write(handle, &item, 1); },
                          KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE);
    VerifyBoundedFailure([&] { return kweb_clipboard_clear(handle); },
                          KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN);
  }
  assert(read() == KWEB_CLIPBOARD_STATUS_OK);
  assert(size == 1 && output[0] == original[0]);
  assert(kweb_clipboard_close(handle) == KWEB_CLIPBOARD_STATUS_OK);
  {
    ClipboardLock lock;
    uint64_t unavailable_handle = 0;
    VerifyBoundedFailure([&] { return kweb_clipboard_open(&unavailable_handle); },
                          KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE);
    assert(unavailable_handle == 0 && kweb_clipboard_live_count() == 0);
  }
  uint64_t reopened = 0;
  assert(kweb_clipboard_open(&reopened) == KWEB_CLIPBOARD_STATUS_OK);
  assert(reopened == handle);
  assert(read() == KWEB_CLIPBOARD_STATUS_OK);
  assert(size == 1 && output[0] == original[0]);
  std::puts("Win32 clipboard contention: released lock succeeds; retained lock is bounded; content preserved.");
}
}  // namespace
#endif

int main() {
  assert(kweb_clipboard_abi_version() == KWEB_CLIPBOARD_ABI_VERSION);
  assert(kweb_clipboard_status_name(KWEB_CLIPBOARD_STATUS_OK) != nullptr);
  assert(kweb_clipboard_provider_id() != nullptr);
  assert(kweb_clipboard_live_count() == 0);

  uint64_t handle = 0;
  const auto open_status = kweb_clipboard_open(&handle);
#if !defined(_WIN32)
  if (open_status == KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE) return 0;
#endif
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

#if defined(_WIN32)
  VerifyWin32Contention(handle);
#endif
  assert(kweb_clipboard_close(handle) == KWEB_CLIPBOARD_STATUS_OK);
  assert(kweb_clipboard_live_count() == 0);
  return 0;
}
