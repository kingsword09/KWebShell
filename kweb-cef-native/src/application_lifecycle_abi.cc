#include "kwebshell/native/application_lifecycle_abi.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <filesystem>
#include <iomanip>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#include <windows.h>
#else
#include <fcntl.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>
#endif

#if defined(KWEB_HAS_GDBUS)
#include <gio/gio.h>
#endif
#if defined(__APPLE__)
#include <sys/types.h>
#endif

#if defined(__APPLE__)
#import <AppKit/AppKit.h>
#import <CoreServices/CoreServices.h>
#endif

#if defined(__APPLE__)
@interface KWebApplicationDelegate : NSObject <NSApplicationDelegate>
@property(nonatomic, assign) void *lifecycle;
@end
#endif

namespace {

constexpr size_t kMaximumFrameBytes = 256U * 1024U;

struct NativeLifecycle {
  std::string application_id;
  std::string transport_root;
  kweb_application_lifecycle_activation_callback callback = nullptr;
  void *user_data = nullptr;
  std::atomic<bool> stopping = false;
  std::thread listener;
#if defined(_WIN32)
  HANDLE mutex = nullptr;
  HANDLE wake = nullptr;
  std::wstring pipe_name;
#else
  int lock_fd = -1;
  int listener_fd = -1;
  std::string socket_path;
#if defined(__APPLE__)
  void *mac_application = nullptr;
  bool mac_appkit_ready = false;
#endif
#if defined(KWEB_HAS_GDBUS)
  GDBusConnection *bus = nullptr;
  GMainLoop *main_loop = nullptr;
  GDBusNodeInfo *node_info = nullptr;
  std::string bus_name;
  guint owner_id = 0;
  guint registration_id = 0;
  std::mutex bus_mutex;
  std::condition_variable bus_condition;
  bool bus_ready = false;
  bool bus_lost = false;
#endif
#endif
};

std::mutex g_registry_mutex;
std::vector<NativeLifecycle *> g_registry;

bool IsValidUtf8Boundary(const char *value, size_t size) {
  if (value == nullptr || size == 0 || size > 4096) return false;
  const auto *bytes = reinterpret_cast<const unsigned char *>(value);
  size_t index = 0;
  while (index < size) {
    const unsigned char first = bytes[index];
    if (first == 0) return false;
    if (first <= 0x7f) {
      ++index;
      continue;
    }
    size_t width = 0;
    unsigned char second_min = 0x80;
    unsigned char second_max = 0xbf;
    if (first >= 0xc2 && first <= 0xdf) {
      width = 2;
    } else if (first >= 0xe0 && first <= 0xef) {
      width = 3;
      if (first == 0xe0) second_min = 0xa0;
      if (first == 0xed) second_max = 0x9f;
    } else if (first >= 0xf0 && first <= 0xf4) {
      width = 4;
      if (first == 0xf0) second_min = 0x90;
      if (first == 0xf4) second_max = 0x8f;
    } else {
      return false;
    }
    if (index + width > size) return false;
    const unsigned char second = bytes[index + 1];
    if (second < second_min || second > second_max) return false;
    for (size_t offset = 2; offset < width; ++offset) {
      const unsigned char continuation = bytes[index + offset];
      if (continuation < 0x80 || continuation > 0xbf) return false;
    }
    index += width;
  }
  return true;
}

bool IsValidPayload(const uint8_t *payload, size_t size) {
  return payload != nullptr && size > 0 && size <= kMaximumFrameBytes;
}

std::vector<std::string> SplitCsv(const char *value, size_t size) {
  std::vector<std::string> result;
  if (value == nullptr || size == 0 || size > 4096) return result;
  std::string current;
  for (size_t index = 0; index < size; ++index) {
    const char character = value[index];
    if (character == ',' || index + 1 == size) {
      if (index + 1 == size && character != ',') current.push_back(character);
      if (current.empty()) return {};
      result.push_back(current);
      current.clear();
    } else {
      if (character == '\0' || character == '\n' || character == '\r' || character == ' ') return {};
      current.push_back(character);
    }
  }
  return result;
}

std::string AssociationDigest(const std::string &application_id,
                              const std::string &package_root,
                              const std::string &executable,
                              const std::vector<std::string> &schemes,
                              const std::vector<std::string> &extensions,
                              bool remove) {
  uint64_t hash = 1469598103934665603ULL;
  const std::string input = application_id + "\n" + package_root + "\n" + executable +
                            "\n" + (remove ? "remove" : "install") + "\n" +
                            [&] {
                              std::string value;
                              for (const auto &item : schemes) value += item + ",";
                              value += "\n";
                              for (const auto &item : extensions) value += item + ",";
                              return value;
                            }();
  for (unsigned char byte : input) {
    hash ^= byte;
    hash *= 1099511628211ULL;
  }
  std::ostringstream output;
  output << "fnv1a64:" << std::hex << std::setw(16) << std::setfill('0') << hash;
  return output.str();
}

bool CopyDigest(const std::string &digest, char *output, size_t output_size) {
  if (output == nullptr || output_size <= digest.size()) return false;
  std::memcpy(output, digest.c_str(), digest.size() + 1);
  return true;
}

void Register(NativeLifecycle *lifecycle) {
  std::lock_guard lock(g_registry_mutex);
  g_registry.push_back(lifecycle);
}

void Unregister(NativeLifecycle *lifecycle) {
  std::lock_guard lock(g_registry_mutex);
  for (auto iterator = g_registry.begin(); iterator != g_registry.end(); ++iterator) {
    if (*iterator == lifecycle) {
      g_registry.erase(iterator);
      return;
    }
  }
}

void Emit(NativeLifecycle *lifecycle, const std::vector<uint8_t> &payload) {
  if (lifecycle->callback == nullptr || payload.empty()) return;
  lifecycle->callback(lifecycle->user_data, payload.data(), payload.size());
}

#if defined(__APPLE__)

void EmitMacUrls(NativeLifecycle *lifecycle, NSArray<NSURL *> *urls) {
  NSMutableArray *uris = [NSMutableArray array];
  NSMutableArray *files = [NSMutableArray array];
  for (NSURL *url in urls) {
    if (url.isFileURL) {
      NSString *path = url.path;
      if (path == nil) continue;
      BOOL exists = [[NSFileManager defaultManager] fileExistsAtPath:path];
      [files addObject:@{
        @"absolutePath": path,
        @"exists": @(exists),
      }];
    } else {
      NSString *absolute = url.absoluteString;
      if (absolute != nil) [uris addObject:absolute];
    }
  }
  if (uris.count == 0 && files.count == 0) return;
  NSString *source = files.count == 0 ? @"PROTOCOL" : @"FILE_OPEN";
  NSDictionary *object = @{
    @"source": source,
    @"uris": uris,
    @"files": files,
  };
  NSError *error = nil;
  NSData *encoded = [NSJSONSerialization dataWithJSONObject:object options:0 error:&error];
  if (error != nil || encoded == nil || encoded.length == 0 ||
      encoded.length > kMaximumFrameBytes) {
    return;
  }
  Emit(lifecycle, std::vector<uint8_t>(
      static_cast<const uint8_t *>(encoded.bytes),
      static_cast<const uint8_t *>(encoded.bytes) + encoded.length));
}

bool InitializeMacApplication(NativeLifecycle *lifecycle) {
  NSApplication *application = [NSApplication sharedApplication];
  if (application == nil) return false;
  [application setActivationPolicy:NSApplicationActivationPolicyAccessory];
  KWebApplicationDelegate *delegate = [KWebApplicationDelegate new];
  delegate.lifecycle = static_cast<void *>(lifecycle);
  application.delegate = delegate;
  [application finishLaunching];
  lifecycle->mac_application = (__bridge void *)application;
  lifecycle->mac_appkit_ready = true;
  return true;
}

void ActivateMacApplication(NativeLifecycle *lifecycle) {
  if (!lifecycle->mac_appkit_ready) return;
  NSRunningApplication *application = [NSRunningApplication currentApplication];
  [application activateWithOptions:0];
}

void ReleaseMacApplication(NativeLifecycle *lifecycle) {
  if (!lifecycle->mac_appkit_ready) return;
  NSApplication *application = (__bridge NSApplication *)lifecycle->mac_application;
  application.delegate = nil;
  lifecycle->mac_application = nullptr;
  lifecycle->mac_appkit_ready = false;
}

#endif

#if defined(_WIN32)

std::wstring Utf8ToWide(const std::string &value) {
  const int required = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS,
                                           value.data(),
                                           static_cast<int>(value.size()),
                                           nullptr, 0);
  if (required <= 0) return {};
  std::wstring result(static_cast<size_t>(required), L'\0');
  if (MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(),
                          static_cast<int>(value.size()), result.data(),
                          required) != required) {
    return {};
  }
  return result;
}

