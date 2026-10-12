#include "screen_internal.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>

namespace {

std::mutex g_registry_mutex;
kwebshell::screen::State g_state;
std::atomic<uint32_t> g_live_count{0};

kweb_screen_status ValidateHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  if (struct_size < expected) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  if (abi_version != KWEB_SCREEN_ABI_VERSION) return KWEB_SCREEN_STATUS_ABI_MISMATCH;
  return KWEB_SCREEN_STATUS_OK;
}

bool ValidSpace(uint32_t space) {
  return space >= KWEB_SCREEN_SPACE_LOGICAL && space <= KWEB_SCREEN_SPACE_PHYSICAL;
}

bool ValidSession(uint32_t session) {
  return session >= KWEB_SCREEN_SESSION_WINDOWS && session <= KWEB_SCREEN_SESSION_WAYLAND;
}

void ResetDisplay(kweb_screen_display *display) {
  std::memset(display, 0, sizeof(*display));
  display->struct_size = sizeof(*display);
  display->abi_version = KWEB_SCREEN_ABI_VERSION;
}

void ResetCapabilities(kweb_screen_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SCREEN_ABI_VERSION;
}

void ResetIdResult(kweb_screen_id_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SCREEN_ABI_VERSION;
}

void ResetPointResult(kweb_screen_point_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SCREEN_ABI_VERSION;
}

void ResetEvent(kweb_screen_event *event) {
  std::memset(event, 0, sizeof(*event));
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_SCREEN_ABI_VERSION;
}

void ResetTopology(kweb_screen_topology_result *result) {
  kweb_screen_display *displays = result->displays;
  const uint32_t capacity = result->capacity;
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SCREEN_ABI_VERSION;
  result->capacity = capacity;
  result->displays = displays;
}

/** Resets every field except the mutex that protects them. */
void ResetStateLocked() {
  g_state.open = false;
  g_state.application_id.clear();
  g_state.package_identity.clear();
  g_state.sequence = 1;
  g_state.last = kwebshell::screen::Topology();
  g_state.platform = nullptr;
  g_state.events.clear();
}

/** Rounds half away from zero, so a conversion is exact for the declared ratio. */
int64_t ScaleRound(int64_t value, int64_t numerator, int64_t denominator) {
  if (denominator == 0) return value;
  const int64_t scaled = value * numerator;
  const int64_t half = denominator / 2;
  return scaled >= 0 ? (scaled + half) / denominator : (scaled - half) / denominator;
}

}  // namespace

namespace kwebshell::screen {

bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr && size != 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first <= 0x7f) {
      ++index;
      continue;
    }
    if (first >= 0xc2 && first <= 0xdf) {
      continuation = 1;
    } else if (first == 0xe0) {
      continuation = 2;
      second_min = 0xa0;
    } else if (first >= 0xe1 && first <= 0xec) {
      continuation = 2;
    } else if (first == 0xed) {
      continuation = 2;
      second_max = 0x9f;
    } else if (first >= 0xee && first <= 0xef) {
      continuation = 2;
    } else if (first == 0xf0) {
      continuation = 3;
      second_min = 0x90;
    } else if (first >= 0xf1 && first <= 0xf3) {
      continuation = 3;
    } else if (first == 0xf4) {
      continuation = 3;
      second_max = 0x8f;
    } else {
      return false;
    }
    if (index + continuation >= size) return false;
    const unsigned char second = data[index + 1];
    if (second < second_min || second > second_max) return false;
    for (size_t offset = 2; offset <= continuation; ++offset) {
      const unsigned char value = data[index + offset];
      if (value < 0x80 || value > 0xbf) return false;
    }
    index += continuation + 1;
  }
  return true;
}

bool ReadString(const kweb_screen_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) ||
      (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  if (capacity == 0) return;
  const size_t count = std::min(capacity - 1u, value.size());
  std::memcpy(target, value.data(), count);
  target[count] = '\0';
}

bool ValidCoordinate(int32_t value) {
  return value >= KWEB_SCREEN_MIN_COORDINATE && value <= KWEB_SCREEN_MAX_COORDINATE;
}

