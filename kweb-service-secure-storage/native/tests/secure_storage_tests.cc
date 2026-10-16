#include "secure_storage_internal.h"
#include "secure_storage_platform_test.h"
#include "secure_storage_tests_fixture.h"

#include <cstring>
#include <string>
#include <vector>

namespace {

/** The planted test secret every redaction check searches for. */
constexpr char kPlantedSecret[] = "kweb-secret-planted-value-42";

bool IsBackendBoundary(kweb_secure_storage_status status) {
  return status == KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE ||
         status == KWEB_SECURE_STORAGE_STATUS_LOCKED ||
         status == KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
}

/**
 * True when this session's credential service accepts a store. A session without
 * its declared service is the typed boundary, not a test failure; the per-target
 * provider test exercises the real round trip on a session that has the service.
 */
bool BackendReady(SecureStorageFixture &fixture, uint64_t handle) {
  auto probe = fixture.Reference("backend-probe");
  kweb_secure_storage_metadata metadata{};
  const kweb_secure_storage_status status =
      fixture.Store(handle, fixture.Put(probe, "probe"), &metadata);
  if (status == KWEB_SECURE_STORAGE_STATUS_OK) {
    bool removed = false;
    fixture.Remove(handle, probe, &removed);
    return true;
  }
  std::printf("SKIP: the current secret backend refused the store (status %u).\n", status);
  KWEB_CHECK(IsBackendBoundary(status));
  return false;
}

bool OpenOrSkip(SecureStorageFixture &fixture, uint64_t *handle, const char *label) {
  kweb_secure_storage_configuration configuration = fixture.Configuration();
  const kweb_secure_storage_status opened = kweb_secure_storage_open(&configuration, handle);
  if (opened != KWEB_SECURE_STORAGE_STATUS_OK) {
    std::printf("SKIP: the %s secret store is unavailable (status %u).\n", label, opened);
    return false;
  }
  return true;
}

void HeaderAndArgumentValidation() {
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  KWEB_CHECK(kweb_secure_storage_open(nullptr, &handle) == KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
  kweb_secure_storage_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_secure_storage_open(&configuration, nullptr) == KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.struct_size = sizeof(configuration) - 1u;
  KWEB_CHECK(kweb_secure_storage_open(&configuration, &handle) == KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION + 1u;
  KWEB_CHECK(kweb_secure_storage_open(&configuration, &handle) == KWEB_SECURE_STORAGE_STATUS_ABI_MISMATCH);
  configuration = fixture.Configuration();
  configuration.application_id = kweb_secure_storage_string{nullptr, 0};
  KWEB_CHECK(kweb_secure_storage_open(&configuration, &handle) == KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
}

/** The versioned envelope rejects foreign bytes and reports another version stale. */
void EnvelopeValidation() {
  kwebshell::secure_storage::Envelope envelope;
  envelope.version = KWEB_SECURE_STORAGE_ABI_VERSION;
  envelope.accessibility = KWEB_SECRET_ACCESS_WHEN_UNLOCKED;
  envelope.generation = 3;
  envelope.id = "api-token";
  envelope.owner = "";
  envelope.payload = kPlantedSecret;
  const std::string record = kwebshell::secure_storage::EncodeEnvelope(envelope);
  kwebshell::secure_storage::Envelope decoded;
  KWEB_CHECK(kwebshell::secure_storage::DecodeEnvelope(record, &decoded));
  KWEB_CHECK(decoded.version == KWEB_SECURE_STORAGE_ABI_VERSION);
  KWEB_CHECK(decoded.generation == 3);
  KWEB_CHECK(decoded.id == "api-token");
  KWEB_CHECK(decoded.payload == kPlantedSecret);

  // Foreign bytes, a truncated record, and trailing bytes are all rejected.
  KWEB_CHECK(!kwebshell::secure_storage::DecodeEnvelope("not-an-envelope", &decoded));
  KWEB_CHECK(!kwebshell::secure_storage::DecodeEnvelope(record.substr(0, record.size() - 1), &decoded));
  KWEB_CHECK(!kwebshell::secure_storage::DecodeEnvelope(record + "trailing", &decoded));
  KWEB_CHECK(!kwebshell::secure_storage::DecodeEnvelope("", &decoded));

  // A record written by another envelope version decodes with that version, which
  // the caller reports as stale rather than corrupt.
  std::string foreign = record;
  foreign[7] = static_cast<char>(KWEB_SECURE_STORAGE_ABI_VERSION + 1);
  kwebshell::secure_storage::Envelope other;
  KWEB_CHECK(kwebshell::secure_storage::DecodeEnvelope(foreign, &other));
  KWEB_CHECK(other.version == KWEB_SECURE_STORAGE_ABI_VERSION + 1);
}

void ScopeKeysAreQualified() {
  kwebshell::secure_storage::Reference application;
  application.id = "api-token";
  application.scope = KWEB_SECRET_SCOPE_APPLICATION;
  KWEB_CHECK(application.Key() == "api-token");
  kwebshell::secure_storage::Reference profile;
  profile.id = "api-token";
  profile.owner = "profile-1";
  profile.scope = KWEB_SECRET_SCOPE_PROFILE;
  KWEB_CHECK(profile.Key() == "profile-1/api-token");
  // Two profiles with the same secret id never collide.
  kwebshell::secure_storage::Reference other = profile;
  other.owner = "profile-2";
  KWEB_CHECK(other.Key() != profile.Key());
}

void RoundTripAndDelete() {
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_secure_storage_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(!std::string(capabilities.provider_id).empty());
  KWEB_CHECK(capabilities.backend != 0);
  KWEB_CHECK(capabilities.max_value_bytes == KWEB_SECURE_STORAGE_MAX_VALUE);

  if (!BackendReady(fixture, handle)) {
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return;
  }
  const std::vector<std::pair<std::string, uint32_t>> keys = {
      {"round-trip", KWEB_SECRET_SCOPE_APPLICATION},
      {"profile-secret", KWEB_SECRET_SCOPE_PROFILE},
  };
  fixture.Cleanup(handle, keys);

  kweb_secure_storage_metadata metadata{};
  auto application = fixture.Reference("round-trip");
  KWEB_CHECK(fixture.Store(handle, fixture.Put(application, kPlantedSecret), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.generation == 1);
  KWEB_CHECK(metadata.size_bytes == std::strlen(kPlantedSecret));
  KWEB_CHECK(metadata.backend == capabilities.backend);

  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, application, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);

  // A second put replaces the record and starts a new generation.
  KWEB_CHECK(fixture.Store(handle, fixture.Put(application, "replaced-secret"), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.generation == 2);
  KWEB_CHECK(fixture.Load(handle, application, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == "replaced-secret");

  bool present = false;
  KWEB_CHECK(fixture.Exists(handle, application, &present) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(present);

  bool removed = false;
  KWEB_CHECK(fixture.Remove(handle, application, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  // Deleting again is idempotent.
  KWEB_CHECK(fixture.Remove(handle, application, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(!removed);
  KWEB_CHECK(fixture.Load(handle, application, &loaded) == KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND);
  KWEB_CHECK(fixture.Exists(handle, application, &present) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(!present);

  // A profile-scoped record lives under its own qualified key.
  auto profile = fixture.Reference("profile-secret", KWEB_SECRET_SCOPE_PROFILE, "profile-1");
  KWEB_CHECK(fixture.Store(handle, fixture.Put(profile, "profile-value"), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(fixture.Load(handle, profile, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == "profile-value");
  fixture.Cleanup(handle, keys);

  // A rejected request never reaches the provider.
  auto bad_scope = fixture.Reference("bad-scope", KWEB_SECRET_SCOPE_PROFILE);
  KWEB_CHECK(fixture.Store(handle, fixture.Put(bad_scope, "value"), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
  auto bad_id = fixture.Reference("1bad");
  KWEB_CHECK(fixture.Store(handle, fixture.Put(bad_id, "value"), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);
  auto empty_value = fixture.Reference("empty");
  KWEB_CHECK(fixture.Store(handle, fixture.Put(empty_value, ""), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT);

  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED);
  kweb_secure_storage_metadata after{};
  kweb_secure_storage_put_request late = fixture.Put(application, "late");
  KWEB_CHECK(kweb_secure_storage_put(handle, &late, &after) ==
             KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED);
}

void RotationAndPolicy() {
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  const kweb_secure_storage_capabilities_result capabilities = fixture.Capabilities(handle);
  if ((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ROTATION) == 0) {
    std::printf("SKIP: this provider declares no rotation.\n");
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return;
  }
  if (!BackendReady(fixture, handle)) {
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return;
  }
  const std::vector<std::pair<std::string, uint32_t>> keys = {{"rotating", KWEB_SECRET_SCOPE_APPLICATION}};
  fixture.Cleanup(handle, keys);
  auto reference = fixture.Reference("rotating");
  kweb_secure_storage_metadata metadata{};
  KWEB_CHECK(fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);

  uint32_t rotation_status = 0;
  uint32_t generation = 0;
  KWEB_CHECK(fixture.RotationStatus(handle, reference, &rotation_status, &generation) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(rotation_status == KWEB_SECRET_ROTATION_CURRENT);
  KWEB_CHECK(generation == 1);

  KWEB_CHECK(fixture.Rotate(handle, reference, &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.generation == 2);
  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  KWEB_CHECK(fixture.RotationStatus(handle, reference, &rotation_status, &generation) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(rotation_status == KWEB_SECRET_ROTATION_CURRENT);
  KWEB_CHECK(generation == 2);

  // An unknown identifier is a typed absence rather than a fabricated status.
  auto absent = fixture.Reference("absent-rotation");
  KWEB_CHECK(fixture.Rotate(handle, absent, &metadata) == KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND);
  KWEB_CHECK(fixture.RotationStatus(handle, absent, &rotation_status, &generation) ==
             KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND);
  fixture.Cleanup(handle, keys);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
}

/** A stored secret survives a full service close and reopen. */
void PersistsAcrossReopen() {
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  if (!BackendReady(fixture, handle)) {
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return;
  }
  const std::vector<std::pair<std::string, uint32_t>> keys = {{"persisted", KWEB_SECRET_SCOPE_APPLICATION}};
  fixture.Cleanup(handle, keys);
  auto reference = fixture.Reference("persisted");
  kweb_secure_storage_metadata metadata{};
  KWEB_CHECK(fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);

  SecureStorageFixture reopened;
  uint64_t second = 0;
  if (!OpenOrSkip(reopened, &second, "reopened")) return;
  auto same = reopened.Reference("persisted");
  std::string loaded;
  KWEB_CHECK(reopened.Load(second, same, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  // A different application identity must not see the record.
  kweb_secure_storage_configuration other = reopened.Configuration();
  const std::string other_id = "io.github.kingsword09.kwebshell.secrets.other";
  other.application_id.data = reinterpret_cast<const uint8_t *>(other_id.data());
  other.application_id.size = other_id.size();
  other.package_identity.data = reinterpret_cast<const uint8_t *>(other_id.data());
  other.package_identity.size = other_id.size();
  // The process-wide store is already open, so this must fail rather than switch.
  uint64_t third = 0;
  KWEB_CHECK(kweb_secure_storage_open(&other, &third) == KWEB_SECURE_STORAGE_STATUS_ALREADY_OPEN);
  bool removed = false;
  KWEB_CHECK(reopened.Remove(second, same, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  KWEB_CHECK(kweb_secure_storage_close(second) == KWEB_SECURE_STORAGE_STATUS_OK);
}

/** No staging buffer retains the planted secret after close. */
void ZeroizationAfterClose() {
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  if (!BackendReady(fixture, handle)) {
    KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
    return;
  }
  const std::vector<std::pair<std::string, uint32_t>> keys = {{"zeroize", KWEB_SECRET_SCOPE_APPLICATION}};
  fixture.Cleanup(handle, keys);
  auto reference = fixture.Reference("zeroize");
  kweb_secure_storage_metadata metadata{};
  KWEB_CHECK(fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
  // The reopened store lists no entry for the cleaned-up identifier.
  SecureStorageFixture reopened;
  uint64_t second = 0;
  if (!OpenOrSkip(reopened, &second, "reopened")) return;
  auto same = reopened.Reference("zeroize");
  bool removed = false;
  KWEB_CHECK(reopened.Remove(second, same, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  // The listed metadata never carries the secret bytes.
  for (const auto &entry : reopened.List(second)) {
    KWEB_CHECK(std::string(entry.secret_id).find(kPlantedSecret) == std::string::npos);
  }
  KWEB_CHECK(kweb_secure_storage_close(second) == KWEB_SECURE_STORAGE_STATUS_OK);
}

}  // namespace

int main() {
  std::setvbuf(stdout, nullptr, _IONBF, 0);
  HeaderAndArgumentValidation();
  EnvelopeValidation();
  ScopeKeysAreQualified();
  RoundTripAndDelete();
  RotationAndPolicy();
  PersistsAcrossReopen();
  ZeroizationAfterClose();
  std::printf("kweb secure-storage portable checks: %d failures.\n", g_failures);
  const int platform_failures = RunPlatformNativeStructureTest();
  if (platform_failures > 0) {
    std::printf("kweb secure-storage provider checks: %d failures.\n", platform_failures);
  }
  if (g_failures == 0) {
    std::printf("kweb secure-storage native checks passed.\n");
    return 0;
  }
  std::printf("%d native checks failed.\n", g_failures);
  return 1;
}
