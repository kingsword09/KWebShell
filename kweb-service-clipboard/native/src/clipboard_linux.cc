#include "clipboard_internal.h"

#include <gtk/gtk.h>

#include <algorithm>
#include <cstring>
#include <string>

namespace {

GtkClipboard *Clipboard(kwebshell::clipboard::State &state) {
  return static_cast<GtkClipboard *>(state.native_clipboard);
}

const char *TargetName(kweb_clipboard_format format) {
  switch (format) {
    case KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN: return "UTF8_STRING";
    case KWEB_CLIPBOARD_FORMAT_TEXT_HTML: return "text/html";
    case KWEB_CLIPBOARD_FORMAT_TEXT_RTF: return "text/rtf";
    case KWEB_CLIPBOARD_FORMAT_URI_LIST: return "text/uri-list";
    default: return nullptr;
  }
}

void Pump() {
  while (g_main_context_pending(nullptr)) {
    g_main_context_iteration(nullptr, FALSE);
  }
}

void ClipboardGet(GtkClipboard *, GtkSelectionData *selection_data,
                  guint info, gpointer user_data) {
  auto *state = static_cast<kwebshell::clipboard::State *>(user_data);
  if (info < KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN ||
      info > KWEB_CLIPBOARD_FORMAT_URI_LIST) {
    return;
  }
  const auto &bytes = state->values[info - 1u];
  if (info == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN) {
    std::string text(bytes.begin(), bytes.end());
    gtk_selection_data_set_text(selection_data, text.c_str(), static_cast<gint>(text.size()));
    return;
  }
  GdkAtom target = gdk_atom_intern_static_string(TargetName(info));
  gtk_selection_data_set(selection_data, target, 8, bytes.data(), static_cast<gint>(bytes.size()));
}

void ClipboardClear(GtkClipboard *, gpointer user_data) {
  auto *state = static_cast<kwebshell::clipboard::State *>(user_data);
  state->owned = false;
}

uint32_t CurrentMask(GtkClipboard *clipboard) {
  GdkAtom *targets = nullptr;
  gint count = 0;
  uint32_t mask = 0;
  if (gtk_clipboard_wait_for_targets(clipboard, &targets, &count)) {
    for (gint index = 0; index < count; ++index) {
      gchar *name = gdk_atom_name(targets[index]);
      if (name == nullptr) continue;
      for (uint32_t format = KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN;
           format <= KWEB_CLIPBOARD_FORMAT_URI_LIST; ++format) {
        if (std::string(name) == TargetName(format) ||
            (format == KWEB_CLIPBOARD_FORMAT_TEXT_PLAIN &&
             std::string(name) == "text/plain;charset=utf-8")) {
          mask |= 1u << (format - 1u);
        }
      }
      g_free(name);
    }
    g_free(targets);
  }
  return mask;
}

kweb_clipboard_status CopyBytes(const guint8 *source, size_t size,
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
  return "linux.GTK.CLIPBOARD";
}

kweb_clipboard_status NativeOpen(State &state) {
  if (!gtk_init_check(nullptr, nullptr)) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  GtkClipboard *clipboard = gtk_clipboard_get(GDK_SELECTION_CLIPBOARD);
  if (clipboard == nullptr) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  state.native_clipboard = clipboard;
  state.format_mask = CurrentMask(clipboard);
  state.owned = false;
  Pump();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeSnapshot(State &state, kweb_clipboard_snapshot_record &snapshot) {
  Pump();
  GtkClipboard *clipboard = Clipboard(state);
  if (clipboard == nullptr) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  const uint32_t mask = CurrentMask(clipboard);
  if (mask != state.format_mask) {
    state.format_mask = mask;
    state.owned = false;
    state.sequence += 1;
  }
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
  Pump();
  GtkClipboard *clipboard = Clipboard(state);
  const char *target_name = TargetName(format);
  if (clipboard == nullptr || target_name == nullptr) {
    return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  }
  GdkAtom target = gdk_atom_intern_static_string(target_name);
  GtkSelectionData *selection = gtk_clipboard_wait_for_contents(clipboard, target);
  if (selection == nullptr) return KWEB_CLIPBOARD_STATUS_FORMAT_UNSUPPORTED;
  gint length = gtk_selection_data_get_length(selection);
  const guint8 *data = gtk_selection_data_get_data(selection);
  kweb_clipboard_status status = length < 0 || (length > 0 && data == nullptr)
      ? KWEB_CLIPBOARD_STATUS_READ_UNAVAILABLE
      : CopyBytes(data, static_cast<size_t>(length), buffer, capacity, size);
  gtk_selection_data_free(selection);
  Pump();
  return status;
}

kweb_clipboard_status NativeWrite(State &state, const kweb_clipboard_item *items,
                                  size_t count) {
  GtkClipboard *clipboard = Clipboard(state);
  if (clipboard == nullptr) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  std::array<GtkTargetEntry, 4> targets{};
  for (size_t index = 0; index < count; ++index) {
    const auto &item = items[index];
    if (!ValidFormat(item.format) || (item.data == nullptr && item.size != 0)) {
      return KWEB_CLIPBOARD_STATUS_INVALID_ARGUMENT;
    }
    state.values[item.format - 1u].assign(item.data, item.data + item.size);
    targets[index] = GtkTargetEntry{
        const_cast<gchar *>(TargetName(item.format)), 0, item.format};
  }
  if (!gtk_clipboard_set_with_data(
          clipboard, targets.data(), static_cast<guint>(count),
          ClipboardGet, ClipboardClear, &state)) {
    return KWEB_CLIPBOARD_STATUS_WRITE_OUTCOME_UNKNOWN;
  }
  state.format_mask = 0;
  for (size_t index = 0; index < count; ++index) {
    state.format_mask |= 1u << (items[index].format - 1u);
  }
  state.owned = true;
  state.sequence += 1;
  Pump();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeClear(State &state) {
  GtkClipboard *clipboard = Clipboard(state);
  if (clipboard == nullptr) return KWEB_CLIPBOARD_STATUS_NATIVE_UNAVAILABLE;
  gtk_clipboard_clear(clipboard);
  state.values = {};
  state.format_mask = 0;
  state.owned = true;
  state.sequence += 1;
  Pump();
  return KWEB_CLIPBOARD_STATUS_OK;
}

kweb_clipboard_status NativeClose(State &state) {
  if (state.native_clipboard != nullptr) {
    gtk_clipboard_clear(Clipboard(state));
    Pump();
  }
  state.native_clipboard = nullptr;
  state.values = {};
  return KWEB_CLIPBOARD_STATUS_OK;
}

}  // namespace kwebshell::clipboard
