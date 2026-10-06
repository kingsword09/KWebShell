#define NOMINMAX
#include <windows.h>

#include "image_internal.h"

#include <cstring>

namespace kwebshell::image {

const char *ProviderId() { return "windows.Win32.HBITMAP"; }

uint32_t CreateNative(const kweb_image_rgba &input, void **value) {
  if (value == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  *value = nullptr;
  BITMAPINFO info{};
  info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
  info.bmiHeader.biWidth = static_cast<LONG>(input.width);
  info.bmiHeader.biHeight = -static_cast<LONG>(input.height);
  info.bmiHeader.biPlanes = 1;
  info.bmiHeader.biBitCount = 32;
  info.bmiHeader.biCompression = BI_RGB;
  void *bits = nullptr;
  HBITMAP bitmap = CreateDIBSection(nullptr, &info, DIB_RGB_COLORS, &bits, nullptr, 0);
  if (bitmap == nullptr || bits == nullptr) {
    if (bitmap != nullptr) DeleteObject(bitmap);
    return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  }
  auto *destination = static_cast<uint8_t *>(bits);
  for (uint32_t index = 0; index < input.width * input.height; ++index) {
    destination[index * 4] = input.bytes[index * 4 + 2];
    destination[index * 4 + 1] = input.bytes[index * 4 + 1];
    destination[index * 4 + 2] = input.bytes[index * 4];
    destination[index * 4 + 3] = input.bytes[index * 4 + 3];
  }
  *value = bitmap;
  return KWEB_IMAGE_STATUS_OK;
}

uint32_t ReleaseNative(void *value) {
  if (value == nullptr || !DeleteObject(static_cast<HBITMAP>(value))) {
    return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  }
  return KWEB_IMAGE_STATUS_OK;
}

}  // namespace kwebshell::image
