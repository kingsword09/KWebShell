#include "interop_probe_abi.h"

#include <array>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <new>
#include <thread>

#include "kwebshell/native/engine_abi.h"
#include "utf8_validation.h"

namespace {

template <typename T, size_t Size>
uint64_t OffsetAt(const std::array<T, Size> &offsets, uint32_t index) {
  return index < offsets.size()
             ? static_cast<uint64_t>(offsets[index])
             : KWEB_PROBE_INVALID_LAYOUT_VALUE;
}

constexpr std::array<size_t, 2> kStringViewOffsets = {
    offsetof(kweb_string_view, data),
    offsetof(kweb_string_view, size),
};

constexpr std::array<size_t, 6> kEngineEventOffsets = {
    offsetof(kweb_engine_event, struct_size),
    offsetof(kweb_engine_event, abi_version),
    offsetof(kweb_engine_event, type),
    offsetof(kweb_engine_event, reserved),
    offsetof(kweb_engine_event, engine),
    offsetof(kweb_engine_event, sequence),
};

constexpr std::array<size_t, 12> kEngineConfigOffsets = {
    offsetof(kweb_engine_config, struct_size),
    offsetof(kweb_engine_config, abi_version),
    offsetof(kweb_engine_config, callback),
    offsetof(kweb_engine_config, user_data),
    offsetof(kweb_engine_config, cef_runtime_path),
    offsetof(kweb_engine_config, browser_subprocess_path),
    offsetof(kweb_engine_config, resources_path),
    offsetof(kweb_engine_config, locales_path),
    offsetof(kweb_engine_config, root_cache_path),
    offsetof(kweb_engine_config, log_path),
    offsetof(kweb_engine_config, remote_debugging_port),
    offsetof(kweb_engine_config, reserved),
};

constexpr std::array<size_t, 12> kBrowserEventOffsets = {
    offsetof(kweb_browser_event, struct_size),
    offsetof(kweb_browser_event, abi_version),
    offsetof(kweb_browser_event, type),
    offsetof(kweb_browser_event, flags),
    offsetof(kweb_browser_event, engine),
    offsetof(kweb_browser_event, browser),
    offsetof(kweb_browser_event, sequence),
    offsetof(kweb_browser_event, text),
    offsetof(kweb_browser_event, status_code),
    offsetof(kweb_browser_event, width),
    offsetof(kweb_browser_event, height),
    offsetof(kweb_browser_event, reserved),
};

constexpr std::array<size_t, 8> kBridgeEventOffsets = {
    offsetof(kweb_bridge_event, struct_size),
    offsetof(kweb_bridge_event, abi_version),
    offsetof(kweb_bridge_event, type),
    offsetof(kweb_bridge_event, reserved),
    offsetof(kweb_bridge_event, engine),
    offsetof(kweb_bridge_event, browser),
    offsetof(kweb_bridge_event, request_id),
    offsetof(kweb_bridge_event, payload),
};

constexpr std::array<size_t, 14> kExtensionResultOffsets = {
    offsetof(kweb_extension_result, struct_size),
    offsetof(kweb_extension_result, abi_version),
    offsetof(kweb_extension_result, operation_handle),
    offsetof(kweb_extension_result, operation),
    offsetof(kweb_extension_result, outcome),
    offsetof(kweb_extension_result, state),
    offsetof(kweb_extension_result, reserved),
    offsetof(kweb_extension_result, engine),
    offsetof(kweb_extension_result, browser),
    offsetof(kweb_extension_result, extension_id),
    offsetof(kweb_extension_result, version),
    offsetof(kweb_extension_result, path),
    offsetof(kweb_extension_result, error_code),
    offsetof(kweb_extension_result, error_message),
};

constexpr std::array<size_t, 9> kExtensionConfigOffsets = {
    offsetof(kweb_extension_config, struct_size),
    offsetof(kweb_extension_config, abi_version),
    offsetof(kweb_extension_config, operation),
    offsetof(kweb_extension_config, reserved),
    offsetof(kweb_extension_config, extension_id),
    offsetof(kweb_extension_config, expected_version),
    offsetof(kweb_extension_config, extension_path),
    offsetof(kweb_extension_config, callback),
    offsetof(kweb_extension_config, user_data),
};

constexpr std::array<size_t, 16> kBrowserConfigOffsets = {
    offsetof(kweb_browser_config, struct_size),
    offsetof(kweb_browser_config, abi_version),
    offsetof(kweb_browser_config, engine),
    offsetof(kweb_browser_config, reserved),
    offsetof(kweb_browser_config, native_parent),
    offsetof(kweb_browser_config, x),
    offsetof(kweb_browser_config, y),
    offsetof(kweb_browser_config, width),
    offsetof(kweb_browser_config, height),
    offsetof(kweb_browser_config, profile_path),
    offsetof(kweb_browser_config, initial_url),
    offsetof(kweb_browser_config, callback),
    offsetof(kweb_browser_config, user_data),
    offsetof(kweb_browser_config, bridge_origin),
    offsetof(kweb_browser_config, bridge_callback),
    offsetof(kweb_browser_config, bridge_user_data),
};

std::atomic<uint64_t> live_native_bytes{0};

} // namespace

