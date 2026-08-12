#ifndef KWEBSHELL_NATIVE_ENGINE_INTERNAL_H_
#define KWEBSHELL_NATIVE_ENGINE_INTERNAL_H_

#include <filesystem>

#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

kweb_status ValidateEngineForBrowser(kweb_engine_handle engine,
                                     std::filesystem::path *root_cache_out);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_ENGINE_INTERNAL_H_