bool ValidDisplay(const Display &display) {
  if (display.id.empty() || !ValidCoordinate(display.left) || !ValidCoordinate(display.top) ||
      display.width < 1 || display.width > KWEB_SCREEN_MAX_EXTENT || display.height < 1 ||
      display.height > KWEB_SCREEN_MAX_EXTENT) {
    return false;
  }
  if (!ValidCoordinate(display.native_left) || !ValidCoordinate(display.native_top) || display.native_width < 1 ||
      display.native_width > KWEB_SCREEN_MAX_EXTENT || display.native_height < 1 ||
      display.native_height > KWEB_SCREEN_MAX_EXTENT) {
    return false;
  }
  const bool has_work_area = (display.fact_bits & KWEB_SCREEN_FACT_WORK_AREA) != 0;
  if (has_work_area) {
    if (display.work_left < display.left || display.work_top < display.top ||
        display.work_width < 1 || display.work_height < 1 ||
        display.work_left + display.work_width > display.left + display.width ||
        display.work_top + display.work_height > display.top + display.height) {
      return false;
    }
  }
  if ((display.fact_bits & KWEB_SCREEN_FACT_SCALE) != 0) {
    if (display.scale_permille < KWEB_SCREEN_MIN_SCALE_PERMILLE ||
        display.scale_permille > KWEB_SCREEN_MAX_SCALE_PERMILLE) {
      return false;
    }
  }
  if ((display.fact_bits & KWEB_SCREEN_FACT_ROTATION) != 0) {
    if (display.rotation < KWEB_SCREEN_ROTATION_NONE || display.rotation > KWEB_SCREEN_ROTATION_QUAD_3) {
      return false;
    }
  }
  if ((display.fact_bits & KWEB_SCREEN_FACT_REFRESH_RATE) != 0) {
    if (display.refresh_hertz < 1 || display.refresh_hertz > KWEB_SCREEN_MAX_REFRESH_HERTZ) return false;
  }
  if ((display.fact_bits & KWEB_SCREEN_FACT_COLOR_DEPTH) != 0) {
    if (display.color_depth_bits < 1 || display.color_depth_bits > KWEB_SCREEN_MAX_COLOR_DEPTH) return false;
  }
  if ((display.fact_bits & KWEB_SCREEN_FACT_PHYSICAL_SIZE) != 0) {
    if (display.physical_width_mm < 1 || display.physical_width_mm > KWEB_SCREEN_MAX_MILLIMETRES ||
        display.physical_height_mm < 1 || display.physical_height_mm > KWEB_SCREEN_MAX_MILLIMETRES) {
      return false;
    }
  }
  return true;
}

bool ValidTopology(const Topology &topology) {
  if (topology.provider_id.empty() || topology.provider_id.size() >= KWEB_SCREEN_MAX_PROVIDER ||
      !ValidSession(topology.session) || topology.displays.empty() ||
      topology.displays.size() > KWEB_SCREEN_MAX_DISPLAYS) {
    return false;
  }
  size_t primaries = 0;
  for (size_t index = 0; index < topology.displays.size(); ++index) {
    const Display &display = topology.displays[index];
    if (!ValidDisplay(display)) return false;
    if ((display.fact_bits & KWEB_SCREEN_FACT_PRIMARY) != 0) primaries += 1;
    for (size_t other = index + 1; other < topology.displays.size(); ++other) {
      if (topology.displays[other].id == display.id) return false;
    }
  }
  return primaries <= 1;
}

uint32_t SpaceBitsOf(const Topology &topology) {
  uint32_t bits = KWEB_SCREEN_SPACES_REQUIRED;
  for (const Display &display : topology.displays) {
    if ((display.fact_bits & KWEB_SCREEN_FACT_PHYSICAL_SIZE) != 0) {
      bits |= 1u << (KWEB_SCREEN_SPACE_PHYSICAL - 1u);
      break;
    }
  }
  return bits;
}