bool SameUser(HANDLE pipe) {
  if (!ImpersonateNamedPipeClient(pipe)) return false;
  HANDLE client_token = nullptr;
  const bool opened = OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE,
                                      &client_token) != FALSE;
  bool same_user = false;
  if (opened) {
    HANDLE process_token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &process_token)) {
      DWORD client_size = 0;
      DWORD process_size = 0;
      GetTokenInformation(client_token, TokenUser, nullptr, 0, &client_size);
      GetTokenInformation(process_token, TokenUser, nullptr, 0, &process_size);
      std::vector<uint8_t> client_buffer(client_size);
      std::vector<uint8_t> process_buffer(process_size);
      if (GetTokenInformation(client_token, TokenUser, client_buffer.data(),
                              client_size, &client_size) &&
          GetTokenInformation(process_token, TokenUser, process_buffer.data(),
                              process_size, &process_size)) {
        const auto *client_user = reinterpret_cast<const TOKEN_USER *>(client_buffer.data());
        const auto *process_user = reinterpret_cast<const TOKEN_USER *>(process_buffer.data());
        same_user = EqualSid(client_user->User.Sid, process_user->User.Sid) != FALSE;
      }
      CloseHandle(process_token);
    }
    CloseHandle(client_token);
  }
  RevertToSelf();
  return same_user;
}

bool ReadPipe(HANDLE pipe, std::vector<uint8_t> *payload) {
  uint32_t size = 0;
  DWORD read = 0;
  if (!ReadFile(pipe, &size, sizeof(size), &read, nullptr) ||
      read != sizeof(size) || size == 0 || size > kMaximumFrameBytes) {
    return false;
  }
  payload->resize(size);
  return ReadFile(pipe, payload->data(), size, &read, nullptr) && read == size;
}

bool WritePipe(HANDLE pipe, const uint8_t *payload, size_t size) {
  const uint32_t frame_size = static_cast<uint32_t>(size);
  DWORD written = 0;
  return WriteFile(pipe, &frame_size, sizeof(frame_size), &written, nullptr) &&
         written == sizeof(frame_size) &&
         WriteFile(pipe, payload, frame_size, &written, nullptr) &&
         written == frame_size;
}

