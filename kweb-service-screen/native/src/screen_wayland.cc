#include "screen_internal.h"

#if !defined(__linux__)
#error "The Wayland screen provider must only be compiled on Linux."
#endif

#include <gdk/gdk.h>
#if __has_include(<gdk/gdkwayland.h>)
#include <gdk/gdkwayland.h>
#define KWEB_SCREEN_HAS_WAYLAND_MACRO 1
#else
#define KWEB_SCREEN_HAS_WAYLAND_MACRO 0
#endif

#include <condition_variable>
#include <cstdio>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

namespace {

using ScreenDisplay = kwebshell::screen::Display;
using kwebshell::screen::State;
using kwebshell::screen::Topology;

constexpr char kProviderId[] = "screen.linux.wayland-gdk";

/** One GDK display with its monitor signals and its own main loop. */
struct WaylandState {
  GdkDisplay *display = nullptr;
  GMainContext *context = nullptr;
  GMainLoop *loop = nullptr;
  guint added_handler = 0;
  guint removed_handler = 0;
  std::mutex mutex;
  std::condition_variable ready;
  std::thread thread;
  bool started = false;
  bool stopping = false;
};

State *g_state = nullptr;

WaylandState *StateOf(State &state) { return static_cast<WaylandState *>(state.platform); }

/** True when this GDK display is the Wayland backend. */
bool IsWaylandDisplay(GdkDisplay *display) {
  if (display == nullptr) return false;
#if KWEB_SCREEN_HAS_WAYLAND_MACRO
  return GDK_IS_WAYLAND_DISPLAY(display) != 0;
#else
  const char *type_name = G_OBJECT_TYPE_NAME(display);
  return type_name != nullptr && std::string(type_name) == "GdkWaylandDisplay";
#endif
}

/** Runs one block on the provider's GDK thread and waits for it. */
bool RunOnGdkThread(WaylandState &state, const std::function<void()> &block) {
  {
    std::lock_guard<std::mutex> lock(state.mutex);
    if (!state.started || state.stopping || state.context == nullptr) return false;
  }
  std::mutex completion_mutex;
  std::condition_variable completion;
  bool done = false;
  auto *work = new std::function<void()>([&block, &done, &completion_mutex, &completion] {
    block();
    {
      std::lock_guard<std::mutex> lock(completion_mutex);
      done = true;
    }
    completion.notify_all();
  });
  g_main_context_invoke_full(
      state.context, G_PRIORITY_DEFAULT,
      [](gpointer user_data) -> gboolean {
        auto *queued = static_cast<std::function<void()> *>(user_data);
        (*queued)();
        delete queued;
        return G_SOURCE_REMOVE;
      },
      work, nullptr);
  std::unique_lock<std::mutex> lock(completion_mutex);
  completion.wait(lock, [&done] { return done; });
  return true;
}

/**
 * Reads the compositor's monitors. GDK publishes geometry, work area, scale, and
 * the primary monitor; a compositor publishes no rotation, no refresh rate, no
 * physical size, and no global cursor position, so those facts stay absent.
 */
kweb_screen_status ReadTopology(WaylandState &state, Topology *topology) {
  if (state.display == nullptr) return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  const int count = gdk_display_get_n_monitors(state.display);
  if (count <= 0) return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  GdkMonitor *primary = gdk_display_get_primary_monitor(state.display);
  topology->provider_id = kProviderId;
  topology->session = KWEB_SCREEN_SESSION_WAYLAND;
  topology->capability_bits = KWEB_SCREEN_CAP_WORK_AREA | KWEB_SCREEN_CAP_SCALE |
                              KWEB_SCREEN_CAP_TOPOLOGY_EVENTS | KWEB_SCREEN_CAP_MULTI_DISPLAY;
  topology->displays.clear();
  for (int index = 0; index < count && index < static_cast<int>(KWEB_SCREEN_MAX_DISPLAYS); ++index) {
    GdkMonitor *monitor = gdk_display_get_monitor(state.display, index);
    if (monitor == nullptr) continue;
    GdkRectangle geometry = {};
    GdkRectangle work = {};
    gdk_monitor_get_geometry(monitor, &geometry);
    gdk_monitor_get_workarea(monitor, &work);
    if (geometry.width <= 0 || geometry.height <= 0) continue;
    ScreenDisplay display;
    const char *model = gdk_monitor_get_model(monitor);
    display.id = std::string("display.") + std::to_string(index);
    if (model != nullptr) {
      const std::string candidate(model);
      if (!candidate.empty() && candidate.size() <= KWEB_SCREEN_MAX_NAME) display.name = candidate;
    }
    display.left = geometry.x;
    display.top = geometry.y;
    display.width = geometry.width;
    display.height = geometry.height;
    const int scale = gdk_monitor_get_scale_factor(monitor);
    const uint32_t permille = scale > 0 ? static_cast<uint32_t>(scale) * 1000u : 0u;
    // Native geometry is the same rectangle in the monitor's own device pixels.
    const uint32_t factors = permille == 0 ? 1000u : permille;
    display.native_left = static_cast<int32_t>(static_cast<int64_t>(geometry.x) * factors / 1000);
    display.native_top = static_cast<int32_t>(static_cast<int64_t>(geometry.y) * factors / 1000);
    display.native_width = static_cast<int32_t>(static_cast<int64_t>(geometry.width) * factors / 1000);
    display.native_height = static_cast<int32_t>(static_cast<int64_t>(geometry.height) * factors / 1000);
    if (permille >= KWEB_SCREEN_MIN_SCALE_PERMILLE && permille <= KWEB_SCREEN_MAX_SCALE_PERMILLE) {
      display.scale_permille = permille;
      display.fact_bits |= KWEB_SCREEN_FACT_SCALE;
    }
    if (work.width > 0 && work.height > 0) {
      display.work_left = work.x;
      display.work_top = work.y;
      display.work_width = work.width;
      display.work_height = work.height;
      display.fact_bits |= KWEB_SCREEN_FACT_WORK_AREA;
    }
    if (primary != nullptr && monitor == primary) display.fact_bits |= KWEB_SCREEN_FACT_PRIMARY;
    topology->displays.push_back(display);
  }
  if (topology->displays.empty()) return KWEB_SCREEN_STATUS_TOPOLOGY_UNAVAILABLE;
  return KWEB_SCREEN_STATUS_OK;
}

void PublishCurrentTopology() {
  if (g_state == nullptr) return;
  WaylandState *state = StateOf(*g_state);
  if (state == nullptr) return;
  Topology topology;
  if (ReadTopology(*state, &topology) != KWEB_SCREEN_STATUS_OK) return;
  if (!kwebshell::screen::ValidTopology(topology)) {
    kwebshell::screen::PushFailed(*g_state, "screen.topology-invalid");
    return;
  }
  kwebshell::screen::PublishTopology(*g_state, topology);
}

void OnMonitorAdded(GdkDisplay *display, GdkMonitor *monitor, gpointer user_data) {
  (void)display;
  (void)monitor;
  (void)user_data;
  PublishCurrentTopology();
}

void OnMonitorRemoved(GdkDisplay *display, GdkMonitor *monitor, gpointer user_data) {
  (void)display;
  (void)monitor;
  (void)user_data;
  PublishCurrentTopology();
}

void StopLoop(WaylandState *state) {
  if (state == nullptr) return;
  if (state->context != nullptr) {
    g_main_context_invoke_full(
        state->context, G_PRIORITY_DEFAULT,
        [](gpointer user_data) -> gboolean {
          auto *wayland = static_cast<WaylandState *>(user_data);
          if (wayland->loop != nullptr) g_main_loop_quit(wayland->loop);
          return G_SOURCE_REMOVE;
        },
        state, nullptr);
  }
  if (state->thread.joinable()) state->thread.join();
  if (state->display != nullptr) {
    if (state->added_handler != 0) g_signal_handler_disconnect(state->display, state->added_handler);
    if (state->removed_handler != 0) g_signal_handler_disconnect(state->display, state->removed_handler);
    g_object_unref(state->display);
    state->display = nullptr;
  }
}

}  // namespace

