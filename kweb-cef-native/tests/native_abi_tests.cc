#include "kwebshell/native/abi.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <iostream>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>

namespace {

struct EventSnapshot final {
  kweb_event_type type;
  kweb_session_handle session;
  uint64_t sequence;
  std::string text;
  int32_t width;
  int32_t height;
};

struct EventCollector final {
  std::mutex mutex;
  std::vector<EventSnapshot> events;
  bool invalid_event = false;
};

int failures = 0;

void Check(bool condition, const std::string &message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << std::endl;
    ++failures;
  }
}

void KWEB_ABI_CALL CollectEvent(void *user_data, const kweb_event *event) {
  auto *collector = static_cast<EventCollector *>(user_data);
  std::lock_guard lock(collector->mutex);
  if (event == nullptr || event->struct_size < sizeof(kweb_event) ||
      event->abi_version != KWEB_ABI_VERSION || event->sequence == 0 ||
      (event->text == nullptr && event->text_size != 0)) {
    collector->invalid_event = true;
    return;
  }
  collector->events.push_back(
      EventSnapshot{event->type, event->session, event->sequence,
                    std::string(event->text, event->text_size), event->width,
                    event->height});
}

kweb_session_config Configuration(EventCollector *collector) {
  return kweb_session_config{sizeof(kweb_session_config), KWEB_ABI_VERSION,
                             &CollectEvent, collector};
}

std::vector<EventSnapshot> Snapshot(EventCollector *collector) {
  std::lock_guard lock(collector->mutex);
  return collector->events;
}

void CheckContiguousSequence(const std::vector<EventSnapshot> &events,
                             const std::string &context) {
  for (size_t index = 0; index < events.size(); ++index) {
    Check(events[index].sequence == index + 1,
          context + " event sequence should be contiguous");
  }
}

void TestVersionAndConfigurationContract() {
  Check(kweb_abi_version() == KWEB_ABI_VERSION,
        "runtime ABI version should match the header");
  Check(std::string(kweb_status_name(KWEB_STATUS_REENTRANT_CLOSE)) ==
            "reentrant-close",
        "status names should be stable and actionable");
  Check(std::string(kweb_status_name(9999)) == "unknown-status",
        "unknown status values should be named explicitly");

  EventCollector collector;
  collector.events.reserve(4);
  kweb_session_handle handle = 42;
  auto config = Configuration(&collector);

  Check(kweb_session_create(nullptr, &handle) == KWEB_STATUS_INVALID_ARGUMENT,
        "null configuration should be rejected");
  Check(handle == 42, "null configuration should not mutate caller output");
  Check(kweb_session_create(&config, nullptr) == KWEB_STATUS_INVALID_ARGUMENT,
        "null output handle should be rejected");

  config.struct_size = 0;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_ABI_MISMATCH,
        "undersized configuration should be rejected");
  config = Configuration(&collector);
  config.abi_version = KWEB_ABI_VERSION + 1;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_ABI_MISMATCH,
        "unknown ABI version should be rejected");
  config = Configuration(&collector);
  config.callback = nullptr;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_INVALID_ARGUMENT,
        "missing callback should be rejected");
  Check(kweb_live_session_count() == 0,
        "invalid configurations must not allocate sessions");
}

void TestOrderedUnicodeLifecycle() {
  EventCollector collector;
  collector.events.reserve(4);
  auto config = Configuration(&collector);
  kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_OK,
        "valid session should be created");
  Check(handle != KWEB_INVALID_SESSION_HANDLE,
        "created session should have an opaque handle");

  const std::string url =
      "https://example.test/"
      "\xE8\xB7\xAF\xE5\xBE\x84?q="
      "\xF0\x9F\x99\x82";
  Check(kweb_session_request_navigation(handle, url.data(), url.size()) ==
            KWEB_STATUS_OK,
        "UTF-8 navigation request should be accepted");
  Check(kweb_session_resize(handle, 1280, 720) == KWEB_STATUS_OK,
        "viewport change should be accepted");
  Check(kweb_session_close(handle) == KWEB_STATUS_OK,
        "session should close and drain accepted commands");

  const auto events = Snapshot(&collector);
  Check(!collector.invalid_event, "all callback event structures should be valid");
  Check(events.size() == 4, "ordered lifecycle should emit four events");
  if (events.size() == 4) {
    Check(events[0].type == KWEB_EVENT_SESSION_OPENED,
          "session-opened should be first");
    Check(events[1].type == KWEB_EVENT_NAVIGATION_REQUESTED,
          "navigation-requested should follow open");
    Check(events[1].text == url, "callback should preserve UTF-8 exactly");
    Check(events[2].type == KWEB_EVENT_VIEWPORT_CHANGED &&
              events[2].width == 1280 && events[2].height == 720,
          "viewport callback should preserve dimensions");
    Check(events[3].type == KWEB_EVENT_SESSION_CLOSED,
          "session-closed should be terminal");
    for (const auto &event : events) {
      Check(event.session == handle,
            "every callback should retain session ownership");
    }
  }
  CheckContiguousSequence(events, "ordered lifecycle");
  Check(kweb_live_session_count() == 0,
        "closed lifecycle should release the native session");
}

