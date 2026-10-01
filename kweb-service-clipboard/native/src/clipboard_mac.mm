#import <AppKit/AppKit.h>

#include "clipboard_internal.h"

#include <algorithm>
#include <cstring>

namespace {

NSString *TypeFor(kweb_clipboard_format format) {
  switch (format) {
    case KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN: return NSPasteboardTypeString;
    case KWEB_CLIPBOARD_FORMAT_TEXT_HTML: return NSPasteboardTypeHTML;
    case KWEB_CLIPBOARD_FORMAT_TEXT_RTF: return NSPasteboardTypeRTF;
    case KWEB_CLIPBOARD_FORMAT_URI_LIST: return @"public.url";
    default: return nil;
  }
}

uint32_t MaskFor(NSPasteboard *pasteboard) {
  uint32_t mask = 0;
  NSArray<NSPasteboardType> *types = pasteboard.types;
  for (uint32_t format = KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN;
       format <= KWEB_CLIPBOARD_FORMAT_URI_LIST; ++format) {
    NSString *type = TypeFor(format);
    if (type != nil && [types containsObject:type]) {
      mask |= 1u << (format - 1u);
    }
  }
  return mask;
}

kweb_clipboard_status CopyBytes(const uint8_t *source, size_t size,
                                uint8_t *buffer, size_t capacity, size_t *written) {
  if (written == nullptr) return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
  *written = size;
  if (size > capacity || (size != 0 && buffer == nullptr)) {
    return KWEB_CLIPBOARD_STATUS_BUFFER_SMALL;
  }
  if (size != 0) std::memcpy(buffer, source, size);
  return KWEB_CLIPBOARD_STATUS_OK;
}

}  // namespace

namespace kwebshell::clipboard {

const char *ProviderId() {
  return "macos.AppKit.NSPasteboard";
}

kweb_clipboard_status NativeOpen(State &state) {
  @autoreleasepool {
    NSPasteboard *pasteboard = [NSPasteboard generalPasteboard];
    if (pasteboard == nil) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
    state.native_clipboard = (__bridge void *)pasteboard;
    state.native_sequence = static_cast<uint64_t>(pasteboard.changeCount);
    state.format_mask = MaskFor(pasteboard);
    state.owned = false;
    return KWEB_CLIPBOARD_STATUS_OK;
  }
}

kweb_clipboard_status NativeSnapshot(State &state, kweb_clipboard_snapshot_record &snapshot) {
  @autoreleasepool {
    auto *pasteboard = (__bridge NSPasteboard *)state.native_clipboard;
    if (pasteboard == nil) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
    const uint64_t change_count = static_cast<uint64_t>(pasteboard.changeCount);
    const uint32_t mask = MaskFor(pasteboard);
    if (change_count != state.native_sequence) {
      state.native_sequence = change_count;
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
}

kweb_clipboard_status NativeRead(State &state, kweb_clipboard_format format,
                                 uint8_t *buffer, size_t capacity, size_t *size) {
  @autoreleasepool {
    auto *pasteboard = (__bridge NSPasteboard *)state.native_clipboard;
    NSString *type = TypeFor(format);
    if (pasteboard == nil || type == nil) return KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED;
    NSData *data = nil;
    if (format == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN ||
        format == KWEB_CLIPBOARD_FORMAT_URI_LIST) {
      NSString *value = [pasteboard stringForType:type];
      if (value == nil) return KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED;
      data = [value dataUsingEncoding:NSUTF8StringEncoding];
    } else {
      data = [pasteboard dataForType:type];
    }
    if (data == nil) return KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED;
    return CopyBytes(static_cast<const uint8_t *>(data.bytes), data.length,
                     buffer, capacity, size);
  }
}

kweb_clipboard_status NativeWrite(State &state, const kweb_clipboard_item *items,
                                  size_t count) {
  @autoreleasepool {
    auto *pasteboard = (__bridge NSPasteboard *)state.native_clipboard;
    if (pasteboard == nil) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
    if ([pasteboard clearContents] == NO) return KWEB_CLIPBOARD_STATUS_WRITE_UNAVAILABLE;
    uint32_t mask = 0;
    for (size_t index = 0; index < count; ++index) {
      const auto &item = items[index];
      NSString *type = TypeFor(item.format);
      if (type == nil || (item.data == nullptr && item.size != 0)) {
        return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
      }
      NSData *data = [NSData dataWithBytes:item.data length:item.size];
      BOOL accepted;
      if (item.format == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN ||
          item.format == KWEB_CLIPBOARD_FORMAT_URI_LIST) {
        NSString *value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        accepted = value != nil && [pasteboard setString:value forType:type];
      } else {
        accepted = [pasteboard setData:data forType:type];
      }
      if (!accepted) return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
      mask |= 1u << (item.format - 1u);
    }
    state.format_mask = mask;
    state.native_sequence = static_cast<uint64_t>(pasteboard.changeCount);
    state.owned = true;
    state.sequence += 1;
    return KWEB_CLIPBOARD_STATUS_OK;
  }
}

kweb_clipboard_status NativeClear(State &state) {
  @autoreleasepool {
    auto *pasteboard = (__bridge NSPasteboard *)state.native_clipboard;
    if (pasteboard == nil || [pasteboard clearContents] == NO) {
      return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
    }
    state.format_mask = 0;
    state.native_sequence = static_cast<uint64_t>(pasteboard.changeCount);
    state.owned = true;
    state.sequence += 1;
    return KWEB_CLIPBOARD_STATUS_OK;
  }
}

kweb_clipboard_status NativeClose(State &state) {
  state.native_clipboard = nullptr;
  state.values = {};
  return KWEB_CLIPBOARD_STATUS_OK;
}

}  // namespace kwebshell::clipboard