uint32_t KWEB_ABI_CALL kweb_probe_abi_version(void) {
  return KWEB_INTEROP_PROBE_ABI_VERSION;
}

uint64_t KWEB_ABI_CALL kweb_probe_layout_size(kweb_probe_layout_type type) {
  switch (type) {
  case KWEB_PROBE_LAYOUT_STRING_VIEW:
    return sizeof(kweb_string_view);
  case KWEB_PROBE_LAYOUT_ENGINE_EVENT:
    return sizeof(kweb_engine_event);
  case KWEB_PROBE_LAYOUT_ENGINE_CONFIG:
    return sizeof(kweb_engine_config);
  case KWEB_PROBE_LAYOUT_BROWSER_EVENT:
    return sizeof(kweb_browser_event);
  case KWEB_PROBE_LAYOUT_BRIDGE_EVENT:
    return sizeof(kweb_bridge_event);
  case KWEB_PROBE_LAYOUT_EXTENSION_RESULT:
    return sizeof(kweb_extension_result);
  case KWEB_PROBE_LAYOUT_EXTENSION_CONFIG:
    return sizeof(kweb_extension_config);
  case KWEB_PROBE_LAYOUT_BROWSER_CONFIG:
    return sizeof(kweb_browser_config);
  default:
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
}

uint64_t KWEB_ABI_CALL
kweb_probe_layout_alignment(kweb_probe_layout_type type) {
  switch (type) {
  case KWEB_PROBE_LAYOUT_STRING_VIEW:
    return alignof(kweb_string_view);
  case KWEB_PROBE_LAYOUT_ENGINE_EVENT:
    return alignof(kweb_engine_event);
  case KWEB_PROBE_LAYOUT_ENGINE_CONFIG:
    return alignof(kweb_engine_config);
  case KWEB_PROBE_LAYOUT_BROWSER_EVENT:
    return alignof(kweb_browser_event);
  case KWEB_PROBE_LAYOUT_BRIDGE_EVENT:
    return alignof(kweb_bridge_event);
  case KWEB_PROBE_LAYOUT_EXTENSION_RESULT:
    return alignof(kweb_extension_result);
  case KWEB_PROBE_LAYOUT_EXTENSION_CONFIG:
    return alignof(kweb_extension_config);
  case KWEB_PROBE_LAYOUT_BROWSER_CONFIG:
    return alignof(kweb_browser_config);
  default:
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
}

uint32_t KWEB_ABI_CALL
kweb_probe_layout_field_count(kweb_probe_layout_type type) {
  switch (type) {
  case KWEB_PROBE_LAYOUT_STRING_VIEW:
    return static_cast<uint32_t>(kStringViewOffsets.size());
  case KWEB_PROBE_LAYOUT_ENGINE_EVENT:
    return static_cast<uint32_t>(kEngineEventOffsets.size());
  case KWEB_PROBE_LAYOUT_ENGINE_CONFIG:
    return static_cast<uint32_t>(kEngineConfigOffsets.size());
  case KWEB_PROBE_LAYOUT_BROWSER_EVENT:
    return static_cast<uint32_t>(kBrowserEventOffsets.size());
  case KWEB_PROBE_LAYOUT_BRIDGE_EVENT:
    return static_cast<uint32_t>(kBridgeEventOffsets.size());
  case KWEB_PROBE_LAYOUT_EXTENSION_RESULT:
    return static_cast<uint32_t>(kExtensionResultOffsets.size());
  case KWEB_PROBE_LAYOUT_EXTENSION_CONFIG:
    return static_cast<uint32_t>(kExtensionConfigOffsets.size());
  case KWEB_PROBE_LAYOUT_BROWSER_CONFIG:
    return static_cast<uint32_t>(kBrowserConfigOffsets.size());
  default:
    return 0;
  }
}

uint64_t KWEB_ABI_CALL kweb_probe_layout_field_offset(
    kweb_probe_layout_type type, uint32_t field_index) {
  switch (type) {
  case KWEB_PROBE_LAYOUT_STRING_VIEW:
    return OffsetAt(kStringViewOffsets, field_index);
  case KWEB_PROBE_LAYOUT_ENGINE_EVENT:
    return OffsetAt(kEngineEventOffsets, field_index);
  case KWEB_PROBE_LAYOUT_ENGINE_CONFIG:
    return OffsetAt(kEngineConfigOffsets, field_index);
  case KWEB_PROBE_LAYOUT_BROWSER_EVENT:
    return OffsetAt(kBrowserEventOffsets, field_index);
  case KWEB_PROBE_LAYOUT_BRIDGE_EVENT:
    return OffsetAt(kBridgeEventOffsets, field_index);
  case KWEB_PROBE_LAYOUT_EXTENSION_RESULT:
    return OffsetAt(kExtensionResultOffsets, field_index);
  case KWEB_PROBE_LAYOUT_EXTENSION_CONFIG:
    return OffsetAt(kExtensionConfigOffsets, field_index);
  case KWEB_PROBE_LAYOUT_BROWSER_CONFIG:
    return OffsetAt(kBrowserConfigOffsets, field_index);
  default:
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
}

namespace {

uint64_t Fnv1a(const char *text, size_t size) {
  uint64_t hash = 1469598103934665603ULL;
  for (size_t index = 0; index < size; ++index) {
    hash ^= static_cast<uint8_t>(text[index]);
    hash *= 1099511628211ULL;
  }
  return hash;
}

uint64_t Mix(uint64_t value, uint64_t next) {
  value ^= next + 0x9e3779b97f4a7c15ULL + (value << 6) + (value >> 2);
  return value;
}

} // namespace

uint64_t KWEB_ABI_CALL kweb_probe_integer_call(uint64_t handle,
                                               int32_t width,
                                               int32_t height) {
  if (width <= 0 || height <= 0) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  return Mix(handle, (static_cast<uint64_t>(static_cast<uint32_t>(width))
                      << 32) |
                 static_cast<uint32_t>(height));
}

uint64_t KWEB_ABI_CALL kweb_probe_utf8_call(const char *text_utf8,
                                            size_t text_size) {
  if (text_size > KWEB_PROBE_MAXIMUM_UTF8_SIZE ||
      !kwebshell::IsValidUtf8(text_utf8, text_size)) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  return Mix(Fnv1a(text_utf8, text_size), text_size);
}

uint64_t KWEB_ABI_CALL kweb_probe_fixed_upcall(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count) {
  if (callback == nullptr || count == 0) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  uint64_t result = 0;
  for (uint32_t sequence = 1; sequence <= count; ++sequence) {
    result = Mix(result, callback(user_data, sequence));
  }
  return result;
}

uint64_t KWEB_ABI_CALL kweb_probe_threaded_fixed_upcall(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count) {
  if (callback == nullptr || count == 0) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  uint64_t result = KWEB_PROBE_INVALID_LAYOUT_VALUE;
  try {
    std::thread worker([&result, callback, user_data, count]() {
      result = kweb_probe_fixed_upcall(callback, user_data, count);
    });
    worker.join();
  } catch (...) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  return result;
}

uint64_t KWEB_ABI_CALL kweb_probe_utf8_upcall(
    kweb_probe_utf8_callback callback, void *user_data, const char *text_utf8,
    size_t text_size, uint32_t count) {
  if (callback == nullptr || count == 0 ||
      text_size > KWEB_PROBE_MAXIMUM_UTF8_SIZE ||
      !kwebshell::IsValidUtf8(text_utf8, text_size)) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  uint64_t result = 0;
  for (uint32_t sequence = 1; sequence <= count; ++sequence) {
    result = Mix(result, callback(user_data, text_utf8, text_size, sequence));
  }
  return result;
}

uint64_t KWEB_ABI_CALL kweb_probe_malformed_utf8_upcall(
    kweb_probe_utf8_callback callback, void *user_data) {
  if (callback == nullptr) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  const char malformed[] = {static_cast<char>(0xC0), static_cast<char>(0x80)};
  return callback(user_data, malformed, sizeof(malformed), 1);
}

uint64_t KWEB_ABI_CALL kweb_probe_owner_cycles(
    kweb_probe_fixed_callback callback, void *user_data, uint32_t count) {
  if (callback == nullptr || count == 0) {
    return KWEB_PROBE_INVALID_LAYOUT_VALUE;
  }
  struct Owner {
    kweb_probe_fixed_callback callback;
    void *user_data;
    uint64_t sequence;
  };
  uint64_t result = 0;
  for (uint32_t sequence = 1; sequence <= count; ++sequence) {
    auto *owner = new (std::nothrow) Owner{callback, user_data, sequence};
    if (owner == nullptr) {
      return KWEB_PROBE_INVALID_LAYOUT_VALUE;
    }
    live_native_bytes.fetch_add(sizeof(Owner), std::memory_order_acq_rel);
    result = Mix(result, owner->callback(owner->user_data, owner->sequence));
    live_native_bytes.fetch_sub(sizeof(Owner), std::memory_order_acq_rel);
    delete owner;
  }
  return result;
}

uint64_t KWEB_ABI_CALL kweb_probe_live_native_bytes(void) {
  return live_native_bytes.load(std::memory_order_acquire);
}
