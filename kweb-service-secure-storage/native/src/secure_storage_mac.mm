#include "secure_storage_internal.h"

#if !defined(__APPLE__)
#error "The macOS secure-storage provider must only be compiled on macOS."
#endif

#import <Foundation/Foundation.h>
#import <Security/Security.h>

#include <string>

namespace {

constexpr char kProviderId[] = "secure-storage.macos.keychain";

using kwebshell::secure_storage::Envelope;
using kwebshell::secure_storage::Metadata;
using kwebshell::secure_storage::Reference;
using kwebshell::secure_storage::State;

State *g_state = nullptr;

struct MacState {
  /** The application identity the items are scoped to. */
  std::string service;
};

MacState *StateOf(State &state) { return static_cast<MacState *>(state.platform); }

/** The Keychain service name: the application identity, never a shared constant. */
NSString *ServiceName(State &state) {
  auto *mac = static_cast<MacState *>(state.platform);
  if (mac != nullptr && !mac->service.empty()) {
    return [NSString stringWithUTF8String:mac->service.c_str()];
  }
  return [NSString stringWithUTF8String:state.application_id.c_str()];
}

NSMutableDictionary *BaseQuery(State &state, const Reference &reference) {
  return [@{
    (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: ServiceName(state),
    (__bridge id)kSecAttrAccount: [NSString stringWithUTF8String:reference.Key().c_str()],
  } mutableCopy];
}

CFStringRef AccessibleConstant(uint32_t accessibility) {
  switch (accessibility) {
    case KWEB_SECRET_ACCESS_WHEN_UNLOCKED:
      return kSecAttrAccessibleWhenUnlocked;
    case KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY:
      return kSecAttrAccessibleWhenUnlockedThisDeviceOnly;
    case KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK:
      return kSecAttrAccessibleAfterFirstUnlock;
    case KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY:
      return kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;
    default:
      return nullptr;
  }
}

/** Maps one Keychain status onto the declared typed code. */
kweb_secure_storage_status MapStatus(OSStatus status) {
  switch (status) {
    case errSecSuccess:
      return KWEB_SECURE_STORAGE_STATUS_OK;
    case errSecItemNotFound:
      return KWEB_SECURE_STORAGE_STATUS_SECRET_NOT_FOUND;
    case errSecDuplicateItem:
      return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
    case errSecUserCanceled:
      return KWEB_SECURE_STORAGE_STATUS_CANCELLED;
    case errSecAuthFailed:
      return KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_DENIED;
    case errSecInteractionNotAllowed:
      return KWEB_SECURE_STORAGE_STATUS_LOCKED;
    case errSecDecode:
    case errSecParam:
      return KWEB_SECURE_STORAGE_STATUS_INVALID_ARGUMENT;
    default:
      return KWEB_SECURE_STORAGE_STATUS_NATIVE_FAILED;
  }
}

/** The stored accessibility of one item, or 0 when the item publishes none. */
uint32_t AccessibilityOf(CFTypeRef attributes) {
  if (attributes == nullptr || CFGetTypeID(attributes) != CFDictionaryGetTypeID()) return 0;
  CFTypeRef value = CFDictionaryGetValue(static_cast<CFDictionaryRef>(attributes),
                                         kSecAttrAccessible);
  if (value == nullptr) return 0;
  if (CFEqual(value, kSecAttrAccessibleWhenUnlocked)) return KWEB_SECRET_ACCESS_WHEN_UNLOCKED;
  if (CFEqual(value, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)) {
    return KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY;
  }
  if (CFEqual(value, kSecAttrAccessibleAfterFirstUnlock)) {
    return KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK;
  }
  if (CFEqual(value, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)) {
    return KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY;
  }
  return 0;
}

}  // namespace

namespace kwebshell::secure_storage {

kweb_secure_storage_status NativeOpen(State &state) {
  auto *mac = new MacState();
  // A signed application keeps a stable identity; the package identity is the
  // declared stable scope when the session publishes one.
  mac->service = state.package_identity.empty() ? state.application_id : state.package_identity;
  state.platform = mac;
  g_state = &state;
  state.capabilities.provider_id = kProviderId;
  state.capabilities.backend = KWEB_SECRET_BACKEND_MACOS_KEYCHAIN;
  state.capabilities.capability_bits = KWEB_SECURE_STORAGE_CAP_ACCESSIBILITY_POLICY |
                                       KWEB_SECURE_STORAGE_CAP_USER_PRESENCE |
                                       KWEB_SECURE_STORAGE_CAP_ROTATION |
                                       KWEB_SECURE_STORAGE_CAP_PROFILE_SCOPE;
  // The Keychain implements all four declared accessibility policies.
  state.capabilities.accessibility_bits = (1u << (KWEB_SECRET_ACCESS_WHEN_UNLOCKED - 1u)) |
                                          (1u << (KWEB_SECRET_ACCESS_WHEN_UNLOCKED_THIS_DEVICE_ONLY - 1u)) |
                                          (1u << (KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK - 1u)) |
                                          (1u << (KWEB_SECRET_ACCESS_AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY - 1u));
  state.capabilities.max_value_bytes = KWEB_SECURE_STORAGE_MAX_VALUE;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativePut(State &state, const Reference &reference, const Envelope &envelope,
                                     const std::string &record, Metadata *metadata) {
  if (StateOf(state) == nullptr || metadata == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  CFStringRef accessible = AccessibleConstant(envelope.accessibility);
  if (accessible == nullptr) return KWEB_SECURE_STORAGE_STATUS_ACCESSIBILITY_UNSUPPORTED;
  NSMutableDictionary *item = BaseQuery(state, reference);
  item[(__bridge id)kSecValueData] =
      [NSData dataWithBytes:record.data() length:record.size()];
  if (envelope.require_user_presence != 0) {
    SecAccessControlRef control = SecAccessControlCreateWithFlags(
        kCFAllocatorDefault, kSecAttrAccessibleWhenUnlocked, kSecAccessControlUserPresence, nullptr);
    if (control == nullptr) return KWEB_SECURE_STORAGE_STATUS_USER_PRESENCE_UNSUPPORTED;
    item[(__bridge id)kSecAttrAccessControl] = (__bridge_transfer id)control;
  } else {
    item[(__bridge id)kSecAttrAccessible] = (__bridge id)accessible;
  }
  // Replace in place: a duplicate is updated, never silently left in place.
  NSDictionary *match = BaseQuery(state, reference);
  SecItemDelete((__bridge CFDictionaryRef)match);
  OSStatus status = SecItemAdd((__bridge CFDictionaryRef)item, nullptr);
  if (status != errSecSuccess) return MapStatus(status);
  metadata->accessibility = envelope.accessibility;
  metadata->generation = envelope.generation;
  metadata->size_bytes = static_cast<uint32_t>(envelope.payload.size());
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeGet(State &state, const Reference &reference, std::string *record,
                                     Metadata *metadata) {
  if (StateOf(state) == nullptr || record == nullptr || metadata == nullptr) {
    return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  }
  NSMutableDictionary *query = BaseQuery(state, reference);
  query[(__bridge id)kSecReturnData] = @YES;
  query[(__bridge id)kSecReturnAttributes] = @YES;
  CFTypeRef found = nullptr;
  const OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &found);
  if (status != errSecSuccess) return MapStatus(status);
  if (found == nullptr || CFGetTypeID(found) != CFDictionaryGetTypeID()) {
    if (found != nullptr) CFRelease(found);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  CFDictionaryRef attributes = static_cast<CFDictionaryRef>(found);
  CFTypeRef data = CFDictionaryGetValue(attributes, kSecValueData);
  if (data == nullptr || CFGetTypeID(data) != CFDataGetTypeID()) {
    CFRelease(found);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  const CFDataRef bytes = static_cast<CFDataRef>(data);
  record->assign(reinterpret_cast<const char *>(CFDataGetBytePtr(bytes)),
                 static_cast<size_t>(CFDataGetLength(bytes)));
  metadata->accessibility = AccessibilityOf(attributes);
  CFRelease(found);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeDelete(State &state, const Reference &reference, bool *removed) {
  if (StateOf(state) == nullptr || removed == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  NSDictionary *query = BaseQuery(state, reference);
  const OSStatus status = SecItemDelete((__bridge CFDictionaryRef)query);
  if (status == errSecItemNotFound) {
    *removed = false;
    return KWEB_SECURE_STORAGE_STATUS_OK;
  }
  if (status != errSecSuccess) return MapStatus(status);
  *removed = true;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeList(State &state, std::vector<Metadata> *entries) {
  if (StateOf(state) == nullptr || entries == nullptr) return KWEB_SECURE_STORAGE_STATUS_BACKEND_UNAVAILABLE;
  entries->clear();
  NSMutableDictionary *query = [@{
    (__bridge id)kSecClass: (__bridge id)kSecClassGenericPassword,
    (__bridge id)kSecAttrService: ServiceName(state),
    (__bridge id)kSecReturnAttributes: @YES,
    (__bridge id)kSecMatchLimit: (__bridge id)kSecMatchLimitAll,
  } mutableCopy];
  CFTypeRef found = nullptr;
  const OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &found);
  if (status == errSecItemNotFound) return KWEB_SECURE_STORAGE_STATUS_OK;
  if (status != errSecSuccess) return MapStatus(status);
  if (found == nullptr || CFGetTypeID(found) != CFArrayGetTypeID()) {
    if (found != nullptr) CFRelease(found);
    return KWEB_SECURE_STORAGE_STATUS_CORRUPTED;
  }
  CFArrayRef items = static_cast<CFArrayRef>(found);
  for (CFIndex index = 0; index < CFArrayGetCount(items); ++index) {
    CFDictionaryRef attributes =
        static_cast<CFDictionaryRef>(CFArrayGetValueAtIndex(items, index));
    CFTypeRef account = CFDictionaryGetValue(attributes, kSecAttrAccount);
    if (account == nullptr || CFGetTypeID(account) != CFStringGetTypeID()) continue;
    Metadata metadata;
    const char *text = CFStringGetCStringPtr(static_cast<CFStringRef>(account), kCFStringEncodingUTF8);
    if (text != nullptr) {
      metadata.id = text;
    } else {
      const CFIndex length = CFStringGetLength(static_cast<CFStringRef>(account));
      const CFIndex capacity = CFStringGetMaximumSizeForEncoding(length, kCFStringEncodingUTF8) + 1;
      std::string buffer(static_cast<size_t>(capacity), '\0');
      if (CFStringGetCString(static_cast<CFStringRef>(account), buffer.data(), capacity,
                             kCFStringEncodingUTF8)) {
        buffer.resize(std::strlen(buffer.c_str()));
        metadata.id = buffer;
      }
    }
    metadata.accessibility = AccessibilityOf(attributes);
    metadata.backend = KWEB_SECRET_BACKEND_MACOS_KEYCHAIN;
    metadata.envelope_version = KWEB_SECURE_STORAGE_ABI_VERSION;
    entries->push_back(metadata);
  }
  CFRelease(found);
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

kweb_secure_storage_status NativeClose(State &state) {
  MacState *mac = StateOf(state);
  if (mac == nullptr) return KWEB_SECURE_STORAGE_STATUS_OK;
  if (g_state == &state) g_state = nullptr;
  delete mac;
  state.platform = nullptr;
  return KWEB_SECURE_STORAGE_STATUS_OK;
}

}  // namespace kwebshell::secure_storage
