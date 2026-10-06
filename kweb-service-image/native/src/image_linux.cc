#include "image_internal.h"

#include <gdk-pixbuf/gdk-pixbuf.h>

#include <cstring>

namespace {
void FreePixels(guchar *pixels, gpointer) { g_free(pixels); }
}  // namespace

namespace kwebshell::image {

const char *ProviderId() { return "linux.GdkPixbuf"; }

uint32_t CreateNative(const kweb_image_rgba &input, void **value) {
  if (value == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  *value = nullptr;
  auto *pixels = static_cast<guchar *>(g_malloc(input.size));
  if (pixels == nullptr) return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  std::memcpy(pixels, input.bytes, input.size);
  GdkPixbuf *pixbuf = gdk_pixbuf_new_from_data(
      pixels, GDK_COLORSPACE_RGB, TRUE, 8,
      static_cast<int>(input.width), static_cast<int>(input.height),
      static_cast<int>(input.width * 4), FreePixels, nullptr);
  if (pixbuf == nullptr) {
    g_free(pixels);
    return KWEB_IMAGE_STATUS_NATIVE_FAILED;
  }
  *value = pixbuf;
  return KWEB_IMAGE_STATUS_OK;
}

uint32_t ReleaseNative(void *value) {
  if (value == nullptr) return KWEB_IMAGE_STATUS_INVALID_ARGUMENT;
  g_object_unref(static_cast<GObject *>(value));
  return KWEB_IMAGE_STATUS_OK;
}

}  // namespace kwebshell::image