void TestInvalidCommandArguments() {
  EventCollector collector;
  collector.events.reserve(2);
  auto config = Configuration(&collector);
  kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_OK,
        "argument test session should be created");

  Check(kweb_session_request_navigation(handle, nullptr, 1) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "null navigation bytes should be rejected");
  Check(kweb_session_request_navigation(handle, "", 0) ==
            KWEB_STATUS_INVALID_ARGUMENT,
        "empty navigation request should be rejected");
  const char invalid_utf8[] = {static_cast<char>(0xC0),
                               static_cast<char>(0xAF)};
  Check(kweb_session_request_navigation(handle, invalid_utf8,
                                        sizeof(invalid_utf8)) ==
            KWEB_STATUS_INVALID_TEXT_ENCODING,
        "overlong UTF-8 should be rejected");
  const char embedded_null[] = {'a', '\0', 'b'};
  Check(kweb_session_request_navigation(handle, embedded_null,
                                        sizeof(embedded_null)) ==
            KWEB_STATUS_INVALID_TEXT_ENCODING,
        "embedded null should be rejected");
  const std::string oversized(1024 * 1024 + 1, 'a');
  Check(kweb_session_request_navigation(handle, oversized.data(),
                                        oversized.size()) ==
            KWEB_STATUS_TEXT_TOO_LARGE,
        "oversized text should be rejected");
  Check(kweb_session_resize(handle, 0, 720) ==
            KWEB_STATUS_INVALID_DIMENSIONS,
        "zero width should be rejected");
  Check(kweb_session_resize(handle, 800, 32769) ==
            KWEB_STATUS_INVALID_DIMENSIONS,
        "oversized height should be rejected");
  Check(kweb_session_close(handle) == KWEB_STATUS_OK,
        "argument test session should close");
  Check(kweb_live_session_count() == 0,
        "argument failures should not leak the session");
}

void TestRepeatedCyclesDoNotLeakOrReuseHandles() {
  std::set<kweb_session_handle> handles;
  for (int cycle = 0; cycle < 256; ++cycle) {
    EventCollector collector;
    collector.events.reserve(4);
    auto config = Configuration(&collector);
    kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
    Check(kweb_session_create(&config, &handle) == KWEB_STATUS_OK,
          "repeated session should be created");
    handles.insert(handle);
    const std::string url =
        "https://cycle.test/" + std::to_string(cycle);
    Check(kweb_session_request_navigation(handle, url.data(), url.size()) ==
              KWEB_STATUS_OK,
          "repeated navigation request should be accepted");
    Check(kweb_session_resize(handle, 640 + cycle, 480 + cycle) ==
              KWEB_STATUS_OK,
          "repeated resize should be accepted");
    Check(kweb_session_close(handle) == KWEB_STATUS_OK,
          "repeated session should close");
    const auto events = Snapshot(&collector);
    Check(events.size() == 4,
          "repeated session should drain every accepted event");
    CheckContiguousSequence(events, "repeated lifecycle");
    Check(kweb_live_session_count() == 0,
          "each repeated cycle should release its session");
  }
  Check(handles.size() == 256,
        "opaque handles should not be reused across repeated cycles");
}

