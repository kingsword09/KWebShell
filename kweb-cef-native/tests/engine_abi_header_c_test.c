#include "kwebshell/native/engine_abi.h"

#include <stddef.h>
#include <stdint.h>

_Static_assert(KWEB_INVALID_ENGINE_HANDLE == 0,
               "the invalid engine handle must remain zero");
_Static_assert(KWEB_ENGINE_EVENT_OPENED != KWEB_ENGINE_EVENT_CLOSED,
               "engine lifecycle events must remain distinct");

static void KWEB_ABI_CALL receive_engine_event(void *user_data,
                                               const kweb_engine_event *event) {
  uint64_t *sequence = (uint64_t *)user_data;
  if (event != NULL) {
    *sequence = event->sequence;
  }
}

int main(void) {
  uint64_t sequence = 0;
  const kweb_engine_config configuration = {
      (uint32_t)sizeof(kweb_engine_config),
      KWEB_ABI_VERSION,
      receive_engine_event,
      &sequence,
      {"/runtime", 8},
      {"/subprocess", 11},
      {"/resources", 10},
      {"/locales", 8},
      {"/cache", 6},
      {"/cache/cef.log", 14},
  };
  const kweb_engine_event event = {(uint32_t)sizeof(kweb_engine_event),
                                   KWEB_ABI_VERSION,
                                   KWEB_ENGINE_EVENT_OPENED,
                                   0,
                                   1,
                                   1};
  configuration.callback(configuration.user_data, &event);
  if (configuration.struct_size != sizeof(kweb_engine_config) ||
      event.struct_size != sizeof(kweb_engine_event) || sequence != 1) {
    return 1;
  }
  return 0;
}
