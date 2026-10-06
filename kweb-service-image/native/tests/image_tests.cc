#include "kweb_image.h"

#include <cassert>
#include <cstdint>
#include <vector>

int main() {
  constexpr uint32_t width = 2;
  constexpr uint32_t height = 2;
  const std::vector<uint8_t> pixels(width * height * 4, 0x7f);
  kweb_image_rgba input{
      static_cast<uint32_t>(sizeof(kweb_image_rgba)), 1, width, height,
      pixels.data(), pixels.size()};
  uint64_t handle = 0;
  assert(kweb_image_abi_version() == 1);
  assert(kweb_image_provider_id() != nullptr);
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_OK);
  assert(handle != 0);
  uint32_t count = 0;
  assert(kweb_image_live_count(&count) == KWEB_IMAGE_STATUS_OK);
  assert(count == 1);
  assert(kweb_image_release(handle) == KWEB_IMAGE_STATUS_OK);
  assert(kweb_image_live_count(&count) == KWEB_IMAGE_STATUS_OK);
  assert(count == 0);
  assert(kweb_image_release(handle) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  input.size = 1;
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  return 0;
}
