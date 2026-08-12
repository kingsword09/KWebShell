#include "engine_configuration.h"
#include "remote_debugging_port.h"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

#if defined(_WIN32)
#include <winsock2.h>
#else
#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace {

int failures = 0;

void Check(bool condition, const std::string &message) {
  if (!condition) {
    std::cerr << "FAILED: " << message << std::endl;
    ++failures;
  }
}

void WriteFile(const std::filesystem::path &path) {
  std::filesystem::create_directories(path.parent_path());
  std::ofstream stream(path, std::ios::binary);
  stream << "contract";
  stream.close();
#if !defined(_WIN32)
  std::filesystem::permissions(path,
                               std::filesystem::perms::owner_read |
                                   std::filesystem::perms::owner_write |
                                   std::filesystem::perms::owner_exec |
                                   std::filesystem::perms::group_read |
                                   std::filesystem::perms::group_exec |
                                   std::filesystem::perms::others_read |
                                   std::filesystem::perms::others_exec,
                               std::filesystem::perm_options::replace);
#endif
}

struct Fixture final {
  std::filesystem::path root;
  std::filesystem::path runtime;
  std::filesystem::path subprocess;
  std::filesystem::path resources;
  std::filesystem::path locales;
  std::filesystem::path cache;
  std::filesystem::path log;

  Fixture() {
    const auto id = std::chrono::steady_clock::now().time_since_epoch().count();
    root = std::filesystem::temp_directory_path() /
           ("kwebshell-engine-configuration-" + std::to_string(id));
#if defined(__APPLE__)
    const auto application = root / "KWebShell.app";
    const auto frameworks = application / "Contents" / "Frameworks";
    const auto framework = frameworks / "Chromium Embedded Framework.framework";
    runtime = framework / "Chromium Embedded Framework";
    resources = framework / "Resources";
    locales = resources;
    subprocess = frameworks / "KWebShell Helper.app" / "Contents" / "MacOS" /
                 "KWebShell Helper";
    WriteFile(resources / "en.lproj" / "locale.pak");
#elif defined(_WIN32)
    resources = root / "runtime";
    runtime = resources / "libcef.dll";
    locales = resources / "locales";
    subprocess = resources / "KWebShell.exe";
    WriteFile(locales / "en-US.pak");
#else
    resources = root / "runtime";
    runtime = resources / "libcef.so";
    locales = resources / "locales";
    subprocess = resources / "KWebShell";
    WriteFile(locales / "en-US.pak");
#endif
    WriteFile(runtime);
    WriteFile(subprocess);
    WriteFile(resources / "resources.pak");
    WriteFile(resources / "icudtl.dat");
    cache = root / "cache";
    std::filesystem::create_directories(cache);
    log = cache / "cef.log";
  }

  ~Fixture() {
    std::error_code error;
    std::filesystem::remove_all(root, error);
  }
};

struct ConfigurationStorage final {
  std::string runtime;
  std::string subprocess;
  std::string resources;
  std::string locales;
  std::string cache;
  std::string log;
  int32_t remote_debugging_port = 0;

  explicit ConfigurationStorage(const Fixture &fixture)
      : runtime(fixture.runtime.string()),
        subprocess(fixture.subprocess.string()),
        resources(fixture.resources.string()),
        locales(fixture.locales.string()), cache(fixture.cache.string()),
        log(fixture.log.string()) {}

  static kweb_string_view View(const std::string &value) {
    return {value.data(), value.size()};
  }

  kweb_engine_config Configuration() const {
    return {sizeof(kweb_engine_config),
            KWEB_ABI_VERSION,
            nullptr,
            nullptr,
            View(runtime),
            View(subprocess),
            View(resources),
            View(locales),
            View(cache),
            View(log),
            remote_debugging_port,
            0};
  }
};

void TestValidExplicitLayout() {
  Fixture fixture;
  ConfigurationStorage storage(fixture);
  const auto configuration = storage.Configuration();
  kwebshell::ValidatedEngineConfiguration validated;
  Check(kwebshell::ValidateEngineConfiguration(configuration, &validated) ==
            KWEB_STATUS_OK,
        "the declared platform runtime layout should be accepted");
  Check(std::filesystem::is_regular_file(fixture.log),
        "validation should prove that the explicit log path is writable");
  Check(validated.root_cache_path == std::filesystem::canonical(fixture.cache),
        "the validated root cache should be canonical");
}