uint32_t EffectiveCapabilityBits(const Topology &topology) {
  uint32_t published = 0;
  for (const Display &display : topology.displays) {
    const uint32_t facts = display.fact_bits;
    if ((facts & KWEB_SCREEN_FACT_WORK_AREA) != 0) published |= KWEB_SCREEN_CAP_WORK_AREA;
    if ((facts & KWEB_SCREEN_FACT_SCALE) != 0) published |= KWEB_SCREEN_CAP_SCALE;
    if ((facts & KWEB_SCREEN_FACT_ROTATION) != 0) published |= KWEB_SCREEN_CAP_ROTATION;
    if ((facts & KWEB_SCREEN_FACT_REFRESH_RATE) != 0) published |= KWEB_SCREEN_CAP_REFRESH_RATE;
    if ((facts & KWEB_SCREEN_FACT_COLOR_DEPTH) != 0) published |= KWEB_SCREEN_CAP_COLOR_DEPTH;
    if ((facts & KWEB_SCREEN_FACT_PHYSICAL_SIZE) != 0) published |= KWEB_SCREEN_CAP_PHYSICAL_SIZE;
  }
  // Per-display capabilities follow the facts the provider published, so a
  // declared-but-unpublished fact is dropped and a published fact is never hidden.
  // Cursor, topology events, and multi-display support are provider declarations
  // rather than per-display facts, so those pass through unchanged.
  const uint32_t declarative =
      KWEB_SCREEN_CAP_CURSOR | KWEB_SCREEN_CAP_TOPOLOGY_EVENTS | KWEB_SCREEN_CAP_MULTI_DISPLAY;
  return published | (topology.capability_bits & declarative);
}

TopologyDiff Compare(const Topology &before, const Topology &after) {
  TopologyDiff diff;
  for (const Display &display : after.displays) {
    const auto existing = std::find_if(before.displays.begin(), before.displays.end(),
                                       [&display](const Display &candidate) {
                                         return candidate.id == display.id;
                                       });
    if (existing == before.displays.end()) {
      diff.added.push_back(display.id);
      continue;
    }
    const bool same = existing->left == display.left && existing->top == display.top &&
                      existing->width == display.width && existing->height == display.height &&
                      existing->native_left == display.native_left &&
                      existing->native_top == display.native_top &&
                      existing->native_width == display.native_width &&
                      existing->native_height == display.native_height &&
                      existing->fact_bits == display.fact_bits &&
                      existing->work_left == display.work_left && existing->work_top == display.work_top &&
                      existing->work_width == display.work_width &&
                      existing->work_height == display.work_height &&
                      existing->scale_permille == display.scale_permille &&
                      existing->rotation == display.rotation &&
                      existing->refresh_hertz == display.refresh_hertz &&
                      existing->color_depth_bits == display.color_depth_bits &&
                      existing->physical_width_mm == display.physical_width_mm &&
                      existing->physical_height_mm == display.physical_height_mm &&
                      existing->name == display.name;
    if (!same) diff.changed.push_back(display.id);
  }
  for (const Display &display : before.displays) {
    const auto found = std::find_if(after.displays.begin(), after.displays.end(),
                                    [&display](const Display &candidate) {
                                      return candidate.id == display.id;
                                    });
    if (found == after.displays.end()) diff.removed.push_back(display.id);
  }
  return diff;
}

void PublishTopology(State &state, const Topology &topology) {
  std::lock_guard<std::mutex> lock(state.mutex);
  const TopologyDiff diff = Compare(state.last, topology);
  const bool structure_changed = state.last.provider_id != topology.provider_id ||
                                 state.last.session != topology.session ||
                                 state.last.capability_bits != topology.capability_bits ||
                                 state.last.displays.size() != topology.displays.size();
  state.last = topology;
  if (diff.added.empty() && diff.removed.empty() && diff.changed.empty() && !structure_changed) return;
  state.sequence += 1;
  kweb_screen_event event{};
  ResetEvent(&event);
  event.kind = KWEB_SCREEN_EVENT_TOPOLOGY_CHANGED;
  event.added_count = static_cast<uint32_t>(diff.added.size());
  event.removed_count = static_cast<uint32_t>(diff.removed.size());
  event.changed_count = static_cast<uint32_t>(diff.changed.size());
  event.sequence = state.sequence;
  size_t cursor = 0;
  const auto append = [&event, &cursor](const std::vector<std::string> &ids) {
    for (const std::string &id : ids) {
      if (cursor >= KWEB_SCREEN_EVENT_IDS) return;
      CopyBounded(event.ids[cursor], KWEB_SCREEN_MAX_ID, id);
      cursor += 1;
    }
  };
  append(diff.added);
  append(diff.removed);
  append(diff.changed);
  if (state.events.size() >= KWEB_SCREEN_EVENT_CAPACITY) state.events.pop_front();
  state.events.push_back(event);
}

