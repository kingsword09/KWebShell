#include "network_policy.h"

#include <algorithm>
#include <cctype>
#include <initializer_list>
#include <map>
#include <optional>
#include <set>
#include <utility>

#include "engine_platform.h"
#include "include/cef_parser.h"
#include "include/cef_request.h"
#include "include/cef_response.h"
#include "include/cef_task.h"
#include "kwebshell/native/cef_network_abi.h"
#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumPolicyBytes = 256 * 1024;
constexpr size_t kMaximumRules = 256;
constexpr size_t kMaximumRuleIdBytes = 128;
constexpr size_t kMaximumPatternBytes = 2048;
constexpr size_t kMaximumHeaderMutations = 32;
constexpr size_t kMaximumHeaderValueBytes = 8192;
constexpr int kMaximumRedirectDepth = 5;

std::string PathToUtf8(const std::filesystem::path &path) {
#if defined(_WIN32)
  const std::u8string utf8 = path.u8string();
  return std::string(reinterpret_cast<const char *>(utf8.data()), utf8.size());
#else
  return path.string();
#endif
}

struct HeaderMutation final {
  std::string name;
  std::optional<std::string> value;
};

struct PolicyRule final {
  std::string id;
  std::string pattern;
  std::vector<std::string> resource_types;
  std::vector<std::string> methods;
  int priority = 0;
  std::string action = "allow";
  std::string redirect_url;
  std::vector<HeaderMutation> header_mutations;
  size_t declaration_order = 0;
};

bool HasString(const CefRefPtr<CefDictionaryValue> &dictionary,
              const char *key) {
  return dictionary && dictionary->HasKey(key) &&
         dictionary->GetType(key) == VTYPE_STRING;
}

bool HasOnlyKeys(const CefRefPtr<CefDictionaryValue> &dictionary,
                 std::initializer_list<const char *> allowed) {
  if (!dictionary) return false;
  CefDictionaryValue::KeyList keys;
  if (!dictionary->GetKeys(keys)) return false;
  for (const auto &key : keys) {
    const std::string name = key.ToString();
    if (std::none_of(allowed.begin(), allowed.end(),
                     [&name](const char *candidate) {
                       return name == candidate;
                     })) {
      return false;
    }
  }
  return true;
}

bool IsBlank(const std::string &value) {
  return value.empty() ||
         std::all_of(value.begin(), value.end(), [](char character) {
           return std::isspace(static_cast<unsigned char>(character)) != 0;
         });
}

bool IsMethodName(const std::string &value) {
  return !value.empty() && value.size() <= 16 &&
         std::all_of(value.begin(), value.end(), [](char character) {
           return (character >= 'A' && character <= 'Z') ||
                  (character >= 'a' && character <= 'z');
         });
}

bool IsHeaderName(const std::string &value) {
  return !value.empty() && value.size() <= 128 &&
         std::all_of(value.begin(), value.end(), [](char character) {
           return (character >= 'A' && character <= 'Z') ||
                  (character >= 'a' && character <= 'z') ||
                  (character >= '0' && character <= '9') ||
                  character == '-';
         });
}

bool IsSafeHeaderValue(const std::string &value) {
  return std::all_of(value.begin(), value.end(), [](char character) {
    const auto byte = static_cast<unsigned char>(character);
    return byte == '\t' || (byte >= 0x20 && byte != 0x7f);
  });
}

bool IsSafeNetworkContextValue(const std::string &value) {
  return std::all_of(value.begin(), value.end(), [](char character) {
    const auto byte = static_cast<unsigned char>(character);
    return byte >= 0x20 && byte != 0x7f;
  });
}

std::string LowerAscii(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](char character) {
    return static_cast<char>(std::tolower(static_cast<unsigned char>(character)));
  });
  return value;
}

bool IsResourceType(const std::string &value) {
  static const std::set<std::string> supported = {
      "main_frame", "sub_frame",  "stylesheet", "script", "image",
      "font",       "object",     "media",      "worker", "xhr",
      "ping",       "csp_report", "other",
  };
  return supported.contains(value);
}

bool IsHttpUrl(const std::string &value) {
  if (value.empty() || value.size() > 8192) return false;
  CefURLParts parts;
  if (!CefParseURL(value, parts)) {
    return false;
  }
  const std::string scheme = CefString(&parts.scheme).ToString();
  return (scheme == "http" || scheme == "https") &&
         !CefString(&parts.host).empty() &&
         CefString(&parts.username).empty() &&
         CefString(&parts.password).empty();
}

bool IsHttpPattern(const std::string &value) {
  if (value.empty() || value.size() > kMaximumPatternBytes) return false;
  if (std::any_of(value.begin(), value.end(), [](char character) {
        const auto byte = static_cast<unsigned char>(character);
        return byte <= 0x20 || byte == 0x7f || character == '\\';
      })) {
    return false;
  }
  std::string candidate = value;
  std::replace(candidate.begin(), candidate.end(), '*', 'x');
  CefURLParts parts;
  if (!CefParseURL(candidate, parts)) return false;
  const std::string scheme = CefString(&parts.scheme).ToString();
  return (scheme == "http" || scheme == "https") &&
         !CefString(&parts.host).empty() &&
         CefString(&parts.username).empty() &&
         CefString(&parts.password).empty();
}

