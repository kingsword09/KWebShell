#ifndef KWEBSHELL_NATIVE_UTF8_VALIDATION_H_
#define KWEBSHELL_NATIVE_UTF8_VALIDATION_H_

#include <cstddef>

namespace kwebshell {

bool IsValidUtf8(const char *text, size_t size);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_UTF8_VALIDATION_H_