void PushFailed(State &state, const std::string &code) {
  std::lock_guard<std::mutex> lock(state.mutex);
  state.sequence += 1;
  kweb_screen_event event{};
  ResetEvent(&event);
  event.kind = KWEB_SCREEN_EVENT_FAILED;
  event.sequence = state.sequence;
  CopyBounded(event.code, sizeof(event.code), code);
  if (state.events.size() >= KWEB_SCREEN_EVENT_CAPACITY) state.events.pop_front();
  state.events.push_back(event);
}

kweb_screen_status ConvertPoint(const Topology &topology, const Display &display, uint32_t from_space,
                                uint32_t to_space, int32_t x, int32_t y, int32_t *out_x, int32_t *out_y) {
  if (out_x == nullptr || out_y == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  if (!ValidSpace(from_space) || !ValidSpace(to_space)) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  if (!ValidCoordinate(x) || !ValidCoordinate(y)) return KWEB_SCREEN_STATUS_POINT_INVALID;
  const uint32_t spaces = SpaceBitsOf(topology);
  if ((spaces & (1u << (from_space - 1u))) == 0 || (spaces & (1u << (to_space - 1u))) == 0) {
    return KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED;
  }
  if (from_space == to_space) {
    *out_x = x;
    *out_y = y;
    return KWEB_SCREEN_STATUS_OK;
  }
  // Every conversion goes through native pixels, which every session publishes.
  int64_t native_x = x;
  int64_t native_y = y;
  if (from_space == KWEB_SCREEN_SPACE_LOGICAL) {
    native_x = ScaleRound(static_cast<int64_t>(x) - display.left, display.native_width, display.width) +
               display.native_left;
    native_y = ScaleRound(static_cast<int64_t>(y) - display.top, display.native_height, display.height) +
               display.native_top;
  } else if (from_space == KWEB_SCREEN_SPACE_PHYSICAL) {
    if ((display.fact_bits & KWEB_SCREEN_FACT_PHYSICAL_SIZE) == 0) {
      return KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED;
    }
    native_x = ScaleRound(x, display.native_width, display.physical_width_mm) + display.native_left;
    native_y = ScaleRound(y, display.native_height, display.physical_height_mm) + display.native_top;
  }
  int64_t result_x = native_x;
  int64_t result_y = native_y;
  if (to_space == KWEB_SCREEN_SPACE_LOGICAL) {
    result_x = ScaleRound(native_x - display.native_left, display.width, display.native_width) + display.left;
    result_y = ScaleRound(native_y - display.native_top, display.height, display.native_height) + display.top;
  } else if (to_space == KWEB_SCREEN_SPACE_PHYSICAL) {
    if ((display.fact_bits & KWEB_SCREEN_FACT_PHYSICAL_SIZE) == 0) {
      return KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED;
    }
    result_x = ScaleRound(native_x - display.native_left, display.physical_width_mm, display.native_width);
    result_y = ScaleRound(native_y - display.native_top, display.physical_height_mm, display.native_height);
  }
  if (result_x < KWEB_SCREEN_MIN_COORDINATE || result_x > KWEB_SCREEN_MAX_COORDINATE ||
      result_y < KWEB_SCREEN_MIN_COORDINATE || result_y > KWEB_SCREEN_MAX_COORDINATE) {
    return KWEB_SCREEN_STATUS_POINT_INVALID;
  }
  *out_x = static_cast<int32_t>(result_x);
  *out_y = static_cast<int32_t>(result_y);
  return KWEB_SCREEN_STATUS_OK;
}

}  // namespace kwebshell::screen