bool GlobMatches(const std::string &pattern, const std::string &value) {
  size_t pattern_index = 0;
  size_t value_index = 0;
  size_t star_index = std::string::npos;
  size_t star_value_index = 0;
  while (value_index < value.size()) {
    if (pattern_index < pattern.size() &&
        (pattern[pattern_index] == value[value_index] ||
         pattern[pattern_index] == '*')) {
      if (pattern[pattern_index] == '*') {
        star_index = pattern_index++;
        star_value_index = value_index;
      } else {
        ++pattern_index;
        ++value_index;
      }
    } else if (star_index != std::string::npos) {
      pattern_index = star_index + 1;
      value_index = ++star_value_index;
    } else {
      return false;
    }
  }
  while (pattern_index < pattern.size() && pattern[pattern_index] == '*') {
    ++pattern_index;
  }
  return pattern_index == pattern.size();
}

std::string ResourceTypeName(CefRequest::ResourceType type) {
  switch (type) {
    case RT_MAIN_FRAME:
      return "main_frame";
    case RT_SUB_FRAME:
      return "sub_frame";
    case RT_STYLESHEET:
      return "stylesheet";
    case RT_SCRIPT:
      return "script";
    case RT_IMAGE:
      return "image";
    case RT_FONT_RESOURCE:
      return "font";
    case RT_OBJECT:
      return "object";
    case RT_MEDIA:
      return "media";
    case RT_WORKER:
    case RT_SHARED_WORKER:
      return "worker";
    case RT_XHR:
      return "xhr";
    case RT_PING:
      return "ping";
    case RT_CSP_REPORT:
      return "csp_report";
    default:
      return "other";
  }
}

bool Contains(const std::vector<std::string> &values, const std::string &value) {
  return values.empty() ||
         std::find(values.begin(), values.end(), value) != values.end();
}

bool ForbiddenHeader(const std::string &name) {
  std::string lower;
  lower.reserve(name.size());
  for (const char value : name) {
    lower.push_back(static_cast<char>(std::tolower(static_cast<unsigned char>(value))));
  }
  return lower == "host" || lower == "content-length" || lower == "cookie" ||
         lower == "set-cookie" || lower == "authorization" ||
         lower == "proxy-authorization" || lower == "origin" ||
         lower == "referer";
}

std::string RedactUrlUserInfo(const std::string &url) {
  const size_t scheme_end = url.find("://");
  if (scheme_end == std::string::npos) return url;
  const size_t authority_start = scheme_end + 3;
  const size_t authority_end = url.find_first_of("/?#", authority_start);
  const size_t end = authority_end == std::string::npos ? url.size()
                                                        : authority_end;
  const size_t user_info_end = url.rfind('@', end);
  if (user_info_end == std::string::npos || user_info_end < authority_start ||
      user_info_end >= end) {
    return url;
  }
  return url.substr(0, authority_start) + url.substr(user_info_end + 1);
}

const char *CompletionStatusName(cef_urlrequest_status_t status) {
  switch (status) {
    case UR_SUCCESS:
      return "success";
    case UR_IO_PENDING:
      return "pending";
    case UR_CANCELED:
      return "canceled";
    case UR_FAILED:
      return "failed";
    case UR_UNKNOWN:
    default:
      return "unknown";
  }
}

std::string JsonEvent(uint64_t request_id, const std::string &phase,
                      const std::string &url, const std::string &method,
                      const std::string &resource_type,
                      const std::string &action, int policy_version,
                      std::optional<int> status_code = std::nullopt,
                      std::optional<std::string> completion_status =
                          std::nullopt,
                      const std::string &redirected_url = {},
                      const std::string &error_id = {}) {
  auto dictionary = CefDictionaryValue::Create();
  dictionary->SetString("requestId", std::to_string(request_id));
  dictionary->SetString("phase", phase);
  dictionary->SetString("url", RedactUrlUserInfo(url));
  dictionary->SetString("method", method);
  dictionary->SetString("resourceType", resource_type);
  dictionary->SetString("action", action);
  dictionary->SetInt("policyVersion", policy_version);
  if (status_code) dictionary->SetInt("statusCode", *status_code);
  if (completion_status) {
    dictionary->SetString("completionStatus", *completion_status);
  }
  if (!redirected_url.empty()) {
    dictionary->SetString("redirectedUrl", RedactUrlUserInfo(redirected_url));
  }
  if (!error_id.empty()) dictionary->SetString("errorId", error_id);
  auto value = CefValue::Create();
  value->SetDictionary(dictionary);
  return CefWriteJSON(value, JSON_WRITER_DEFAULT).ToString();
}

struct RuntimeApi final {
  cef_kweb_network_abi_fingerprint_fn fingerprint = nullptr;
  cef_kweb_network_set_config_fn set_config = nullptr;
  cef_kweb_network_resolve_proxy_fn resolve_proxy = nullptr;
  cef_kweb_network_clear_config_fn clear_config = nullptr;

