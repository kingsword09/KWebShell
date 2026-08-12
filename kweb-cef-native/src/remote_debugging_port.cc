#include "remote_debugging_port.h"

#if defined(_WIN32)
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <cerrno>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

namespace kwebshell {
namespace {

#if defined(_WIN32)
using Socket = SOCKET;
constexpr Socket kInvalidSocket = INVALID_SOCKET;
int SocketError() { return WSAGetLastError(); }
void CloseSocket(Socket socket) { closesocket(socket); }
bool IsAddressFamilyUnavailable(int error) {
  return error == WSAEAFNOSUPPORT || error == WSAEPROTONOSUPPORT ||
         error == WSAEINVAL || error == WSAEADDRNOTAVAIL;
}
#else
using Socket = int;
constexpr Socket kInvalidSocket = -1;
int SocketError() { return errno; }
void CloseSocket(Socket socket) { close(socket); }
bool IsAddressFamilyUnavailable(int error) {
  return error == EAFNOSUPPORT || error == EPROTONOSUPPORT ||
         error == EADDRNOTAVAIL;
}
#endif

bool CanBindLoopback(int family, int32_t port) {
  const Socket socket = ::socket(family, SOCK_STREAM, IPPROTO_TCP);
  if (socket == kInvalidSocket) {
    return family == AF_INET6 && IsAddressFamilyUnavailable(SocketError());
  }
#if defined(_WIN32)
  const BOOL exclusive = TRUE;
  (void)::setsockopt(socket, SOL_SOCKET, SO_EXCLUSIVEADDRUSE,
                     reinterpret_cast<const char *>(&exclusive),
                     sizeof(exclusive));
#endif
  int bind_result = -1;
  if (family == AF_INET) {
    sockaddr_in address{};
    address.sin_family = AF_INET;
    address.sin_port = htons(static_cast<uint16_t>(port));
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    bind_result = ::bind(socket, reinterpret_cast<sockaddr *>(&address),
                         sizeof(address));
  } else {
    sockaddr_in6 address{};
    address.sin6_family = AF_INET6;
    address.sin6_port = htons(static_cast<uint16_t>(port));
    address.sin6_addr = in6addr_loopback;
    bind_result = ::bind(socket, reinterpret_cast<sockaddr *>(&address),
                         sizeof(address));
  }
  if (bind_result == 0) {
    CloseSocket(socket);
    return true;
  }
  const int error = SocketError();
  CloseSocket(socket);
  return family == AF_INET6 && IsAddressFamilyUnavailable(error);
}

} // namespace

kweb_status ValidateRemoteDebuggingPortAvailability(int32_t port) {
  if (port == 0) {
    return KWEB_STATUS_OK;
  }
#if defined(_WIN32)
  WSADATA data{};
  if (WSAStartup(MAKEWORD(2, 2), &data) != 0) {
    return KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE;
  }
#endif
  const bool available = CanBindLoopback(AF_INET, port) &&
                         CanBindLoopback(AF_INET6, port);
#if defined(_WIN32)
  WSACleanup();
#endif
  return available ? KWEB_STATUS_OK
                   : KWEB_STATUS_REMOTE_DEBUGGING_PORT_UNAVAILABLE;
}

} // namespace kwebshell
