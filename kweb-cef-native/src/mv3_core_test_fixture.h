#ifndef KWEBSHELL_NATIVE_MV3_CORE_TEST_FIXTURE_H_
#define KWEBSHELL_NATIVE_MV3_CORE_TEST_FIXTURE_H_

#include <filesystem>
#include <optional>
#include <string>

namespace kwebshell {

std::optional<std::filesystem::path>
ValidateMv3CoreTestFixture(const std::filesystem::path &requested_path,
                           std::string &error);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_MV3_CORE_TEST_FIXTURE_H_