  bool complete() const {
    return fingerprint && set_config && resolve_proxy && clear_config;
  }
};

RuntimeApi LoadRuntimeApi() {
  RuntimeApi api;
  api.fingerprint = reinterpret_cast<cef_kweb_network_abi_fingerprint_fn>(
      ResolveCefRuntimeSymbol("cef_kweb_network_abi_fingerprint"));
  api.set_config = reinterpret_cast<cef_kweb_network_set_config_fn>(
      ResolveCefRuntimeSymbol("cef_kweb_network_set_config"));
  api.resolve_proxy = reinterpret_cast<cef_kweb_network_resolve_proxy_fn>(
      ResolveCefRuntimeSymbol("cef_kweb_network_resolve_proxy"));
  api.clear_config = reinterpret_cast<cef_kweb_network_clear_config_fn>(
      ResolveCefRuntimeSymbol("cef_kweb_network_clear_config"));
  if (!api.complete() || api.fingerprint() == nullptr ||
      std::string(api.fingerprint()) != CEF_KWEB_NETWORK_ABI_FINGERPRINT) {
    return {};
  }
  return api;
}

RuntimeApi &Runtime() {
  static RuntimeApi api = LoadRuntimeApi();
  return api;
}

kweb_status MapRuntimeStatus(cef_kweb_network_status_t status) {
  switch (status) {
    case CEF_KWEB_NETWORK_STATUS_OK:
      return KWEB_STATUS_OK;
    case CEF_KWEB_NETWORK_STATUS_INVALID_ARGUMENT:
    case CEF_KWEB_NETWORK_STATUS_ABI_MISMATCH:
      return KWEB_STATUS_NETWORK_POLICY_INVALID;
    case CEF_KWEB_NETWORK_STATUS_PROXY_INVALID:
      return KWEB_STATUS_NETWORK_PROXY_INVALID;
    case CEF_KWEB_NETWORK_STATUS_PROFILE_NOT_FOUND:
      return KWEB_STATUS_PROFILE_PATH_INVALID;
    case CEF_KWEB_NETWORK_STATUS_PROXY_RESOLVE_FAILED:
      return KWEB_STATUS_NETWORK_PROXY_UNAVAILABLE;
    case CEF_KWEB_NETWORK_STATUS_USER_AGENT_LOCKED:
      return KWEB_STATUS_NETWORK_USER_AGENT_REQUIRES_PROFILE_REOPEN;
    default:
      return KWEB_STATUS_INTERNAL_ERROR;
  }
}

struct ResolveContext final {
  ProfileNetworkCompletion completion = nullptr;
  void *user_data = nullptr;
};

void KWEB_CEF_NETWORK_CALLBACK ReceiveResolve(
    void *user_data, cef_kweb_network_status_t status,
    cef_kweb_network_string_view result) {
  std::unique_ptr<ResolveContext> context(static_cast<ResolveContext *>(user_data));
  std::string value(result.data == nullptr ? "" : result.data, result.size);
  context->completion(context->user_data, MapRuntimeStatus(status),
                      std::move(value));
}

std::mutex registry_mutex;
std::map<std::filesystem::path, std::shared_ptr<NetworkPolicyState>> registry;

}  // namespace

struct NetworkPolicySnapshot final {
  int version = 1;
  std::vector<PolicyRule> rules;
  cef_kweb_network_proxy_mode_t proxy_mode = CEF_KWEB_NETWORK_PROXY_DIRECT;
  bool pac_mandatory = false;
  std::string proxy_rules;
  std::string pac_url;
  std::string bypass_list;
  std::string user_agent;
  std::string accept_language;
};

class PreparedNetworkPolicy final {
 public:
  PreparedNetworkPolicy(
      std::filesystem::path profile_path,
      std::shared_ptr<NetworkPolicyState> state,
      std::shared_ptr<const NetworkPolicySnapshot> snapshot)
      : profile_path(std::move(profile_path)),
        state(std::move(state)),
        snapshot(std::move(snapshot)) {}

  const std::filesystem::path profile_path;
  const std::shared_ptr<NetworkPolicyState> state;
  const std::shared_ptr<const NetworkPolicySnapshot> snapshot;
};

NetworkPolicyState::NetworkPolicyState()
    : current_(std::make_shared<NetworkPolicySnapshot>()) {}

