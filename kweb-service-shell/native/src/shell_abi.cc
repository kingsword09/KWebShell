#include "kweb_shell.h"
#include "shell_platform.h"

#include <cstring>
#include <string>

namespace {
bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr || size == 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    if (first <= 0x7f) { ++index; continue; }
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first >= 0xc2 && first <= 0xdf) continuation = 1;
    else if (first == 0xe0) { continuation = 2; second_min = 0xa0; }
    else if (first >= 0xe1 && first <= 0xec) continuation = 2;
    else if (first == 0xed) { continuation = 2; second_max = 0x9f; }
    else if (first >= 0xee && first <= 0xef) continuation = 2;
    else if (first == 0xf0) { continuation = 3; second_min = 0x90; }
    else if (first >= 0xf1 && first <= 0xf3) continuation = 3;
    else if (first == 0xf4) { continuation = 3; second_max = 0x8f; }
    else return false;
    if (index + continuation >= size) return false;
    const unsigned char second = data[index + 1];
    if (second < second_min || second > second_max) return false;
    for (size_t offset = 2; offset <= continuation; ++offset) {
      const unsigned char value = data[index + offset];
      if (value < 0x80 || value > 0xbf) return false;
    }
    index += continuation + 1;
  }
  return true;
}
void Reset(kweb_shell_result *result) {
  result->struct_size = sizeof(kweb_shell_result);
  result->abi_version = KWEB_SHELL_ABI_VERSION;
  result->outcome = KWEB_SHELL_OUTCOME_NONE;
  result->resource_kind = KWEB_SHELL_RESOURCE_NONE;
  result->reserved = 0;
}
bool ValidAction(uint32_t action) {
  return action >= KWEB_SHELL_ACTION_OPEN_EXTERNAL &&
         action <= KWEB_SHELL_ACTION_TRASH_RESOURCE;
}
bool ValidKind(uint32_t action, uint32_t kind) {
  return action == KWEB_SHELL_ACTION_OPEN_EXTERNAL
      ? kind == KWEB_SHELL_RESOURCE_NONE
      : kind == KWEB_SHELL_RESOURCE_FILE || kind == KWEB_SHELL_RESOURCE_DIRECTORY;
}
}  // namespace

extern "C" {
uint32_t KWEB_SHELL_CALL kweb_shell_abi_version(void) {
  return KWEB_SHELL_ABI_VERSION;
}
const char *KWEB_SHELL_CALL kweb_shell_status_name(kweb_shell_status status) {
  switch (status) {
    case KWEB_SHELL_STATUS_OK: return "ok";
    case KWEB_SHELL_STATUS_INVALID_ARGUMENT: return "invalid-argument";
    case KWEB_SHELL_STATUS_ABI_MISMATCH: return "abi-mismatch";
    case KWEB_SHELL_STATUS_NATIVE_UNAVAILABLE: return "native-unavailable";
    case KWEB_SHELL_STATUS_HANDLER_REJECTED: return "handler-rejected";
    case KWEB_SHELL_STATUS_REVEAL_UNAVAILABLE: return "reveal-unavailable";
    case KWEB_SHELL_STATUS_TRASH_FAILED: return "trash-failed";
    case KWEB_SHELL_STATUS_TRASH_VERIFICATION_FAILED: return "trash-verification-failed";
    case KWEB_SHELL_STATUS_NATIVE_FAILED: return "native-failed";
    default: return "unknown";
  }
}
const char *KWEB_SHELL_CALL kweb_shell_provider_id(void) {
  return kwebshell::shell::ProviderId();
}
kweb_shell_status KWEB_SHELL_CALL
kweb_shell_execute(const kweb_shell_request *request, kweb_shell_result *result) {
  if (result == nullptr) return KWEB_SHELL_STATUS_INVALID_ARGUMENT;
  Reset(result);
  if (request == nullptr || request->struct_size < sizeof(kweb_shell_request) ||
      request->abi_version != KWEB_SHELL_ABI_VERSION || request->reserved != 0) {
    return request == nullptr || request->abi_version != KWEB_SHELL_ABI_VERSION
        ? KWEB_SHELL_STATUS_ABI_MISMATCH : KWEB_SHELL_STATUS_INVALID_ARGUMENT;
  }
  if (!ValidAction(request->action) || !ValidKind(request->action, request->resource_kind) ||
      request->value.data == nullptr || request->value.size == 0 ||
      request->value.size > 8192 ||
      std::memchr(request->value.data, 0, request->value.size) != nullptr ||
      !IsUtf8(request->value.data, request->value.size)) {
    return KWEB_SHELL_STATUS_INVALID_ARGUMENT;
  }
  try {
    const std::string value(reinterpret_cast<const char *>(request->value.data), request->value.size);
    uint32_t outcome = KWEB_SHELL_OUTCOME_NONE;
    const kweb_shell_status status = kwebshell::shell::ExecutePlatform(
        request->action, request->resource_kind, value, &outcome);
    if (status == KWEB_SHELL_STATUS_OK) {
      result->outcome = outcome;
      result->resource_kind = request->resource_kind;
    }
    return status;
  } catch (...) {
    Reset(result);
    return KWEB_SHELL_STATUS_NATIVE_FAILED;
  }
}
uint32_t KWEB_SHELL_CALL kweb_shell_live_count(void) { return 0; }
}  // extern "C"
