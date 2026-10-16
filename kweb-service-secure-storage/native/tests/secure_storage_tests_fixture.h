#ifndef KWEB_SECURE_STORAGE_TESTS_FIXTURE_H_
#define KWEB_SECURE_STORAGE_TESTS_FIXTURE_H_

#include "kweb_secure_storage.h"

#include <cstdio>
#include <deque>
#include <string>
#include <vector>

/** One shared assertion counter so every translation unit reports one total. */
inline int g_failures = 0;

#define KWEB_CHECK(condition)                                                                    \
  do {                                                                                           \
    if (!(condition)) {                                                                          \
      std::printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #condition);                            \
      g_failures += 1;                                                                           \
    }                                                                                            \
  } while (false)

/** Owns the ABI buffers and the service handle one test needs. */
struct SecureStorageFixture {
  static constexpr const char *kApplicationId = "io.github.kingsword09.kwebshell.secrets.fixture";
  static constexpr const char *kPackageIdentity = "io.github.kingsword09.kwebshell.secrets.fixture";

  kweb_secure_storage_configuration Configuration() {
    kweb_secure_storage_configuration configuration{};
    configuration.struct_size = sizeof(configuration);
    configuration.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    configuration.application_id.data = reinterpret_cast<const uint8_t *>(kApplicationId);
    configuration.application_id.size = std::string(kApplicationId).size();
    configuration.package_identity.data = reinterpret_cast<const uint8_t *>(kPackageIdentity);
    configuration.package_identity.size = std::string(kPackageIdentity).size();
    return configuration;
  }

  /** Builds one reference; the fixture owns the string storage. */
  kweb_secure_storage_reference Reference(const std::string &id,
                                          uint32_t scope = KWEB_SECRET_SCOPE_APPLICATION,
                                          const std::string &owner = "") {
    ids_.push_back(id);
    owners_.push_back(owner);
    kweb_secure_storage_reference reference{};
    reference.struct_size = sizeof(reference);
    reference.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    reference.secret_id.data = reinterpret_cast<const uint8_t *>(ids_.back().data());
    reference.secret_id.size = ids_.back().size();
    reference.owner_id.data = reinterpret_cast<const uint8_t *>(owners_.back().data());
    reference.owner_id.size = owners_.back().size();
    reference.scope = scope;
    return reference;
  }

  kweb_secure_storage_put_request Put(const kweb_secure_storage_reference &reference,
                                      const std::string &value,
                                      uint32_t accessibility = KWEB_SECRET_ACCESS_WHEN_UNLOCKED,
                                      uint32_t require_user_presence = 0) {
    kweb_secure_storage_put_request request{};
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    request.reference = reference;
    request.value.data = reinterpret_cast<const uint8_t *>(value.data());
    request.value.size = value.size();
    request.accessibility = accessibility;
    request.require_user_presence = require_user_presence;
    return request;
  }

  kweb_secure_storage_capabilities_result Capabilities(uint64_t handle) {
    kweb_secure_storage_capabilities_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    const kweb_secure_storage_status status = kweb_secure_storage_capabilities(handle, &result);
    if (status != KWEB_SECURE_STORAGE_STATUS_OK) std::printf("capabilities status %u\n", status);
    return result;
  }

  kweb_secure_storage_status Store(uint64_t handle, const kweb_secure_storage_put_request &request,
                                   kweb_secure_storage_metadata *result) {
    return kweb_secure_storage_put(handle, &request, result);
  }

  /** Reads one secret into this fixture's buffer and releases it again. */
  kweb_secure_storage_status Load(uint64_t handle, const kweb_secure_storage_reference &reference,
                                  std::string *value, uint32_t *status_out = nullptr) {
    kweb_secure_storage_value_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    result.capacity = static_cast<uint32_t>(buffer_.size());
    result.data = buffer_.data();
    const kweb_secure_storage_status status = kweb_secure_storage_get(handle, &reference, &result);
    if (status_out != nullptr) *status_out = status;
    if (status == KWEB_SECURE_STORAGE_STATUS_OK) {
      value->assign(reinterpret_cast<const char *>(buffer_.data()), result.size);
    }
    kweb_secure_storage_release(handle, &result);
    return status;
  }

  kweb_secure_storage_status Remove(uint64_t handle, const kweb_secure_storage_reference &reference,
                                    bool *removed) {
    kweb_secure_storage_presence_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    const kweb_secure_storage_status status = kweb_secure_storage_delete(handle, &reference, &result);
    if (status == KWEB_SECURE_STORAGE_STATUS_OK) *removed = result.present != 0;
    return status;
  }

  kweb_secure_storage_status Exists(uint64_t handle, const kweb_secure_storage_reference &reference,
                                    bool *present) {
    kweb_secure_storage_presence_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    const kweb_secure_storage_status status = kweb_secure_storage_exists(handle, &reference, &result);
    if (status == KWEB_SECURE_STORAGE_STATUS_OK) *present = result.present != 0;
    return status;
  }

  kweb_secure_storage_status Rotate(uint64_t handle, const kweb_secure_storage_reference &reference,
                                    kweb_secure_storage_metadata *result) {
    return kweb_secure_storage_rotate(handle, &reference, result);
  }

  kweb_secure_storage_status RotationStatus(uint64_t handle,
                                            const kweb_secure_storage_reference &reference,
                                            uint32_t *status_out, uint32_t *generation_out) {
    kweb_secure_storage_rotation_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    const kweb_secure_storage_status status =
        kweb_secure_storage_rotation_status(handle, &reference, &result);
    if (status == KWEB_SECURE_STORAGE_STATUS_OK) {
      *status_out = result.status;
      *generation_out = result.generation;
    }
    return status;
  }

  std::vector<kweb_secure_storage_metadata> List(uint64_t handle) {
    kweb_secure_storage_list_result result{};
    result.struct_size = sizeof(result);
    result.abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    result.capacity = static_cast<uint32_t>(entries_.size());
    result.entries = entries_.data();
    std::vector<kweb_secure_storage_metadata> values;
    if (kweb_secure_storage_list(handle, &result) != KWEB_SECURE_STORAGE_STATUS_OK) return values;
    for (uint32_t index = 0; index < result.count; ++index) values.push_back(entries_[index]);
    return values;
  }

  /** Removes every identifier this fixture created, so runs never leak records. */
  void Cleanup(uint64_t handle, const std::vector<std::pair<std::string, uint32_t>> &keys) {
    for (const auto &key : keys) {
      kweb_secure_storage_reference reference = Reference(key.first, key.second);
      bool removed = false;
      Remove(handle, reference, &removed);
    }
  }

 private:
  // Deques keep the addresses of earlier references stable across inserts.
  std::deque<std::string> ids_;
  std::deque<std::string> owners_;
  std::vector<uint8_t> buffer_ = std::vector<uint8_t>(KWEB_SECURE_STORAGE_MAX_VALUE);
  std::vector<kweb_secure_storage_metadata> entries_ =
      std::vector<kweb_secure_storage_metadata>(KWEB_SECURE_STORAGE_MAX_METADATA);
};

#endif