kweb_status NetworkPolicyState::Prepare(
    const std::string &payload,
    std::shared_ptr<const NetworkPolicySnapshot> *prepared) {
  if (prepared == nullptr) return KWEB_STATUS_INVALID_ARGUMENT;
  if (payload.empty() || payload.size() > kMaximumPolicyBytes) {
    return payload.size() > kMaximumPolicyBytes
               ? KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED
               : KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  CefRefPtr<CefValue> root = CefParseJSON(payload, JSON_PARSER_RFC);
  if (!root || root->GetType() != VTYPE_DICTIONARY) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  auto dictionary = root->GetDictionary();
  if (!dictionary ||
      !HasOnlyKeys(dictionary,
                   {"version", "rules", "proxy", "userAgent",
                    "acceptLanguage"}) ||
      dictionary->GetType("version") != VTYPE_INT ||
      dictionary->GetInt("version") != 1 ||
      dictionary->GetType("rules") != VTYPE_LIST ||
      dictionary->GetType("proxy") != VTYPE_DICTIONARY) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  auto snapshot = std::make_shared<NetworkPolicySnapshot>();
  snapshot->version = 1;
  auto rules = dictionary->GetList("rules");
  if (!rules) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  if (rules->GetSize() > kMaximumRules) {
    return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
  }
  std::map<std::string, bool> ids;
  for (size_t index = 0; index < rules->GetSize(); ++index) {
    auto rule = rules->GetDictionary(index);
    if (!rule ||
        !HasOnlyKeys(rule, {"id", "urlPattern", "resourceTypes", "methods",
                             "priority", "action", "redirectUrl",
                             "headerMutations"}) ||
        !HasString(rule, "id") || !HasString(rule, "urlPattern") ||
        rule->GetType("action") != VTYPE_STRING ||
        rule->GetType("priority") != VTYPE_INT) {
      return KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
    PolicyRule parsed;
    parsed.id = rule->GetString("id").ToString();
    parsed.pattern = rule->GetString("urlPattern").ToString();
    parsed.action = rule->GetString("action").ToString();
    parsed.priority = rule->GetInt("priority");
    parsed.declaration_order = index;
    if (parsed.id.size() > kMaximumRuleIdBytes ||
        parsed.pattern.size() > kMaximumPatternBytes) {
      return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
    }
    if (IsBlank(parsed.id) || !ids.emplace(parsed.id, true).second ||
        !IsHttpPattern(parsed.pattern) ||
        (parsed.action != "allow" && parsed.action != "block" &&
         parsed.action != "redirect")) {
      return KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
    if (rule->HasKey("redirectUrl")) {
      if (!HasString(rule, "redirectUrl")) return KWEB_STATUS_NETWORK_POLICY_INVALID;
      parsed.redirect_url = rule->GetString("redirectUrl").ToString();
    }
    if ((parsed.action == "redirect" &&
         (parsed.redirect_url.empty() || !IsHttpUrl(parsed.redirect_url))) ||
        (parsed.action != "redirect" && !parsed.redirect_url.empty())) {
      if (parsed.action != "redirect") return KWEB_STATUS_NETWORK_POLICY_INVALID;
      return parsed.redirect_url.size() > 8192
                 ? KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED
                 : KWEB_STATUS_NETWORK_REDIRECT_INVALID;
    }
    if (rule->HasKey("methods")) {
      auto methods = rule->GetList("methods");
      if (!methods) return KWEB_STATUS_NETWORK_POLICY_INVALID;
      std::set<std::string> unique_methods;
      for (size_t method_index = 0; method_index < methods->GetSize(); ++method_index) {
        if (methods->GetType(method_index) != VTYPE_STRING) return KWEB_STATUS_NETWORK_POLICY_INVALID;
        const std::string method = methods->GetString(method_index).ToString();
        if (!IsMethodName(method) || !unique_methods.insert(method).second) {
          return KWEB_STATUS_NETWORK_POLICY_INVALID;
        }
        parsed.methods.push_back(method);
      }
    }
    if (rule->HasKey("resourceTypes")) {
      auto types = rule->GetList("resourceTypes");
      if (!types) return KWEB_STATUS_NETWORK_POLICY_INVALID;
      std::set<std::string> unique_types;
      for (size_t type_index = 0; type_index < types->GetSize(); ++type_index) {
        if (types->GetType(type_index) != VTYPE_STRING) return KWEB_STATUS_NETWORK_POLICY_INVALID;
        const std::string type = types->GetString(type_index).ToString();
        if (!IsResourceType(type) || !unique_types.insert(type).second) {
          return KWEB_STATUS_NETWORK_POLICY_INVALID;
        }
        parsed.resource_types.push_back(type);
      }
    }
    if (rule->HasKey("headerMutations")) {
      auto mutations = rule->GetList("headerMutations");
      if (!mutations || mutations->GetSize() > kMaximumHeaderMutations) {
        return mutations && mutations->GetSize() > kMaximumHeaderMutations
                   ? KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED
                   : KWEB_STATUS_NETWORK_POLICY_INVALID;
      }
      std::set<std::string> unique_headers;
      for (size_t mutation_index = 0; mutation_index < mutations->GetSize(); ++mutation_index) {
        auto mutation = mutations->GetDictionary(mutation_index);
        if (!mutation || !HasOnlyKeys(mutation, {"name", "value"}) ||
            !HasString(mutation, "name")) {
          return KWEB_STATUS_NETWORK_POLICY_INVALID;
        }
        HeaderMutation parsed_mutation;
        parsed_mutation.name = mutation->GetString("name").ToString();
        if (parsed_mutation.name.size() > 128) {
          return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
        }
        if (!IsHeaderName(parsed_mutation.name) ||
            !unique_headers.insert(LowerAscii(parsed_mutation.name)).second) {
          return KWEB_STATUS_NETWORK_POLICY_INVALID;
        }
        if (ForbiddenHeader(parsed_mutation.name)) {
          return KWEB_STATUS_NETWORK_HEADER_FORBIDDEN;
        }
        if (mutation->HasKey("value")) {
          if (!HasString(mutation, "value")) return KWEB_STATUS_NETWORK_POLICY_INVALID;
          parsed_mutation.value = mutation->GetString("value").ToString();
          if (parsed_mutation.value->size() > kMaximumHeaderValueBytes) {
            return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
          }
          if (!IsSafeHeaderValue(*parsed_mutation.value)) {
            return KWEB_STATUS_NETWORK_POLICY_INVALID;
          }
        }
        parsed.header_mutations.push_back(std::move(parsed_mutation));
      }
    }
    snapshot->rules.push_back(std::move(parsed));
  }
  std::stable_sort(snapshot->rules.begin(), snapshot->rules.end(),
                   [](const PolicyRule &left, const PolicyRule &right) {
                     return left.priority > right.priority;
                   });

  auto proxy = dictionary->GetDictionary("proxy");
  if (!HasOnlyKeys(proxy, {"mode", "rules", "pacUrl", "pacMandatory",
                            "bypassList"})) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  if (proxy->HasKey("mode") && !HasString(proxy, "mode")) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  const std::string mode = HasString(proxy, "mode")
                               ? proxy->GetString("mode").ToString()
                               : "direct";
  if (mode == "direct") snapshot->proxy_mode = CEF_KWEB_NETWORK_PROXY_DIRECT;
  else if (mode == "fixed") snapshot->proxy_mode = CEF_KWEB_NETWORK_PROXY_FIXED;
  else if (mode == "pac") snapshot->proxy_mode = CEF_KWEB_NETWORK_PROXY_PAC;
  else return KWEB_STATUS_NETWORK_POLICY_INVALID;
  if (proxy->HasKey("rules")) {
    if (!HasString(proxy, "rules")) return KWEB_STATUS_NETWORK_POLICY_INVALID;
    snapshot->proxy_rules = proxy->GetString("rules").ToString();
  }
  if (proxy->HasKey("pacUrl")) {
    if (!HasString(proxy, "pacUrl")) return KWEB_STATUS_NETWORK_POLICY_INVALID;
    snapshot->pac_url = proxy->GetString("pacUrl").ToString();
  }
  if (proxy->HasKey("pacMandatory")) {
    if (proxy->GetType("pacMandatory") != VTYPE_BOOL) return KWEB_STATUS_NETWORK_POLICY_INVALID;
    snapshot->pac_mandatory = proxy->GetBool("pacMandatory");
  }
  if (proxy->HasKey("bypassList")) {
    auto bypass = proxy->GetList("bypassList");
    if (!bypass) return KWEB_STATUS_NETWORK_POLICY_INVALID;
    for (size_t index = 0; index < bypass->GetSize(); ++index) {
      if (bypass->GetType(index) != VTYPE_STRING) return KWEB_STATUS_NETWORK_POLICY_INVALID;
      const std::string value = bypass->GetString(index).ToString();
      if (value.size() > 2048) {
        return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
      }
      if (IsBlank(value)) {
        return KWEB_STATUS_NETWORK_POLICY_INVALID;
      }
      if (!snapshot->bypass_list.empty()) snapshot->bypass_list.append(",");
      snapshot->bypass_list.append(value);
    }
  }
  if (snapshot->proxy_rules.size() > 8192 ||
      snapshot->bypass_list.size() > 8192) {
    return KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED;
  }
  if (snapshot->proxy_mode == CEF_KWEB_NETWORK_PROXY_DIRECT &&
      (!snapshot->proxy_rules.empty() || !snapshot->pac_url.empty() ||
       !snapshot->bypass_list.empty() || snapshot->pac_mandatory)) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  if (snapshot->proxy_mode == CEF_KWEB_NETWORK_PROXY_FIXED &&
      (snapshot->proxy_rules.empty() || !snapshot->pac_url.empty() ||
       snapshot->pac_mandatory)) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  if (snapshot->proxy_mode == CEF_KWEB_NETWORK_PROXY_PAC &&
      (!snapshot->proxy_rules.empty() || !snapshot->bypass_list.empty() ||
       !IsHttpUrl(snapshot->pac_url))) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  if (dictionary->HasKey("userAgent")) {
    if (!HasString(dictionary, "userAgent")) {
      return KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
    snapshot->user_agent = dictionary->GetString("userAgent").ToString();
    if (IsBlank(snapshot->user_agent) ||
        !IsSafeNetworkContextValue(snapshot->user_agent) ||
        snapshot->user_agent.size() > 1024) {
      return snapshot->user_agent.size() > 1024
                 ? KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED
                 : KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
  }
  if (dictionary->HasKey("acceptLanguage")) {
    if (!HasString(dictionary, "acceptLanguage")) {
      return KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
    snapshot->accept_language = dictionary->GetString("acceptLanguage").ToString();
    if (IsBlank(snapshot->accept_language) ||
        !IsSafeNetworkContextValue(snapshot->accept_language) ||
        snapshot->accept_language.size() > 1024) {
      return snapshot->accept_language.size() > 1024
                 ? KWEB_STATUS_NETWORK_POLICY_LIMIT_EXCEEDED
                 : KWEB_STATUS_NETWORK_POLICY_INVALID;
    }
  }
  *prepared = std::move(snapshot);
  return KWEB_STATUS_OK;
}

void NetworkPolicyState::Install(
    std::shared_ptr<const NetworkPolicySnapshot> snapshot) {
  std::lock_guard lock(mutex_);
  current_ = std::move(snapshot);
  runtime_policy_installed_ = true;
}

void NetworkPolicyState::Clear() {
  std::lock_guard lock(mutex_);
  current_ = std::make_shared<NetworkPolicySnapshot>();
  runtime_policy_installed_ = false;
  redirect_depth_.clear();
}

bool NetworkPolicyState::HasInstalledRuntimePolicy() const {
  std::lock_guard lock(mutex_);
  return runtime_policy_installed_;
}

int NetworkPolicyState::IncrementRedirectDepth(uint64_t request_id) {
  std::lock_guard lock(mutex_);
  return ++redirect_depth_[request_id];
}

void NetworkPolicyState::ClearRedirectDepth(uint64_t request_id) {
  std::lock_guard lock(mutex_);
  redirect_depth_.erase(request_id);
}

std::shared_ptr<const NetworkPolicySnapshot> NetworkPolicyState::Snapshot() const {
  std::lock_guard lock(mutex_);
  return current_;
}

struct Decision final {
  std::string action = "allow";
  std::string redirect_url;
  std::vector<HeaderMutation> header_mutations;
  int version = 1;
};

std::optional<Decision> Match(const NetworkPolicySnapshot &snapshot,
                              CefRefPtr<CefRequest> request) {
  const std::string url = request->GetURL().ToString();
  const std::string method = request->GetMethod().ToString();
  const std::string resource_type = ResourceTypeName(request->GetResourceType());
  for (const auto &rule : snapshot.rules) {
    if (!GlobMatches(rule.pattern, url) || !Contains(rule.methods, method) ||
        !Contains(rule.resource_types, resource_type)) {
      continue;
    }
    return Decision{rule.action, rule.redirect_url, rule.header_mutations,
                    snapshot.version};
  }
  return std::nullopt;
}

NetworkPolicyRequestHandler::NetworkPolicyRequestHandler(
    std::shared_ptr<NetworkPolicyState> state, NetworkEventSink event_sink)
    : state_(std::move(state)), event_sink_(std::move(event_sink)) {}

std::shared_ptr<const NetworkPolicySnapshot>
NetworkPolicyRequestHandler::CaptureSnapshot(uint64_t request_id) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found != requests_.end()) return found->second.snapshot;
  auto snapshot = state_->Snapshot();
  if (snapshot) requests_.emplace(request_id, RequestObservation{snapshot});
  return snapshot;
}

void NetworkPolicyRequestHandler::CaptureInitialDecision(
    uint64_t request_id, const std::string &action) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found == requests_.end() || found->second.initial_decision_captured) {
    return;
  }
  found->second.initial_action = action;
  found->second.initial_decision_captured = true;
}

void NetworkPolicyRequestHandler::UpdateTerminalResult(
    uint64_t request_id, const std::string &action,
    const std::string &error_id) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found == requests_.end()) return;
  found->second.terminal_action = action;
  found->second.error_id = error_id;
}

