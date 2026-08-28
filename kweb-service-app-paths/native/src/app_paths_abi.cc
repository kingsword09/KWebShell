#include "kwebshell/services/app_paths_abi.h"

#include "app_paths_platform.h"

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <limits>
#include <new>
#include <optional>
#include <string>

namespace {

constexpr size_t kMaximumInputBytes = 16 * 1024;

bool IsValidUtf8(const char *data, size_t size) {
  if (data == nullptr) {
    return false;
  }
  size_t index = 0;
  while (index < size) {
    const unsigned char first = static_cast<unsigned char>(data[index]);
    if (first <= 0x7f) {
      ++index;
      continue;
    }
    size_t continuation_count = 0;
    unsigned char second_minimum = 0x80;
    unsigned char second_maximum = 0xbf;
    if (first >= 0xc2 && first <= 0xdf) {
      continuation_count = 1;
    } else if (first == 0xe0) {
      continuation_count = 2;
      second_minimum = 0xa0;
    } else if (first >= 0xe1 && first <= 0xec) {
      continuation_count = 2;
    } else if (first == 0xed) {
      continuation_count = 2;
      second_maximum = 0x9f;
    } else if (first >= 0xee && first <= 0xef) {
      continuation_count = 2;
    } else if (first == 0xf0) {
      continuation_count = 3;
      second_minimum = 0x90;
    } else if (first >= 0xf1 && first <= 0xf3) {
      continuation_count = 3;
    } else if (first == 0xf4) {
      continuation_count = 3;
      second_maximum = 0x8f;
    } else {
      return false;
    }
    if (index + continuation_count >= size) {
      return false;
    }
    const unsigned char second = static_cast<unsigned char>(data[index + 1]);
    if (second < second_minimum || second > second_maximum) {
      return false;
    }
    for (size_t offset = 2; offset <= continuation_count; ++offset) {
      const unsigned char continuation =
          static_cast<unsigned char>(data[index + offset]);
      if (continuation < 0x80 || continuation > 0xbf) {
        return false;
      }
    }
    index += continuation_count + 1;
  }
  return true;
}

bool IsKnownKind(uint32_t kind) {
  return kind >= KWEB_APP_PATH_HOME && kind <= KWEB_APP_PATH_VIDEOS;
}

bool IsValidApplicationId(const std::string &value) {
  if (value.empty() || value.size() > 127 || value.front() == '.' ||
      value.back() == '.') {
    return false;
  }
  size_t label_start = 0;
  while (label_start < value.size()) {
    const size_t label_end = value.find('.', label_start);
    const size_t end =
        label_end == std::string::npos ? value.size() : label_end;
    const size_t length = end - label_start;
    const auto is_lower_ascii = [](unsigned char character) {
      return character >= 'a' && character <= 'z';
    };
    const auto is_alnum_ascii = [&](unsigned char character) {
      return is_lower_ascii(character) ||
             (character >= '0' && character <= '9');
    };
    if (length == 0 || length > 63 ||
        !is_lower_ascii(static_cast<unsigned char>(value[label_start])) ||
        !is_alnum_ascii(static_cast<unsigned char>(value[end - 1]))) {
      return false;
    }
    for (size_t index = label_start; index < end; ++index) {
      const unsigned char character = static_cast<unsigned char>(value[index]);
      if (!(is_lower_ascii(character) ||
            (character >= '0' && character <= '9') || character == '-')) {
        return false;
      }
    }
    if (label_end == std::string::npos) {
      break;
    }
    label_start = label_end + 1;
  }
  return value.find('.') != std::string::npos;
}

std::optional<std::string> CopyStringView(kweb_services_string_view view) {
  if (view.data == nullptr || view.size == 0 ||
      view.size > kMaximumInputBytes) {
    return std::nullopt;
  }
  if (std::memchr(view.data, '\0', view.size) != nullptr) {
    return std::nullopt;
  }
  if (!IsValidUtf8(view.data, view.size)) {
    return std::nullopt;
  }
  return std::string(view.data, view.size);
}

bool IsAbsolutePath(const std::filesystem::path &path) {
#if defined(_WIN32)
  return path.has_root_name() && path.has_root_directory();
#else
  return path.is_absolute();
#endif
}

std::optional<std::filesystem::path>
CanonicalizeWithoutCreating(const std::filesystem::path &requested) {
  if (!IsAbsolutePath(requested)) {
    return std::nullopt;
  }
  std::filesystem::path current = requested.lexically_normal();
  std::filesystem::path suffix;
  std::error_code error;
  while (!std::filesystem::exists(current, error)) {
    if (error) {
      return std::nullopt;
    }
    const std::filesystem::path parent = current.parent_path();
    if (parent == current || parent.empty()) {
      return std::nullopt;
    }
    suffix = current.filename() / suffix;
    current = parent;
  }
  const std::filesystem::path canonical =
      std::filesystem::weakly_canonical(current, error);
  if (error || canonical.empty()) {
    return std::nullopt;
  }
  return (canonical / suffix).lexically_normal();
}

bool AssignOwnedString(const std::string &value,
                       kweb_services_string_view *output) {
  if (output == nullptr || value.empty() ||
      value.size() > std::numeric_limits<size_t>::max() - 1) {
    return false;
  }
  char *memory = static_cast<char *>(std::malloc(value.size() + 1));
  if (memory == nullptr) {
    return false;
  }
  std::memcpy(memory, value.data(), value.size());
  memory[value.size()] = '\0';
  output->data = memory;
  output->size = value.size();
  return true;
}

void ResetResult(kweb_app_paths_result *result) {
  if (result == nullptr) {
    return;
  }
  result->struct_size = sizeof(kweb_app_paths_result);
  result->abi_version = KWEB_SERVICES_ABI_VERSION;
  result->kind = 0;
  result->reserved = 0;
  result->path = {nullptr, 0};
  result->source = {nullptr, 0};
}

} // namespace

