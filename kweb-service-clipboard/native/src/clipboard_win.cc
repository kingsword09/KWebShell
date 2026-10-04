#include "clipboard_internal.h"

#include <windows.h>

#include <algorithm>
#include <chrono>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace {

bool OpenClipboardForTransfer() {
  using Clock = std::chrono::steady_clock;
  const auto deadline = Clock::now() + std::chrono::milliseconds(250);
  for (;;) {
    if (OpenClipboard(nullptr)) return true;
    if (GetLastError() != ERROR_ACCESS_DENIED) return false;
    const auto now = Clock::now();
    if (now >= deadline) return false;
    // Other applications and readers hold the same OS lock. Only acquisition
    // is retried; the transfer itself still executes once after admission.
    std::this_thread::sleep_until((std::min)(deadline, now + std::chrono::milliseconds(5)));
  }
}

UINT HtmlFormat() {
  static const UINT value = RegisterClipboardFormatW(L"HTML Format");
  return value;
}
UINT RtfFormat() {
  static const UINT value = RegisterClipboardFormatW(L"Rich Text Format");
  return value;
}
UINT UriFormat() {
  static const UINT value = RegisterClipboardFormatW(L"text/uri-list");
  return value;
}

UINT NativeFormat(kweb_clipboard_format format) {
  switch (format) {
    case KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN: return CF_UNICODETEXT;
    case KWEB_CLIPBOARD_FORMAT_TEXT_HTML: return HtmlFormat();
    case KWEB_CLIPBOARD_FORMAT_TEXT_RTF: return RtfFormat();
    case KWEB_CLIPBOARD_FORMAT_URI_LIST: return UriFormat();
    default: return 0;
  }
}

uint32_t CurrentMask() {
  uint32_t mask = 0;
  for (uint32_t format = KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN;
       format <= KWEB_CLIPBOARD_FORMAT_URI_LIST; ++format) {
    if (IsClipboardFormatAvailable(NativeFormat(format))) {
      mask |= 1u << (format - 1u);
    }
  }
  return mask;
}

std::vector<uint8_t> Utf8ToWideBytes(const uint8_t *data, size_t size) {
  if (size == 0) return std::vector<uint8_t>(sizeof(wchar_t), 0);
  if (size > INT_MAX) return {};
  const int wide_size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                             reinterpret_cast<const char *>(data),
                                             static_cast<int>(size), nullptr, 0);
  if (wide_size <= 0) return {};
  std::vector<wchar_t> wide(static_cast<size_t>(wide_size) + 1u);
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                          reinterpret_cast<const char *>(data),
                          static_cast<int>(size), wide.data(), wide_size) <= 0) {
    return {};
  }
  std::vector<uint8_t> result((wide.size()) * sizeof(wchar_t));
  std::memcpy(result.data(), wide.data(), result.size());
  return result;
}

std::vector<uint8_t> WideBytesToUtf8(const void *data, size_t size) {
  const auto *wide = static_cast<const wchar_t *>(data);
  int wide_count = static_cast<int>(size / sizeof(wchar_t));
  while (wide_count > 0 && wide[wide_count - 1] == L'\0') --wide_count;
  if (wide_count == 0) return {};
  const int utf8_size = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
                                             wide, wide_count, nullptr, 0,
                                             nullptr, nullptr);
  if (utf8_size <= 0) return {};
  std::vector<uint8_t> result(static_cast<size_t>(utf8_size));
  if (WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS,
                          wide, wide_count,
                          reinterpret_cast<char *>(result.data()), utf8_size,
                          nullptr, nullptr) <= 0) {
    return {};
  }
  return result;
}

kweb_clipboard_status CopyBytes(const std::vector<uint8_t> &source,
                                uint8_t *buffer, size_t capacity, size_t *size) {
  if (size == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  *size = source.size();
  if (source.size() > capacity || (source.size() != 0 && buffer == nullptr)) {
    return KWEB_CLIPBOARD_STATUS_BUFFER_SMALL;
  }
  if (!source.empty()) std::memcpy(buffer, source.data(), source.size());
  return KWEB_CLIPBOARD_STATUS_OK;
}

}  // namespace

