#include "security_challenges.h"

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <map>
#include <optional>
#include <set>
#include <string_view>
#include <utility>
#include <vector>

#include "bridge_protocol.h"
#include "include/base/cef_callback.h"
#include "include/base/cef_bind.h"
#include "include/cef_parser.h"
#include "include/cef_task.h"
#include "include/wrapper/cef_closure_task.h"
#include "include/wrapper/cef_helpers.h"
#include "utf8_validation.h"

namespace kwebshell {
namespace {

constexpr size_t kMaximumChallengePayloadBytes = 64 * 1024;
constexpr size_t kMaximumHostBytes = 512;
constexpr size_t kMaximumCertificateCount = 64;
constexpr size_t kMaximumResolvedIds = 256;
constexpr auto kChallengeTimeout = std::chrono::seconds(5);
constexpr auto kMaximumTlsExceptionLifetime = std::chrono::hours(24);
constexpr uint64_t kMaximumRequestId =
    static_cast<uint64_t>((std::numeric_limits<int64_t>::max)());

std::atomic<uint64_t> next_request_id{1};

bool HasOnlyKeys(const CefRefPtr<CefDictionaryValue>& dictionary,
                 std::initializer_list<const char*> allowed) {
  if (!dictionary) return false;
  CefDictionaryValue::KeyList keys;
  if (!dictionary->GetKeys(keys)) return false;
  for (const auto& key : keys) {
    const std::string name = key.ToString();
    if (std::none_of(allowed.begin(), allowed.end(),
                     [&name](const char* candidate) {
                       return name == candidate;
                     })) {
      return false;
    }
  }
  return true;
}

bool HasString(const CefRefPtr<CefDictionaryValue>& dictionary,
               const char* key) {
  return dictionary && dictionary->HasKey(key) &&
         dictionary->GetType(key) == VTYPE_STRING;
}

std::string BoundUtf8(std::string value, size_t maximum_bytes,
                     bool allow_empty = true) {
  if (!allow_empty && value.empty()) return {};
  value.erase(std::remove(value.begin(), value.end(), '\0'), value.end());
  if (!IsValidUtf8(value.data(), value.size())) return {};
  if (value.size() <= maximum_bytes) return value;
  value.resize(maximum_bytes);
  while (!value.empty() && !IsValidUtf8(value.data(), value.size())) {
    value.pop_back();
  }
  return value;
}

std::string LowerAscii(std::string value) {
  std::transform(value.begin(), value.end(), value.begin(), [](char character) {
    const auto byte = static_cast<unsigned char>(character);
    return static_cast<char>(byte >= 'A' && byte <= 'Z' ? byte - 'A' + 'a'
                                                         : byte);
  });
  return value;
}

std::optional<std::string> OriginFromUrl(const std::string& url) {
  CefURLParts parts;
  if (!CefParseURL(url, parts)) return std::nullopt;
  const std::string origin =
      BoundUtf8(CefString(&parts.origin).ToString(), 2048, false);
  return origin.empty() ? std::nullopt : std::optional<std::string>(origin);
}

std::string OriginForBrowser(CefRefPtr<CefBrowser> browser, bool is_proxy) {
  if (is_proxy || !browser || !browser->GetMainFrame()) return {};
  return BoundUtf8(
      BridgeOriginFromUrl(browser->GetMainFrame()->GetURL()).value_or(""),
      2048);
}

class Sha256 final {
 public:
  Sha256() {
    state_ = {0x6a09e667U, 0xbb67ae85U, 0x3c6ef372U, 0xa54ff53aU,
              0x510e527fU, 0x9b05688cU, 0x1f83d9abU, 0x5be0cd19U};
  }

  void Update(const uint8_t* data, size_t length) {
    for (size_t index = 0; index < length; ++index) {
      buffer_[buffer_length_++] = data[index];
      if (buffer_length_ == buffer_.size()) {
        Transform();
        total_bits_ += 512;
        buffer_length_ = 0;
      }
    }
  }

