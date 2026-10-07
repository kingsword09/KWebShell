#include "image_internal.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <mutex>
#include <unordered_map>

static_assert(sizeof(size_t) == sizeof(uint64_t));
static_assert(offsetof(kweb_image_rgba, bytes) == 16);
static_assert(offsetof(kweb_image_rgba, size) == 24);
static_assert(sizeof(kweb_image_rgba) == 32);

namespace {
std::mutex kLock;
std::unordered_map<uint64_t, void *> kImages;
std::atomic<uint64_t> kNextHandle{1};

bool ValidInput(const kweb_image_rgba *input) {
  if (input == nullptr || input->struct_size < sizeof(kweb_image_rgba) ||
      input->abi_version != 1 || input->width == 0 || input->height == 0 ||
      input->width > 16384 || input->height > 16384 || input->bytes == nullptr) {
    return false;
  }
  const uint64_t pixels = static_cast<uint64_t>(input->width) * input->height;
  if (pixels > 6ULL * 1000ULL * 1000ULL) return false;
  return input->size == pixels * 4ULL;
}
}  // namespace

extern "C" {

uint32_t kweb_image_abi_version(void) { return 1; }

const char *kweb_image_status_name(uint32_t status) {
  switch (status) {
    case KWEB_IMAGE_STATUS_OK: return "ok";
    case KWEB_IMAGE_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_IMAGE_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_IMAGE_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_IMAGE_STATUS_NATIVE_FAILED: return "native-failed";
    case KWEB_IMAGE_STATUS_HANDLE_LIMIT: return "handle-limit";
    case KWEB_IMAGE_STATUS_OUTCOME_UNKNOWN: return "outcome-unknown";
    default: return "unknown";
  }
}

const char *kweb_image_provider_id(void) { return kwebshell::image::ProviderId(); }

uint32_t kweb_image_create(const kweb_image_rgba *input, uint64_t *handle) {
  if (handle == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  *handle = 0;
  if (input == nullptr || input->struct_size < sizeof(kweb_image_rgba)) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  if (input->abi_version != 1) return KWEB_IMAGE_STATUS_ABI_MISMATCH;
  if (!ValidInput(input)) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  void *value = nullptr;
  const auto discard = [&value](uint32_t status) noexcept {
    if (value == nullptr) return status;
    void *abandoned = value;
    value = nullptr;
    return kwebshell::image::ReleaseNative(abandoned) == KWEB_IMAGE_STATUS_OK
        ? status : static_cast<uint32_t>(KWEB_IMAGE_STATUS_OUTCOME_UNKNOWN);
  };
  try {
    std::lock_guard lock(kLock);
    if (kImages.size() >= KWEB_IMAGE_MAX_NATIVE_HANDLES) return KWEB_IMAGE_STATUS_HANDLE_LIMIT;
    const uint32_t status = kwebshell::image::CreateNative(*input, &value);
    if (status != KWEB_IMAGE_STATUS_OK) {
      return discard(status);
    }
    if (value == nullptr) return KWEB_IMAGE_STATUS_NATIVE_FAILED;
    const uint64_t id = kNextHandle.fetch_add(1);
    if (id == 0) {
      return discard(KWEB_IMAGE_STATUS_NATIVE_FAILED);
    }
    try {
      if (!kImages.emplace(id, value).second) {
        return discard(KWEB_IMAGE_STATUS_NATIVE_FAILED);
      }
    } catch (...) {
      return discard(KWEB_IMAGE_STATUS_NATIVE_FAILED);
    }
    *handle = id;
    return KWEB_IMAGE_STATUS_OK;
  } catch (...) {
    return discard(KWEB_IMAGE_STATUS_NATIVE_FAILED);
  }
}

uint32_t kweb_image_release(uint64_t handle) {
  if (handle == 0) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  try {
    std::lock_guard lock(kLock);
    const auto found = kImages.find(handle);
    if (found == kImages.end()) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
    const uint32_t status = kwebshell::image::ReleaseNative(found->second);
    if (status == KWEB_IMAGE_STATUS_OK) {
      kImages.erase(handle);
    }
    return status;
  } catch (...) {
    return KWEB_IMAGE_STATUS_OUTCOME_UNKNOWN;
  }
}

uint32_t kweb_image_live_count(uint32_t *count) {
  if (count == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  *count = 0;
  try {
    std::lock_guard lock(kLock);
    *count = static_cast<uint32_t>(kImages.size());
    return KWEB_IMAGE_STATUS_OK;
  } catch (...) {
    return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  }
}

}  // extern "C"