namespace kwebshell::services {

uint32_t kweb_services_abi_version() { return KWEB_SERVICES_ABI_VERSION; }

const char *kweb_services_status_name(kweb_services_status status) {
  switch (status) {
  case KWEB_SERVICES_STATUS_OK:
    return "ok";
  case KWEB_SERVICES_STATUS_INVALID_ARGUMENT:
    return "invalid-argument";
  case KWEB_SERVICES_STATUS_ABI_MISMATCH:
    return "abi-mismatch";
  case KWEB_SERVICES_STATUS_ALLOCATION_FAILED:
    return "allocation-failed";
  case KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN:
    return "path-kind-unknown";
  case KWEB_SERVICES_STATUS_NATIVE_UNAVAILABLE:
    return "native-unavailable";
  case KWEB_SERVICES_STATUS_PATH_INVALID:
    return "path-invalid";
  case KWEB_SERVICES_STATUS_NATIVE_FAILED:
    return "native-failed";
  default:
    return "unknown";
  }
}

kweb_services_status
kweb_app_paths_resolve(const kweb_app_paths_request *request,
                       kweb_app_paths_result *result) {
  if (result == nullptr) {
    return KWEB_SERVICES_STATUS_INVALID_ARGUMENT;
  }
  ResetResult(result);
  try {
    if (request == nullptr ||
        request->struct_size < sizeof(kweb_app_paths_request) ||
        request->abi_version != KWEB_SERVICES_ABI_VERSION ||
        request->reserved != 0) {
      return request == nullptr ||
                     request->abi_version != KWEB_SERVICES_ABI_VERSION
                 ? KWEB_SERVICES_STATUS_ABI_MISMATCH
                 : KWEB_SERVICES_STATUS_INVALID_ARGUMENT;
    }
    if (!IsKnownKind(request->kind)) {
      return KWEB_SERVICES_STATUS_PATH_KIND_UNKNOWN;
    }
    const auto application_id = CopyStringView(request->application_id);
    const auto application_data_root =
        CopyStringView(request->application_data_root);
    const auto session_data_root = CopyStringView(request->session_data_root);
    if (!application_id || !application_data_root || !session_data_root ||
        !IsValidApplicationId(*application_id)) {
      return KWEB_SERVICES_STATUS_INVALID_ARGUMENT;
    }

    PlatformPathResult platform;
    uint32_t platform_status = KWEB_SERVICES_STATUS_NATIVE_FAILED;
    if (request->kind == KWEB_APP_PATH_USER_DATA ||
        request->kind == KWEB_APP_PATH_SESSION_DATA) {
      const std::filesystem::path configured = PathFromUtf8(
          request->kind == KWEB_APP_PATH_USER_DATA ? *application_data_root
                                                   : *session_data_root);
      const auto canonical = CanonicalizeWithoutCreating(configured);
      if (!canonical) {
        return KWEB_SERVICES_STATUS_PATH_INVALID;
      }
      platform.path = *canonical;
      platform.source = request->kind == KWEB_APP_PATH_USER_DATA
                            ? "kweb.config.application-data-root"
                            : "kweb.config.session-data-root";
    } else if (!ResolvePlatformPath(request->kind, &platform,
                                    &platform_status)) {
      return platform_status;
    }

    const auto canonical = CanonicalizeWithoutCreating(platform.path);
    if (!canonical) {
      return KWEB_SERVICES_STATUS_PATH_INVALID;
    }
    const auto path_utf8 = canonical->u8string();
    const std::string path(reinterpret_cast<const char *>(path_utf8.data()),
                           path_utf8.size());
    if (!AssignOwnedString(path, &result->path) ||
        !AssignOwnedString(platform.source, &result->source)) {
      kweb_app_paths_result_free(result);
      return KWEB_SERVICES_STATUS_ALLOCATION_FAILED;
    }
    result->kind = request->kind;
    return KWEB_SERVICES_STATUS_OK;
  } catch (const std::bad_alloc &) {
    kweb_app_paths_result_free(result);
    return KWEB_SERVICES_STATUS_ALLOCATION_FAILED;
  } catch (...) {
    kweb_app_paths_result_free(result);
    return KWEB_SERVICES_STATUS_NATIVE_FAILED;
  }
}

void kweb_app_paths_result_free(kweb_app_paths_result *result) {
  if (result == nullptr) {
    return;
  }
  std::free(const_cast<char *>(result->path.data));
  std::free(const_cast<char *>(result->source.data));
  ResetResult(result);
}

} // namespace kwebshell::services

extern "C" uint32_t KWEB_SERVICES_ABI_CALL kweb_services_abi_version(void) {
  return kwebshell::services::kweb_services_abi_version();
}

extern "C" const char *KWEB_SERVICES_ABI_CALL
kweb_services_status_name(kweb_services_status status) {
  return kwebshell::services::kweb_services_status_name(status);
}

extern "C" kweb_services_status KWEB_SERVICES_ABI_CALL kweb_app_paths_resolve(
    const kweb_app_paths_request *request, kweb_app_paths_result *result) {
  return kwebshell::services::kweb_app_paths_resolve(request, result);
}

extern "C" void KWEB_SERVICES_ABI_CALL
kweb_app_paths_result_free(kweb_app_paths_result *result) {
  kwebshell::services::kweb_app_paths_result_free(result);
}
