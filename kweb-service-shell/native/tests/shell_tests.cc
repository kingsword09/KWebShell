#include "kweb_shell.h"
#include <cassert>
#include <cstring>

int main() {
  assert(kweb_shell_abi_version() == KWEB_SHELL_ABI_VERSION);
  assert(kweb_shell_provider_id() != nullptr);
  assert(std::strcmp(kweb_shell_status_name(KWEB_SHELL_STATUS_OK), "ok") == 0);
  assert(kweb_shell_live_count() == 0);
  kweb_shell_result result{};
  assert(kweb_shell_execute(nullptr, &result) == KWEB_SHELL_STATUS_ABI_MISMATCH);
  assert(result.struct_size == sizeof(kweb_shell_result));
  kweb_shell_request request{};
  request.struct_size = sizeof(request);
  request.abi_version = KWEB_SHELL_ABI_VERSION;
  request.action = KWEB_SHELL_ACTION_OPEN_EXTERNAL;
  request.resource_kind = KWEB_SHELL_RESOURCE_NONE;
  request.value = {nullptr, 0};
  assert(kweb_shell_execute(&request, &result) == KWEB_SHELL_STATUS_INVALID_ARGUMENT);
  return 0;
}