namespace kwebshell::clipboard {

const char *ProviderId() {
  return "windows.Win32.clipboard";
}

kweb_clipboard_status NativeOpen(State &state) {
  if (!OpenClipboardForTransfer()) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  state.native_sequence = GetClipboardSequenceNumber();
  state.format_mask = CurrentMask();
  state.owned = false;
  CloseClipboard();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeSnapshot(State &state, kweb_clipboard_snapshot_record &snapshot) {
  if (!OpenClipboardForTransfer()) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  const uint64_t native_sequence = GetClipboardSequenceNumber();
  const uint32_t mask = CurrentMask();
  CloseClipboard();
  if (native_sequence != state.native_sequence) {
    state.native_sequence = native_sequence;
    state.owned = false;
    state.sequence += 1;
  }
  state.format_mask = mask;
  snapshot.sequence = state.sequence;
  snapshot.format_mask = mask;
  snapshot.ownership = mask == 0
      ? KWEB_CLIPBOARD_OWNERSHIP_EMPTY
      : (state.owned ? KWEB_CLIPBOARD_OWNERSHIP_OWNED
                     : KWEB_CLIPBOARD_OWNERSHIP_FOREIGN);
  snapshot.reserved = 0;
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeRead(State &state, kweb_clipboard_format format,
                                 uint8_t *buffer, size_t capacity, size_t *size) {
  (void)state;
  if (!OpenClipboardForTransfer()) return KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE;
  HANDLE handle = GetClipboardData(NativeFormat(format));
  if (handle == nullptr) {
    CloseClipboard();
    return KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED;
  }
  SIZE_T bytes = GlobalSize(handle);
  void *data = GlobalLock(handle);
  if (data == nullptr) {
    CloseClipboard();
    return KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE;
  }
  std::vector<uint8_t> result;
  if (format == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN) {
    result = WideBytesToUtf8(data, bytes);
  } else {
    const auto *raw = static_cast<const uint8_t *>(data);
    while (bytes > 0 && raw[bytes - 1] == 0) --bytes;
    result.assign(raw, raw + bytes);
  }
  GlobalUnlock(handle);
  CloseClipboard();
  return CopyBytes(result, buffer, capacity, size);
}

kweb_clipboard_status NativeWrite(State &state, const kweb_clipboard_item *items,
                                  size_t count) {
  if (!OpenClipboardForTransfer()) return KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE;
  if (!EmptyClipboard()) {
    CloseClipboard();
    return KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE;
  }
  for (size_t index = 0; index < count; ++index) {
    const auto &item = items[index];
    std::vector<uint8_t> bytes;
    if (item.format == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN) {
      bytes = Utf8ToWideBytes(item.data, item.size);
      if (bytes.empty()) {
        CloseClipboard();
        return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
      }
      bytes.resize(bytes.size() + sizeof(wchar_t), 0);
    } else {
      bytes.assign(item.data, item.data + item.size);
      bytes.push_back(0);
    }
    HGLOBAL memory = GlobalAlloc(GMEM_MOVEABLE, bytes.size());
    if (memory == nullptr) {
      CloseClipboard();
      return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
    }
    void *target = GlobalLock(memory);
    std::memcpy(target, bytes.data(), bytes.size());
    GlobalUnlock(memory);
    if (SetClipboardData(NativeFormat(item.format), memory) == nullptr) {
      GlobalFree(memory);
      CloseClipboard();
      return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
    }
  }
  state.format_mask = 0;
  for (size_t index = 0; index < count; ++index) {
    state.format_mask |= 1u << (items[index].format - 1u);
  }
  state.native_sequence = GetClipboardSequenceNumber();
  state.owned = true;
  state.sequence += 1;
  CloseClipboard();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeClear(State &state) {
  if (!OpenClipboardForTransfer()) return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
  if (!EmptyClipboard()) {
    CloseClipboard();
    return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
  }
  state.format_mask = 0;
  state.native_sequence = GetClipboardSequenceNumber();
  state.owned = true;
  state.sequence += 1;
  CloseClipboard();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeClose(State &state) {
  state.native_clipboard = nullptr;
  state.values = {};
  return KWEB_CLIPBOARD_STATUS_OK;
}

}  // namespace kwebshell::clipboard
