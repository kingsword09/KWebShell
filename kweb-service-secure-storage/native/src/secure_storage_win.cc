#include "secure_storage_internal.h"

#if !defined(_WIN32)
#error "The Win32 secure-storage provider must only be compiled on Windows."
#endif

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif

#include <windows.h>
#include <wincred.h>

#include <string>
#include <vector>

namespace {

constexpr char kProviderId[] = "secure-storage.windows.credential-manager";

using kwebshell::secure_storage::Envelope;
using kwebshell::secure_storage::Metadata;
using kwebshell::secure_storage::Reference;
using kwebshell::secure_storage::State;

State *g_state = nullptr;

/** The scope-qualified target name, prefixed by the owning application. */
std::wstring TargetName(State &state, const Reference &reference) {
  const std::string &owner = state.package_identity.empty() ? state.application_id : state.package_identity;
  const std::string target = owner + ":" + reference.Key();
  const int size = MultiByteToWideChar(CP_UTF8, 0, target.c_str(), static_cast<int>(target.size()),
                                       nullptr, 0);
  std::wstring wide(static_cast<size_t>(size), L'\0');
  MultiByteToWideChar(CP_UTF8, 0, target.c_str(), static_cast<int>(target.size()), wide.data(), size);
  return wide;
}

/** Maps one Win32 credential error onto the declared typed code. */
kweb_secure_storage_status MapError(DWORD error) {
  switch (error) {
    case ERROR_SUCCESS:
      return KWEB_SECURE_STORAGE_STATUS_OK;
    case ERROR_NOT_FOUND:
      return KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND;
    case ERROR_NO_SUCH_LOGON_SESSION:
    case ERROR_NO_SUCH_DOMAIN:
      return KWEB_SECURE_STORAGE_STATUS_SERVICE_UNAVAILABLE;
    case ERROR_ACCESS_DENIED:
      return KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_DENIED;
    case ERROR_INVALID_PARAMETER:
    case ERROR_INVALID_FLAGS:
      return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
    case ERROR_INSUFFICIENT_BUFFER:
      return KWEB_SECURE_STORAGE_STATUS_BUFFER_TOO_SMALL;
    default:
      return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
  }
}

}  // namespace

namespace kwebshell::secure_storage {

kweb_secure_storage_status NativeOpen(State &state) {
  state.platform = nullptr;
  g_state = &state;
  state.capabilities.provider_id = kProviderId;
  state.capabilities.backend = KWEB_SECRET_BACKEND_WINDOWS_CREDENTIAL_MANAGER;
  // The Credential Manager encrypts the blob under the user's logon credential but
  // publishes no per-item accessibility policy and cannot require user presence.
  state.capabilities.capability_bits = KWEB_SECURE_STORAGE_CAP_ROTATION |
                                       KWEB_SECURE_STORAGE_CAP_PROFILE_SCOPE;
  state.capabilities.accessibility_bits = 0;
  state.capabilities.max_value_bytes = KWEB_SECURE_STORAGE_MAX_VALUE;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativePut(State &state, const Reference &reference, const Envelope &envelope,
                                     const std::string &record, Metadata *metadata) {
  if (metadata == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  const std::wstring target = TargetName(state, reference);
  std::vector<BYTE> blob(record.begin(), record.end());
  CREDENTIALW credential{};
  credential.Type = CRED_TYPE_GENERIC;
  credential.TargetName = const_cast<LPWSTR>(target.c_str());
  // The label is the application-visible name, never the secret itself.
  credential.Comment = const_cast<LPWSTR>(L"KWebShell application secret");
  credential.CredentialBlobSize = static_cast<DWORD>(blob.size());
  credential.CredentialBlob = blob.empty() ? nullptr : blob.data();
  credential.Persist = CRED_PERSIST_LOCAL_MACHINE;
  credential.UserName = const_cast<LPWSTR>(L"kwebshell");
  if (CredWriteW(&credential, 0) == FALSE) return MapError(GetLastError());
  metadata->accessibility = envelope.accessibility;
  metadata->generation = envelope.generation;
  metadata->size_bytes = static_cast<uint32_t>(envelope.payload.size());
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeGet(State &state, const Reference &reference, std::string *record,
                                     Metadata *metadata) {
  if (record == nullptr || metadata == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  const std::wstring target = TargetName(state, reference);
  PCREDENTIALW credential = nullptr;
  if (CredReadW(target.c_str(), CRED_TYPE_GENERIC, 0, &credential) == FALSE) {
    return MapError(GetLastError());
  }
  record->assign(reinterpret_cast<const char *>(credential->CredentialBlob),
                 credential->CredentialBlobSize);
  CredFree(credential);
  metadata->backend = KWEB_SECRET_BACKEND_WINDOWS_CREDENTIAL_MANAGER;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeDelete(State &state, const Reference &reference, bool *removed) {
  if (removed == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  const std::wstring target = TargetName(state, reference);
  if (CredDeleteW(target.c_str(), CRED_TYPE_GENERIC, 0) == FALSE) {
    const DWORD error = GetLastError();
    if (error == ERROR_NOT_FOUND) {
      *removed = false;
      return KWEB_SECURE_STORAGE_STATUS_OK;
    }
    return MapError(error);
  }
  *removed = true;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeList(State &state, std::vector<Metadata> *entries) {
  if (entries == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  entries->clear();
  DWORD count = 0;
  PCREDENTIALW *credentials = nullptr;
  if (CredEnumerateW(nullptr, 0, &count, &credentials) == FALSE) {
    const DWORD error = GetLastError();
    if (error == ERROR_NOT_FOUND) return KWEB_SECURE_STORAGE_STATUS_OK;
    return MapError(error);
  }
  const std::string &owner = state.package_identity.empty() ? state.application_id : state.package_identity;
  const std::wstring prefix = TargetName(state, Reference());
  for (DWORD index = 0; index < count; ++index) {
    PCREDENTIALW credential = credentials[index];
    if (credential->TargetName == nullptr) continue;
    const std::wstring target(credential->TargetName);
    if (target.compare(0, prefix.size(), prefix) != 0) continue;
    Metadata metadata;
    const std::wstring key = target.substr(prefix.size());
    const int size = WideCharToMultiByte(CP_UTF8, 0, key.c_str(), static_cast<int>(key.size()),
                                         nullptr, 0, nullptr, nullptr);
    std::string narrow(static_cast<size_t>(size), '\0');
    WideCharToMultiByte(CP_UTF8, 0, key.c_str(), static_cast<int>(key.size()), narrow.data(), size,
                        nullptr, nullptr);
    metadata.id = narrow;
    metadata.backend = KWEB_SECRET_BACKEND_WINDOWS_CREDENTIAL_MANAGER;
    metadata.envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    entries->push_back(metadata);
  }
  CredFree(credentials);
  (void)owner;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeClose(State &state) {
  if (g_state == &state) g_state = nullptr;
  state.platform = nullptr;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

}  // namespace kwebshell::secure_storage
