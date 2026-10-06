#include "image_internal.h"

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <mutex>
#include <unordered_map>

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
  if (pixels > 64ULL * 1024ULL * 1024ULL) return false;
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
    default: return "unknown";
  }
}

const char *kweb_image_provider_id(void) { return kwebshell::image::ProviderId(); }

uint32_t kweb_image_create(const kweb_image_rgba *input, uint64_t *handle) {
  if (!ValidInput(input) || handle == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  void *value = nullptr;
  const uint32_t status = kwebshell::image::CreateNative(*input, &value);
  if (status != KWEB_IMAGE_STATUS_OK || value == nullptr) return status;
  const uint64_t id = kNextHandle.fetch_add(1);
  if (id == 0) {
    kwebshell::image::ReleaseNative(value);
    return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  }
  {
    std::lock_guard lock(kLock);
    if (!kImages.emplace(id, value).second) {
      kwebshell::image::ReleaseNative(value);
      return KWEB_IMAGE_STATUS_NATIVE_FAILED;
    }
  }
  *handle = id;
  return KWEB_IMAGE_STATUS_OK;
}

uint32_t kweb_image_release(uint64_t handle) {
  if (handle == 0) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  void *value = nullptr;
  {
    std::lock_guard lock(kLock);
    const auto found = kImages.find(handle);
    if (found == kImages.end()) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
    value = found->second;
    kImages.erase(found);
  }
  return kwebshell::image::ReleaseNative(value);
}

uint32_t kweb_image_live_count(uint32_t *count) {
  if (count == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  std::lock_guard lock(kLock);
  *count = static_cast<uint32_t>(kImages.size());
  return KWEB_IMAGE_STATUS_OK;
}

}  // extern "C"