std::optional<NetworkPolicyRequestHandler::RequestObservation>
NetworkPolicyRequestHandler::TakeObservation(uint64_t request_id) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found == requests_.end()) return std::nullopt;
  RequestObservation observation = std::move(found->second);
  requests_.erase(found);
  return observation;
}

CefResourceRequestHandler::ReturnValue
NetworkPolicyRequestHandler::OnBeforeResourceLoad(
    CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
    CefRefPtr<CefRequest> request, CefRefPtr<CefCallback> callback) {
  (void)browser;
  (void)frame;
  (void)callback;
  if (!request) return RV_CONTINUE;
  const uint64_t request_id = static_cast<uint64_t>(request->GetIdentifier());
  auto snapshot = CaptureSnapshot(request_id);
  if (!snapshot || !request) return RV_CONTINUE;
  const std::string original_url = request->GetURL().ToString();
  const auto decision = Match(*snapshot, request);
  const Decision effective =
      decision.value_or(Decision{"allow", {}, {}, snapshot->version});
  CaptureInitialDecision(request_id, effective.action);
  const std::string method = request->GetMethod().ToString();
  const std::string resource_type = ResourceTypeName(request->GetResourceType());
  if (effective.action == "block") {
    UpdateTerminalResult(request_id, "block");
    if (event_sink_) {
      event_sink_(JsonEvent(request_id, "before-request", original_url, method,
                            resource_type, "block", effective.version));
    }
    return RV_CANCEL;
  }
  if (!effective.header_mutations.empty()) {
    CefRequest::HeaderMap headers;
    request->GetHeaderMap(headers);
    for (const auto &mutation : effective.header_mutations) {
      for (auto it = headers.begin(); it != headers.end();) {
        const std::string header_name = it->first.ToString();
        if (header_name.size() == mutation.name.size() &&
            std::equal(mutation.name.begin(), mutation.name.end(),
                       header_name.begin(),
                       [](char left, char right) {
                         return std::tolower(static_cast<unsigned char>(left)) ==
                                std::tolower(static_cast<unsigned char>(right));
                       })) {
          it = headers.erase(it);
        } else {
          ++it;
        }
      }
      if (mutation.value) headers.emplace(mutation.name, *mutation.value);
    }
    request->SetHeaderMap(headers);
  }
  std::string redirected_url;
  if (effective.action == "redirect") {
    const int depth = state_->IncrementRedirectDepth(request->GetIdentifier());
    if (depth > kMaximumRedirectDepth) {
      state_->ClearRedirectDepth(request->GetIdentifier());
      UpdateTerminalResult(request_id, "block", "network.redirect.loop");
      if (event_sink_) {
        event_sink_(JsonEvent(request_id, "before-request", original_url,
                              method, resource_type, "block", effective.version,
                              std::nullopt, std::nullopt, {},
                              "network.redirect.loop"));
      }
      return RV_CANCEL;
    }
    redirected_url = effective.redirect_url;
    request->SetURL(redirected_url);
  }
  if (event_sink_) {
    event_sink_(JsonEvent(request_id, "before-request", original_url, method,
                          resource_type, effective.action, effective.version,
                          std::nullopt, std::nullopt, redirected_url));
  }
  return RV_CONTINUE;
}

