#ifndef KWEBSHELL_NATIVE_NETWORK_POLICY_H_
#define KWEBSHELL_NATIVE_NETWORK_POLICY_H_

#include <functional>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "include/cef_resource_request_handler.h"
#include "browser_session.h"
#include "kwebshell/native/engine_abi.h"

namespace kwebshell {

class NetworkPolicyState;
class PreparedNetworkPolicy;
struct NetworkPolicySnapshot;

using NetworkEventSink = std::function<void(std::string)>;

class NetworkPolicyRequestHandler final : public CefResourceRequestHandler {
 public:
  NetworkPolicyRequestHandler(std::shared_ptr<NetworkPolicyState> state,
                              NetworkEventSink event_sink);

  ReturnValue OnBeforeResourceLoad(CefRefPtr<CefBrowser> browser,
                                   CefRefPtr<CefFrame> frame,
                                   CefRefPtr<CefRequest> request,
                                   CefRefPtr<CefCallback> callback) override;
  void OnResourceLoadComplete(CefRefPtr<CefBrowser> browser,
                              CefRefPtr<CefFrame> frame,
                              CefRefPtr<CefRequest> request,
                              CefRefPtr<CefResponse> response,
                              URLRequestStatus status,
                              int64_t received_content_length) override;

  private:
  struct RequestObservation final {
    uint64_t public_request_id = 0;
    std::shared_ptr<const NetworkPolicySnapshot> snapshot;
    std::string url;
    std::string method;
    std::string resource_type;
    std::string initial_action = "allow";
    bool initial_decision_captured = false;
    std::string terminal_action = "allow";
    std::string error_id;
  };

  std::shared_ptr<const NetworkPolicySnapshot> CaptureSnapshot(
      uint64_t request_id, uint64_t *public_request_id);
  void CaptureInitialDecision(uint64_t request_id, const std::string &url,
                              const std::string &method,
                              const std::string &resource_type,
                              const std::string &action);
  void UpdateTerminalResult(uint64_t request_id, const std::string &action,
                            const std::string &error_id = {});
  std::optional<RequestObservation> TakeObservation(uint64_t request_id);

  const std::shared_ptr<NetworkPolicyState> state_;
  const NetworkEventSink event_sink_;
  std::mutex request_mutex_;
  std::map<uint64_t, RequestObservation> requests_;
  uint64_t next_public_request_id_ = 1;

  IMPLEMENT_REFCOUNTING(NetworkPolicyRequestHandler);
};

class NetworkPolicyState final {
 public:
  NetworkPolicyState();

  kweb_status Prepare(const std::string &payload,
                      std::shared_ptr<const NetworkPolicySnapshot> *snapshot);
  void Install(std::shared_ptr<const NetworkPolicySnapshot> snapshot);
  void Clear();
  std::shared_ptr<const NetworkPolicySnapshot> Snapshot() const;
  int IncrementRedirectDepth(uint64_t request_id);
  void ClearRedirectDepth(uint64_t request_id);

 private:
  mutable std::mutex mutex_;
  std::shared_ptr<const NetworkPolicySnapshot> current_;
  std::map<uint64_t, int> redirect_depth_;
};

std::shared_ptr<NetworkPolicyState> CreateNetworkPolicyState();

kweb_status PrepareProfileNetworkPolicy(
    const std::filesystem::path &profile_path, const std::string &payload,
    std::shared_ptr<PreparedNetworkPolicy> *prepared);
kweb_status SetProfileNetworkPolicy(
    const std::filesystem::path &profile_path,
    const std::shared_ptr<PreparedNetworkPolicy> &prepared,
    std::string *result_payload);
kweb_status ClearProfileNetworkPolicy(
    const std::filesystem::path &profile_path);
kweb_status ReleaseAllProfileNetworkPolicies();

std::shared_ptr<NetworkPolicyState> GetNetworkPolicyState(
    const std::filesystem::path &profile_path);

}  // namespace kwebshell

#endif  // KWEBSHELL_NATIVE_NETWORK_POLICY_H_
