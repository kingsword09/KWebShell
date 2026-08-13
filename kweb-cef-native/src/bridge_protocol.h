#ifndef KWEBSHELL_NATIVE_BRIDGE_PROTOCOL_H_
#define KWEBSHELL_NATIVE_BRIDGE_PROTOCOL_H_

#include <optional>
#include <string>

#include "include/cef_parser.h"
#include "include/wrapper/cef_message_router.h"
#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

inline constexpr char kBridgeEnabledKey[] = "kweb.bridge.enabled";
inline constexpr char kBridgeOriginKey[] = "kweb.bridge.origin";
inline constexpr char kBridgeQueryFunction[] = "__kwebBridgeQuery";
inline constexpr char kBridgeCancelFunction[] = "__kwebBridgeCancel";
inline constexpr int kBridgeFailureCode = 1;
inline constexpr size_t kMaximumBridgePayloadSize = 1024 * 1024;

CefMessageRouterConfig BridgeRouterConfig();
std::optional<std::string> ValidateBridgeOrigin(kweb_string_view value);
std::optional<std::string> BridgeOriginFromUrl(const CefString &url);
bool IsValidBridgeJson(const std::string &value);

} // namespace kwebshell

#endif // KWEBSHELL_NATIVE_BRIDGE_PROTOCOL_H_