void TestRelativeAndMissingPathsFail() {
  Fixture fixture;
  ConfigurationStorage storage(fixture);
  storage.cache = "relative-cache";
  auto configuration = storage.Configuration();
  kwebshell::ValidatedEngineConfiguration validated;
  Check(kwebshell::ValidateEngineConfiguration(configuration, &validated) ==
            KWEB_STATUS_PATH_NOT_ABSOLUTE,
        "a relative root cache should fail before CEF initialization");

  storage = ConfigurationStorage(fixture);
  std::filesystem::remove(fixture.runtime);
  configuration = storage.Configuration();
  Check(kwebshell::ValidateEngineConfiguration(configuration, &validated) ==
            KWEB_STATUS_PATH_NOT_FOUND,
        "a missing runtime binary should fail before CEF initialization");
}

void TestMismatchedLayoutFails() {
  Fixture fixture;
  ConfigurationStorage storage(fixture);
  const auto other_directory = fixture.root / "other";
  std::filesystem::create_directories(other_directory);
  storage.log = (other_directory / "cef.log").string();
  const auto configuration = storage.Configuration();
  kwebshell::ValidatedEngineConfiguration validated;
  Check(kwebshell::ValidateEngineConfiguration(configuration, &validated) ==
            KWEB_STATUS_PATH_MISMATCH,
        "a log outside the declared root cache should be rejected");
}

void TestRemoteDebuggingPortValidation() {
  Fixture fixture;
  ConfigurationStorage storage(fixture);
  kwebshell::ValidatedEngineConfiguration validated;
  storage.remote_debugging_port = 1023;
  Check(kwebshell::ValidateEngineConfiguration(storage.Configuration(),
                                               &validated) ==
            KWEB_STATUS_REMOTE_DEBUGGING_PORT_INVALID,
        "ports below the explicit remote-debugging range must fail");
  storage.remote_debugging_port = 9222;
  Check(kwebshell::ValidateEngineConfiguration(storage.Configuration(),
                                               &validated) == KWEB_STATUS_OK,
        "a fixed remote-debugging port must be accepted");
}

void TestRemoteDebuggingPortAvailability() {
  Check(kwebshell::ValidateRemoteDebuggingPortAvailability(0) ==
            KWEB_STATUS_OK,
        "port zero should disable remote debugging without probing");
#if defined(_WIN32)
  WSADATA data{};
  Check(WSAStartup(MAKEWORD(2, 2), &data) == 0,
        "the test should initialize Winsock");
  SOCKET socket_fd = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  Check(socket_fd != INVALID_SOCKET,
        "the test should create an IPv4 loopback socket");
  if (socket_fd == INVALID_SOCKET) {
    WSACleanup();
    return;
  }
#else
  const int socket_fd = ::socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  Check(socket_fd >= 0, "the test should create an IPv4 loopback socket");
  if (socket_fd < 0) {
    return;
  }
#endif
  sockaddr_in address{};
  address.sin_family = AF_INET;
  address.sin_port = 0;
  address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
  Check(::bind(socket_fd, reinterpret_cast<sockaddr *>(&address),
               sizeof(address)) == 0,
        "the test should reserve an IPv4 loopback port");
#if defined(_WIN32)
  int address_size = sizeof(address);
#else
  socklen_t address_size = sizeof(address);
#endif
  Check(::getsockname(socket_fd, reinterpret_cast<sockaddr *>(&address),
                      &address_size) == 0,
        "the test should discover the reserved port");
  Check(kwebshell::ValidateRemoteDebuggingPortAvailability(
            ntohs(address.sin_port)) ==
            KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE,
        "an occupied loopback port should be rejected");
#if defined(_WIN32)
  closesocket(socket_fd);
  WSACleanup();
#else
  ::close(socket_fd);
#endif
}

} // namespace

int main() {
  TestValidExplicitLayout();
  TestRelativeAndMissingPathsFail();
  TestMismatchedLayoutFails();
  TestRemoteDebuggingPortValidation();
  TestRemoteDebuggingPortAvailability();
  if (failures != 0) {
    std::cerr << failures << " engine configuration assertion(s) failed."
              << std::endl;
    return 1;
  }
  std::cout << "All engine configuration tests passed." << std::endl;
  return 0;
}
