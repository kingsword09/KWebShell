#include "secure_storage_platform_test.h"

#if defined(_WIN32)

#include "secure_storage_tests_fixture.h"

#include <cstring>
#include <string>

/** The planted test secret every redaction check searches for. */
namespace {

constexpr char kPlantedSecret[] = "kweb-secret-planted-value-42";

}  // namespace

/** The real Credential Manager round trip and its typed facts. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  kweb_secure_storage_configuration configuration = fixture.Configuration();
  const kweb_secure_storage_status opened = kweb_secure_storage_open(&configuration, &handle);
  if (opened != KWEB_SECURE_STORAGE_STATUS_OK) {
    std::printf("SKIP: the Windows credential store is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_secure_storage_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "secure-storage.windows.credential-manager") == 0);
  KWEB_CHECK(capabilities.backend == KWEB_SECRET_BACKEND_WINDOWS_CREDENTIAL_MANAGER);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ROTATION) != 0);
  // The Credential Manager publishes no accessibility policy and no user presence.
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY) == 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_USER_PRESENCE) == 0);
  KWEB_CHECK(capabilities.accessibility_bits == 0);

  const std::vector<std::pair<std::string, uint32_t>> keys = {
      {"win-secret", KWEB_SECRET_SCOPE_APPLICATION},
  };
  fixture.Cleanup(handle, keys);

  auto reference = fixture.Reference("win-secret");
  kweb_secure_storage_metadata metadata{};
  const kweb_secure_storage_status stored =
      fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata);
  if (stored != KWEB_SECURE_STORAGE_STATUS_OK) {
    // A session without the declared credential facility is the typed boundary.
    KWEB_CHECK(stored == KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE ||
               stored == KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_DENIED);
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return g_failures - failures_before;
  }

  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  bool present = false;
  KWEB_CHECK(fixture.Exists(handle, reference, &present) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(present);

  // The accessibility policy is recorded, not claimed, and user presence is a
  // hard typed refusal because the backend cannot require it.
  KWEB_CHECK(fixture.Store(
                 handle,
                 fixture.Put(reference, kPlantedSecret, KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK),
                 &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.accessibility == KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK);
  KWEB_CHECK(fixture.Store(handle, fixture.Put(reference, kPlantedSecret, KWEB_SECRET_ACCESS_WHEN_UNLOCKED, 1),
                           &metadata) == KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_UNSUPPORTED);

  // Rotation rewrites the record and keeps the secret.
  KWEB_CHECK(fixture.Rotate(handle, reference, &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  uint32_t rotation_status = 0;
  uint32_t generation = 0;
  KWEB_CHECK(fixture.RotationStatus(handle, reference, &rotation_status, &generation) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(rotation_status == KWEB_SECRET_ROTATION_CURRENT);

  bool removed = false;
  KWEB_CHECK(fixture.Remove(handle, reference, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  fixture.Cleanup(handle, keys);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
  return g_failures - failures_before;
}

#endif