void WindowsListen(NativeLifecycle *lifecycle) {
  while (!lifecycle->stopping.load(std::memory_order_acquire)) {
    HANDLE pipe = CreateNamedPipeW(
        lifecycle->pipe_name.c_str(), PIPE_ACCESS_INBOUND,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT | PIPE_REJECT_REMOTE_CLIENTS,
        1, static_cast<DWORD>(kMaximumFrameBytes),
        static_cast<DWORD>(kMaximumFrameBytes), 1000, nullptr);
    if (pipe == INVALID_HANDLE_VALUE) return;
    const BOOL connected = ConnectNamedPipe(pipe, nullptr)
                               ? TRUE
                               : (GetLastError() == ERROR_PIPE_CONNECTED);
    if (connected && SameUser(pipe)) {
      std::vector<uint8_t> payload;
      if (ReadPipe(pipe, &payload)) Emit(lifecycle, payload);
    }
    DisconnectNamedPipe(pipe);
    CloseHandle(pipe);
  }
}

int32_t WindowsAcquire(NativeLifecycle *lifecycle,
                       const uint8_t *initial_payload,
                       size_t initial_payload_size) {
  const std::wstring app_id = Utf8ToWide(lifecycle->application_id);
  if (app_id.empty()) return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  const std::wstring suffix = L"KWebShell-" + app_id;
  const std::wstring mutex_name = L"Local\\" + suffix;
  lifecycle->pipe_name = L"\\\\.\\pipe\\" + suffix;
  lifecycle->mutex = CreateMutexW(nullptr, TRUE, mutex_name.c_str());
  if (lifecycle->mutex == nullptr) return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  if (GetLastError() == ERROR_ALREADY_EXISTS) {
    CloseHandle(lifecycle->mutex);
    lifecycle->mutex = nullptr;
    for (int attempt = 0; attempt < 100; ++attempt) {
      if (WaitNamedPipeW(lifecycle->pipe_name.c_str(), 100)) {
        HANDLE pipe = CreateFileW(lifecycle->pipe_name.c_str(), GENERIC_WRITE, 0,
                                  nullptr, OPEN_EXISTING, 0, nullptr);
        if (pipe != INVALID_HANDLE_VALUE) {
          const bool sent = WritePipe(pipe, initial_payload, initial_payload_size);
          CloseHandle(pipe);
          return sent ? KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY
                      : KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
        }
      }
      Sleep(10);
    }
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  Register(lifecycle);
  lifecycle->listener = std::thread(WindowsListen, lifecycle);
  return KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY;
}

void WindowsRelease(NativeLifecycle *lifecycle) {
  lifecycle->stopping.store(true, std::memory_order_release);
  if (!lifecycle->pipe_name.empty()) {
    HANDLE pipe = CreateFileW(lifecycle->pipe_name.c_str(), GENERIC_WRITE, 0,
                              nullptr, OPEN_EXISTING, 0, nullptr);
    if (pipe != INVALID_HANDLE_VALUE) CloseHandle(pipe);
  }
  if (lifecycle->listener.joinable()) lifecycle->listener.join();
  if (lifecycle->mutex != nullptr) {
    CloseHandle(lifecycle->mutex);
    lifecycle->mutex = nullptr;
  }
  Unregister(lifecycle);
}

bool WriteRegistryValue(HKEY root, const std::wstring &path,
                        const std::wstring &name, const std::wstring &value) {
  HKEY key = nullptr;
  if (RegCreateKeyExW(root, path.c_str(), 0, nullptr, 0, KEY_SET_VALUE, nullptr,
                      &key, nullptr) != ERROR_SUCCESS) {
    return false;
  }
  const auto *bytes = reinterpret_cast<const BYTE *>(value.c_str());
  const DWORD size = static_cast<DWORD>((value.size() + 1) * sizeof(wchar_t));
  const bool success = RegSetValueExW(key, name.empty() ? nullptr : name.c_str(),
                                      0, REG_SZ, bytes, size) == ERROR_SUCCESS;
  RegCloseKey(key);
  return success;
}

bool DeleteRegistryTree(const std::wstring &path) {
  const LONG result = RegDeleteTreeW(HKEY_CURRENT_USER, path.c_str());
  return result == ERROR_SUCCESS || result == ERROR_FILE_NOT_FOUND ||
         result == ERROR_PATH_NOT_FOUND;
}

int32_t WindowsRegisterAssociations(const std::string &application_id,
                                    const std::string &executable,
                                    const std::vector<std::string> &schemes,
                                    const std::vector<std::string> &extensions,
                                    bool remove) {
  const std::wstring id = Utf8ToWide(application_id);
  const std::wstring exe = Utf8ToWide(executable);
  if (id.empty() || exe.empty()) return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  const std::wstring classes = L"Software\\Classes\\";
  if (remove) {
    bool success = true;
    for (const auto &scheme : schemes) {
      success = success && DeleteRegistryTree(classes + Utf8ToWide(scheme));
    }
    for (const auto &extension : extensions) {
      success = success && DeleteRegistryTree(classes + Utf8ToWide(extension));
    }
    success = success && DeleteRegistryTree(classes + id + L".file");
    return success ? KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY
                   : KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  const std::wstring command = L"\"" + exe + L"\" \"%1\"";
  for (const auto &scheme : schemes) {
    const std::wstring root = classes + Utf8ToWide(scheme);
    if (!WriteRegistryValue(HKEY_CURRENT_USER, root, L"", id) ||
        !WriteRegistryValue(HKEY_CURRENT_USER, root, L"URL Protocol", L"") ||
        !WriteRegistryValue(HKEY_CURRENT_USER, root + L"\\shell\\open\\command", L"", command)) {
      return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
    }
  }
  const std::wstring file_id = id + L".file";
  if (!WriteRegistryValue(HKEY_CURRENT_USER, classes + file_id, L"", id) ||
      !WriteRegistryValue(HKEY_CURRENT_USER, classes + file_id + L"\\shell\\open\\command", L"", command)) {
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  for (const auto &extension : extensions) {
    if (!WriteRegistryValue(HKEY_CURRENT_USER, classes + Utf8ToWide(extension), L"", file_id)) {
      return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
    }
  }
  return KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY;
}

#else

#if defined(__APPLE__)

int32_t MacRegisterAssociations(const std::string &package_root, bool remove) {
  std::filesystem::path application(package_root);
  if (application.extension() != ".app") application /= "KWebShell.app";
  if (!std::filesystem::is_directory(application)) {
    return KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED;
  }
  const std::string path = application.string();
  CFURLRef url = CFURLCreateFromFileSystemRepresentation(
      nullptr, reinterpret_cast<const UInt8 *>(path.c_str()), path.size(), true);
  if (url == nullptr) return KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED;
  OSStatus status = noErr;
  if (remove) {
    NSTask *task = [NSTask new];
    task.launchPath = @"/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister";
    task.arguments = @[ @"-u", [NSString stringWithUTF8String:path.c_str()] ];
    @try {
      [task launch];
      [task waitUntilExit];
      if (task.terminationStatus != 0) status = kLSApplicationNotFoundErr;
    } @catch (NSException *exception) {
      (void)exception;
      status = kLSApplicationNotFoundErr;
    }
  } else {
    status = LSRegisterURL(url, true);
  }
  CFRelease(url);
  return status == noErr ? KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY
                         : KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED;
}

#endif

#if !defined(KWEB_HAS_GDBUS)

bool SameUser(int socket_fd) {
#if defined(__APPLE__)
  uid_t uid = 0;
  gid_t gid = 0;
  return getpeereid(socket_fd, &uid, &gid) == 0 && uid == getuid();
#elif defined(SO_PEERCRED)
  struct ucred credentials;
  socklen_t length = sizeof(credentials);
  return getsockopt(socket_fd, SOL_SOCKET, SO_PEERCRED, &credentials, &length) == 0 &&
         credentials.uid == getuid();
#else
  (void)socket_fd;
  return true;
#endif
}

bool ReadAll(int fd, void *output, size_t size) {
  auto *bytes = static_cast<uint8_t *>(output);
  size_t offset = 0;
  while (offset < size) {
    const ssize_t count = read(fd, bytes + offset, size - offset);
    if (count <= 0) return false;
    offset += static_cast<size_t>(count);
  }
  return true;
}

bool WriteAll(int fd, const void *input, size_t size) {
  const auto *bytes = static_cast<const uint8_t *>(input);
  size_t offset = 0;
  while (offset < size) {
    const ssize_t count = write(fd, bytes + offset, size - offset);
    if (count <= 0) return false;
    offset += static_cast<size_t>(count);
  }
  return true;
}

bool ReadSocket(int fd, std::vector<uint8_t> *payload) {
  uint32_t size = 0;
  if (!ReadAll(fd, &size, sizeof(size)) || size == 0 || size > kMaximumFrameBytes) {
    return false;
  }
  payload->resize(size);
  return ReadAll(fd, payload->data(), size);
}

bool WriteSocket(int fd, const uint8_t *payload, size_t size) {
  const uint32_t frame_size = static_cast<uint32_t>(size);
  return WriteAll(fd, &frame_size, sizeof(frame_size)) &&
         WriteAll(fd, payload, size);
}

#endif

#if defined(KWEB_HAS_GDBUS)

constexpr const char *kApplicationInterfaceXml =
    "<node>"
    "<interface name='org.freedesktop.Application'>"
    "<method name='Activate'>"
    "<arg type='a{sv}' name='platform_data' direction='in'/>"
    "</method>"
    "<method name='Open'>"
    "<arg type='as' name='uris' direction='in'/>"
    "<arg type='a{sv}' name='platform_data' direction='in'/>"
    "</method>"
    "</interface>"
    "</node>";

bool VerifyDbusSender(GDBusConnection *connection, const gchar *sender) {
  (void)connection;
  if (sender == nullptr || sender[0] != ':') return false;
  GError *connection_error = nullptr;
  GDBusConnection *credentials = g_bus_get_sync(
      G_BUS_TYPE_SESSION, nullptr, &connection_error);
  if (connection_error != nullptr || credentials == nullptr) {
    if (connection_error != nullptr) g_error_free(connection_error);
    if (credentials != nullptr) g_object_unref(credentials);
    return false;
  }
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      credentials, "org.freedesktop.DBus", "/org/freedesktop/DBus",
      "org.freedesktop.DBus", "GetConnectionUnixUser",
      g_variant_new("(s)", sender), G_VARIANT_TYPE("(u)"),
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (error != nullptr || reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    if (reply != nullptr) g_variant_unref(reply);
    g_object_unref(credentials);
    return false;
  }
  guint32 uid = 0;
  g_variant_get(reply, "(u)", &uid);
  g_variant_unref(reply);
  if (uid != static_cast<guint32>(getuid())) return false;

  error = nullptr;
  reply = g_dbus_connection_call_sync(
      credentials, "org.freedesktop.DBus", "/org/freedesktop/DBus",
      "org.freedesktop.DBus", "GetConnectionUnixProcessID",
      g_variant_new("(s)", sender), G_VARIANT_TYPE("(u)"),
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (error != nullptr || reply == nullptr) {
    if (error != nullptr) g_error_free(error);
    if (reply != nullptr) g_variant_unref(reply);
    g_object_unref(credentials);
    return false;
  }
  guint32 pid = 0;
  g_variant_get(reply, "(u)", &pid);
  g_variant_unref(reply);
  g_object_unref(credentials);
  return pid != 0;
}

GVariant *PayloadFromOptions(GVariant *options) {
  GVariant *payload = g_variant_lookup_value(options, "kweb-payload",
                                              G_VARIANT_TYPE("ay"));
  if (payload == nullptr) return nullptr;
  gsize size = 0;
  const auto *data = static_cast<const uint8_t *>(
      g_variant_get_fixed_array(payload, &size, sizeof(uint8_t)));
  if (data == nullptr || size == 0 || size > kMaximumFrameBytes) {
    g_variant_unref(payload);
    return nullptr;
  }
  return payload;
}

void OnDbusMethodCall(GDBusConnection *connection, const gchar *sender,
                      const gchar *object_path, const gchar *interface_name,
                      const gchar *method_name, GVariant *parameters,
                      GDBusMethodInvocation *invocation, gpointer user_data) {
  (void)connection;
  (void)object_path;
  (void)interface_name;
  auto *lifecycle = static_cast<NativeLifecycle *>(user_data);
  if (!VerifyDbusSender(connection, sender)) {
    g_dbus_method_invocation_return_dbus_error(
        invocation, "org.freedesktop.Application.Error",
        "The activation sender is not an authenticated local user.");
    return;
  }
  GVariant *options = nullptr;
  if (std::strcmp(method_name, "Activate") == 0) {
    g_variant_get(parameters, "(@a{sv})", &options);
  } else if (std::strcmp(method_name, "Open") == 0) {
    GVariant *uris = nullptr;
    g_variant_get(parameters, "(@as@a{sv})", &uris, &options);
    g_variant_unref(uris);
  } else {
    g_dbus_method_invocation_return_dbus_error(
        invocation, "org.freedesktop.Application.Error", "Unknown lifecycle method.");
    return;
  }
  GVariant *array = PayloadFromOptions(options);
  g_variant_unref(options);
  if (array == nullptr) {
    g_dbus_method_invocation_return_dbus_error(
        invocation, "org.freedesktop.Application.Error", "Invalid activation payload.");
    return;
  }
  gsize size = 0;
  const auto *data = static_cast<const uint8_t *>(
      g_variant_get_fixed_array(array, &size, sizeof(uint8_t)));
  if (data == nullptr || size == 0 || size > kMaximumFrameBytes) {
    g_variant_unref(array);
    g_dbus_method_invocation_return_dbus_error(
        invocation, "org.freedesktop.Application.Error", "Invalid activation payload.");
    return;
  }
  Emit(lifecycle, std::vector<uint8_t>(data, data + size));
  g_variant_unref(array);
  g_dbus_method_invocation_return_value(invocation, nullptr);
}

const GDBusInterfaceVTable kApplicationInterfaceVTable = {
    OnDbusMethodCall, nullptr, nullptr, {nullptr}};

void OnDbusNameAcquired(GDBusConnection *connection, const gchar *name,
                        gpointer user_data) {
  (void)name;
  auto *lifecycle = static_cast<NativeLifecycle *>(user_data);
  GError *error = nullptr;
  lifecycle->node_info = g_dbus_node_info_new_for_xml(
      kApplicationInterfaceXml, &error);
  if (error != nullptr) {
    g_error_free(error);
  } else {
    lifecycle->registration_id = g_dbus_connection_register_object(
        connection, "/org/kwebshell/Application",
        lifecycle->node_info->interfaces[0], &kApplicationInterfaceVTable,
        lifecycle, nullptr, &error);
    if (error != nullptr) g_error_free(error);
  }
  {
    std::lock_guard lock(lifecycle->bus_mutex);
    lifecycle->bus_ready = lifecycle->registration_id != 0;
  }
  lifecycle->bus_condition.notify_all();
}

void OnDbusNameLost(GDBusConnection *connection, const gchar *name,
                    gpointer user_data) {
  (void)connection;
  (void)name;
  auto *lifecycle = static_cast<NativeLifecycle *>(user_data);
  {
    std::lock_guard lock(lifecycle->bus_mutex);
    lifecycle->bus_lost = true;
  }
  lifecycle->bus_condition.notify_all();
}

void DbusThread(NativeLifecycle *lifecycle) {
  GError *error = nullptr;
  lifecycle->bus = g_bus_get_sync(G_BUS_TYPE_SESSION, nullptr, &error);
  if (error != nullptr || lifecycle->bus == nullptr) {
    if (error != nullptr) g_error_free(error);
    {
      std::lock_guard lock(lifecycle->bus_mutex);
      lifecycle->bus_lost = true;
    }
    lifecycle->bus_condition.notify_all();
    return;
  }
  lifecycle->main_loop = g_main_loop_new(nullptr, FALSE);
  lifecycle->owner_id = g_bus_own_name_on_connection(
      lifecycle->bus, lifecycle->bus_name.c_str(), G_BUS_NAME_OWNER_FLAGS_NONE,
      OnDbusNameAcquired, OnDbusNameLost, lifecycle, nullptr);
  g_main_loop_run(lifecycle->main_loop);
  if (lifecycle->owner_id != 0) g_bus_unown_name(lifecycle->owner_id);
  if (lifecycle->registration_id != 0) {
    g_dbus_connection_unregister_object(lifecycle->bus,
                                        lifecycle->registration_id);
    lifecycle->registration_id = 0;
  }
  if (lifecycle->node_info != nullptr) {
    g_dbus_node_info_unref(lifecycle->node_info);
    lifecycle->node_info = nullptr;
  }
  if (lifecycle->main_loop != nullptr) {
    g_main_loop_unref(lifecycle->main_loop);
    lifecycle->main_loop = nullptr;
  }
  g_object_unref(lifecycle->bus);
  lifecycle->bus = nullptr;
}

bool DbusSend(NativeLifecycle *lifecycle, const uint8_t *payload, size_t size) {
  if (lifecycle->bus == nullptr) return false;
  GVariant *array = g_variant_new_fixed_array(G_VARIANT_TYPE_BYTE, payload, size,
                                              sizeof(uint8_t));
  GVariantBuilder options;
  g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
  g_variant_builder_add(&options, "{sv}", "kweb-payload", array);
  GError *error = nullptr;
  GVariant *reply = g_dbus_connection_call_sync(
      lifecycle->bus, lifecycle->bus_name.c_str(), "/org/kwebshell/Application",
      "org.freedesktop.Application", "Activate",
      g_variant_new("(@a{sv})", g_variant_builder_end(&options)), nullptr,
      G_DBUS_CALL_FLAGS_NONE, 2000, nullptr, &error);
  if (reply != nullptr) g_variant_unref(reply);
  if (error != nullptr) {
    g_error_free(error);
    return false;
  }
  return true;
}

int32_t DbusAcquire(NativeLifecycle *lifecycle,
                    const uint8_t *initial_payload,
                    size_t initial_payload_size) {
  lifecycle->bus_name = lifecycle->application_id;
  lifecycle->listener = std::thread(DbusThread, lifecycle);
  {
    std::unique_lock lock(lifecycle->bus_mutex);
    if (!lifecycle->bus_condition.wait_for(lock, std::chrono::seconds(5),
                                           [lifecycle] {
                                             return lifecycle->bus_ready || lifecycle->bus_lost;
                                           })) {
      lifecycle->stopping.store(true, std::memory_order_release);
      return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
    }
  }
  if (lifecycle->bus_ready) {
    Register(lifecycle);
    return KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY;
  }
  const bool sent = DbusSend(lifecycle, initial_payload, initial_payload_size);
  lifecycle->stopping.store(true, std::memory_order_release);
  if (lifecycle->main_loop != nullptr) g_main_loop_quit(lifecycle->main_loop);
  if (lifecycle->listener.joinable()) lifecycle->listener.join();
  return sent ? KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY
              : KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
}

void DbusRelease(NativeLifecycle *lifecycle) {
  lifecycle->stopping.store(true, std::memory_order_release);
  if (lifecycle->main_loop != nullptr) g_main_loop_quit(lifecycle->main_loop);
  if (lifecycle->listener.joinable()) lifecycle->listener.join();
  Unregister(lifecycle);
}

#endif

#if defined(KWEB_HAS_GDBUS)

GAppInfo *FindDesktopApp(const std::string &desktop_id) {
  GList *all = g_app_info_get_all();
  GAppInfo *result = nullptr;
  for (GList *entry = all; entry != nullptr; entry = entry->next) {
    auto *candidate = G_APP_INFO(entry->data);
    const char *id = g_app_info_get_id(candidate);
    if (id != nullptr && desktop_id == id) {
      result = G_APP_INFO(g_object_ref(candidate));
      break;
    }
  }
  g_list_free_full(all, g_object_unref);
  return result;
}

int32_t LinuxRegisterAssociations(const std::string &application_id,
                                  const std::string &executable,
                                  const std::vector<std::string> &schemes,
                                  const std::vector<std::string> &extensions,
                                  bool remove) {
  const std::string desktop_id = application_id + ".desktop";
  GAppInfo *desktop = FindDesktopApp(desktop_id);
  if (desktop == nullptr) {
    GError *error = nullptr;
    const std::string command = executable + " %U";
    desktop = g_app_info_create_from_commandline(
        command.c_str(), application_id.c_str(), G_APP_INFO_CREATE_SUPPORTS_URIS, &error);
    if (error != nullptr) {
      g_error_free(error);
      return KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED;
    }
  }
  GAppInfo *app = desktop;
  bool success = true;
  for (const auto &scheme : schemes) {
    const std::string content_type = "x-scheme-handler/" + scheme;
    if (remove) {
      GAppInfo *current = g_app_info_get_default_for_uri_scheme(scheme.c_str());
      const char *current_executable = current == nullptr ? nullptr : g_app_info_get_executable(current);
      const bool owned = current_executable != nullptr && executable == current_executable;
      if (current != nullptr) g_object_unref(current);
      if (owned) g_app_info_reset_type_associations(content_type.c_str());
    } else {
      GError *error = nullptr;
      g_app_info_set_as_default_for_type(app, content_type.c_str(), &error);
      if (error != nullptr) {
        g_error_free(error);
        success = false;
      }
    }
  }
  if (!extensions.empty()) {
    const std::string content_type = "application/x-kwebshell";
    if (remove) {
      GAppInfo *current = g_app_info_get_default_for_type(content_type.c_str(), FALSE);
      const char *current_executable = current == nullptr ? nullptr : g_app_info_get_executable(current);
      const bool owned = current_executable != nullptr && executable == current_executable;
      if (current != nullptr) g_object_unref(current);
      if (owned) g_app_info_reset_type_associations(content_type.c_str());
    } else {
      GError *error = nullptr;
      g_app_info_set_as_default_for_type(app, content_type.c_str(), &error);
      if (error != nullptr) {
        g_error_free(error);
        success = false;
      }
    }
  }
  g_object_unref(desktop);
  return success ? KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY
                 : KWEB_APPLICATION_LIFECYCLE_REGISTRATION_FAILED;
}

#endif

#if !defined(KWEB_HAS_GDBUS)

bool MakeAddress(const std::string &path, sockaddr_un *address) {
  if (path.size() >= sizeof(address->sun_path)) return false;
  std::memset(address, 0, sizeof(*address));
  address->sun_family = AF_UNIX;
  std::memcpy(address->sun_path, path.c_str(), path.size() + 1);
  return true;
}

std::string ShortSocketPath(const std::string &transport_root,
                            const std::string &application_id) {
  uint64_t hash = 1469598103934665603ULL;
  for (unsigned char byte : transport_root + "\n" + application_id) {
    hash ^= byte;
    hash *= 1099511628211ULL;
  }
  return "/tmp/kwebshell-" + std::to_string(hash) + ".sock";
}

void PosixListen(NativeLifecycle *lifecycle) {
  while (!lifecycle->stopping.load(std::memory_order_acquire)) {
    const int client = accept(lifecycle->listener_fd, nullptr, nullptr);
    if (client < 0) {
      if (lifecycle->stopping.load(std::memory_order_acquire)) break;
      continue;
    }
    if (SameUser(client)) {
      std::vector<uint8_t> payload;
      if (ReadSocket(client, &payload)) Emit(lifecycle, payload);
    }
    close(client);
  }
}

int ConnectAndSend(const std::string &socket_path, const uint8_t *payload, size_t size) {
  sockaddr_un address{};
  if (!MakeAddress(socket_path, &address)) return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  for (int attempt = 0; attempt < 100; ++attempt) {
    const int client = socket(AF_UNIX, SOCK_STREAM, 0);
    if (client >= 0 && connect(client, reinterpret_cast<sockaddr *>(&address), sizeof(address)) == 0) {
      const bool sent = WriteSocket(client, payload, size);
      close(client);
      return sent ? KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY
                  : KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
    }
    if (client >= 0) close(client);
    std::this_thread::sleep_for(std::chrono::milliseconds(10));
  }
  return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
}

#endif

int32_t PosixAcquire(NativeLifecycle *lifecycle,
                     const uint8_t *initial_payload,
                     size_t initial_payload_size) {
#if defined(__APPLE__)
  if (!InitializeMacApplication(lifecycle)) {
    return KWEB_APPLICATION_LIFECYCLE_NOT_SUPPORTED;
  }
#endif
#if defined(KWEB_HAS_GDBUS)
  return DbusAcquire(lifecycle, initial_payload, initial_payload_size);
#else
  std::error_code error;
  std::filesystem::create_directories(lifecycle->transport_root, error);
  if (error) {
#if defined(__APPLE__)
    ReleaseMacApplication(lifecycle);
#endif
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  const std::filesystem::path root(lifecycle->transport_root);
  const std::filesystem::path lock_path = root / ".kweb-application.lock";
  lifecycle->socket_path = (root / ".kweb-application.sock").string();
  if (lifecycle->socket_path.size() >= sizeof(sockaddr_un::sun_path)) {
    lifecycle->socket_path = ShortSocketPath(
        lifecycle->transport_root, lifecycle->application_id);
  }
  lifecycle->lock_fd = open(lock_path.c_str(), O_CREAT | O_RDWR, 0600);
  if (lifecycle->lock_fd < 0) {
#if defined(__APPLE__)
    ReleaseMacApplication(lifecycle);
#endif
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  if (flock(lifecycle->lock_fd, LOCK_EX | LOCK_NB) != 0) {
    close(lifecycle->lock_fd);
    lifecycle->lock_fd = -1;
#if defined(__APPLE__)
    ActivateMacApplication(lifecycle);
#endif
    return ConnectAndSend(lifecycle->socket_path, initial_payload, initial_payload_size);
  }

  unlink(lifecycle->socket_path.c_str());
  lifecycle->listener_fd = socket(AF_UNIX, SOCK_STREAM, 0);
  if (lifecycle->listener_fd < 0) {
#if defined(__APPLE__)
    ReleaseMacApplication(lifecycle);
#endif
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  sockaddr_un address{};
  if (!MakeAddress(lifecycle->socket_path, &address) ||
      bind(lifecycle->listener_fd, reinterpret_cast<sockaddr *>(&address), sizeof(address)) != 0 ||
      chmod(lifecycle->socket_path.c_str(), 0600) != 0 || listen(lifecycle->listener_fd, 8) != 0) {
    close(lifecycle->listener_fd);
    lifecycle->listener_fd = -1;
    unlink(lifecycle->socket_path.c_str());
#if defined(__APPLE__)
    ReleaseMacApplication(lifecycle);
#endif
    return KWEB_APPLICATION_LIFECYCLE_TRANSPORT_FAILED;
  }
  Register(lifecycle);
  lifecycle->listener = std::thread(PosixListen, lifecycle);
  return KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY;
#endif
}

void PosixRelease(NativeLifecycle *lifecycle) {
#if defined(KWEB_HAS_GDBUS)
  DbusRelease(lifecycle);
#else
  lifecycle->stopping.store(true, std::memory_order_release);
  if (lifecycle->listener_fd >= 0) {
    shutdown(lifecycle->listener_fd, SHUT_RDWR);
    close(lifecycle->listener_fd);
    lifecycle->listener_fd = -1;
  }
  if (lifecycle->listener.joinable()) lifecycle->listener.join();
  if (!lifecycle->socket_path.empty()) unlink(lifecycle->socket_path.c_str());
  if (lifecycle->lock_fd >= 0) {
    flock(lifecycle->lock_fd, LOCK_UN);
    close(lifecycle->lock_fd);
    lifecycle->lock_fd = -1;
  }
#if defined(__APPLE__)
  ReleaseMacApplication(lifecycle);
#endif
  Unregister(lifecycle);
#endif
}

#endif

}  // namespace

#if defined(__APPLE__)

@implementation KWebApplicationDelegate

- (void)application:(NSApplication *)application openURLs:(NSArray<NSURL *> *)urls {
  (void)application;
  EmitMacUrls(static_cast<NativeLifecycle *>(self.lifecycle), urls);
}

- (void)application:(NSApplication *)application openFiles:(NSArray<NSString *> *)filenames {
  NSMutableArray *urls = [NSMutableArray arrayWithCapacity:filenames.count];
  for (NSString *filename in filenames) {
    NSURL *url = [NSURL fileURLWithPath:filename isDirectory:NO];
    if (url != nil) [urls addObject:url];
  }
  EmitMacUrls(static_cast<NativeLifecycle *>(self.lifecycle), urls);
  [application replyToOpenOrPrint:NSApplicationDelegateReplySuccess];
}

- (BOOL)applicationShouldHandleReopen:(NSApplication *)application
                     hasVisibleWindows:(BOOL)hasVisibleWindows {
  (void)application;
  (void)hasVisibleWindows;
  [[NSRunningApplication currentApplication] activateWithOptions:0];
  return YES;
}

@end

#endif

extern "C" {

uint32_t KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_abi_version(void) {
  return KWEB_APPLICATION_LIFECYCLE_ABI_VERSION;
}

const char *KWEB_APPLICATION_LIFECYCLE_CALL
kweb_application_lifecycle_provider_id(void) {
#if defined(_WIN32)
  return "windows-named-mutex-authenticated-pipe";
#elif defined(__APPLE__)
  return "macos-appkit-launch-services";
#elif defined(KWEB_HAS_GDBUS)
  return "linux-dbus-freedesktop-application";
#else
  return "unsupported-platform";
#endif
}

int32_t KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_acquire(
    const char *application_id, size_t application_id_size,
    const char *transport_root, size_t transport_root_size,
    const uint8_t *initial_payload, size_t initial_payload_size,
    kweb_application_lifecycle_activation_callback callback, void *user_data,
    kweb_application_lifecycle_handle *handle_out) {
  if (!IsValidUtf8Boundary(application_id, application_id_size) ||
      !IsValidUtf8Boundary(transport_root, transport_root_size) ||
      !IsValidPayload(initial_payload, initial_payload_size) || callback == nullptr ||
      handle_out == nullptr) {
    return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  }
  auto lifecycle = std::make_unique<NativeLifecycle>();
  lifecycle->application_id.assign(application_id, application_id_size);
  lifecycle->transport_root.assign(transport_root, transport_root_size);
  lifecycle->callback = callback;
  lifecycle->user_data = user_data;
#if defined(_WIN32)
  const int32_t result = WindowsAcquire(lifecycle.get(), initial_payload, initial_payload_size);
#else
  const int32_t result = PosixAcquire(lifecycle.get(), initial_payload, initial_payload_size);
#endif
  if (result == KWEB_APPLICATION_LIFECYCLE_OK_SECONDARY) return result;
  if (result != KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY) return result;
  *handle_out = reinterpret_cast<kweb_application_lifecycle_handle>(lifecycle.release());
  return result;
}

int32_t KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_release(
    kweb_application_lifecycle_handle handle) {
  if (handle == 0) return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  auto *lifecycle = reinterpret_cast<NativeLifecycle *>(handle);
#if defined(_WIN32)
  WindowsRelease(lifecycle);
#else
  PosixRelease(lifecycle);
#endif
  delete lifecycle;
  return KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY;
}

int32_t KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_register_associations(
    const char *application_id, size_t application_id_size,
    const char *package_root, size_t package_root_size,
    const char *executable, size_t executable_size,
    const char *schemes_csv, size_t schemes_csv_size,
    const char *extensions_csv, size_t extensions_csv_size,
    uint8_t remove, char *digest_out, size_t digest_out_size) {
  if (!IsValidUtf8Boundary(application_id, application_id_size) ||
      !IsValidUtf8Boundary(package_root, package_root_size) ||
      !IsValidUtf8Boundary(executable, executable_size) || digest_out == nullptr ||
      digest_out_size == 0) {
    return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  }
  const auto schemes = schemes_csv_size == 0
                           ? std::vector<std::string>{}
                           : SplitCsv(schemes_csv, schemes_csv_size);
  const auto extensions = extensions_csv_size == 0
                              ? std::vector<std::string>{}
                              : SplitCsv(extensions_csv, extensions_csv_size);
  if ((schemes_csv_size != 0 && schemes.empty()) ||
      (extensions_csv_size != 0 && extensions.empty())) {
    return KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
  }
  const std::string application(application_id, application_id_size);
  const std::string root(package_root, package_root_size);
  const std::string executable_path(executable, executable_size);
  const bool is_remove = remove != 0;
  int32_t status = KWEB_APPLICATION_LIFECYCLE_NOT_SUPPORTED;
#if defined(_WIN32)
  status = WindowsRegisterAssociations(application, executable_path, schemes,
                                       extensions, is_remove);
#elif defined(__APPLE__)
  status = MacRegisterAssociations(root, is_remove);
#elif defined(KWEB_HAS_GDBUS)
  status = LinuxRegisterAssociations(application, executable_path, schemes, extensions, is_remove);
#endif
  if (status != KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY) return status;
  const std::string digest = AssociationDigest(
      application, root, executable_path, schemes, extensions, is_remove);
  return CopyDigest(digest, digest_out, digest_out_size)
             ? KWEB_APPLICATION_LIFECYCLE_OK_PRIMARY
             : KWEB_APPLICATION_LIFECYCLE_INVALID_ARGUMENT;
}

uint64_t KWEB_APPLICATION_LIFECYCLE_CALL kweb_application_lifecycle_live_count(void) {
  std::lock_guard lock(g_registry_mutex);
  return static_cast<uint64_t>(g_registry.size());
}

}  // extern "C"