void TestConcurrentCommandsAndCloseHaveDeclaredResults() {
  EventCollector collector;
  collector.events.reserve(1000);
  auto config = Configuration(&collector);
  kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_OK,
        "race session should be created");

  std::atomic<bool> start = false;
  std::atomic<int> unexpected_statuses = 0;
  std::vector<std::thread> submitters;
  submitters.reserve(8);
  for (int thread_index = 0; thread_index < 8; ++thread_index) {
    submitters.emplace_back([&, thread_index] {
      while (!start.load(std::memory_order_acquire)) {
        std::this_thread::yield();
      }
      for (int request = 0; request < 100; ++request) {
        kweb_status status = KWEB_STATUS_INTERNAL_ERROR;
        if ((request + thread_index) % 2 == 0) {
          const std::string url = "https://race.test/" +
                                  std::to_string(thread_index) + "/" +
                                  std::to_string(request);
          status = kweb_session_request_navigation(handle, url.data(),
                                                   url.size());
        } else {
          status = kweb_session_resize(handle, 800 + request, 600 + request);
        }
        if (status != KWEB_STATUS_OK &&
            status != KWEB_STATUS_SESSION_CLOSING &&
            status != KWEB_STATUS_INVALID_HANDLE) {
          unexpected_statuses.fetch_add(1);
        }
      }
    });
  }

  kweb_status close_status = KWEB_STATUS_INTERNAL_ERROR;
  std::thread closer([&] {
    while (!start.load(std::memory_order_acquire)) {
      std::this_thread::yield();
    }
    close_status = kweb_session_close(handle);
  });
  start.store(true, std::memory_order_release);
  for (auto &submitter : submitters) {
    submitter.join();
  }
  closer.join();

  Check(close_status == KWEB_STATUS_OK,
        "one concurrent owner should close the session");
  Check(unexpected_statuses.load() == 0,
        "racing commands should return only declared statuses");
  const auto events = Snapshot(&collector);
  Check(!events.empty() &&
            events.back().type == KWEB_EVENT_SESSION_CLOSED,
        "race should still end with the closed event");
  CheckContiguousSequence(events, "racing lifecycle");
  Check(kweb_live_session_count() == 0,
        "concurrent close should release the session");

  const size_t callback_count_after_close = events.size();
  std::this_thread::sleep_for(std::chrono::milliseconds(25));
  Check(Snapshot(&collector).size() == callback_count_after_close,
        "no callback may begin after close returns");
  Check(kweb_session_resize(handle, 800, 600) == KWEB_STATUS_INVALID_HANDLE,
        "use-after-close should reject the stale handle");
}

struct ReentrantCloseContext final {
  EventCollector collector;
  std::atomic<kweb_status> close_status = KWEB_STATUS_INTERNAL_ERROR;
};

void KWEB_ABI_CALL ReentrantCloseCallback(void *user_data,
                                          const kweb_event *event) {
  auto *context = static_cast<ReentrantCloseContext *>(user_data);
  CollectEvent(&context->collector, event);
  if (event != nullptr && event->type == KWEB_EVENT_SESSION_OPENED) {
    context->close_status.store(kweb_session_close(event->session));
  }
}

void TestReentrantCloseIsRejectedWithoutLosingOwnership() {
  ReentrantCloseContext context;
  context.collector.events.reserve(2);
  const kweb_session_config config = {
      sizeof(kweb_session_config), KWEB_ABI_VERSION,
      &ReentrantCloseCallback, &context};
  kweb_session_handle handle = KWEB_INVALID_SESSION_HANDLE;
  Check(kweb_session_create(&config, &handle) == KWEB_STATUS_OK,
        "reentrant-close session should be created");
  Check(kweb_session_close(handle) == KWEB_STATUS_OK,
        "external owner should still close after reentrant rejection");
  Check(context.close_status.load() == KWEB_STATUS_REENTRANT_CLOSE,
        "callback-thread close should fail explicitly instead of deadlocking");
  Check(kweb_live_session_count() == 0,
        "reentrant rejection should preserve ownership for external close");
}

} // namespace

int main() {
  TestVersionAndConfigurationContract();
  TestOrderedUnicodeLifecycle();
  TestInvalidCommandArguments();
  TestRepeatedCyclesDoNotLeakOrReuseHandles();
  TestConcurrentCommandsAndCloseHaveDeclaredResults();
  TestReentrantCloseIsRejectedWithoutLosingOwnership();

  if (failures != 0) {
    std::cerr << failures << " native ABI assertion(s) failed." << std::endl;
    return 1;
  }
  std::cout << "All native ABI tests passed with zero live sessions."
            << std::endl;
  return 0;
}