namespace kwebshell::screen::wayland_provider {

const char *ProviderId() { return kProviderId; }

/** Closes a provider that failed to finish opening. */
kweb_screen_status Close(State &state);

kweb_screen_status Open(State &state) {
  auto *wayland = new WaylandState();
  state.platform = wayland;
  std::unique_lock<std::mutex> lock(wayland->mutex);
  wayland->thread = std::thread([wayland] {
    GMainContext *context = g_main_context_new();
    g_main_context_push_thread_default(context);
    GMainLoop *loop = g_main_loop_new(context, FALSE);
    GdkDisplay *display = gdk_display_open(nullptr);
    bool usable = IsWaylandDisplay(display);
    if (usable) {
      wayland->added_handler = g_signal_connect(display, "monitor-added", G_CALLBACK(OnMonitorAdded), nullptr);
      wayland->removed_handler =
          g_signal_connect(display, "monitor-removed", G_CALLBACK(OnMonitorRemoved), nullptr);
    } else if (display != nullptr) {
      g_object_unref(display);
      display = nullptr;
    }
    {
      std::lock_guard<std::mutex> guard(wayland->mutex);
      wayland->context = context;
      wayland->loop = loop;
      wayland->display = display;
      wayland->started = true;
    }
    wayland->ready.notify_all();
    if (display != nullptr) g_main_loop_run(loop);
    g_main_loop_unref(loop);
    g_main_context_pop_thread_default(context);
    g_main_context_unref(context);
  });
  wayland->ready.wait(lock, [wayland] { return wayland->started; });
  lock.unlock();
  if (wayland->display == nullptr) {
    StopLoop(wayland);
    delete wayland;
    state.platform = nullptr;
    return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  }
  g_state = &state;
  Topology topology;
  kweb_screen_status status = KWEB_SCREEN_STATUS_NATIVE_FAILED;
  RunOnGdkThread(*wayland, [&status, wayland, &topology] { status = ReadTopology(*wayland, &topology); });
  if (status != KWEB_SCREEN_STATUS_OK) {
    Close(state);
    return status;
  }
  return KWEB_SCREEN_STATUS_OK;
}

kweb_screen_status ReadTopologyOf(State &state, Topology *topology) {
  WaylandState *wayland = StateOf(state);
  if (wayland == nullptr || topology == nullptr) return KWEB_SCREEN_STATUS_PLATFORM_UNAVAILABLE;
  kweb_screen_status status = KWEB_SCREEN_STATUS_NATIVE_FAILED;
  RunOnGdkThread(*wayland, [&status, wayland, topology] { status = ReadTopology(*wayland, topology); });
  return status;
}

kweb_screen_status ReadCursor(State &state, int32_t *x, int32_t *y, std::string *display_id,
                              bool *display_known) {
  (void)state;
  if (x == nullptr || y == nullptr || display_id == nullptr || display_known == nullptr) {
    return KWEB_SCREEN_STATUS_INVALID_ARGUMENT;
  }
  // A compositor publishes no global cursor position, so the provider never
  // synthesizes one.
  return KWEB_SCREEN_STATUS_CURSOR_UNAVAILABLE;
}

kweb_screen_status Close(State &state) {
  WaylandState *wayland = StateOf(state);
  if (wayland == nullptr) return KWEB_SCREEN_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  {
    std::lock_guard<std::mutex> lock(wayland->mutex);
    wayland->stopping = true;
  }
  StopLoop(wayland);
  delete wayland;
  state.platform = nullptr;
  return KWEB_SCREEN_STATUS_OK;
}

}  // namespace kwebshell::screen::wayland_provider
