#ifndef KWEB_SCREEN_INTERNAL_H_
#define KWEB_SCREEN_INTERNAL_H_

#include "kweb_screen.h"

#include <deque>
#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::screen {

/** One display as a provider reads it. */
struct Display {
  std::string id;
  std::string name;
  int32_t left = 0;
  int32_t top = 0;
  int32_t width = 0;
  int32_t height = 0;
  int32_t native_left = 0;
  int32_t native_top = 0;
  int32_t native_width = 0;
  int32_t native_height = 0;
  int32_t work_left = 0;
  int32_t work_top = 0;
  int32_t work_width = 0;
  int32_t work_height = 0;
  uint32_t scale_permille = 0;
  uint32_t rotation = 0;
  uint32_t refresh_hertz = 0;
  uint32_t color_depth_bits = 0;
  uint32_t physical_width_mm = 0;
  uint32_t physical_height_mm = 0;
  /** The facts this display publishes. A value without its bit is absent. */
  uint32_t fact_bits = 0;
};

/** One atomic provider read. */
struct Topology {
  std::string provider_id;
  uint32_t session = 0;
  /** Capabilities the provider declares for this session. */
  uint32_t capability_bits = 0;
  std::vector<Display> displays;
};

/** The single process-wide screen state owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  uint64_t sequence = 1;
  Topology last;
  void *platform = nullptr;
  std::deque<kweb_screen_event> events;
};

/** What moved between two topology reads. */
struct TopologyDiff {
  std::vector<std::string> added;
  std::vector<std::string> removed;
  std::vector<std::string> changed;
};

const char *ProviderId();
bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_screen_string &value, size_t maximum, std::string *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/** True when a coordinate is inside the published range. */
bool ValidCoordinate(int32_t value);
/** True when a display is bounded and consistent with its own facts. */
bool ValidDisplay(const Display &display);
/** True when a topology is bounded, unique, and carries at most one primary. */
bool ValidTopology(const Topology &topology);

/** The space bits a topology actually publishes. */
uint32_t SpaceBitsOf(const Topology &topology);
/** The capability bits the provider may claim, derived from the facts it publishes. */
uint32_t EffectiveCapabilityBits(const Topology &topology);

TopologyDiff Compare(const Topology &before, const Topology &after);

/** Publishes one provider read: de-duplicated, ordered, exactly once. */
void PublishTopology(State &state, const Topology &topology);
void PushFailed(State &state, const std::string &code);

/** Converts one point between two declared spaces of one display. */
kweb_screen_status ConvertPoint(const Topology &topology, const Display &display, uint32_t from_space,
                                uint32_t to_space, int32_t x, int32_t y, int32_t *out_x, int32_t *out_y);

/* The platform provider surface implemented by screen_<platform> sources. */
kweb_screen_status NativeOpen(State &state);
kweb_screen_status NativeReadTopology(State &state, Topology *topology);
kweb_screen_status NativeReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                                    bool *display_known);
kweb_screen_status NativeClose(State &state);

}  // namespace kwebshell::screen

#endif
