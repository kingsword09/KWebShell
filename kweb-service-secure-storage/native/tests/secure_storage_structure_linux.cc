#include "secure_storage_platform_test.h"

#if defined(__linux__)

#include <gio/gio.h>

#include "secure_storage_tests_fixture.h"

#include <cstdlib>
#include <cstring>
#include <string>

namespace {

/** The planted test secret every redaction check searches for. */
constexpr char kPlantedSecret[] = "kweb-secret-planted-value-42";

/** True when this session declares a Secret Service on its own bus. */
bool SecretServiceAvailable() {
  const char *session = g_getenv("DBUS_SESSION_BUS_ADDRESS");
  return session != nullptr && session[0] != '\0';
}

}  // namespace

/** The real Secret Service round trip, or its typed unavailability boundary. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  kweb_secure_storage_configuration configuration = fixture.Configuration();
  const kweb_secure_storage_status opened = kweb_secure_storage_open(&configuration, &handle);
  if (opened != KWEB_SECURE_STORAGE_STATUS_OK) {
    std::printf("SKIP: the Linux secret store is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_secure_storage_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "secure-storage.linux.secret-service") == 0);
  KWEB_CHECK(capabilities.backend == KWEB_SECRET_BACKEND_LINUX_SECRET_SERVICE);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ROTATION) != 0);
  // The Secret Service publishes no accessibility policy and no user presence.
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY) == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_USER_PRESENCE) == 0);
  KWEB_CHECK(capabilities.accessibility_bits == 0);

  const std::vector<std::pair<std::string, uint32_t>> keys = {
      {"linux-secret", KWEB_SECRET_SCOPE_APPLICATION},
  };
  fixture.Cleanup(handle, keys);

  auto reference = fixture.Reference("linux-secret");
  kweb_secure_storage_metadata metadata{};
  const kweb_secure_storage_status stored =
      fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata);

  if (!SecretServiceAvailable()) {
    // No session bus: the provider must fail typed instead of writing a file.
    KWEB_CHECK(stored == KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE ||
               stored == KWEB_SECURE_STORAGE_STATUS_LOCKED ||
               stored == KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED);
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return g_failures - failures_before;
  }

  if (stored != KWEB_SECURE_STORAGE_STATUS_OK) {
    // A session bus without a Secret Service is the declared typed boundary.
    KWEB_CHECK(stored == KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE ||
               stored == KWEB_SECURE_STORAGE_STATUS_LOCKED);
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return g_failures - failures_before;
  }

  // The real Secret Service round trip.
  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  bool present = false;
  KWEB_CHECK(fixture.Exists(handle, reference, &present) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(present);

  // The accessibility policy is a typed absence on this backend: the capability
  // report publishes none, and the declared policy is recorded in the envelope
  // instead of being claimed as enforced.
  auto policy = fixture.Reference("linux-policy");
  KWEB_CHECK(fixture.Store(
                 handle,
                 fixture.Put(policy, kPlantedSecret, KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY),
                 &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.accessibility == KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY);
  // User presence is a hard typed refusal because the backend cannot require it.
  KWEB_CHECK(fixture.Store(handle, fixture.Put(policy, kPlantedSecret, KWEB_SECRET_ACCESS_WHEN_UNLOCKED, 1),
                           &metadata) == KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_UNSUPPORTED);
  bool removed = false;
  KWEB_CHECK(fixture.Remove(handle, policy, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);

  // Rotation rewrites the record through the same backend and keeps the value.
  KWEB_CHECK(fixture.Rotate(handle, reference, &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.generation == 2);
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);

  KWEB_CHECK(fixture.Remove(handle, reference, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  fixture.Cleanup(handle, keys);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
  return g_failures - failures_before;
}

#endif
