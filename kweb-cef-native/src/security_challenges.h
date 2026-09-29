#ifndef KWEBSHELL_NATIVE_SECURITY_CHALLENGES_H_
#define KWEBSHELL_NATIVE_SECURITY_CHALLENGES_H_

#include <chrono>
#include <deque>
#include <filesystem>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>

#include "include/cef_request_handler.h"
#include "include/cef_ssl_info.h"
#include "kwebshell/native/base_abi.h"

namespace kwebshell {

class SecurityProfileState final {
 public:
  SecurityProfileState();

  bool ReserveChallenge();
  void ReleaseChallenge();
  void Close();
  bool IsClosing() const;

  bool HasTlsException(const std::string& origin,
                      const std::string& fingerprint);
  bool PutTlsException(const std::string& origin,
                       const std::string& fingerprint,
                       std::chrono::system_clock::time_point expiry);
 private:
  struct TlsKey {
    std::string origin;
    std::string fingerprint;
  };
  struct TlsKeyLess {
    bool operator()(const TlsKey& left, const TlsKey& right) const;
  };

  mutable std::mutex mutex_;
  bool closing_ = false;
  size_t live_challenges_ = 0;
  std::map<TlsKey, std::chrono::system_clock::time_point, TlsKeyLess>
      tls_exceptions_;
};

class SecurityChallengeRegistry final
    : public std::enable_shared_from_this<SecurityChallengeRegistry> {
 public:
  using EventSink =
      std::function<void(uint64_t, const std::string&, const std::string&,
                         const std::string&)>;

  SecurityChallengeRegistry(std::shared_ptr<SecurityProfileState> profile,
                            EventSink event_sink);

  bool OnCertificateError(CefRefPtr<CefBrowser> browser,
                          cef_errorcode_t cert_error,
                          const CefString& request_url,
                          CefRefPtr<CefSSLInfo> ssl_info,
                          CefRefPtr<CefCallback> callback);
  bool OnSelectClientCertificate(
      CefRefPtr<CefBrowser> browser, bool is_proxy, const CefString& host,
      int port, const CefRequestHandler::X509CertificateList& certificates,
      CefRefPtr<CefSelectClientCertificateCallback> callback);

  kweb_status Respond(uint64_t request_id, const std::string& payload);
  void Close();

  struct ParsedDecision {
    std::string kind;
    std::string decision;
    std::string fingerprint;
    int64_t expires_at_epoch_millis = 0;
  };

 private:
  enum class Kind { TLS, CLIENT_CERTIFICATE };

  struct Record {
    Kind kind = Kind::TLS;
    uint64_t request_id = 0;
    std::chrono::steady_clock::time_point deadline;
    cef_thread_id_t callback_thread = TID_UI;
    std::string origin;
    std::string fingerprint;
    std::map<std::string, CefRefPtr<CefX509Certificate>> certificates;
    CefRefPtr<CefCallback> tls_callback;
    CefRefPtr<CefSelectClientCertificateCallback>
        client_certificate_callback;
  };

  bool Register(uint64_t request_id, Record record,
                const std::string& origin,
                const std::string& url, const std::string& details,
                uint64_t* request_id_out);
  void ScheduleTimeout(uint64_t request_id, cef_thread_id_t thread_id);
  void Timeout(uint64_t request_id);
  kweb_status Resolve(uint64_t request_id, const ParsedDecision& decision);
  bool InvokeCancellation(Record record);
  bool InvokeDecision(Record record, const ParsedDecision& decision);
  void RememberResolved(uint64_t request_id);

  const std::shared_ptr<SecurityProfileState> profile_;
  const EventSink event_sink_;
  mutable std::mutex mutex_;
  std::map<uint64_t, Record> records_;
  std::set<uint64_t> resolved_ids_;
  std::deque<uint64_t> resolved_order_;
  bool closing_ = false;
};

}  // namespace kwebshell

#endif  // KWEBSHELL_NATIVE_SECURITY_CHALLENGES_H_
