#include "bridge_protocol.h"

#include "utf8_validation.h"

namespace kwebshell {

CefMessageRouterConfig BridgeRouterConfig() {
  CefMessageRouterConfig config;
  config.js_query_function = kBridgeQueryFunction;
  config.js_cancel_function = kBridgeCancelFunction;
  return config;
}

std::optional<std::string> ValidateBridgeOrigin(kweb_string_view value) {
  if (value.data == nullptr || value.size == 0 ||
      value.size > kMaximumBridgePayloadSize ||
      !IsValidUtf8(value.data, value.size)) {
    return std::nullopt;
  }
  CefURLParts parts;
  const std::string value_string(value.data, value.size);
  if (!CefParseURL(value_string, parts)) {
    return std::nullopt;
  }
  const std::string scheme = CefString(&parts.scheme).ToString();
  const std::string origin = CefString(&parts.origin).ToString();
  if ((scheme != "http" && scheme != "https") || origin.empty() ||
      !CefString(&parts.username).empty() ||
      !CefString(&parts.password).empty() ||
      CefString(&parts.path).ToString() != "/" ||
      !CefString(&parts.query).empty() ||
      !CefString(&parts.fragment).empty()) {
    return std::nullopt;
  }
  return origin;
}

std::optional<std::string> BridgeOriginFromUrl(const CefString &url) {
  CefURLParts parts;
  if (!CefParseURL(url, parts)) {
    return std::nullopt;
  }
  const std::string origin = CefString(&parts.origin).ToString();
  return origin.empty() ? std::nullopt
                        : std::optional<std::string>(origin);
}

bool IsValidBridgeJson(const std::string &value) {
  return !value.empty() && value.size() <= kMaximumBridgePayloadSize &&
         CefParseJSON(value.data(), value.size(), JSON_PARSER_RFC) != nullptr;
}

} // namespace kwebshell
