#ifndef KWEB_IMAGE_INTERNAL_H_
#define KWEB_IMAGE_INTERNAL_H_

#include "kweb_image.h"

#include <cstdint>

namespace kwebshell::image {

uint32_t CreateNative(const kweb_image_rgba &input, void **value) noexcept;
uint32_t ReleaseNative(void *value) noexcept;
const char *ProviderId() noexcept;

}  // namespace kwebshell::image

#endif
