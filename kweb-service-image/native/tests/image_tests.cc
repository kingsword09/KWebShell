#include "kweb_image.h"
#include "image_internal.h"

#if defined(_WIN32)
#define NOMINMAX
#include <windows.h>
#elif defined(__APPLE__)
#include <CoreGraphics/CoreGraphics.h>
#else
#include <gdk-pixbuf/gdk-pixbuf.h>
#endif

#include <cstring>

#include <cassert>
#include <atomic>
#include <cstdint>
#include <thread>
#include <vector>

namespace {
void CheckNativePixels() {
  const std::vector<uint8_t> pixels{255, 17, 33, 127, 5, 119, 211, 255};
  kweb_image_rgba input{sizeof(kweb_image_rgba), 1, 2, 1, pixels.data(), pixels.size()};
  void *value = nullptr;
  assert(kwebshell::image::CreateNative(input, &value) == KWEB_IMAGE_STATUS_OK);
  assert(value != nullptr);
#if defined(_WIN32)
  BITMAP bitmap{};
  assert(GetObject(static_cast<HBITMAP>(value), static_cast<int>(sizeof(bitmap)), &bitmap) == static_cast<int>(sizeof(bitmap)));
  assert(bitmap.bmWidth == 2 && bitmap.bmHeight == 1 && bitmap.bmBitsPixel == 32);
  const auto *bytes = static_cast<const uint8_t *>(bitmap.bmBits);
  for (size_t index = 0; index < pixels.size(); index += 4) {
    assert(bytes[index] == pixels[index + 2]);
    assert(bytes[index + 1] == pixels[index + 1]);
    assert(bytes[index + 2] == pixels[index]);
    assert(bytes[index + 3] == pixels[index + 3]);
  }
#elif defined(__APPLE__)
  auto image = static_cast<CGImageRef>(value);
  assert(CGImageGetWidth(image) == 2 && CGImageGetHeight(image) == 1);
  assert(CGImageGetAlphaInfo(image) == kCGImageAlphaLast);
  assert(CFEqual(CGColorSpaceGetName(CGImageGetColorSpace(image)), kCGColorSpaceSRGB));
  CFDataRef data = CGDataProviderCopyData(CGImageGetDataProvider(image));
  assert(data != nullptr && static_cast<size_t>(CFDataGetLength(data)) == pixels.size());
  assert(std::memcmp(CFDataGetBytePtr(data), pixels.data(), pixels.size()) == 0);
  CFRelease(data);
#else
  auto *image = static_cast<GdkPixbuf *>(value);
  assert(gdk_pixbuf_get_width(image) == 2 && gdk_pixbuf_get_height(image) == 1);
  assert(gdk_pixbuf_get_has_alpha(image) && gdk_pixbuf_get_n_channels(image) == 4);
  assert(gdk_pixbuf_get_colorspace(image) == GDK_COLORSPACE_RGB);
  assert(std::memcmp(gdk_pixbuf_read_pixels(image), pixels.data(), pixels.size()) == 0);
#endif
  assert(kwebshell::image::ReleaseNative(value) == KWEB_IMAGE_STATUS_OK);
  assert(kwebshell::image::ReleaseNative(nullptr) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  assert(kwebshell::image::CreateNative(input, nullptr) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
}
}  // namespace

int main() {
  CheckNativePixels();
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
  input.struct_size = static_cast<uint32_t>(sizeof(kweb_image_rgba) - 1);
  handle = UINT64_MAX;
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  assert(handle == 0);
  input.struct_size = sizeof(kweb_image_rgba);
  input.abi_version = 2;
  input.size = pixels.size();
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_ABI_MISMATCH);
  input.abi_version = 1;
  input.width = 4096;
  input.height = 2048;
  input.size = static_cast<size_t>(input.width) * input.height * 4;
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  input.size = 1;
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_INVALID_ARGUMENT);
  input.width = width;
  input.height = height;
  input.size = pixels.size();
  std::vector<uint64_t> handles;
  handles.reserve(KWEB_IMAGE_MAX_NATIVE_HANDLES);
  for (uint32_t index = 0; index < KWEB_IMAGE_MAX_NATIVE_HANDLES; ++index) {
    assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_OK);
    handles.push_back(handle);
  }
  assert(kweb_image_live_count(&count) == KWEB_IMAGE_STATUS_OK);
  assert(count == KWEB_IMAGE_MAX_NATIVE_HANDLES);
  handle = UINT64_MAX;
  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_HANDLE_LIMIT);
  assert(handle == 0);
  for (const uint64_t live_handle : handles) assert(kweb_image_release(live_handle) == KWEB_IMAGE_STATUS_OK);
  assert(kweb_image_live_count(&count) == KWEB_IMAGE_STATUS_OK);
  assert(count == 0);

  assert(kweb_image_create(&input, &handle) == KWEB_IMAGE_STATUS_OK);
  std::atomic<bool> start{false};
  uint32_t first_status = KWEB_IMAGE_STATUS_NATIVE_FAILED;
  uint32_t second_status = KWEB_IMAGE_STATUS_NATIVE_FAILED;
  std::thread first([&] {
    while (!start.load()) std::this_thread::yield();
    first_status = kweb_image_release(handle);
  });
  std::thread second([&] {
    while (!start.load()) std::this_thread::yield();
    second_status = kweb_image_release(handle);
  });
  start.store(true);
  first.join();
  second.join();
  assert((first_status == KWEB_IMAGE_STATUS_OK && second_status == KWEB_IMAGE_STATUS_INVALID_ARGUMENT) ||
         (second_status == KWEB_IMAGE_STATUS_OK && first_status == KWEB_IMAGE_STATUS_INVALID_ARGUMENT));
  assert(kweb_image_live_count(&count) == KWEB_IMAGE_STATUS_OK);
  assert(count == 0);
  return 0;
}