void NetworkPolicyRequestHandler::OnResourceLoadComplete(
    CefRefPtr<CefBrowser> browser, CefRefPtr<CefFrame> frame,
    CefRefPtr<CefRequest> request, CefRefPtr<CefResponse> response,
    URLRequestStatus status, int64_t received_content_length) {
  (void)browser;
  (void)frame;
  (void)received_content_length;
  if (!request) return;
  const uint64_t request_id = static_cast<uint64_t>(request->GetIdentifier());
  state_->ClearRedirectDepth(request_id);
  const auto observation = TakeObservation(request_id);
  if (!observation || !observation->snapshot || !event_sink_) return;
  event_sink_(JsonEvent(
      request_id, "complete", request->GetURL().ToString(),
      request->GetMethod().ToString(), ResourceTypeName(request->GetResourceType()),
      observation->initial_action, observation->snapshot->version,
      response ? std::optional<int>(response->GetStatus()) : std::nullopt,
      std::string(CompletionStatusName(status)), {}, observation->error_id));
}

std::shared_ptr<NetworkPolicyState> CreateNetworkPolicyState() {
  return std::make_shared<NetworkPolicyState>();
}

kweb_status RequireProfileNetworkRuntime() {
  return Runtime().complete() ? KWEB_STATUS_OK
                              : KWEB_STATUS_NETWORK_RUNTIME_CAPABILITY_MISSING;
}

