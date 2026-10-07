#import <CoreGraphics/CoreGraphics.h>

#include "image_internal.h"

#include <CoreFoundation/CoreFoundation.h>

#include <cstring>

namespace kwebshell::image {

const char *ProviderId() noexcept { return "macos.CoreGraphics.CGImage"; }

uint32_t CreateNative(const kweb_image_rgba &input, void **value) noexcept {
  if (value == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  *value = nullptr;
  CFDataRef data = CFDataCreate(kCFAllocatorDefault, input.bytes, static_cast<CFIndex>(input.size));
  if (data == nullptr) return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  CGDataProviderRef provider = CGDataProviderCreateWithCFData(data);
  CGColorSpaceRef color_space = CGColorSpaceCreateWithName(kCGColorSpaceSRGB);
  const CGBitmapInfo bitmap_info = static_cast<CGBitmapInfo>(kCGImageAlphaLast) |
      static_cast<CGBitmapInfo>(kCGBitmapByteOrder32Big);
  CGImageRef image = provider == nullptr || color_space == nullptr
      ? nullptr
      : CGImageCreate(input.width, input.height, 8, 32, input.width * 4,
                      color_space, bitmap_info,
                      provider, nullptr, false, kCGRenderingIntentDefault);
  if (provider != nullptr) CGDataProviderRelease(provider);
  if (color_space != nullptr) CGColorSpaceRelease(color_space);
  CFRelease(data);
  if (image == nullptr) return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  *value = const_cast<void *>(static_cast<const void *>(image));
  return KWEB_IMAGE_STATUS_OK;
}

uint32_t ReleaseNative(void *value) noexcept {
  if (value == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  CGImageRelease(static_cast<CGImageRef>(value));
  return KWEB_IMAGE_STATUS_OK;
}

}  // namespace kwebshell::image
