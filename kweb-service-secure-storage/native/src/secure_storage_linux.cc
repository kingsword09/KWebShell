#include "secure_storage_internal.h"

#if !defined(__linux__)
#error "The Linux secure-storage provider must only be compiled on Linux."
#endif

#include <libsecret/secret.h>

#include <string>

namespace {

constexpr char kProviderId[] = "secure-storage.linux.secret-service";
constexpr char kSchemaName[] = "io.github.kingsword09.kwebshell.secret";

using kwebshell::secure_storage::Envelope;
using kwebshell::secure_storage::Metadata;
using kwebshell::secure_storage::Reference;
using kwebshell::secure_storage::State;

State *g_state = nullptr;

/**
 * True when the session declares the bus the Secret Service lives on. libsecret
 * otherwise tries to autolaunch one and blocks, so the provider checks the
 * declared facility first and fails typed instead of hanging.
 */
bool BusDeclared() {
  const char *bus = g_getenv("DBUS_SESSION_BUS_ADDRESS");
  return bus != nullptr && bus[0] != '\0';
}

/** The declared Secret Service schema: the application and the scoped key. */
const SecretSchema *Schema() {
  static const SecretSchema schema = {
      kSchemaName,
      SECRET_SCHEMA_NONE,
      {
          {"application", SECRET_SCHEMA_ATTRIBUTE_STRING},
          {"key", SECRET_SCHEMA_ATTRIBUTE_STRING},
          {nullptr, static_cast<SecretSchemaAttributeType>(0)},
      },
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
  };
  return &schema;
}

const char *ApplicationOf(State &state) {
  return state.package_identity.empty() ? state.application_id.c_str()
                                        : state.package_identity.c_str();
}

/** Maps one GError onto the declared typed code. */
kweb_secure_storage_status MapError(const GError *error) {
  if (error == nullptr) return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
  if (error->domain == SECRET_ERROR) {
    switch (error->code) {
      case SECRET_ERROR_PROTOCOL:
      case SECRET_ERROR_IS_LOCKED:
        return KWEB_SECURE_STORAGE_STATUS_LOCKED;
      case SECRET_ERROR_NO_SUCH_OBJECT:
        return KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND;
      case SECRET_ERROR_INVALID_FILE_FORMAT:
        return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
      default:
        return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
    }
  }
  if (g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_SERVICE_UNKNOWN) ||
      g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_NAME_HAS_NO_OWNER) ||
      g_error_matches(error, G_DBUS_ERROR, G_DBUS_ERROR_DISCONNECTED) ||
      g_error_matches(error, G_IO_ERROR, G_IO_ERROR_NOT_FOUND)) {
    return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
  }
  return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
}

}  // namespace

namespace kwebshell::secure_storage {

kweb_secure_storage_status NativeOpen(State &state) {
  state.platform = nullptr;
  g_state = &state;
  state.capabilities.provider_id = kProviderId;
  state.capabilities.backend = KWEB_SECRET_BACKEND_LINUX_SECRET_SERVICE;
  // The Secret Service has no per-item accessibility policy and cannot require
  // user presence, so both are typed absences rather than claimed semantics.
  state.capabilities.capability_bits = KWEB_SECURE_STORAGE_CAP_ROTATION |
                                       KWEB_SECURE_STORAGE_CAP_PROFILE_SCOPE;
  state.capabilities.accessibility_bits = 0;
  state.capabilities.max_value_bytes = KWEB_SECURE_STORAGE_MAX_VALUE;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativePut(State &state, const Reference &reference, const Envelope &envelope,
                                     const std::string &record, Metadata *metadata) {
  if (metadata == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  if (!BusDeclared()) return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
  GError *error = nullptr;
  const gboolean stored = secret_password_store_sync(
      Schema(), SECRET_COLLECTION_DEFAULT,
      // The label is the application-visible name, never the secret itself.
      ApplicationOf(state), kwebshell::secure_storage::Base64Encode(record).c_str(), nullptr, &error,
      "application", ApplicationOf(state), "key", reference.Key().c_str(), nullptr);
  if (!stored) {
    const kweb_secure_storage_status status = MapError(error);
    if (error != nullptr) g_error_free(error);
    return status;
  }
  metadata->accessibility = envelope.accessibility;
  metadata->generation = envelope.generation;
  metadata->size_bytes = static_cast<uint32_t>(envelope.payload.size());
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeGet(State &state, const Reference &reference, std::string *record,
                                     Metadata *metadata) {
  if (record == nullptr || metadata == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  if (!BusDeclared()) return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
  GError *error = nullptr;
  gchar *value = secret_password_lookup_sync(
      Schema(), nullptr, &error, "application", ApplicationOf(state), "key",
      reference.Key().c_str(), nullptr);
  if (error != nullptr) {
    const kweb_secure_storage_status status = MapError(error);
    g_error_free(error);
    return status;
  }
  if (value == nullptr) return KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND;
  const bool decoded = kwebshell::secure_storage::Base64Decode(value, record);
  secret_password_free(value);
  return decoded ? KWEB_SECURE_STORAGE_STATUS_OK : KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
}

kweb_secure_storage_status NativeDelete(State &state, const Reference &reference, bool *removed) {
  if (removed == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  if (!BusDeclared()) return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
  GError *error = nullptr;
  const gboolean cleared = secret_password_clear_sync(
      Schema(), nullptr, &error, "application", ApplicationOf(state), "key",
      reference.Key().c_str(), nullptr);
  if (error != nullptr) {
    const kweb_secure_storage_status status = MapError(error);
    g_error_free(error);
    return status;
  }
  *removed = cleared == TRUE;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeList(State &state, std::vector<Metadata> *entries) {
  if (entries == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  if (!BusDeclared()) return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
  entries->clear();
  GError *error = nullptr;
  GHashTable *attributes = g_hash_table_new_full(g_str_hash, g_str_equal, g_free, g_free);
  g_hash_table_insert(attributes, g_strdup("application"), g_strdup(ApplicationOf(state)));
  // Listing metadata must never unlock a collection or load a secret: doing so
  // can block on a prompt the session cannot answer.
  GList *items = secret_service_search_sync(
      nullptr, Schema(), attributes,
      static_cast<SecretSearchFlags>(SECRET_SEARCH_ALL), nullptr, &error);
  g_hash_table_unref(attributes);
  if (error != nullptr) {
    const kweb_secure_storage_status status = MapError(error);
    g_error_free(error);
    return status;
  }
  for (GList *node = items; node != nullptr; node = node->next) {
    auto *item = SECRET_ITEM(node->data);
    GHashTable *item_attributes = secret_item_get_attributes(item);
    const gchar *key = static_cast<const gchar *>(g_hash_table_lookup(item_attributes, "key"));
    Metadata metadata;
    if (key != nullptr) metadata.id = key;
    metadata.backend = KWEB_SECRET_BACKEND_LINUX_SECRET_SERVICE;
    metadata.envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    entries->push_back(metadata);
    g_hash_table_unref(item_attributes);
  }
  g_list_free_full(items, g_object_unref);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeClose(State &state) {
  if (g_state == &state) g_state = nullptr;
  state.platform = nullptr;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

}  // namespace kwebshell::secure_storage