std::shared_ptr<NetworkPolicyState> GetNetworkPolicyState(
    const std::filesystem::path &profile_path) {
  std::lock_guard lock(registry_mutex);
  auto found = registry.find(profile_path);
  if (found != registry.end()) return found->second;
  auto state = CreateNetworkPolicyState();
  registry.emplace(profile_path, state);
  return state;
}

kweb_status PrepareProfileNetworkPolicy(
    const std::filesystem::path &profile_path, const std::string &payload,
    std::shared_ptr<PreparedNetworkPolicy> *prepared) {
  if (prepared == nullptr) return KWEB_STATUS_INVALID_ARGUMENT;
  if (CefCurrentlyOn(TID_UI)) return KWEB_STATUS_WRONG_THREAD;
  auto state = GetNetworkPolicyState(profile_path);
  std::shared_ptr<const NetworkPolicySnapshot> snapshot;
  const kweb_status status = state->Prepare(payload, &snapshot);
  if (status != KWEB_STATUS_OK) return status;
  if (!snapshot) return KWEB_STATUS_NETWORK_POLICY_INVALID;
  *prepared = std::make_shared<PreparedNetworkPolicy>(
      profile_path, std::move(state), std::move(snapshot));
  return KWEB_STATUS_OK;
}

kweb_status SetProfileNetworkPolicy(
    const std::filesystem::path &profile_path,
    const std::shared_ptr<PreparedNetworkPolicy> &prepared,
    std::string *result_payload) {
  if (!prepared || prepared->profile_path != profile_path ||
      !prepared->state || !prepared->snapshot) {
    return KWEB_STATUS_INVALID_ARGUMENT;
  }
  if (!CefCurrentlyOn(TID_UI)) return KWEB_STATUS_WRONG_THREAD;
  auto &runtime = Runtime();
  if (!runtime.complete()) return KWEB_STATUS_NETWORK_RUNTIME_CAPABILITY_MISSING;
  const auto &snapshot = prepared->snapshot;
  const std::string profile_path_utf8 = PathToUtf8(profile_path);
  auto view = [](const std::string &value) -> cef_kweb_network_string_view {
    return {value.data(), value.size()};
  };
  const cef_kweb_network_proxy_config config = {
      sizeof(cef_kweb_network_proxy_config),
      CEF_KWEB_NETWORK_ABI_VERSION,
      snapshot->proxy_mode,
      snapshot->pac_mandatory ? 1U : 0U,
      view(profile_path_utf8),
      view(snapshot->proxy_rules),
      view(snapshot->pac_url),
      view(snapshot->bypass_list),
      view(snapshot->user_agent),
      view(snapshot->accept_language),
  };
  const kweb_status runtime_status = MapRuntimeStatus(runtime.set_config(&config));
  if (runtime_status == KWEB_STATUS_OK) {
    prepared->state->Install(snapshot);
    if (result_payload) *result_payload = "{\"version\":1}";
  }
  return runtime_status;
}

