#include "utf8_validation.h"

#include <cstdint>

namespace kwebshell {
namespace {

bool IsContinuationByte(uint8_t value) { return (value & 0xC0U) == 0x80U; }

} // namespace

bool IsValidUtf8(const char *text, size_t size) {
  if (text == nullptr) {
    return false;
  }
  size_t index = 0;
  while (index < size) {
    const auto first = static_cast<uint8_t>(text[index]);
    if (first == 0) {
      return false;
    }
    if (first <= 0x7FU) {
      ++index;
      continue;
    }
    if (first >= 0xC2U && first <= 0xDFU) {
      if (index + 1 >= size ||
          !IsContinuationByte(static_cast<uint8_t>(text[index + 1]))) {
        return false;
      }
      index += 2;
      continue;
    }
    if (first >= 0xE0U && first <= 0xEFU) {
      if (index + 2 >= size) {
        return false;
      }
      const auto second = static_cast<uint8_t>(text[index + 1]);
      const auto third = static_cast<uint8_t>(text[index + 2]);
      const bool second_valid = IsContinuationByte(second) &&
                                !(first == 0xE0U && second < 0xA0U) &&
                                !(first == 0xEDU && second > 0x9FU);
      if (!second_valid || !IsContinuationByte(third)) {
        return false;
      }
      index += 3;
      continue;
    }
    if (first >= 0xF0U && first <= 0xF4U) {
      if (index + 3 >= size) {
        return false;
      }
      const auto second = static_cast<uint8_t>(text[index + 1]);
      const auto third = static_cast<uint8_t>(text[index + 2]);
      const auto fourth = static_cast<uint8_t>(text[index + 3]);
      const bool second_valid = IsContinuationByte(second) &&
                                !(first == 0xF0U && second < 0x90U) &&
                                !(first == 0xF4U && second > 0x8FU);
      if (!second_valid || !IsContinuationByte(third) ||
          !IsContinuationByte(fourth)) {
        return false;
      }
      index += 4;
      continue;
    }
    return false;
  }
  return true;
}

} // namespace kwebshell
