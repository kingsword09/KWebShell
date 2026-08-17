#include "interop_probe_abi.h"

#include <cstdint>
#include <iostream>
#include <limits>
#include <string>
#include <type_traits>
#include <vector>

#include "kwebshell/native/engine_abi.h"

namespace {

struct LayoutExpectation {
  kweb_probe_layout_type type;
  uint64_t size;
  uint64_t alignment;
  std::vector<uint64_t> offsets;
};

bool Check(bool condition, const char *message) {
  if (!condition) {
    std::cerr << message << std::endl;
  }
  return condition;
}

uint64_t KWEB_ABI_CALL FixedCallback(void *user_data, uint64_t sequence) {
  auto *sum = static_cast<uint64_t *>(user_data);
  *sum += sequence;
  return sequence ^ 0x55AA55AAULL;
}

uint64_t KWEB_ABI_CALL Utf8Callback(void *user_data, const char *text,
                                    size_t size, uint64_t sequence) {
  auto *sum = static_cast<uint64_t *>(user_data);
  *sum += static_cast<uint64_t>(size) + sequence;
  return text == nullptr || size == 0 ? 0 : static_cast<uint8_t>(text[0]);
}

void KWEB_ABI_CALL EngineEventCallback(void *user_data,
                                       const kweb_engine_event *event) {
  if (event != nullptr) {
    *static_cast<uint32_t *>(user_data) |= 1U;
  }
}

void KWEB_ABI_CALL BrowserEventCallback(void *user_data,
                                        const kweb_browser_event *event) {
  if (event != nullptr) {
    *static_cast<uint32_t *>(user_data) |= 2U;
  }
}

void KWEB_ABI_CALL BridgeEventCallback(void *user_data,
                                       const kweb_bridge_event *event) {
  if (event != nullptr) {
    *static_cast<uint32_t *>(user_data) |= 4U;
  }
}

void KWEB_ABI_CALL ExtensionResultCallback(
    void *user_data, const kweb_extension_result *result) {
  if (result != nullptr) {
    *static_cast<uint32_t *>(user_data) |= 8U;
  }
}

static_assert(std::is_same_v<decltype(&EngineEventCallback),
                             kweb_engine_event_callback>);
static_assert(std::is_same_v<decltype(&BrowserEventCallback),
                             kweb_browser_event_callback>);
static_assert(std::is_same_v<decltype(&BridgeEventCallback),
                             kweb_bridge_event_callback>);
static_assert(std::is_same_v<decltype(&ExtensionResultCallback),
                             kweb_extension_result_callback>);

} // namespace