kweb_status ParseProxyResolutionPayload(const std::string &payload,
                                        std::string *url) {
  if (url == nullptr) return KWEB_STATUS_INVALID_ARGUMENT;
  if (CefCurrentlyOn(TID_UI)) return KWEB_STATUS_WRONG_THREAD;
  auto root = CefParseJSON(payload, JSON_PARSER_RFC);
  if (!root || root->GetType() != VTYPE_DICTIONARY ||
      !HasString(root->GetDictionary(), "url")) {
    return KWEB_STATUS_NETWORK_POLICY_INVALID;
  }
  std::string parsed_url =
      root->GetDictionary()->GetString("url").ToString();
  if (!IsHttpUrl(parsed_url)) return KWEB_STATUS_NETWORK_POLICY_INVALID;
  *url = std::move(parsed_url);
  return KWEB_STATUS_OK;
}

kweb_status ResolveProfileProxy(const std::filesystem::path &profile_path,
                                const std::string &url,
                                ProfileNetworkCompletion completion,
                                void *user_data) {
  if (!completion) return KWEB_STATUS_INVALID_ARGUMENT;
  if (!CefCurrentlyOn(TID_UI)) return KWEB_STATUS_WRONG_THREAD;
  if (url.empty()) return KWEB_STATUS_NETWORK_POLICY_INVALID;
  auto &runtime = Runtime();
  if (!runtime.complete()) return KWEB_STATUS_NETWORK_RUNTIME_CAPABILITY_MISSING;
  auto *context = new ResolveContext{completion, user_data};
  const std::string profile_path_utf8 = PathToUtf8(profile_path);
  const auto profile_view = cef_kweb_network_string_view{
      profile_path_utf8.data(), profile_path_utf8.size()};
  const auto url_view = cef_kweb_network_string_view{url.data(), url.size()};
  const auto status = runtime.resolve_proxy(profile_view, url_view,
                                             &ReceiveResolve, context);
  if (status != CEF_KWEB_NETWORK_STATUS_OK) {
    delete context;
    return MapRuntimeStatus(status);
  }
  return KWEB_STATUS_OK;
}

kweb_status ClearProfileNetworkPolicy(
    const std::filesystem::path &profile_path) {
  auto &runtime = Runtime();
  auto state = GetNetworkPolicyState(profile_path);
  if (!runtime.complete()) {
    if (state->HasInstalledRuntimePolicy()) {
      return KWEB_STATUS_NETWORK_RUNTIME_CAPABILITY_MISSING;
    }
    std::lock_guard lock(registry_mutex);
    registry.erase(profile_path);
    return KWEB_STATUS_OK;
  }
  const std::string path = PathToUtf8(profile_path);
  const auto status = runtime.clear_config({path.data(), path.size()});
  if (status != CEF_KWEB_NETWORK_STATUS_OK) return MapRuntimeStatus(status);
  std::lock_guard lock(registry_mutex);
  registry.erase(profile_path);
  return KWEB_STATUS_OK;
}

kweb_status ReleaseAllProfileNetworkPolicies() {
  std::vector<std::filesystem::path> paths;
  {
    std::lock_guard lock(registry_mutex);
    paths.reserve(registry.size());
    for (const auto &[path, state] : registry) {
      (void)state;
      paths.push_back(path);
    }
  }
  if (paths.empty()) return KWEB_STATUS_OK;

  auto &runtime = Runtime();
  if (runtime.complete()) {
    for (const auto &profile_path : paths) {
      const std::string path = PathToUtf8(profile_path);
      const kweb_status status =
          MapRuntimeStatus(runtime.clear_config({path.data(), path.size()}));
      if (status != KWEB_STATUS_OK) return status;
    }
  } else {
    const bool has_installed_policy = std::any_of(
        paths.begin(), paths.end(), [](const std::filesystem::path &path) {
          const auto state = GetNetworkPolicyState(path);
          return state && state->HasInstalledRuntimePolicy();
        });
    if (has_installed_policy) {
      return KWEB_STATUS_NETWORK_RUNTIME_CAPABILITY_MISSING;
    }
  }
  std::lock_guard lock(registry_mutex);
  for (const auto &path : paths) registry.erase(path);
  return KWEB_STATUS_OK;
}

}  // namespace kwebshell
