#include "engine_configuration.h"

#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

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
            View(log)};
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

} // namespace

int main() {
  TestValidExplicitLayout();
  TestRelativeAndMissingPathsFail();
  TestMismatchedLayoutFails();
  if (failures != 0) {
    std::cerr << failures << " engine configuration assertion(s) failed."
              << std::endl;
    return 1;
  }
  std::cout << "All engine configuration tests passed." << std::endl;
  return 0;
}