extern "C" {

uint32_t KWEB_SCREEN_CALL kweb_screen_abi_version(void) { return KWEB_SCREEN_ABI_VERSION; }

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_open(const kweb_screen_configuration *configuration,
                                                     uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header = ValidateHeader(configuration->struct_size, configuration->abi_version,
                                                   sizeof(kweb_screen_configuration));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::screen::ReadString(configuration->application_id, KWEB_SCREEN_MAX_ID, &application_id) ||
      application_id.empty() ||
      !kwebshell::screen::ReadString(configuration->package_identity, KWEB_SCREEN_MAX_ID, &package_identity)) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_SCREEN_STATUS_ALREADY_OPEN;
    ResetStateLocked();
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.open = true;
  }
  const kweb_screen_status opened = kwebshell::screen::NativeOpen(g_state);
  if (opened != KWEB_SCREEN_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return opened;
  }
  kwebshell::screen::Topology topology;
  const kweb_screen_status read = kwebshell::screen::NativeReadTopology(g_state, &topology);
  if (read != KWEB_SCREEN_STATUS_OK) {
    kwebshell::screen::NativeClose(g_state);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return read;
  }
  if (!kwebshell::screen::ValidTopology(topology)) {
    kwebshell::screen::NativeClose(g_state);
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return KWEB_SCREEN_STATUS_TOPOLOGY_INVALID;
  }
  {
    // The starting topology is read once without announcing a change.
    std::lock_guard<std::mutex> lock(g_state.mutex);
    g_state.last = topology;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_capabilities(uint64_t handle,
                                                             kweb_screen_capabilities_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header = ValidateHeader(result->struct_size, result->abi_version,
                                                   sizeof(kweb_screen_capabilities_result));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  ResetCapabilities(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  kwebshell::screen::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                 g_state.last.provider_id);
  result->session = g_state.last.session;
  result->capability_bits = kwebshell::screen::EffectiveCapabilityBits(g_state.last);
  result->space_bits = kwebshell::screen::SpaceBitsOf(g_state.last);
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_topology(uint64_t handle,
                                                         kweb_screen_topology_result *result) {
  if (handle != 1 || result == nullptr || result->displays == nullptr || result->capacity == 0) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  const kweb_screen_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_screen_topology_result));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  ResetTopology(result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  }
  kwebshell::screen::Topology topology;
  const kweb_screen_status read = kwebshell::screen::NativeReadTopology(g_state, &topology);
  if (read != KWEB_SCREEN_STATUS_OK) return read;
  if (!kwebshell::screen::ValidTopology(topology)) return KWEB_SCREEN_STATUS_TOPOLOGY_INVALID;
  kwebshell::screen::PublishTopology(g_state, topology);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  const std::vector<kwebshell::screen::Display> &displays = g_state.last.displays;
  if (displays.size() > result->capacity) {
    result->display_count = static_cast<uint32_t>(displays.size());
    return KWEB_SCREEN_STATUS_BUFFER_TOO_SMALL;
  }
  for (size_t index = 0; index < displays.size(); ++index) {
    const kwebshell::screen::Display &display = displays[index];
    kweb_screen_display *target = &result->displays[index];
    ResetDisplay(target);
    kwebshell::screen::CopyBounded(target->id, sizeof(target->id), display.id);
    kwebshell::screen::CopyBounded(target->name, sizeof(target->name), display.name);
    target->fact_bits = display.fact_bits;
    target->session = g_state.last.session;
    target->left = display.left;
    target->top = display.top;
    target->width = display.width;
    target->height = display.height;
    target->native_left = display.native_left;
    target->native_top = display.native_top;
    target->native_width = display.native_width;
    target->native_height = display.native_height;
    target->work_left = display.work_left;
    target->work_top = display.work_top;
    target->work_width = display.work_width;
    target->work_height = display.work_height;
    target->scale_permille = display.scale_permille;
    target->rotation = display.rotation;
    target->refresh_hertz = display.refresh_hertz;
    target->color_depth_bits = display.color_depth_bits;
    target->physical_width_mm = display.physical_width_mm;
    target->physical_height_mm = display.physical_height_mm;
  }
  kwebshell::screen::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                 g_state.last.provider_id);
  result->session = g_state.last.session;
  result->display_count = static_cast<uint32_t>(displays.size());
  result->capacity = static_cast<uint32_t>(result->capacity);
  result->sequence = g_state.sequence;
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_cursor(uint64_t handle,
                                                       kweb_screen_point_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_screen_point_result));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  ResetPointResult(result);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  }
  int32_t x = 0;
  int32_t y = 0;
  std::string display_id;
  bool display_known = false;
  const kweb_screen_status status =
      kwebshell::screen::NativeReadCursor(g_state, &x, &y, &display_id, &display_known);
  if (status != KWEB_SCREEN_STATUS_OK) return status;
  if (!kwebshell::screen::ValidCoordinate(x) || !kwebshell::screen::ValidCoordinate(y)) {
    return KWEB_SCREEN_STATUS_POINT_INVALID;
  }
  result->x = x;
  result->y = y;
  result->space = KWEB_SCREEN_SPACE_NATIVE;
  result->display_known = display_known ? 1u : 0u;
  if (display_known) kwebshell::screen::CopyBounded(result->display_id, sizeof(result->display_id), display_id);
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_display_at(uint64_t handle, int32_t x, int32_t y,
                                                           uint32_t space,
                                                           kweb_screen_id_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_screen_id_result));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  if (!ValidSpace(space)) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  if (!kwebshell::screen::ValidCoordinate(x) || !kwebshell::screen::ValidCoordinate(y)) {
    return KWEB_SCREEN_STATUS_POINT_INVALID;
  }
  ResetIdResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  const uint32_t spaces = kwebshell::screen::SpaceBitsOf(g_state.last);
  if ((spaces & (1u << (space - 1u))) == 0) return KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED;
  for (const kwebshell::screen::Display &display : g_state.last.displays) {
    int32_t converted_x = 0;
    int32_t converted_y = 0;
    const kweb_screen_status status = kwebshell::screen::ConvertPoint(
        g_state.last, display, space, KWEB_SCREEN_SPACE_LOGICAL, x, y, &converted_x, &converted_y);
    if (status != KWEB_SCREEN_STATUS_OK) return status;
    const bool inside = converted_x >= display.left && converted_x < display.left + display.width &&
                        converted_y >= display.top && converted_y < display.top + display.height;
    if (!inside) continue;
    result->found = 1u;
    kwebshell::screen::CopyBounded(result->id, sizeof(result->id), display.id);
    return KWEB_SCREEN_STATUS_OK;
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_nearest(uint64_t handle, int32_t x, int32_t y,
                                                        uint32_t space, kweb_screen_id_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_screen_id_result));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  if (!ValidSpace(space)) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  if (!kwebshell::screen::ValidCoordinate(x) || !kwebshell::screen::ValidCoordinate(y)) {
    return KWEB_SCREEN_STATUS_POINT_INVALID;
  }
  ResetIdResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  const uint32_t spaces = kwebshell::screen::SpaceBitsOf(g_state.last);
  if ((spaces & (1u << (space - 1u))) == 0) return KWEB_SCREEN_STATUS_SPACE_UNSUPPORTED;
  const kwebshell::screen::Display *best = nullptr;
  int64_t best_distance = 0;
  for (const kwebshell::screen::Display &display : g_state.last.displays) {
    int32_t converted_x = 0;
    int32_t converted_y = 0;
    const kweb_screen_status status = kwebshell::screen::ConvertPoint(
        g_state.last, display, space, KWEB_SCREEN_SPACE_LOGICAL, x, y, &converted_x, &converted_y);
    if (status != KWEB_SCREEN_STATUS_OK) return status;
    const int64_t right = static_cast<int64_t>(display.left) + display.width;
    const int64_t bottom = static_cast<int64_t>(display.top) + display.height;
    const int64_t dx = converted_x < display.left ? static_cast<int64_t>(display.left) - converted_x
                                                  : (converted_x >= right ? converted_x - right + 1 : 0);
    const int64_t dy = converted_y < display.top ? static_cast<int64_t>(display.top) - converted_y
                                                 : (converted_y >= bottom ? converted_y - bottom + 1 : 0);
    const int64_t distance = dx * dx + dy * dy;
    const bool primary = (display.fact_bits & KWEB_SCREEN_FACT_PRIMARY) != 0;
    const bool better = best == nullptr || distance < best_distance ||
                        (distance == best_distance && primary);
    if (better) {
      best = &display;
      best_distance = distance;
    }
  }
  if (best == nullptr) return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  result->found = 1u;
  kwebshell::screen::CopyBounded(result->id, sizeof(result->id), best->id);
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_convert(uint64_t handle,
                                                        const kweb_screen_conversion_request *request,
                                                        kweb_screen_point_result *result) {
  if (handle != 1 || request == nullptr || result == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status request_header = ValidateHeader(
      request->struct_size, request->abi_version, sizeof(kweb_screen_conversion_request));
  if (request_header != KWEB_SCREEN_STATUS_OK) return request_header;
  const kweb_screen_status result_header =
      ValidateHeader(result->struct_size, result->abi_version, sizeof(kweb_screen_point_result));
  if (result_header != KWEB_SCREEN_STATUS_OK) return result_header;
  if (!ValidSpace(request->from_space) || !ValidSpace(request->to_space)) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  if (!kwebshell::screen::ValidCoordinate(request->x) || !kwebshell::screen::ValidCoordinate(request->y)) {
    return KWEB_SCREEN_STATUS_POINT_INVALID;
  }
  std::string display_id;
  if (!kwebshell::screen::ReadString(request->display_id, KWEB_SCREEN_MAX_ID, &display_id) ||
      display_id.empty()) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  ResetPointResult(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  const kwebshell::screen::Display *display = nullptr;
  for (const kwebshell::screen::Display &candidate : g_state.last.displays) {
    if (candidate.id == display_id) {
      display = &candidate;
      break;
    }
  }
  if (display == nullptr) return KWEB_SCREEN_STATUS_DISPLAY_UNKNOWN;
  int32_t converted_x = 0;
  int32_t converted_y = 0;
  const kweb_screen_status status = kwebshell::screen::ConvertPoint(
      g_state.last, *display, request->from_space, request->to_space, request->x, request->y, &converted_x,
      &converted_y);
  if (status != KWEB_SCREEN_STATUS_OK) return status;
  result->x = converted_x;
  result->y = converted_y;
  result->space = request->to_space;
  result->display_known = 1u;
  kwebshell::screen::CopyBounded(result->display_id, sizeof(result->display_id), display->id);
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_poll_event(uint64_t handle, kweb_screen_event *event) {
  if (handle != 1 || event == nullptr) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  const kweb_screen_status header =
      ValidateHeader(event->struct_size, event->abi_version, sizeof(kweb_screen_event));
  if (header != KWEB_SCREEN_STATUS_OK) return header;
  ResetEvent(event);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
  if (g_state.events.empty()) return KWEB_SCREEN_STATUS_NO_EVENT;
  const kweb_screen_event next = g_state.events.front();
  g_state.events.pop_front();
  *event = next;
  event->struct_size = sizeof(*event);
  event->abi_version = KWEB_SCREEN_ABI_VERSION;
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status KWEB_SCREEN_CALL kweb_screen_close(uint64_t handle) {
  if (handle != 1) return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SCREEN_STATUS_OWNER_CLOSED;
    g_state.open = false;
    g_state.events.clear();
  }
  const kweb_screen_status status = kwebshell::screen::NativeClose(g_state);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
  }
  g_live_count.fetch_sub(1);
  return status;
}

}  // extern "C"
