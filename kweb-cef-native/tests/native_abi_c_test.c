#include "kwebshell/native/abi.h"

#include <stdint.h>
#include <string.h>

typedef struct event_collector {
  int event_count;
  int invalid_event;
  int navigation_matches;
  kweb_event_type types[4];
  uint64_t sequences[4];
  int32_t width;
  int32_t height;
} event_collector;

static const char navigation_url[] =
    "https://example.test/\xE8\xB7\xAF\xE5\xBE\x84?q="
    "\xF0\x9F\x99\x82";

static void KWEB_ABI_CALL collect_event(void *user_data,
                                        const kweb_event *event) {
  event_collector *collector = (event_collector *)user_data;
  if (collector == NULL || event == NULL ||
      event->struct_size < sizeof(kweb_event) ||
      event->abi_version != KWEB_ABI_VERSION || event->sequence == 0 ||
      collector->event_count >= 4) {
    if (collector != NULL) {
      collector->invalid_event = 1;
    }
    return;
  }

  collector->types[collector->event_count] = event->type;
  collector->sequences[collector->event_count] = event->sequence;
  if (event->type == KWEB_EVENT_NAVIGATION_REQUESTED) {
    collector->navigation_matches =
        event->text != NULL && event->text_size == sizeof(navigation_url) - 1 &&
        memcmp(event->text, navigation_url, event->text_size) == 0;
  } else if (event->type == KWEB_EVENT_VIEWPORT_CHANGED) {
    collector->width = event->width;
    collector->height = event->height;
  }
  ++collector->event_count;
}

int main(void) {
  event_collector collector = {0};
  kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
  const kweb_session_config config = {
      (uint32_t)sizeof(kweb_session_config), KWEB_ABI_VERSION, collect_event,
      &collector};

  if (kweb_abi_version() != KWEB_ABI_VERSION) {
    return 1;
  }
  if (strcmp(kweb_status_name(KWEB_STATUS_ABI_MISMATCH), "abi-mismatch") != 0) {
    return 2;
  }
  if (kweb_session_create(&config, &handle) != KWEB_STATUS_OK) {
    return 3;
  }
  if (handle == KWEB_INVALID_SESSION_HANDLE) {
    return 4;
  }
  if (kweb_session_request_navigation(handle, navigation_url,
                                      sizeof(navigation_url) - 1) !=
      KWEB_STATUS_OK) {
    return 5;
  }
  if (kweb_session_resize(handle, 1280, 720) != KWEB_STATUS_OK) {
    return 6;
  }
  if (kweb_session_close(handle) != KWEB_STATUS_OK) {
    return 7;
  }
  if (collector.invalid_event || collector.event_count != 4) {
    return 8;
  }
  if (collector.types[0] != KWEB_EVENT_SESSION_OPENED ||
      collector.types[1] != KWEB_EVENT_NAVIGATION_REQUESTED ||
      collector.types[2] != KWEB_EVENT_VIEWPORT_CHANGED ||
      collector.types[3] != KWEB_EVENT_SESSION_CLOSED) {
    return 9;
  }
  if (collector.sequences[0] != 1 || collector.sequences[1] != 2 ||
      collector.sequences[2] != 3 || collector.sequences[3] != 4) {
    return 10;
  }
  if (!collector.navigation_matches || collector.width != 1280 ||
      collector.height != 720) {
    return 11;
  }
  if (kweb_live_session_count() != 0) {
    return 12;
  }
  if (kweb_session_resize(handle, 800, 600) != KWEB_STATUS_INVALID_HANDLE) {
    return 13;
  }
  return 0;
}
