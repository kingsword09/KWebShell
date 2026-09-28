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
  const std::string scheme =
      LowerAscii(CefString(&parts.scheme).ToString());
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
  const std::string scheme =
      LowerAscii(CefString(&parts.scheme).ToString());
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

std::mutex registry_mutex;
std::map<std::filesystem::path, std::shared_ptr<NetworkPolicyState>> registry;

}  // namespace

struct NetworkPolicySnapshot final {
  int version = 1;
  std::vector<PolicyRule> rules;
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
      !HasOnlyKeys(dictionary, {"version", "rules"}) ||
      dictionary->GetType("version") != VTYPE_INT ||
      dictionary->GetInt("version") != 1 ||
      dictionary->GetType("rules") != VTYPE_LIST) {
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

  *prepared = std::move(snapshot);
  return KWEB_STATUS_OK;
}

void NetworkPolicyState::Install(
    std::shared_ptr<const NetworkPolicySnapshot> snapshot) {
  std::lock_guard lock(mutex_);
  current_ = std::move(snapshot);
}

void NetworkPolicyState::Clear() {
  std::lock_guard lock(mutex_);
  current_ = std::make_shared<NetworkPolicySnapshot>();
  redirect_depth_.clear();
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
NetworkPolicyRequestHandler::CaptureSnapshot(uint64_t request_id,
                                              uint64_t *public_request_id) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found != requests_.end()) {
    if (public_request_id) *public_request_id = found->second.public_request_id;
    return found->second.snapshot;
  }
  auto snapshot = state_->Snapshot();
  if (snapshot) {
    if (next_public_request_id_ == 0) next_public_request_id_ = 1;
    const uint64_t assigned_id = next_public_request_id_++;
    requests_.emplace(request_id, RequestObservation{});
    auto inserted = requests_.find(request_id);
    inserted->second.public_request_id = assigned_id;
    inserted->second.snapshot = snapshot;
    if (public_request_id) *public_request_id = assigned_id;
  }
  return snapshot;
}

void NetworkPolicyRequestHandler::CaptureInitialDecision(
    uint64_t request_id, const std::string &url, const std::string &method,
    const std::string &resource_type, const std::string &action) {
  std::lock_guard lock(request_mutex_);
  auto found = requests_.find(request_id);
  if (found == requests_.end() || found->second.initial_decision_captured) {
    return;
  }
  found->second.url = url;
  found->second.method = method;
  found->second.resource_type = resource_type;
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
  const std::string original_url = request->GetURL().ToString();
  if (original_url.rfind("http://", 0) != 0 &&
      original_url.rfind("https://", 0) != 0) {
    return RV_CONTINUE;
  }
  const uint64_t request_id = static_cast<uint64_t>(request->GetIdentifier());
  uint64_t public_request_id = 0;
  auto snapshot = CaptureSnapshot(request_id, &public_request_id);
  if (!snapshot || !request) return RV_CONTINUE;
  const auto decision = Match(*snapshot, request);
  const Decision effective =
      decision.value_or(Decision{"allow", {}, {}, snapshot->version});
  const std::string method = request->GetMethod().ToString();
  const std::string resource_type = ResourceTypeName(request->GetResourceType());
  CaptureInitialDecision(request_id, original_url, method, resource_type,
                         effective.action);
  if (effective.action == "block") {
    UpdateTerminalResult(request_id, "block");
    if (event_sink_) {
      event_sink_(JsonEvent(public_request_id, "before-request", original_url, method,
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
        event_sink_(JsonEvent(public_request_id, "before-request", original_url,
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
    event_sink_(JsonEvent(public_request_id, "before-request", original_url, method,
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
  const std::optional<int> status_code =
      response && response->GetStatus() > 0
          ? std::optional<int>(response->GetStatus())
          : std::nullopt;
  const std::string completion_status =
      observation->initial_action == "block"
          ? "canceled"
          : CompletionStatusName(status);
  event_sink_(JsonEvent(
      observation->public_request_id, "complete", observation->url,
      observation->method, observation->resource_type,
      observation->initial_action, observation->snapshot->version,
      status_code, completion_status, {}, observation->error_id));
}

std::shared_ptr<NetworkPolicyState> CreateNetworkPolicyState() {
  return std::make_shared<NetworkPolicyState>();
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
  prepared->state->Install(prepared->snapshot);
  if (result_payload) *result_payload = "{\"version\":1}";
  return KWEB_STATUS_OK;
}

kweb_status ClearProfileNetworkPolicy(
    const std::filesystem::path &profile_path) {
  auto state = GetNetworkPolicyState(profile_path);
  state->Clear();
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
  for (const auto &path : paths) GetNetworkPolicyState(path)->Clear();
  std::lock_guard lock(registry_mutex);
  for (const auto &path : paths) registry.erase(path);
  return KWEB_STATUS_OK;
}

}  // namespace kwebshell
