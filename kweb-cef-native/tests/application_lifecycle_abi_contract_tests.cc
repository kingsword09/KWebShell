#include "kwebshell/native/application_lifecycle_abi.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace {

struct CallbackState {
  std::mutex mutex;
  std::condition_variable condition;
  std::vector<uint8_t> payload;
};

void Activation(void *user_data, const uint8_t *payload, size_t payload_size) {
  auto *state = static_cast<CallbackState *>(user_data);
  {
    std::lock_guard lock(state->mutex);
    state->payload.assign(payload, payload + payload_size);
  }
  state->condition.notify_all();
}

std::filesystem::path TestRoot() {
  return std::filesystem::temp_directory_path() /
         "kweb-application-lifecycle-abi-contract";
}

}  // namespace

int main() {
  if (kweb_application_lifecycle_abi_version() !=
      KWEB_APPLICATION_LIFECYCLE_ABI_VERSION) {
    return 1;
  }
  const std::string provider = kweb_application_lifecycle_provider_id();
#if defined(_WIN32)
  if (provider != "windows-named-mutex-authenticated-pipe") return 9;
#elif defined(__APPLE__)
  if (provider != "macos-appkit-launch-services") return 9;
#elif defined(KWEB_HAS_GDBUS)
  if (provider != "linux-dbus-freedesktop-application") return 9;
#else
  if (provider != "unsupported-platform") return 9;
#endif
  const std::string application_id = "io.github.kwebshell.lifecycle.contract";
  char digest[128] = {};
  if (kweb_application_lifecycle_register_associations(
          application_id.data(), application_id.size(), nullptr, 0,
          application_id.data(), application_id.size(), "kweb", 4, ".kweb", 5,
          0, digest, sizeof(digest)) !=
      KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT) {
    return 10;
  }
  const std::filesystem::path root = TestRoot();
  std::error_code error;
  std::filesystem::remove_all(root, error);
  std::filesystem::create_directories(root, error);
  if (error) return 2;

  const std::string transport_root = root.string();
  const std::vector<uint8_t> initial = {'i', 'n', 'i', 't', 'i', 'a', 'l'};
  CallbackState callback;
  kweb_application_lifecycle_handle primary = 0;
  const int32_t primary_status = kweb_application_lifecycle_acquire(
      application_id.data(), application_id.size(), transport_root.data(),
      transport_root.size(), initial.data(), initial.size(), Activation,
      &callback, &primary);
  if (primary_status != KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY || primary == 0) {
    std::fprintf(stderr, "primary acquire failed: status=%d handle=%llu\n",
                 primary_status, static_cast<unsigned long long>(primary));
    return 3;
  }

  const std::vector<uint8_t> forwarded = {'s', 'e', 'c', 'o', 'n', 'd'};
  kweb_application_lifecycle_handle secondary = 0;
  const int32_t secondary_status = kweb_application_lifecycle_acquire(
      application_id.data(), application_id.size(), transport_root.data(),
      transport_root.size(), forwarded.data(), forwarded.size(), Activation,
      &callback, &secondary);
  if (secondary_status != KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY || secondary != 0) {
    std::fprintf(stderr, "secondary acquire failed: status=%d handle=%llu\n",
                 secondary_status, static_cast<unsigned long long>(secondary));
    kweb_application_lifecycle_release(primary);
    return 4;
  }

  {
    std::unique_lock lock(callback.mutex);
    if (!callback.condition.wait_for(lock, std::chrono::seconds(2), [&callback] {
          return callback.payload == std::vector<uint8_t>{'s', 'e', 'c', 'o', 'n', 'd'};
        })) {
      std::fprintf(stderr, "activation callback timed out after secondary acquire\n");
      kweb_application_lifecycle_release(primary);
      return 5;
    }
  }

  if (kweb_application_lifecycle_live_count() != 1) {
    kweb_application_lifecycle_release(primary);
    return 6;
  }
  if (kweb_application_lifecycle_release(primary) !=
      KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY) {
    return 7;
  }
  std::filesystem::remove_all(root, error);
  return error ? 8 : 0;
}
