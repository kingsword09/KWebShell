#include "secure_storage_internal.h"

#include <algorithm>
#include <atomic>
#include <cstring>

namespace {

std::mutex g_registry_mutex;
kwebshell::secure_storage::State g_state;
std::atomic<uint32_t> g_live_count{0};

kweb_secure_storage_status ValidateHeader(uint32_t struct_size, uint32_t abi_version, uint32_t expected) {
  if (struct_size < expected) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  if (abi_version != KWEB_SECURE_STORAGE_ABI_VERSION) return KWEB_SECURE_STORAGE_STATUS_ABI_MISMATCH;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

void ResetCapabilities(kweb_secure_storage_capabilities_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
}

void ResetMetadata(kweb_secure_storage_metadata *result) {
  std::memset(result, 0, sizeof(*result));
}

void ResetValue(kweb_secure_storage_value_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
}

void ResetPresence(kweb_secure_storage_presence_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
}

void ResetRotation(kweb_secure_storage_rotation_result *result) {
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
}

/** Resets every field except the mutex that protects them. */
void ResetStateLocked() {
  g_state.open = false;
  g_state.application_id.clear();
  g_state.package_identity.clear();
  g_state.capabilities = kwebshell::secure_storage::Capabilities();
  g_state.entries.clear();
  for (std::string *buffer : g_state.staging) {
    kwebshell::secure_storage::Zeroize(buffer);
    delete buffer;
  }
  g_state.staging.clear();
  g_state.platform = nullptr;
}

void FillMetadata(const kwebshell::secure_storage::Metadata &metadata,
                  kweb_secure_storage_metadata *target) {
  ResetMetadata(target);
  kwebshell::secure_storage::CopyBounded(target->secret_id, sizeof(target->secret_id), metadata.id);
  target->scope = metadata.scope;
  target->backend = metadata.backend;
  target->accessibility = metadata.accessibility;
  target->envelope_version = metadata.envelope_version;
  target->generation = metadata.generation;
  target->size_bytes = metadata.size_bytes;
}

int IndexOfLocked(const std::string &key) {
  for (size_t index = 0; index < g_state.entries.size(); ++index) {
    if (g_state.entries[index].id == key) return static_cast<int>(index);
  }
  return -1;
}

/** Reads one reference from the wire form. Caller validates the header first. */
kweb_secure_storage_status ReadReference(const kweb_secure_storage_reference &wire,
                                         kwebshell::secure_storage::Reference *reference) {
  const kweb_secure_storage_status header = ValidateHeader(
      wire.struct_size, wire.abi_version, sizeof(kweb_secure_storage_reference));
  if (header != KWEB_SECURE_STORAGE_STATUS_OK) return header;
  std::string id;
  std::string owner;
  if (!kwebshell::secure_storage::ReadString(wire.secret_id, KWEB_SECURE_STORAGE_MAX_ID, &id) ||
      !kwebshell::secure_storage::ReadString(wire.owner_id, KWEB_SECURE_STORAGE_MAX_OWNER, &owner)) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  reference->id = id;
  reference->owner = owner;
  reference->scope = wire.scope;
  if (!kwebshell::secure_storage::ValidIdentifier(id) ||
      !kwebshell::secure_storage::ValidReference(*reference)) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

}  // namespace

namespace kwebshell::secure_storage {

std::string Reference::Key() const {
  if (scope == KWEB_SECRET_SCOPE_PROFILE) return owner + "/" + id;
  return id;
}

bool IsUtf8(const uint8_t *data, size_t size) {
  if (data == nullptr && size != 0) return false;
  size_t index = 0;
  while (index < size) {
    const unsigned char first = data[index];
    size_t continuation = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first <= 0x7f) {
      ++index;
      continue;
    }
    if (first >= 0xc2 && first <= 0xdf) {
      continuation = 1;
    } else if (first == 0xe0) {
      continuation = 2;
      second_min = 0xa0;
    } else if (first >= 0xe1 && first <= 0xec) {
      continuation = 2;
    } else if (first == 0xed) {
      continuation = 2;
      second_max = 0x9f;
    } else if (first >= 0xee && first <= 0xef) {
      continuation = 2;
    } else if (first == 0xf0) {
      continuation = 3;
      second_min = 0x90;
    } else if (first >= 0xf1 && first <= 0xf3) {
      continuation = 3;
    } else if (first == 0xf4) {
      continuation = 3;
      second_max = 0x8f;
    } else {
      return false;
    }
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

bool ReadString(const kweb_secure_storage_string &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0) ||
      !IsUtf8(value.data, value.size) ||
      (value.size != 0 && std::memchr(value.data, 0, value.size) != nullptr)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

bool ReadBytes(const kweb_secure_storage_bytes &value, size_t maximum, std::string *output) {
  if (output == nullptr || value.size > maximum || (value.data == nullptr && value.size != 0)) {
    return false;
  }
  output->assign(reinterpret_cast<const char *>(value.data), value.size);
  return true;
}

void CopyBounded(char *target, size_t capacity, const std::string &value) {
  if (capacity == 0) return;
  const size_t count = std::min(capacity - 1u, value.size());
  std::memcpy(target, value.data(), count);
  target[count] = '\0';
}

bool ValidIdentifier(const std::string &value) {
  if (value.empty() || value.size() > 128) return false;
  const unsigned char first = static_cast<unsigned char>(value[0]);
  if (!((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'))) return false;
  for (size_t index = 1; index < value.size(); ++index) {
    const unsigned char character = static_cast<unsigned char>(value[index]);
    const bool allowed = (character >= 'A' && character <= 'Z') ||
                         (character >= 'a' && character <= 'z') ||
                         (character >= '0' && character <= '9') || character == '.' ||
                         character == '_' || character == '-';
    if (!allowed) return false;
  }
  return true;
}

bool ValidScope(uint32_t scope) {
  return scope == KWEB_SECRET_SCOPE_APPLICATION || scope == KWEB_SECRET_SCOPE_PROFILE;
}

bool ValidAccessibility(uint32_t accessibility) {
  return accessibility >= KWEB_SECRET_ACCESS_WHEN_UNLOCKED &&
         accessibility <= KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY;
}

bool ValidReference(const Reference &reference) {
  if (reference.scope == KWEB_SECRET_SCOPE_APPLICATION) return reference.owner.empty();
  return !reference.owner.empty() && reference.owner.size() <= 128;
}

void Zeroize(std::string *value) {
  if (value == nullptr) return;
  if (!value->empty()) {
    volatile char *bytes = const_cast<volatile char *>(value->data());
    for (size_t index = 0; index < value->size(); ++index) bytes[index] = 0;
  }
  value->clear();
}

void TrackStaging(State &state, std::string *value) { state.staging.push_back(value); }

namespace {

/** Big-endian length prefix, so the envelope framing is platform independent. */
void AppendField(std::string *target, const std::string &value) {
  const uint32_t size = static_cast<uint32_t>(value.size());
  target->push_back(static_cast<char>((size >> 24) & 0xff));
  target->push_back(static_cast<char>((size >> 16) & 0xff));
  target->push_back(static_cast<char>((size >> 8) & 0xff));
  target->push_back(static_cast<char>(size & 0xff));
  target->append(value);
}

void AppendUint32(std::string *target, uint32_t value) {
  target->push_back(static_cast<char>((value >> 24) & 0xff));
  target->push_back(static_cast<char>((value >> 16) & 0xff));
  target->push_back(static_cast<char>((value >> 8) & 0xff));
  target->push_back(static_cast<char>(value & 0xff));
}

bool ReadUint32(const std::string &source, size_t *cursor, uint32_t *value) {
  if (*cursor + 4 > source.size()) return false;
  *value = (static_cast<uint32_t>(static_cast<unsigned char>(source[*cursor])) << 24) |
           (static_cast<uint32_t>(static_cast<unsigned char>(source[*cursor + 1])) << 16) |
           (static_cast<uint32_t>(static_cast<unsigned char>(source[*cursor + 2])) << 8) |
           static_cast<uint32_t>(static_cast<unsigned char>(source[*cursor + 3]));
  *cursor += 4;
  return true;
}

bool ReadField(const std::string &source, size_t *cursor, std::string *value) {
  uint32_t size = 0;
  if (!ReadUint32(source, cursor, &size)) return false;
  if (*cursor + size > source.size()) return false;
  value->assign(source, *cursor, size);
  *cursor += size;
  return true;
}

}  // namespace

namespace {

constexpr char kBase64Alphabet[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

int Base64Value(char character) {
  if (character >= 'A' && character <= 'Z') return character - 'A';
  if (character >= 'a' && character <= 'z') return character - 'a' + 26;
  if (character >= '0' && character <= '9') return character - '0' + 52;
  if (character == '+') return 62;
  if (character == '/') return 63;
  return -1;
}

}  // namespace

std::string Base64Encode(const std::string &value) {
  std::string encoded;
  encoded.reserve(((value.size() + 2) / 3) * 4);
  size_t index = 0;
  while (index + 3 <= value.size()) {
    const uint32_t block = (static_cast<uint32_t>(static_cast<unsigned char>(value[index])) << 16) |
                           (static_cast<uint32_t>(static_cast<unsigned char>(value[index + 1])) << 8) |
                           static_cast<uint32_t>(static_cast<unsigned char>(value[index + 2]));
    encoded.push_back(kBase64Alphabet[(block >> 18) & 0x3f]);
    encoded.push_back(kBase64Alphabet[(block >> 12) & 0x3f]);
    encoded.push_back(kBase64Alphabet[(block >> 6) & 0x3f]);
    encoded.push_back(kBase64Alphabet[block & 0x3f]);
    index += 3;
  }
  const size_t remaining = value.size() - index;
  if (remaining == 1) {
    const uint32_t block = static_cast<uint32_t>(static_cast<unsigned char>(value[index])) << 16;
    encoded.push_back(kBase64Alphabet[(block >> 18) & 0x3f]);
    encoded.push_back(kBase64Alphabet[(block >> 12) & 0x3f]);
    encoded.append("==");
  } else if (remaining == 2) {
    const uint32_t block = (static_cast<uint32_t>(static_cast<unsigned char>(value[index])) << 16) |
                           (static_cast<uint32_t>(static_cast<unsigned char>(value[index + 1])) << 8);
    encoded.push_back(kBase64Alphabet[(block >> 18) & 0x3f]);
    encoded.push_back(kBase64Alphabet[(block >> 12) & 0x3f]);
    encoded.push_back(kBase64Alphabet[(block >> 6) & 0x3f]);
    encoded.push_back('=');
  }
  return encoded;
}

bool Base64Decode(const std::string &value, std::string *decoded) {
  if (decoded == nullptr || value.size() % 4 != 0) return false;
  decoded->clear();
  decoded->reserve((value.size() / 4) * 3);
  for (size_t index = 0; index < value.size(); index += 4) {
    int quad[4] = {0, 0, 0, 0};
    for (size_t offset = 0; offset < 4; ++offset) {
      const char character = value[index + offset];
      if (character == '=') {
        // Padding is only legal in the last group.
        if (index + 4 != value.size()) return false;
        quad[offset] = -2;
        continue;
      }
      const int digit = Base64Value(character);
      if (digit < 0) return false;
      quad[offset] = digit;
    }
    if (quad[0] < 0 || quad[1] < 0) return false;
    decoded->push_back(static_cast<char>((quad[0] << 2) | (quad[1] >> 4)));
    if (quad[2] >= 0) {
      decoded->push_back(static_cast<char>(((quad[1] & 0x0f) << 4) | (quad[2] >> 2)));
    } else if (quad[3] >= 0) {
      return false;
    }
    if (quad[3] >= 0) {
      if (quad[2] < 0) return false;
      decoded->push_back(static_cast<char>(((quad[2] & 0x03) << 6) | quad[3]));
    }
  }
  return true;
}

std::string EncodeEnvelope(const Envelope &envelope) {
  std::string encoded;
  // A fixed magic distinguishes a real record from arbitrary stored bytes.
  encoded.append("KWSS");
  AppendUint32(&encoded, envelope.version);
  AppendUint32(&encoded, envelope.accessibility);
  AppendUint32(&encoded, envelope.require_user_presence);
  AppendUint32(&encoded, envelope.generation);
  AppendField(&encoded, envelope.id);
  AppendField(&encoded, envelope.owner);
  AppendField(&encoded, envelope.payload);
  return encoded;
}

bool DecodeEnvelope(const std::string &record, Envelope *envelope) {
  if (envelope == nullptr || record.size() < 24) return false;
  if (record.compare(0, 4, "KWSS") != 0) return false;
  size_t cursor = 4;
  uint32_t version = 0;
  uint32_t accessibility = 0;
  uint32_t presence = 0;
  uint32_t generation = 0;
  if (!ReadUint32(record, &cursor, &version) || !ReadUint32(record, &cursor, &accessibility) ||
      !ReadUint32(record, &cursor, &presence) || !ReadUint32(record, &cursor, &generation)) {
    return false;
  }
  if (version != KWEB_SECURE_STORAGE_ABI_VERSION) {
    // A record from another envelope version is a valid record of a different
    // version; the caller reports it stale rather than corrupted.
    envelope->version = version;
  } else {
    envelope->version = version;
  }
  envelope->accessibility = accessibility;
  envelope->require_user_presence = presence;
  envelope->generation = generation;
  if (!ReadField(record, &cursor, &envelope->id) || !ReadField(record, &cursor, &envelope->owner) ||
      !ReadField(record, &cursor, &envelope->payload)) {
    return false;
  }
  // Trailing bytes mean the record was not written by this encoder.
  return cursor == record.size();
}

}  // namespace kwebshell::secure_storage

extern "C" {

uint32_t KWEB_SECURE_STORAGE_CALL kweb_secure_storage_abi_version(void) {
  return KWEB_SECURE_STORAGE_ABI_VERSION;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_open(
    const kweb_secure_storage_configuration *configuration, uint64_t *handle) {
  if (configuration == nullptr || handle == nullptr) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  const kweb_secure_storage_status header = ValidateHeader(
      configuration->struct_size, configuration->abi_version, sizeof(kweb_secure_storage_configuration));
  if (header != KWEB_SECURE_STORAGE_STATUS_OK) return header;
  std::string application_id;
  std::string package_identity;
  if (!kwebshell::secure_storage::ReadString(configuration->application_id,
                                             KWEB_SECURE_STORAGE_MAX_ID, &application_id) ||
      application_id.empty() ||
      !kwebshell::secure_storage::ReadString(configuration->package_identity,
                                             KWEB_SECURE_STORAGE_MAX_ID, &package_identity)) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (g_state.open) return KWEB_SECURE_STORAGE_STATUS_ALREADY_OPEN;
    ResetStateLocked();
    g_state.application_id = application_id;
    g_state.package_identity = package_identity;
    g_state.open = true;
  }
  const kweb_secure_storage_status opened = kwebshell::secure_storage::NativeOpen(g_state);
  if (opened != KWEB_SECURE_STORAGE_STATUS_OK) {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
    return opened;
  }
  g_live_count.fetch_add(1);
  *handle = 1;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_capabilities(
    uint64_t handle, kweb_secure_storage_capabilities_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  const kweb_secure_storage_status header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_capabilities_result));
  if (header != KWEB_SECURE_STORAGE_STATUS_OK) return header;
  ResetCapabilities(result);
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  kwebshell::secure_storage::CopyBounded(result->provider_id, sizeof(result->provider_id),
                                         g_state.capabilities.provider_id);
  result->backend = g_state.capabilities.backend;
  result->capability_bits = g_state.capabilities.capability_bits;
  result->accessibility_bits = g_state.capabilities.accessibility_bits;
  result->max_value_bytes = g_state.capabilities.max_value_bytes;
  result->secret_count = static_cast<uint32_t>(g_state.entries.size());
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_put(
    uint64_t handle, const kweb_secure_storage_put_request *request,
    kweb_secure_storage_metadata *result) {
  if (handle != 1 || request == nullptr || result == nullptr) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  const kweb_secure_storage_status header = ValidateHeader(
      request->struct_size, request->abi_version, sizeof(kweb_secure_storage_put_request));
  if (header != KWEB_SECURE_STORAGE_STATUS_OK) return header;
  kwebshell::secure_storage::Reference reference;
  const kweb_secure_storage_status parsed =
      ReadReference(request->reference, &reference);
  if (parsed != KWEB_SECURE_STORAGE_STATUS_OK) return parsed;
  if (!kwebshell::secure_storage::ValidAccessibility(request->accessibility)) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  std::string value;
  if (!kwebshell::secure_storage::ReadBytes(request->value, KWEB_SECURE_STORAGE_MAX_VALUE, &value) ||
      value.empty()) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) {
      kwebshell::secure_storage::Zeroize(&value);
      return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
    }
    // A provider that implements an accessibility policy must support the one
    // requested. A provider that publishes no accessibility distinction (Windows
    // Credential Manager and the Linux Secret Service) records the declared
    // policy in the envelope instead of claiming to enforce it.
    if ((g_state.capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY) != 0 &&
        (g_state.capabilities.accessibility_bits & (1u << (request->accessibility - 1u))) == 0) {
      kwebshell::secure_storage::Zeroize(&value);
      return KWEB_SECURE_STORAGE_STATUS_ACCESSIBILITY_UNSUPPORTED;
    }
    if (request->require_user_presence != 0 &&
        (g_state.capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_USER_PRESENCE) == 0) {
      kwebshell::secure_storage::Zeroize(&value);
      return KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_UNSUPPORTED;
    }
  }
  kwebshell::secure_storage::Envelope envelope;
  envelope.version = KWEB_SECURE_STORAGE_ABI_VERSION;
  envelope.accessibility = request->accessibility;
  envelope.require_user_presence = request->require_user_presence;
  envelope.id = reference.id;
  envelope.owner = reference.owner;
  envelope.payload = value;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    const int existing = IndexOfLocked(reference.Key());
    envelope.generation = existing >= 0 ? g_state.entries[static_cast<size_t>(existing)].generation + 1 : 1;
  }
  const std::string record = kwebshell::secure_storage::EncodeEnvelope(envelope);
  kwebshell::secure_storage::Metadata metadata;
  const kweb_secure_storage_status stored =
      kwebshell::secure_storage::NativePut(g_state, reference, envelope, record, &metadata);
  kwebshell::secure_storage::Zeroize(&value);
  std::string scratch = record;
  kwebshell::secure_storage::Zeroize(&scratch);
  if (stored != KWEB_SECURE_STORAGE_STATUS_OK) return stored;
  metadata.id = reference.Key();
  metadata.scope = reference.scope;
  metadata.backend = g_state.capabilities.backend;
  metadata.accessibility = request->accessibility;
  metadata.envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
  metadata.generation = envelope.generation;
  metadata.size_bytes = static_cast<uint32_t>(envelope.payload.size());
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  const int existing = IndexOfLocked(reference.Key());
  if (existing >= 0) {
    g_state.entries[static_cast<size_t>(existing)] = metadata;
  } else {
    g_state.entries.push_back(metadata);
  }
  FillMetadata(metadata, result);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_get(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_value_result *result) {
  if (handle != 1 || reference == nullptr || result == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  if (result->data == nullptr || result->capacity == 0) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  const kweb_secure_storage_status result_header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_value_result));
  if (result_header != KWEB_SECURE_STORAGE_STATUS_OK) return result_header;
  uint8_t *buffer = result->data;
  const uint32_t capacity = result->capacity;
  kwebshell::secure_storage::Reference parsed;
  const kweb_secure_storage_status read = ReadReference(*reference, &parsed);
  if (read != KWEB_SECURE_STORAGE_STATUS_OK) return read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  }
  std::string record;
  kwebshell::secure_storage::Metadata metadata;
  const kweb_secure_storage_status fetched =
      kwebshell::secure_storage::NativeGet(g_state, parsed, &record, &metadata);
  if (fetched != KWEB_SECURE_STORAGE_STATUS_OK) {
    kwebshell::secure_storage::Zeroize(&record);
    return fetched;
  }
  kwebshell::secure_storage::Envelope envelope;
  if (!kwebshell::secure_storage::DecodeEnvelope(record, &envelope)) {
    kwebshell::secure_storage::Zeroize(&record);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  if (envelope.version != KWEB_SECURE_STORAGE_ABI_VERSION) {
    kwebshell::secure_storage::Zeroize(&record);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  const uint32_t size = static_cast<uint32_t>(envelope.payload.size());
  ResetValue(result);
  result->capacity = capacity;
  result->data = buffer;
  result->size = size;
  if (size > capacity) {
    kwebshell::secure_storage::Zeroize(&record);
    return KWEB_SECURE_STORAGE_STATUS_BUFFER_TOO_SMALL;
  }
  std::memcpy(buffer, envelope.payload.data(), size);
  kwebshell::secure_storage::Zeroize(&envelope.payload);
  kwebshell::secure_storage::Zeroize(&record);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_release(
    uint64_t handle, kweb_secure_storage_value_result *value) {
  if (handle != 1 || value == nullptr) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  if (value->data != nullptr && value->capacity != 0) {
    volatile uint8_t *bytes = value->data;
    for (uint32_t index = 0; index < value->capacity; ++index) bytes[index] = 0;
  }
  const uint32_t capacity = value->capacity;
  uint8_t *buffer = value->data;
  std::memset(value, 0, sizeof(*value));
  value->struct_size = sizeof(*value);
  value->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
  value->capacity = capacity;
  value->data = buffer;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_delete(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_presence_result *result) {
  if (handle != 1 || reference == nullptr || result == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  const kweb_secure_storage_status result_header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_presence_result));
  if (result_header != KWEB_SECURE_STORAGE_STATUS_OK) return result_header;
  kwebshell::secure_storage::Reference parsed;
  const kweb_secure_storage_status read = ReadReference(*reference, &parsed);
  if (read != KWEB_SECURE_STORAGE_STATUS_OK) return read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  }
  bool removed = false;
  const kweb_secure_storage_status deleted =
      kwebshell::secure_storage::NativeDelete(g_state, parsed, &removed);
  if (deleted != KWEB_SECURE_STORAGE_STATUS_OK) return deleted;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  const int index = IndexOfLocked(parsed.Key());
  if (index >= 0) g_state.entries.erase(g_state.entries.begin() + index);
  ResetPresence(result);
  result->present = removed ? 1u : 0u;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_exists(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_presence_result *result) {
  if (handle != 1 || reference == nullptr || result == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  const kweb_secure_storage_status result_header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_presence_result));
  if (result_header != KWEB_SECURE_STORAGE_STATUS_OK) return result_header;
  kwebshell::secure_storage::Reference parsed;
  const kweb_secure_storage_status read = ReadReference(*reference, &parsed);
  if (read != KWEB_SECURE_STORAGE_STATUS_OK) return read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  }
  std::string record;
  kwebshell::secure_storage::Metadata metadata;
  const kweb_secure_storage_status fetched =
      kwebshell::secure_storage::NativeGet(g_state, parsed, &record, &metadata);
  kwebshell::secure_storage::Zeroize(&record);
  ResetPresence(result);
  if (fetched == KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND) return KWEB_SECURE_STORAGE_STATUS_OK;
  if (fetched != KWEB_SECURE_STORAGE_STATUS_OK) return fetched;
  result->present = 1u;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_rotate(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_metadata *result) {
  if (handle != 1 || reference == nullptr || result == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  kwebshell::secure_storage::Reference parsed;
  const kweb_secure_storage_status read = ReadReference(*reference, &parsed);
  if (read != KWEB_SECURE_STORAGE_STATUS_OK) return read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
    if ((g_state.capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ROTATION) == 0) {
      return KWEB_SECURE_STORAGE_STATUS_ROTATION_UNSUPPORTED;
    }
  }
  std::string record;
  kwebshell::secure_storage::Metadata metadata;
  const kweb_secure_storage_status fetched =
      kwebshell::secure_storage::NativeGet(g_state, parsed, &record, &metadata);
  if (fetched != KWEB_SECURE_STORAGE_STATUS_OK) {
    kwebshell::secure_storage::Zeroize(&record);
    return fetched;
  }
  kwebshell::secure_storage::Envelope envelope;
  if (!kwebshell::secure_storage::DecodeEnvelope(record, &envelope)) {
    kwebshell::secure_storage::Zeroize(&record);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  envelope.version = KWEB_SECURE_STORAGE_ABI_VERSION;
  // The envelope already carries the policy the record was written with.
  envelope.generation += 1;
  const std::string rewritten = kwebshell::secure_storage::EncodeEnvelope(envelope);
  kwebshell::secure_storage::Metadata refreshed;
  const kweb_secure_storage_status stored =
      kwebshell::secure_storage::NativePut(g_state, parsed, envelope, rewritten, &refreshed);
  kwebshell::secure_storage::Zeroize(&envelope.payload);
  std::string scratch = rewritten;
  kwebshell::secure_storage::Zeroize(&scratch);
  kwebshell::secure_storage::Zeroize(&record);
  if (stored != KWEB_SECURE_STORAGE_STATUS_OK) return stored;
  refreshed.id = parsed.Key();
  refreshed.scope = parsed.scope;
  refreshed.backend = metadata.backend != 0 ? metadata.backend : g_state.capabilities.backend;
  refreshed.accessibility = envelope.accessibility;
  refreshed.envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
  refreshed.generation = envelope.generation;
  refreshed.size_bytes = metadata.size_bytes;
  std::lock_guard<std::mutex> lock(g_state.mutex);
  if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  const int index = IndexOfLocked(parsed.Key());
  if (index >= 0) g_state.entries[static_cast<size_t>(index)] = refreshed;
  FillMetadata(refreshed, result);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_rotation_status(
    uint64_t handle, const kweb_secure_storage_reference *reference,
    kweb_secure_storage_rotation_result *result) {
  if (handle != 1 || reference == nullptr || result == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  const kweb_secure_storage_status result_header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_rotation_result));
  if (result_header != KWEB_SECURE_STORAGE_STATUS_OK) return result_header;
  kwebshell::secure_storage::Reference parsed;
  const kweb_secure_storage_status read = ReadReference(*reference, &parsed);
  if (read != KWEB_SECURE_STORAGE_STATUS_OK) return read;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  }
  std::string record;
  kwebshell::secure_storage::Metadata metadata;
  const kweb_secure_storage_status fetched =
      kwebshell::secure_storage::NativeGet(g_state, parsed, &record, &metadata);
  if (fetched != KWEB_SECURE_STORAGE_STATUS_OK) {
    kwebshell::secure_storage::Zeroize(&record);
    return fetched;
  }
  kwebshell::secure_storage::Envelope envelope;
  const bool decoded = kwebshell::secure_storage::DecodeEnvelope(record, &envelope);
  kwebshell::secure_storage::Zeroize(&envelope.payload);
  kwebshell::secure_storage::Zeroize(&record);
  if (!decoded) return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  ResetRotation(result);
  if (envelope.version != KWEB_SECURE_STORAGE_ABI_VERSION) {
    result->status = KWEB_SECRET_ROTATION_STALE_ENVELOPE;
    result->generation = envelope.generation;
    return KWEB_SECURE_STORAGE_STATUS_OK;
  }
  // A record is current when its envelope version matches and the policy it was
  // written with is still one the provider implements; a policy the backend no
  // longer enforces is stale and must be rotated rather than silently kept.
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
    if (!kwebshell::secure_storage::ValidAccessibility(envelope.accessibility) ||
        ((g_state.capabilities.capability_bits & KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY) != 0 &&
         (g_state.capabilities.accessibility_bits & (1u << (envelope.accessibility - 1u))) == 0)) {
      result->status = KWEB_SECRET_ROTATION_STALE_POLICY;
      result->generation = envelope.generation;
      return KWEB_SECURE_STORAGE_STATUS_OK;
    }
  }
  result->status = KWEB_SECRET_ROTATION_CURRENT;
  result->generation = envelope.generation;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_list(
    uint64_t handle, kweb_secure_storage_list_result *result) {
  if (handle != 1 || result == nullptr) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  const kweb_secure_storage_status header = ValidateHeader(
      result->struct_size, result->abi_version, sizeof(kweb_secure_storage_list_result));
  if (header != KWEB_SECURE_STORAGE_STATUS_OK) return header;
  if (result->capacity != 0 && result->entries == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  }
  const uint32_t capacity = result->capacity;
  kweb_secure_storage_metadata *buffer = result->entries;
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
  }
  std::vector<kwebshell::secure_storage::Metadata> entries;
  const kweb_secure_storage_status listed = kwebshell::secure_storage::NativeList(g_state, &entries);
  if (listed != KWEB_SECURE_STORAGE_STATUS_OK) return listed;
  std::memset(result, 0, sizeof(*result));
  result->struct_size = sizeof(*result);
  result->abi_version = KWEB_SECURE_STORAGE_ABI_VERSION;
  result->capacity = capacity;
  result->entries = buffer;
  result->count = static_cast<uint32_t>(entries.size());
  if (result->count > capacity) return KWEB_SECURE_STORAGE_STATUS_BUFFER_TOO_SMALL;
  for (size_t index = 0; index < entries.size(); ++index) {
    FillMetadata(entries[index], &buffer[index]);
  }
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status KWEB_SECURE_STORAGE_CALL kweb_secure_storage_close(uint64_t handle) {
  if (handle != 1) return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
  std::lock_guard<std::mutex> registry_lock(g_registry_mutex);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    if (!g_state.open) return KWEB_SECURE_STORAGE_STATUS_OWNER_CLOSED;
    g_state.open = false;
  }
  const kweb_secure_storage_status status = kwebshell::secure_storage::NativeClose(g_state);
  {
    std::lock_guard<std::mutex> lock(g_state.mutex);
    ResetStateLocked();
  }
  g_live_count.fetch_sub(1);
  return status;
}

}  // extern "C"