  std::array<uint8_t, 32> Final() {
    const uint64_t original_bits = total_bits_ + buffer_length_ * 8;
    buffer_[buffer_length_++] = 0x80;
    if (buffer_length_ > 56) {
      while (buffer_length_ < 64) buffer_[buffer_length_++] = 0;
      Transform();
      buffer_length_ = 0;
    }
    while (buffer_length_ < 56) buffer_[buffer_length_++] = 0;
    for (int index = 7; index >= 0; --index) {
      buffer_[buffer_length_++] =
          static_cast<uint8_t>((original_bits >> (index * 8)) & 0xffU);
    }
    Transform();

    std::array<uint8_t, 32> result{};
    for (size_t index = 0; index < state_.size(); ++index) {
      result[index * 4] = static_cast<uint8_t>((state_[index] >> 24) & 0xffU);
      result[index * 4 + 1] =
          static_cast<uint8_t>((state_[index] >> 16) & 0xffU);
      result[index * 4 + 2] =
          static_cast<uint8_t>((state_[index] >> 8) & 0xffU);
      result[index * 4 + 3] = static_cast<uint8_t>(state_[index] & 0xffU);
    }
    return result;
  }

 private:
  static uint32_t RotateRight(uint32_t value, uint32_t amount) {
    return (value >> amount) | (value << (32 - amount));
  }

  void Transform() {
    static constexpr std::array<uint32_t, 64> kRoundConstants = {
        0x428a2f98U, 0x71374491U, 0xb5c0fbcfU, 0xe9b5dba5U,
        0x3956c25bU, 0x59f111f1U, 0x923f82a4U, 0xab1c5ed5U,
        0xd807aa98U, 0x12835b01U, 0x243185beU, 0x550c7dc3U,
        0x72be5d74U, 0x80deb1feU, 0x9bdc06a7U, 0xc19bf174U,
        0xe49b69c1U, 0xefbe4786U, 0x0fc19dc6U, 0x240ca1ccU,
        0x2de92c6fU, 0x4a7484aaU, 0x5cb0a9dcU, 0x76f988daU,
        0x983e5152U, 0xa831c66dU, 0xb00327c8U, 0xbf597fc7U,
        0xc6e00bf3U, 0xd5a79147U, 0x06ca6351U, 0x14292967U,
        0x27b70a85U, 0x2e1b2138U, 0x4d2c6dfcU, 0x53380d13U,
        0x650a7354U, 0x766a0abbU, 0x81c2c92eU, 0x92722c85U,
        0xa2bfe8a1U, 0xa81a664bU, 0xc24b8b70U, 0xc76c51a3U,
        0xd192e819U, 0xd6990624U, 0xf40e3585U, 0x106aa070U,
        0x19a4c116U, 0x1e376c08U, 0x2748774cU, 0x34b0bcb5U,
        0x391c0cb3U, 0x4ed8aa4aU, 0x5b9cca4fU, 0x682e6ff3U,
        0x748f82eeU, 0x78a5636fU, 0x84c87814U, 0x8cc70208U,
        0x90befffaU, 0xa4506cebU, 0xbef9a3f7U, 0xc67178f2U};
    std::array<uint32_t, 64> schedule{};
    for (size_t index = 0; index < 16; ++index) {
      schedule[index] = (static_cast<uint32_t>(buffer_[index * 4]) << 24) |
                        (static_cast<uint32_t>(buffer_[index * 4 + 1]) << 16) |
                        (static_cast<uint32_t>(buffer_[index * 4 + 2]) << 8) |
                        static_cast<uint32_t>(buffer_[index * 4 + 3]);
    }
    for (size_t index = 16; index < schedule.size(); ++index) {
      const uint32_t s0 = RotateRight(schedule[index - 15], 7) ^
                          RotateRight(schedule[index - 15], 18) ^
                          (schedule[index - 15] >> 3);
      const uint32_t s1 = RotateRight(schedule[index - 2], 17) ^
                          RotateRight(schedule[index - 2], 19) ^
                          (schedule[index - 2] >> 10);
      schedule[index] = schedule[index - 16] + s0 + schedule[index - 7] + s1;
    }
    uint32_t a = state_[0];
    uint32_t b = state_[1];
    uint32_t c = state_[2];
    uint32_t d = state_[3];
    uint32_t e = state_[4];
    uint32_t f = state_[5];
    uint32_t g = state_[6];
    uint32_t h = state_[7];
    for (size_t index = 0; index < schedule.size(); ++index) {
      const uint32_t s1 = RotateRight(e, 6) ^ RotateRight(e, 11) ^
                          RotateRight(e, 25);
      const uint32_t choose = (e & f) ^ (~e & g);
      const uint32_t temporary1 = h + s1 + choose + kRoundConstants[index] +
                                  schedule[index];
      const uint32_t s0 = RotateRight(a, 2) ^ RotateRight(a, 13) ^
                          RotateRight(a, 22);
      const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
      const uint32_t temporary2 = s0 + majority;
      h = g;
      g = f;
      f = e;
      e = d + temporary1;
      d = c;
      c = b;
      b = a;
      a = temporary1 + temporary2;
    }
    state_[0] += a;
    state_[1] += b;
    state_[2] += c;
    state_[3] += d;
    state_[4] += e;
    state_[5] += f;
    state_[6] += g;
    state_[7] += h;
  }