int main() {
  bool passed = true;
  passed &= Check(kweb_probe_abi_version() == KWEB_INTEROP_PROBE_ABI_VERSION,
                  "probe ABI version mismatch");
  passed &= Check(sizeof(size_t) == 8 && sizeof(uintptr_t) == 8,
                  "Objective 8.1 requires a 64-bit size_t and uintptr_t");

  const std::vector<LayoutExpectation> layouts = {
      {KWEB_PROBE_LAYOUT_STRING_VIEW, 16, 8, {0, 8}},
      {KWEB_PROBE_LAYOUT_ENGINE_EVENT, 32, 8, {0, 4, 8, 12, 16, 24}},
      {KWEB_PROBE_LAYOUT_ENGINE_CONFIG, 128, 8,
       {0, 4, 8, 16, 24, 40, 56, 72, 88, 104, 120, 124}},
      {KWEB_PROBE_LAYOUT_BROWSER_EVENT, 72, 8,
       {0, 4, 8, 12, 16, 24, 32, 40, 56, 60, 64, 68}},
      {KWEB_PROBE_LAYOUT_BRIDGE_EVENT, 56, 8,
       {0, 4, 8, 12, 16, 24, 32, 40}},
      {KWEB_PROBE_LAYOUT_EXTENSION_RESULT, 128, 8,
       {0, 4, 8, 16, 20, 24, 28, 32, 40, 48, 64, 80, 96, 112}},
      {KWEB_PROBE_LAYOUT_EXTENSION_CONFIG, 80, 8,
       {0, 4, 8, 12, 16, 32, 48, 64, 72}},
      {KWEB_PROBE_LAYOUT_BROWSER_CONFIG, 128, 8,
       {0, 4, 8, 16, 24, 32, 36, 40, 44, 48, 64, 80, 88, 96, 112,
        120}},
  };
  passed &= Check(layouts.size() == 8,
                  "native ABI layout inventory must contain 8 entries");
  for (const auto &layout : layouts) {
    passed &= Check(kweb_probe_layout_size(layout.type) == layout.size,
                    "native layout size mismatch");
    passed &= Check(kweb_probe_layout_alignment(layout.type) == layout.alignment,
                    "native layout alignment mismatch");
    passed &= Check(kweb_probe_layout_field_count(layout.type) ==
                        layout.offsets.size(),
                    "native layout field count mismatch");
    for (uint32_t index = 0; index < layout.offsets.size(); ++index) {
      passed &= Check(kweb_probe_layout_field_offset(layout.type, index) ==
                          layout.offsets[index],
                      "native layout field offset mismatch");
    }
    passed &= Check(kweb_probe_layout_field_offset(
                        layout.type,
                        static_cast<uint32_t>(layout.offsets.size())) ==
                        KWEB_PROBE_INVALID_LAYOUT_VALUE,
                    "native layout accepted an out-of-range field");
  }

  const std::string text = "KWebShell UTF-8 \xE2\x9C\x93";
  passed &= Check(kweb_probe_integer_call(7, 800, 600) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "integer probe call failed");
  passed &= Check(kweb_probe_utf8_call(text.data(), text.size()) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "valid UTF-8 probe call failed");
  const char invalid[] = {static_cast<char>(0xC0), static_cast<char>(0x80)};
  passed &= Check(kweb_probe_utf8_call(invalid, sizeof(invalid)) ==
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "invalid UTF-8 probe call was accepted");
  const std::string maximum_text(KWEB_PROBE_MAXIMUM_UTF8_SIZE, 'K');
  passed &= Check(kweb_probe_utf8_call(maximum_text.data(),
                                       maximum_text.size()) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "maximum UTF-8 probe payload was rejected");
  const std::string oversized_text(KWEB_PROBE_MAXIMUM_UTF8_SIZE + 1, 'K');
  passed &= Check(kweb_probe_utf8_call(oversized_text.data(),
                                       oversized_text.size()) ==
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "oversized UTF-8 probe payload was accepted");

  uint32_t callback_mask = 0;
  const kweb_engine_event engine_event{};
  const kweb_browser_event browser_event{};
  const kweb_bridge_event bridge_event{};
  const kweb_extension_result extension_result{};
  const kweb_engine_event_callback engine_callback = EngineEventCallback;
  const kweb_browser_event_callback browser_callback = BrowserEventCallback;
  const kweb_bridge_event_callback bridge_callback = BridgeEventCallback;
  const kweb_extension_result_callback extension_callback =
      ExtensionResultCallback;
  engine_callback(&callback_mask, &engine_event);
  browser_callback(&callback_mask, &browser_event);
  bridge_callback(&callback_mask, &bridge_event);
  extension_callback(&callback_mask, &extension_result);
  passed &= Check(callback_mask == 15U,
                  "engine callback calling conventions failed");

  uint64_t fixed_sum = 0;
  passed &= Check(kweb_probe_fixed_upcall(FixedCallback, &fixed_sum, 32) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE &&
                      fixed_sum == 528,
                  "fixed callback calling convention failed");
  passed &= Check(kweb_probe_fixed_upcall(FixedCallback, &fixed_sum, 0) ==
                      KWEB_PROBE_INVALID_LAYOUT_VALUE,
                  "zero-count fixed callback was accepted");
  uint64_t threaded_sum = 0;
  passed &= Check(kweb_probe_threaded_fixed_upcall(
                      FixedCallback, &threaded_sum, 32) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE &&
                      threaded_sum == 528,
                  "native-thread callback calling convention failed");
  uint64_t utf8_sum = 0;
  passed &= Check(kweb_probe_utf8_upcall(Utf8Callback, &utf8_sum, text.data(),
                                         text.size(), 16) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE &&
                      utf8_sum == text.size() * 16 + (16 * 17) / 2,
                  "UTF-8 callback calling convention failed");
  uint64_t malformed_sum = 0;
  passed &= Check(kweb_probe_malformed_utf8_upcall(
                      Utf8Callback, &malformed_sum) == 0xC0 &&
                      malformed_sum == 3,
                  "malformed UTF-8 callback fixture failed");
  uint64_t owner_sum = 0;
  passed &= Check(kweb_probe_owner_cycles(FixedCallback, &owner_sum, 64) !=
                      KWEB_PROBE_INVALID_LAYOUT_VALUE &&
                      owner_sum == 2080,
                  "native owner lifecycle failed");
  passed &= Check(kweb_probe_validate_native_parent(0) ==
                      KWEB_STATUS_PARENT_SURFACE_INVALID,
                  "null native parent was accepted");
  passed &= Check(kweb_probe_live_native_bytes() == 0,
                  "probe leaked native bytes");
  return passed ? 0 : 1;
}
