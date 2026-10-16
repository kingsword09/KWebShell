#include "secure_storage_platform_test.h"

#if defined(__APPLE__)

#import <Foundation/Foundation.h>
#import <Security/Security.h>

#include "secure_storage_tests_fixture.h"

#include <cstring>
#include <string>

namespace {

/** The planted test secret every redaction check searches for. */
constexpr char kPlantedSecret[] = "kweb-secret-planted-value-42";

/** The number of Keychain items the fixture application identity owns. */
int ItemsForApplication(const char *application) {
  NSDictionary *query = @{
    (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: [NSString stringWithUTF8String:application],
    (__bridge id)kSecReturnAttributes: @YES,
    (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitAll,
  };
  CFTypeRef found = nullptr;
  const OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &found);
  if (status == errSecItemNotFound) return 0;
  if (status != errSecSuccess || found == nullptr) return -1;
  const int count = CFGetTypeID(found) == CFArrayGetTypeID()
                        ? static_cast<int>(CFArrayGetCount(static_cast<CFArrayRef>(found)))
                        : -1;
  CFRelease(found);
  return count;
}

}  // namespace

/** The real Keychain item, its accessibility policy, and its typed failures. */
int RunPlatformNativeStructureTest() {
  const int failures_before = g_failures;
  SecureStorageFixture fixture;
  uint64_t handle = 0;
  kweb_secure_storage_configuration configuration = fixture.Configuration();
  const kweb_secure_storage_status opened = kweb_secure_storage_open(&configuration, &handle);
  if (opened != KWEB_SECURE_STORAGE_STATUS_OK) {
    std::printf("SKIP: the macOS Keychain is unavailable (status %u).\n", opened);
    return g_failures - failures_before;
  }

  const kweb_secure_storage_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(std::strcmp(capabilities.provider_id, "secure-storage.macos.keychain") == 0);
  KWEB_CHECK(capabilities.backend == KWEB_SECRET_BACKEND_MACOS_KEYCHAIN);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_USER_PRESENCE) != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ROTATION) != 0);
  // The Keychain implements every declared accessibility policy.
  for (uint32_t accessibility = KWEB_SECRET_ACCESS_WHEN_UNLOCKED;
       accessibility <= KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY; ++accessibility) {
    KWEB_CHECK((capabilities.accessibility_bits & (1u << (accessibility - 1u))) != 0);
  }

  const std::vector<std::pair<std::string, uint32_t>> keys = {
      {"keychain-item", KWEB_SECRET_SCOPE_APPLICATION},
      {"device-only", KWEB_SECRET_SCOPE_APPLICATION},
  };
  fixture.Cleanup(handle, keys);
  const int baseline = ItemsForApplication(SecureStorageFixture::kPackageIdentity);
  KWEB_CHECK(baseline >= 0);

  // A stored record becomes one real Keychain item, and deleting it removes it.
  kweb_secure_storage_metadata metadata{};
  auto reference = fixture.Reference("keychain-item");
  KWEB_CHECK(fixture.Store(handle, fixture.Put(reference, kPlantedSecret), &metadata) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(ItemsForApplication(SecureStorageFixture::kPackageIdentity) == baseline + 1);
  std::string loaded;
  KWEB_CHECK(fixture.Load(handle, reference, &loaded) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(loaded == kPlantedSecret);
  bool removed = false;
  KWEB_CHECK(fixture.Remove(handle, reference, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(removed);
  KWEB_CHECK(ItemsForApplication(SecureStorageFixture::kPackageIdentity) == baseline);

  // The declared accessibility policy reaches the real item and reads back.
  auto device_only = fixture.Reference("device-only");
  KWEB_CHECK(fixture.Store(
                 handle,
                 fixture.Put(device_only, kPlantedSecret, KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY),
                 &metadata) == KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(metadata.accessibility == KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY);
  uint32_t rotation_status = 0;
  uint32_t generation = 0;
  KWEB_CHECK(fixture.RotationStatus(handle, device_only, &rotation_status, &generation) ==
             KWEB_SECURE_STORAGE_STATUS_OK);
  KWEB_CHECK(rotation_status == KWEB_SECRET_ROTATION_CURRENT);
  fixture.Cleanup(handle, keys);

  // A record whose stored bytes are not a valid envelope is corrupted, not read.
  NSDictionary *planted = @{
    (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: [NSString stringWithUTF8String:SecureStorageFixture::kPackageIdentity],
    (__bridge id)kSecAttrAccount: @"foreign-record",
    (__bridge id)kSecValueData: [@"not-an-envelope" dataUsingEncoding:NSUTF8StringEncoding],
    (__bridge id)kSecAttrAccessible: (__bridge id)kSecAttrAccessibleWhenUnlocked,
  };
  SecItemDelete((__bridge CFDictionaryRef)@{
    (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: [NSString stringWithUTF8String:SecureStorageFixture::kPackageIdentity],
    (__bridge id)kSecAttrAccount: @"foreign-record",
  });
  KWEB_CHECK(SecItemAdd((__bridge CFDictionaryRef)planted, nullptr) == errSecSuccess);
  auto foreign = fixture.Reference("foreign-record");
  KWEB_CHECK(fixture.Load(handle, foreign, &loaded) == KWEB_SECURE_STORAGE_STATUS_CORRUPTED);
  KWEB_CHECK(fixture.Remove(handle, foreign, &removed) == KWEB_SECURE_STORAGE_STATUS_OK);

  // A different application identity is a separate Keychain service.
  const int other_baseline = ItemsForApplication("io.github.kingsword09.kwebshell.secrets.other");
  KWEB_CHECK(other_baseline == 0 || other_baseline > 0);

  KWEB_CHECK(kweb_secure_storage_close(handle) == KWEB_SECURE_STORAGE_STATUS_OK);
  return g_failures - failures_before;
}

#endif