  std::array<uint32_t, 8> state_{};
  std::array<uint8_t, 64> buffer_{};
  size_t buffer_length_ = 0;
  uint64_t total_bits_ = 0;
};

std::string Hex(const uint8_t* data, size_t size) {
  static constexpr char kHex[] = "0123456789abcdef";
  std::string result;
  result.reserve(size * 2);
  for (size_t index = 0; index < size; ++index) {
    result.push_back(kHex[(data[index] >> 4) & 0x0f]);
    result.push_back(kHex[data[index] & 0x0f]);
  }
  return result;
}

std::optional<std::vector<uint8_t>> BinaryBytes(
    CefRefPtr<CefBinaryValue> value, size_t maximum_size) {
  if (!value) return std::nullopt;
  const size_t size = value->GetSize();
  if (size == 0 || size > maximum_size) return std::nullopt;
  std::vector<uint8_t> result(size);
  if (value->GetData(result.data(), size, 0) != size) return std::nullopt;
  return result;
}

struct CertificateSummary final {
  std::string fingerprint;
  std::string subject;
  std::string issuer;
  std::string serial_number;
  std::optional<int64_t> valid_start_epoch_millis;
  std::optional<int64_t> valid_expiry_epoch_millis;
};

std::optional<CertificateSummary> SummarizeCertificate(
    CefRefPtr<CefX509Certificate> certificate) {
  if (!certificate) return std::nullopt;
  const auto der = BinaryBytes(certificate->GetDEREncoded(), 4 * 1024 * 1024);
  const auto serial = BinaryBytes(certificate->GetSerialNumber(), 1024);
  if (!der || !serial) return std::nullopt;
  Sha256 digest;
  digest.Update(der->data(), der->size());
  const auto fingerprint_bytes = digest.Final();
  const auto subject = certificate->GetSubject();
  const auto issuer = certificate->GetIssuer();
  CertificateSummary result{
      Hex(fingerprint_bytes.data(), fingerprint_bytes.size()),
      BoundUtf8(subject ? subject->GetDisplayName().ToString() : "", 512),
      BoundUtf8(issuer ? issuer->GetDisplayName().ToString() : "", 512),
      Hex(serial->data(), serial->size()),
      std::nullopt,
      std::nullopt};
  if (result.fingerprint.size() != 64 || result.serial_number.empty() ||
      result.subject.size() > 512 || result.issuer.size() > 512) {
    return std::nullopt;
  }
  constexpr int64_t kWindowsEpochOffsetMicros = 11644473600000000LL;
  const int64_t valid_start_micros = certificate->GetValidStart().val;
  const int64_t valid_expiry_micros = certificate->GetValidExpiry().val;
  if (valid_start_micros != 0) {
    result.valid_start_epoch_millis =
        (valid_start_micros - kWindowsEpochOffsetMicros) / 1000;
  }
  if (valid_expiry_micros != 0) {
    result.valid_expiry_epoch_millis =
        (valid_expiry_micros - kWindowsEpochOffsetMicros) / 1000;
  }
  return result;
}

void SetNullableEpochMillis(CefRefPtr<CefDictionaryValue> dictionary,
                            const char* key,
                            const std::optional<int64_t>& value) {
  if (value) {
    dictionary->SetDouble(key, static_cast<double>(*value));
  } else {
    dictionary->SetNull(key);
  }
}

CefRefPtr<CefDictionaryValue> CertificateDictionary(
    const CertificateSummary& certificate) {
  auto dictionary = CefDictionaryValue::Create();
  dictionary->SetString("sha256Fingerprint", certificate.fingerprint);
  dictionary->SetString("subject", certificate.subject);
  dictionary->SetString("issuer", certificate.issuer);
  dictionary->SetString("serialNumber", certificate.serial_number);
  SetNullableEpochMillis(dictionary, "validStartEpochMillis",
                         certificate.valid_start_epoch_millis);
  SetNullableEpochMillis(dictionary, "validExpiryEpochMillis",
                         certificate.valid_expiry_epoch_millis);
  return dictionary;
}

std::string WriteJson(CefRefPtr<CefDictionaryValue> dictionary) {
  auto value = CefValue::Create();
  value->SetDictionary(dictionary);
  return CefWriteJSON(value, JSON_WRITER_DEFAULT).ToString();
}

void SetNullableString(CefRefPtr<CefDictionaryValue> dictionary, const char* key,
                       const std::string& value) {
  if (value.empty()) {
    dictionary->SetNull(key);
  } else {
    dictionary->SetString(key, value);
  }
}

std::string TlsDetails(uint64_t request_id, const std::string& request_url,
                       const std::string& origin,
                       const std::string& error_name,
                       const CertificateSummary& certificate,
                       int64_t deadline_epoch_millis) {
  auto dictionary = CefDictionaryValue::Create();
  dictionary->SetInt("version", 1);
  dictionary->SetString("kind", "tls");
  dictionary->SetString("requestId", std::to_string(request_id));
  dictionary->SetString("requestUrl", request_url);
  SetNullableString(dictionary, "origin", origin);
  dictionary->SetString("error", error_name);
  dictionary->SetDouble("deadlineEpochMillis",
                        static_cast<double>(deadline_epoch_millis));
  dictionary->SetDictionary("certificate", CertificateDictionary(certificate));
  return WriteJson(dictionary);
}

std::string ClientCertificateDetails(
    uint64_t request_id, const std::string& origin, const std::string& host,
    int port, bool is_proxy, const std::vector<CertificateSummary>& certificates,
    int64_t deadline_epoch_millis) {
  auto dictionary = CefDictionaryValue::Create();
  dictionary->SetInt("version", 1);
  dictionary->SetString("kind", "clientCertificate");
  dictionary->SetString("requestId", std::to_string(request_id));
  SetNullableString(dictionary, "origin", origin);
  dictionary->SetString("host", host);
  dictionary->SetInt("port", port);
  dictionary->SetBool("isProxy", is_proxy);
  dictionary->SetDouble("deadlineEpochMillis",
                        static_cast<double>(deadline_epoch_millis));
  auto list = CefListValue::Create();
  for (const auto& certificate : certificates) {
    list->SetDictionary(list->GetSize(), CertificateDictionary(certificate));
  }
  dictionary->SetList("certificates", list);
  return WriteJson(dictionary);
}

std::string TlsErrorName(cef_errorcode_t error) {
  switch (error) {
    case ERR_CERT_COMMON_NAME_INVALID:
      return "commonNameInvalid";
    case ERR_CERT_DATE_INVALID:
      return "dateInvalid";
    case ERR_CERT_AUTHORITY_INVALID:
      return "authorityInvalid";
    case ERR_CERT_REVOKED:
      return "revoked";
    case ERR_CERT_WEAK_SIGNATURE_ALGORITHM:
      return "weakSignatureAlgorithm";
    case ERR_CERT_WEAK_KEY:
      return "weakKey";
    case ERR_SSL_PINNED_KEY_NOT_IN_CERT_CHAIN:
      return "pinnedKeyMissing";
    case ERR_CERT_INVALID:
      return "invalid";
    default:
      return "other";
  }
}

int64_t EpochMillis(std::chrono::system_clock::time_point value) {
  return std::chrono::duration_cast<std::chrono::milliseconds>(
             value.time_since_epoch())
      .count();
}

uint64_t AllocateRequestId() {
  uint64_t value = next_request_id.fetch_add(1, std::memory_order_relaxed);
  return value == 0 || value > kMaximumRequestId ? 0 : value;
}

bool ValidFingerprint(const std::string& value) {
  return value.size() == 64 &&
         std::all_of(value.begin(), value.end(), [](char character) {
           return (character >= '0' && character <= '9') ||
                  (character >= 'a' && character <= 'f');
         });
}

std::optional<SecurityChallengeRegistry::ParsedDecision> ParseDecision(
    const std::string& payload) {
  if (payload.empty() || payload.size() > kMaximumChallengePayloadBytes ||
      !IsValidUtf8(payload.data(), payload.size())) {
    return std::nullopt;
  }
  auto value = CefParseJSON(payload, JSON_PARSER_RFC);
  if (!value || value->GetType() != VTYPE_DICTIONARY) return std::nullopt;
  auto dictionary = value->GetDictionary();
  if (!dictionary || !HasString(dictionary, "kind") ||
      !HasString(dictionary, "decision") ||
      dictionary->GetType("version") != VTYPE_INT ||
      dictionary->GetInt("version") != 1) {
    return std::nullopt;
  }
  SecurityChallengeRegistry::ParsedDecision decision;
  decision.kind = dictionary->GetString("kind").ToString();
  decision.decision = dictionary->GetString("decision").ToString();
  if (decision.kind == "tls") {
    if (decision.decision == "allow_for_profile_origin") {
      if (!HasOnlyKeys(dictionary,
                       {"version", "kind", "decision", "expiresAtEpochMillis"}) ||
          (dictionary->GetType("expiresAtEpochMillis") != VTYPE_INT &&
           dictionary->GetType("expiresAtEpochMillis") != VTYPE_DOUBLE)) {
        return std::nullopt;
      }
      const double value = dictionary->GetType("expiresAtEpochMillis") == VTYPE_INT
                               ? dictionary->GetInt("expiresAtEpochMillis")
                               : dictionary->GetDouble("expiresAtEpochMillis");
      if (!std::isfinite(value) || value <= 0 || std::floor(value) != value ||
          value > static_cast<double>((std::numeric_limits<int64_t>::max)())) {
        return std::nullopt;
      }
      decision.expires_at_epoch_millis = static_cast<int64_t>(value);
    } else if (!HasOnlyKeys(dictionary, {"version", "kind", "decision"}) ||
               (decision.decision != "deny" &&
                decision.decision != "allow_once")) {
      return std::nullopt;
    }
    return decision;
  }
  if (decision.kind == "clientCertificate") {
    if (decision.decision == "select") {
      if (!HasOnlyKeys(dictionary, {"version", "kind", "decision",
                                     "sha256Fingerprint"}) ||
          !HasString(dictionary, "sha256Fingerprint")) {
        return std::nullopt;
      }
      decision.fingerprint = LowerAscii(
          dictionary->GetString("sha256Fingerprint").ToString());
      if (!ValidFingerprint(decision.fingerprint)) return std::nullopt;
    } else if (!HasOnlyKeys(dictionary, {"version", "kind", "decision"}) ||
               decision.decision != "deny") {
      return std::nullopt;
    }
    return decision;
  }
  return std::nullopt;
}

}  // namespace

bool SecurityProfileState::TlsKeyLess::operator()(
    const TlsKey& left, const TlsKey& right) const {
  return std::tie(left.origin, left.fingerprint) <
         std::tie(right.origin, right.fingerprint);
}

SecurityProfileState::SecurityProfileState() = default;

bool SecurityProfileState::ReserveChallenge() {
  std::lock_guard lock(mutex_);
  if (closing_ || live_challenges_ >= 64) return false;
  ++live_challenges_;
  return true;
}

void SecurityProfileState::ReleaseChallenge() {
  std::lock_guard lock(mutex_);
  if (live_challenges_ > 0) --live_challenges_;
}

void SecurityProfileState::Close() {
  std::lock_guard lock(mutex_);
  closing_ = true;
  tls_exceptions_.clear();
}

bool SecurityProfileState::IsClosing() const {
  std::lock_guard lock(mutex_);
  return closing_;
}

bool SecurityProfileState::HasTlsException(const std::string& origin,
                                           const std::string& fingerprint) {
  std::lock_guard lock(mutex_);
  const TlsKey key{origin, fingerprint};
  const auto found = tls_exceptions_.find(key);
  if (found == tls_exceptions_.end()) return false;
  if (std::chrono::system_clock::now() >= found->second) {
    tls_exceptions_.erase(found);
    return false;
  }
  return !closing_;
}

bool SecurityProfileState::PutTlsException(
    const std::string& origin, const std::string& fingerprint,
    std::chrono::system_clock::time_point expiry) {
  std::lock_guard lock(mutex_);
  if (closing_) return false;
  tls_exceptions_[TlsKey{origin, fingerprint}] = expiry;
  return true;
}

SecurityChallengeRegistry::SecurityChallengeRegistry(
    std::shared_ptr<SecurityProfileState> profile, EventSink event_sink)
    : profile_(std::move(profile)), event_sink_(std::move(event_sink)) {}

bool SecurityChallengeRegistry::Register(uint64_t request_id, Record record,
                                         const std::string& origin,
                                         const std::string& url,
                                         const std::string& details,
                                         uint64_t* request_id_out) {
  if (!profile_ || details.size() > kMaximumChallengePayloadBytes ||
      !profile_->ReserveChallenge()) {
    return false;
  }
  if (request_id == 0) {
    profile_->ReleaseChallenge();
    return false;
  }
  record.request_id = request_id;
  record.deadline = std::chrono::steady_clock::now() + kChallengeTimeout;
  {
    std::lock_guard lock(mutex_);
    if (closing_) {
      profile_->ReleaseChallenge();
      return false;
    }
    records_.emplace(request_id, std::move(record));
  }
  *request_id_out = request_id;
  event_sink_(request_id, origin, url, details);
  return true;
}

void SecurityChallengeRegistry::ScheduleTimeout(uint64_t request_id,
                                                 cef_thread_id_t thread_id) {
  auto self = shared_from_this();
  if (!CefPostDelayedTask(
          thread_id,
          base::BindOnce(
              [](std::shared_ptr<SecurityChallengeRegistry> registry,
                 uint64_t id) { registry->Timeout(id); },
              std::move(self), request_id),
          std::chrono::duration_cast<std::chrono::milliseconds>(
              kChallengeTimeout)
              .count())) {
    Timeout(request_id);
  }
}

void SecurityChallengeRegistry::Timeout(uint64_t request_id) {
  Record record;
  {
    std::lock_guard lock(mutex_);
    const auto found = records_.find(request_id);
    if (found == records_.end()) return;
    record = std::move(found->second);
    records_.erase(found);
    RememberResolved(request_id);
  }
  profile_->ReleaseChallenge();
  InvokeCancellation(std::move(record));
}

void SecurityChallengeRegistry::RememberResolved(uint64_t request_id) {
  resolved_ids_.insert(request_id);
  resolved_order_.push_back(request_id);
  while (resolved_order_.size() > kMaximumResolvedIds) {
    resolved_ids_.erase(resolved_order_.front());
    resolved_order_.pop_front();
  }
}

bool SecurityChallengeRegistry::InvokeCancellation(Record record) {
  auto invoke = [](Record value) {
    if (value.kind == Kind::TLS && value.tls_callback) {
      value.tls_callback->Cancel();
    } else if (value.kind == Kind::CLIENT_CERTIFICATE &&
               value.client_certificate_callback) {
      value.client_certificate_callback->Select(nullptr);
    }
  };
  if (CefCurrentlyOn(record.callback_thread)) {
    invoke(std::move(record));
    return true;
  }
  return CefPostTask(record.callback_thread,
                     base::BindOnce(std::move(invoke), std::move(record)));
}

bool SecurityChallengeRegistry::InvokeDecision(Record record,
                                               const ParsedDecision& decision) {
  auto invoke = [](ParsedDecision decision, Record value) {
    if (value.kind == Kind::TLS && value.tls_callback) {
      if (decision.decision == "deny") {
        value.tls_callback->Cancel();
      } else {
        value.tls_callback->Continue();
      }
    } else if (value.kind == Kind::CLIENT_CERTIFICATE &&
               value.client_certificate_callback) {
      if (decision.decision == "deny") {
        value.client_certificate_callback->Select(nullptr);
      } else {
        value.client_certificate_callback->Select(
            value.certificates.at(decision.fingerprint));
      }
    }
  };
  if (CefCurrentlyOn(record.callback_thread)) {
    invoke(decision, std::move(record));
    return true;
  }
  return CefPostTask(record.callback_thread,
                     base::BindOnce(std::move(invoke), decision,
                                    std::move(record)));
}

kweb_status SecurityChallengeRegistry::Resolve(
    uint64_t request_id, const ParsedDecision& decision) {
  Record record;
  {
    std::lock_guard lock(mutex_);
    const auto found = records_.find(request_id);
    if (found == records_.end()) {
      return resolved_ids_.contains(request_id)
                 ? KWEB_STATUS_SECURITY_CHALLENGE_ALREADY_RESOLVED
                 : KWEB_STATUS_SECURITY_CHALLENGE_NOT_FOUND;
    }
    const auto now = std::chrono::steady_clock::now();
    if (now >= found->second.deadline) {
      record = std::move(found->second);
      records_.erase(found);
      RememberResolved(request_id);
      profile_->ReleaseChallenge();
      InvokeCancellation(std::move(record));
      return KWEB_STATUS_SECURITY_CHALLENGE_DEADLINE_EXPIRED;
    }
    const Kind kind = found->second.kind;
    const bool kind_matches =
        (kind == Kind::TLS && decision.kind == "tls") ||
        (kind == Kind::CLIENT_CERTIFICATE &&
         decision.kind == "clientCertificate");
    if (!kind_matches) return KWEB_STATUS_SECURITY_CHALLENGE_DECISION_INVALID;
    if (kind == Kind::TLS && decision.decision == "allow_for_profile_origin") {
      const auto now_wall = std::chrono::system_clock::now();
      const auto expiry = std::chrono::system_clock::time_point(
          std::chrono::milliseconds(decision.expires_at_epoch_millis));
      if (found->second.origin.empty() || found->second.fingerprint.empty() ||
          expiry <= now_wall || expiry > now_wall + kMaximumTlsExceptionLifetime) {
        return KWEB_STATUS_SECURITY_TLS_EXPIRY_INVALID;
      }
      if (!profile_->PutTlsException(found->second.origin,
                                     found->second.fingerprint, expiry)) {
        return KWEB_STATUS_SECURITY_CHALLENGE_PROFILE_CLOSING;
      }
    }
    if (kind == Kind::CLIENT_CERTIFICATE && decision.decision == "select" &&
        !found->second.certificates.contains(decision.fingerprint)) {
      return KWEB_STATUS_SECURITY_CLIENT_CERTIFICATE_NOT_OFFERED;
    }
    record = std::move(found->second);
    records_.erase(found);
    RememberResolved(request_id);
  }
  profile_->ReleaseChallenge();
  if (!InvokeDecision(std::move(record), decision)) {
    return KWEB_STATUS_SECURITY_CHALLENGE_CALLBACK_FAILED;
  }
  return KWEB_STATUS_OK;
}

kweb_status SecurityChallengeRegistry::Respond(uint64_t request_id,
                                               const std::string& payload) {
  if (request_id == 0 || payload.size() > kMaximumChallengePayloadBytes) {
    return KWEB_STATUS_SECURITY_CHALLENGE_DECISION_INVALID;
  }
  auto decision = ParseDecision(payload);
  if (!decision) return KWEB_STATUS_SECURITY_CHALLENGE_DECISION_INVALID;
  const kweb_status status = Resolve(request_id, *decision);
  return status;
}

void SecurityChallengeRegistry::Close() {
  std::vector<Record> records;
  {
    std::lock_guard lock(mutex_);
    if (closing_) return;
    closing_ = true;
    records.reserve(records_.size());
    for (auto& entry : records_) {
      RememberResolved(entry.first);
      records.push_back(std::move(entry.second));
    }
    records_.clear();
  }
  for (auto& record : records) {
    profile_->ReleaseChallenge();
    InvokeCancellation(std::move(record));
  }
}

bool SecurityChallengeRegistry::OnCertificateError(
    CefRefPtr<CefBrowser> browser, cef_errorcode_t cert_error,
    const CefString& request_url, CefRefPtr<CefSSLInfo> ssl_info,
    CefRefPtr<CefCallback> callback) {
  CEF_REQUIRE_UI_THREAD();
  const std::string url = BoundUtf8(request_url.ToString(), 8192, false);
  const std::string origin = OriginFromUrl(url).value_or("");
  const auto certificate =
      SummarizeCertificate(ssl_info ? ssl_info->GetX509Certificate() : nullptr);
  if (!callback || url.empty() || !certificate) {
    if (callback) callback->Cancel();
    return true;
  }
  if (profile_->HasTlsException(origin, certificate->fingerprint)) {
    callback->Continue();
    return true;
  }
  const uint64_t expected_id = AllocateRequestId();
  if (expected_id == 0) {
    callback->Cancel();
    return true;
  }
  Record record;
  record.kind = Kind::TLS;
  record.callback_thread = TID_UI;
  record.origin = origin;
  record.fingerprint = certificate->fingerprint;
  record.tls_callback = callback;
  const int64_t deadline = EpochMillis(std::chrono::system_clock::now() +
                                       kChallengeTimeout);
  const std::string details = TlsDetails(
      expected_id, url, origin,
      TlsErrorName(cert_error), *certificate, deadline);
  uint64_t request_id = 0;
  if (!Register(expected_id, std::move(record), origin, url, details,
                &request_id)) {
    callback->Cancel();
    return true;
  }
  ScheduleTimeout(request_id, TID_UI);
  return true;
}

bool SecurityChallengeRegistry::OnSelectClientCertificate(
    CefRefPtr<CefBrowser> browser, bool is_proxy, const CefString& host,
    int port, const CefRequestHandler::X509CertificateList& certificates,
    CefRefPtr<CefSelectClientCertificateCallback> callback) {
  CEF_REQUIRE_UI_THREAD();
  const std::string host_value = BoundUtf8(host.ToString(), kMaximumHostBytes, false);
  if (!callback || host_value.empty() || port <= 0 || port > 65535 ||
      certificates.size() > kMaximumCertificateCount) {
    if (callback) callback->Select(nullptr);
    return true;
  }
  std::vector<CertificateSummary> summaries;
  summaries.reserve(certificates.size());
  Record record;
  record.kind = Kind::CLIENT_CERTIFICATE;
  record.callback_thread = TID_UI;
  record.origin = OriginForBrowser(browser, is_proxy);
  record.client_certificate_callback = callback;
  for (const auto& certificate : certificates) {
    const auto summary = SummarizeCertificate(certificate);
    if (!summary || record.certificates.contains(summary->fingerprint)) {
      continue;
    }
    record.certificates.emplace(summary->fingerprint, certificate);
    summaries.push_back(*summary);
  }
  const uint64_t expected_id = AllocateRequestId();
  if (expected_id == 0) {
    callback->Select(nullptr);
    return true;
  }
  const int64_t deadline = EpochMillis(std::chrono::system_clock::now() +
                                       kChallengeTimeout);
  const std::string details = ClientCertificateDetails(
      expected_id, record.origin, host_value, port, is_proxy, summaries,
      deadline);
  uint64_t request_id = 0;
  const std::string event_origin = record.origin;
  if (!Register(expected_id, std::move(record), event_origin, {}, details,
                &request_id)) {
    callback->Select(nullptr);
    return true;
  }
  ScheduleTimeout(request_id, TID_UI);
  return true;
}

}  // namespace kwebshell
