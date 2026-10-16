#ifndef KWEB_SECURE_STORAGE_INTERNAL_H_
#define KWEB_SECURE_STORAGE_INTERNAL_H_

#include "kweb_secure_storage.h"

#include <mutex>
#include <string>
#include <vector>

namespace kwebshell::secure_storage {

/** One bounded secret reference as Kotlin accepted it. */
struct Reference {
  std::string id;
  std::string owner;
  uint32_t scope = KWEB_SECRET_SCOPE_APPLICATION;

  /** The scope-qualified credential key this record is stored under. */
  std::string Key() const;
};

/** One stored record's metadata. It never carries the secret bytes. */
struct Metadata {
  std::string id;
  uint32_t scope = KWEB_SECRET_SCOPE_APPLICATION;
  uint32_t backend = 0;
  uint32_t accessibility = 0;
  uint32_t envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
  uint32_t generation = 1;
  uint32_t size_bytes = 0;
};

/** The provider's declared capability facts for the current target. */
struct Capabilities {
  std::string provider_id;
  uint32_t backend = 0;
  uint32_t capability_bits = 0;
  uint32_t accessibility_bits = 0;
  uint32_t max_value_bytes = KWEB_SECURE_STORAGE_MAX_VALUE;
};

/**
 * The versioned ciphertext envelope. The provider encrypts the whole envelope, so
 * a record the current key cannot open, a foreign record, or a record written by
 * another envelope version is detected instead of trusted.
 */
struct Envelope {
  uint32_t version = KWEB_SECURE_STORAGE_ABI_VERSION;
  uint32_t accessibility = KWEB_SECRET_ACCESS_WHEN_UNLOCKED;
  uint32_t require_user_presence = 0;
  uint32_t generation = 1;
  std::string id;
  std::string owner;
  std::string payload;
};

/** Encodes one envelope into the record bytes the backend stores. */
std::string EncodeEnvelope(const Envelope &envelope);
/** Decodes a record, or reports that it is not a valid envelope. */
bool DecodeEnvelope(const std::string &record, Envelope *envelope);

/**
 * Base64 for a backend that stores text rather than bytes (the Linux Secret
 * Service), so a binary envelope survives the round trip unchanged.
 */
std::string Base64Encode(const std::string &value);
bool Base64Decode(const std::string &value, std::string *decoded);

/** The single process-wide secret store owned by the opened handle. */
struct State {
  std::mutex mutex;
  bool open = false;
  std::string application_id;
  std::string package_identity;
  Capabilities capabilities;
  std::vector<Metadata> entries;
  /** Staging buffers that must be zeroed on close. */
  std::vector<std::string *> staging;
  void *platform = nullptr;
};

bool IsUtf8(const uint8_t *data, size_t size);
bool ReadString(const kweb_secure_storage_string &value, size_t maximum, std::string *output);
bool ReadBytes(const kweb_secure_storage_bytes &value, size_t maximum, std::string *output);
void CopyBounded(char *target, size_t capacity, const std::string &value);

/** True when the value matches the published `[A-Za-z][A-Za-z0-9._-]{0,127}` form. */
bool ValidIdentifier(const std::string &value);
bool ValidScope(uint32_t scope);
bool ValidAccessibility(uint32_t accessibility);
/** Validates the reference's scope/owner pairing. */
bool ValidReference(const Reference &reference);

/** Zeroes one buffer and releases it. */
void Zeroize(std::string *value);
/** Registers a staging buffer so close zeroes it. Caller holds the state mutex. */
void TrackStaging(State &state, std::string *value);

/* The platform provider surface implemented by secure_storage_<platform> sources. */
kweb_secure_storage_status NativeOpen(State &state);
kweb_secure_storage_status NativePut(State &state, const Reference &reference, const Envelope &envelope,
                                     const std::string &record, Metadata *metadata);
kweb_secure_storage_status NativeGet(State &state, const Reference &reference, std::string *record,
                                     Metadata *metadata);
kweb_secure_storage_status NativeDelete(State &state, const Reference &reference, bool *removed);
kweb_secure_storage_status NativeList(State &state, std::vector<Metadata> *entries);
kweb_secure_storage_status NativeClose(State &state);

}  // namespace kwebshell::secure_storage

#endif
