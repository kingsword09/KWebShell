#include "jni_string.h"

#include <cstdint>
#include <utility>

namespace kwebshell::jni {
namespace {

bool IsHighSurrogate(uint32_t value) {
  return value >= 0xD800U && value <= 0xDBFFU;
}

bool IsLowSurrogate(uint32_t value) {
  return value >= 0xDC00U && value <= 0xDFFFU;
}

void AppendUtf8(uint32_t code_point, std::string *output) {
  if (code_point <= 0x7FU) {
    output->push_back(static_cast<char>(code_point));
  } else if (code_point <= 0x7FFU) {
    output->push_back(static_cast<char>(0xC0U | (code_point >> 6U)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  } else if (code_point <= 0xFFFFU) {
    output->push_back(static_cast<char>(0xE0U | (code_point >> 12U)));
    output->push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  } else {
    output->push_back(static_cast<char>(0xF0U | (code_point >> 18U)));
    output->push_back(static_cast<char>(0x80U | ((code_point >> 12U) & 0x3FU)));
    output->push_back(static_cast<char>(0x80U | ((code_point >> 6U) & 0x3FU)));
    output->push_back(static_cast<char>(0x80U | (code_point & 0x3FU)));
  }
}

bool IsContinuationByte(uint8_t value) { return (value & 0xC0U) == 0x80U; }

} // namespace

std::optional<std::string> JavaStringToUtf8(JNIEnv *env, jstring value) {
  if (value == nullptr) {
    return std::nullopt;
  }
  const jsize length = env->GetStringLength(value);
  const jchar *characters = env->GetStringChars(value, nullptr);
  if (characters == nullptr) {
    if (env->ExceptionCheck()) {
      env->ExceptionClear();
    }
    return std::nullopt;
  }

  std::optional<std::string> result;
  try {
    std::string utf8;
    utf8.reserve(static_cast<size_t>(length) * 3U);
    bool valid = true;
    for (jsize index = 0; index < length && valid; ++index) {
      uint32_t code_point = characters[index];
      if (IsHighSurrogate(code_point)) {
        if (index + 1 >= length || !IsLowSurrogate(characters[index + 1])) {
          valid = false;
          break;
        }
        const uint32_t low = characters[++index];
        code_point =
            0x10000U + ((code_point - 0xD800U) << 10U) + (low - 0xDC00U);
      } else if (IsLowSurrogate(code_point) || code_point == 0) {
        valid = false;
        break;
      }
      AppendUtf8(code_point, &utf8);
    }
    if (valid) {
      result = std::move(utf8);
    }
  } catch (...) {
    env->ReleaseStringChars(value, characters);
    throw;
  }
  env->ReleaseStringChars(value, characters);
  return result;
}

std::optional<std::vector<jchar>> Utf8ToJavaCharacters(std::string_view utf8) {
  std::vector<jchar> output;
  output.reserve(utf8.size());
  size_t index = 0;
  while (index < utf8.size()) {
    const auto first = static_cast<uint8_t>(utf8[index]);
    uint32_t code_point = 0;
    size_t length = 0;
    if (first <= 0x7FU && first != 0) {
      code_point = first;
      length = 1;
    } else if (first >= 0xC2U && first <= 0xDFU && index + 1 < utf8.size() &&
               IsContinuationByte(static_cast<uint8_t>(utf8[index + 1]))) {
      code_point = ((first & 0x1FU) << 6U) |
                   (static_cast<uint8_t>(utf8[index + 1]) & 0x3FU);
      length = 2;
    } else if (first >= 0xE0U && first <= 0xEFU && index + 2 < utf8.size()) {
      const auto second = static_cast<uint8_t>(utf8[index + 1]);
      const auto third = static_cast<uint8_t>(utf8[index + 2]);
      if (!IsContinuationByte(second) || !IsContinuationByte(third) ||
          (first == 0xE0U && second < 0xA0U) ||
          (first == 0xEDU && second > 0x9FU)) {
        return std::nullopt;
      }
      code_point =
          ((first & 0x0FU) << 12U) | ((second & 0x3FU) << 6U) | (third & 0x3FU);
      length = 3;
    } else if (first >= 0xF0U && first <= 0xF4U && index + 3 < utf8.size()) {
      const auto second = static_cast<uint8_t>(utf8[index + 1]);
      const auto third = static_cast<uint8_t>(utf8[index + 2]);
      const auto fourth = static_cast<uint8_t>(utf8[index + 3]);
      if (!IsContinuationByte(second) || !IsContinuationByte(third) ||
          !IsContinuationByte(fourth) || (first == 0xF0U && second < 0x90U) ||
          (first == 0xF4U && second > 0x8FU)) {
        return std::nullopt;
      }
      code_point = ((first & 0x07U) << 18U) | ((second & 0x3FU) << 12U) |
                   ((third & 0x3FU) << 6U) | (fourth & 0x3FU);
      length = 4;
    } else {
      return std::nullopt;
    }

    if (code_point <= 0xFFFFU) {
      output.push_back(static_cast<jchar>(code_point));
    } else {
      const uint32_t adjusted = code_point - 0x10000U;
      output.push_back(static_cast<jchar>(0xD800U + (adjusted >> 10U)));
      output.push_back(static_cast<jchar>(0xDC00U + (adjusted & 0x3FFU)));
    }
    index += length;
  }
  return output;
}

} // namespace kwebshell::jni
