#include "shortcuts_internal.h"
#include "shortcuts_platform_test.h"
#include "shortcuts_tests_fixture.h"

#include <cstring>
#include <vector>

namespace {

constexpr uint32_t kCombo = KWEB_SHORTCUTS_MOD_CONTROL | KWEB_SHORTCUTS_MOD_ALT | KWEB_SHORTCUTS_MOD_SHIFT;

/** Opens the process-wide service, or reports that the session has no facility. */
bool OpenOrSkip(ShortcutsFixture &fixture, uint64_t *handle, const char *label) {
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  const kweb_shortcuts_status opened = kweb_shortcuts_open(&configuration, handle);
  if (opened == KWEB_SHORTCUTS_STATUS_PLATFORM_UNAVAILABLE) {
    std::printf("SKIP: the %s session declares no shortcut facility.\n", label);
    return false;
  }
  KWEB_CHECK(opened == KWEB_SHORTCUTS_STATUS_OK);
  return opened == KWEB_SHORTCUTS_STATUS_OK;
}

void HeaderAndArgumentValidation() {
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  KWEB_CHECK(kweb_shortcuts_open(nullptr, &handle) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_shortcuts_open(&configuration, nullptr) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.struct_size = sizeof(configuration) - 1u;
  KWEB_CHECK(kweb_shortcuts_open(&configuration, &handle) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  configuration = fixture.Configuration();
  configuration.abi_version = KWEB_SHORTCUTS_ABI_VERSION + 1u;
  KWEB_CHECK(kweb_shortcuts_open(&configuration, &handle) == KWEB_SHORTCUTS_STATUS_ABI_MISMATCH);
  configuration = fixture.Configuration();
  configuration.application_id = kweb_shortcuts_string{nullptr, 0};
  KWEB_CHECK(kweb_shortcuts_open(&configuration, &handle) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
}

void RegistrationLifecycle() {
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;

  uint64_t second = 0;
  kweb_shortcuts_configuration configuration = fixture.Configuration();
  KWEB_CHECK(kweb_shortcuts_open(&configuration, &second) == KWEB_SHORTCUTS_STATUS_ALREADY_OPEN);

  const kweb_shortcuts_capabilities_result capabilities = fixture.Capabilities(handle);
  KWEB_CHECK(capabilities.capability_bits != 0);
  KWEB_CHECK((capabilities.capability_bits & KWEB_SHORTCUTS_CAP_GLOBAL_REGISTRATION) != 0);
  KWEB_CHECK(!std::string(capabilities.provider_id).empty());

  kweb_shortcuts_registration_result result{};
  // Rejected requests never touch the provider.
  auto bad_modifier = fixture.Request("badmod", "cmd.badmod", 0x100u, KWEB_SHORTCUTS_KEY_NAMED, 'A');
  KWEB_CHECK(fixture.Register(handle, bad_modifier, &result) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  auto bare = fixture.Request("bare", "cmd.bare", 0u, KWEB_SHORTCUTS_KEY_NAMED, 'A');
  KWEB_CHECK(fixture.Register(handle, bare, &result) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  auto bad_id = fixture.Request("1copy", "cmd.badid", kCombo, KWEB_SHORTCUTS_KEY_NAMED, 'A');
  KWEB_CHECK(fixture.Register(handle, bad_id, &result) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);
  auto bad_key = fixture.Request("badkey", "cmd.badkey", kCombo, KWEB_SHORTCUTS_KEY_NAMED, 0x7fu);
  KWEB_CHECK(fixture.Register(handle, bad_key, &result) == KWEB_SHORTCUTS_STATUS_INVALID_ARGUMENT);

  // A first live registration succeeds and is listed.
  auto copy = fixture.Request("copy", "text.copy", kCombo, KWEB_SHORTCUTS_KEY_NAMED,
                              'A');
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(std::strcmp(result.registration_id, "copy") == 0);
  kweb_shortcuts_presence_result presence{};
  presence.struct_size = sizeof(presence);
  presence.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  kweb_shortcuts_query query{};
  query.struct_size = sizeof(query);
  query.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  const std::string copy_id = "copy";
  query.value.data = reinterpret_cast<const uint8_t *>(copy_id.data());
  query.value.size = copy_id.size();
  KWEB_CHECK(kweb_shortcuts_is_registered(handle, &query, &presence) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(presence.registered == 1u);

  // A duplicate identifier is rejected before the provider sees it.
  auto duplicate = fixture.Request("copy", "text.copy-2", kCombo, KWEB_SHORTCUTS_KEY_NAMED,
                                   'B');
  KWEB_CHECK(fixture.Register(handle, duplicate, &result) == KWEB_SHORTCUTS_STATUS_DUPLICATE_ID);

  // A different identifier on the same combination is a typed native conflict.
  auto clash = fixture.Request("clash", "text.clash", kCombo, KWEB_SHORTCUTS_KEY_NAMED,
                               'A');
  KWEB_CHECK(fixture.Register(handle, clash, &result) == KWEB_SHORTCUTS_STATUS_CONFLICT);

  kweb_shortcuts_registration buffer[KWEB_SHORTCUTS_MAX_REGISTRATIONS]{};
  kweb_shortcuts_list_result list{};
  list.struct_size = sizeof(list);
  list.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  list.registrations = buffer;
  list.capacity = KWEB_SHORTCUTS_MAX_REGISTRATIONS;
  KWEB_CHECK(kweb_shortcuts_registrations(handle, &list) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(list.count == 1);
  KWEB_CHECK(std::strcmp(buffer[0].registration_id, "copy") == 0);

  // Unregister is idempotent.
  kweb_shortcuts_unregistration_result removed{};
  KWEB_CHECK(fixture.Unregister(handle, "copy", &removed) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(removed.was_registered == 1u);
  KWEB_CHECK(fixture.Unregister(handle, "copy", &removed) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(removed.was_registered == 0u);

  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OWNER_CLOSED);
  auto after_close = fixture.Request("late", "text.late", kCombo, KWEB_SHORTCUTS_KEY_NAMED,
                                     'C');
  KWEB_CHECK(fixture.Register(handle, after_close, &result) == KWEB_SHORTCUTS_STATUS_OWNER_CLOSED);
}

void RendererDispatch() {
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;
  kweb_shortcuts_registration_result result{};
  auto copy = fixture.Request("copy", "text.copy", kCombo, KWEB_SHORTCUTS_KEY_NAMED,
                              'A');
  KWEB_CHECK(fixture.Register(handle, copy, &result) == KWEB_SHORTCUTS_STATUS_OK);
  // An undeclared command is refused.
  KWEB_CHECK(fixture.Dispatch(handle, "text.unknown", &result) == KWEB_SHORTCUTS_STATUS_COMMAND_UNDECLARED);
  // A declared command emits one renderer invocation.
  KWEB_CHECK(fixture.Dispatch(handle, "text.copy", &result) == KWEB_SHORTCUTS_STATUS_OK);
  const std::vector<kweb_shortcuts_event> events = fixture.DrainEvents(handle);
  KWEB_CHECK(events.size() == 1);
  if (events.size() == 1) {
    KWEB_CHECK(events[0].kind == KWEB_SHORTCUTS_EVENT_INVOKED);
    KWEB_CHECK(events[0].source == KWEB_SHORTCUTS_SOURCE_RENDERER);
    KWEB_CHECK(std::strcmp(events[0].registration_id, "copy") == 0);
    KWEB_CHECK(std::strcmp(events[0].command_id, "text.copy") == 0);
  }
  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
}

void ReplacementIsAtomicAndBounded() {
  ShortcutsFixture fixture;
  uint64_t handle = 0;
  if (!OpenOrSkip(fixture, &handle, "current")) return;

  // Duplicate identifiers are rejected before any native call.
  auto a = fixture.Request("one", "cmd.one", kCombo, KWEB_SHORTCUTS_KEY_NAMED, 'A');
  auto b = fixture.Request("one", "cmd.one", kCombo, KWEB_SHORTCUTS_KEY_NAMED, 'B');
  kweb_shortcuts_replacement_result replacement{};
  replacement.struct_size = sizeof(replacement);
  replacement.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  const kweb_shortcuts_registration_request duplicates[2] = {a, b};
  KWEB_CHECK(kweb_shortcuts_replace_all(handle, duplicates, 2, &replacement) ==
             KWEB_SHORTCUTS_STATUS_DUPLICATE_ID);

  // More than the declared ceiling is rejected.
  std::vector<kweb_shortcuts_registration_request> many;
  std::vector<std::string> ids;
  std::vector<std::string> commands;
  ids.reserve(KWEB_SHORTCUTS_MAX_REGISTRATIONS + 1);
  commands.reserve(KWEB_SHORTCUTS_MAX_REGISTRATIONS + 1);
  for (uint32_t index = 0; index <= KWEB_SHORTCUTS_MAX_REGISTRATIONS; ++index) {
    ids.push_back("id" + std::to_string(index));
  }
  for (uint32_t index = 0; index < ids.size(); ++index) {
    kweb_shortcuts_registration_request request{};
    request.struct_size = sizeof(request);
    request.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
    request.registration_id.data = reinterpret_cast<const uint8_t *>(ids[index].data());
    request.registration_id.size = ids[index].size();
    commands.push_back("cmd" + std::to_string(index));
    request.command_id.data = reinterpret_cast<const uint8_t *>(commands.back().data());
    request.command_id.size = commands.back().size();
    request.modifier_bits = kCombo;
    request.key_kind = KWEB_SHORTCUTS_KEY_NAMED;
    request.key_code = KWEB_SHORTCUTS_NAMED_F1 + (index % 12u);
    many.push_back(request);
  }
  replacement = kweb_shortcuts_replacement_result{};
  replacement.struct_size = sizeof(replacement);
  replacement.abi_version = KWEB_SHORTCUTS_ABI_VERSION;
  KWEB_CHECK(kweb_shortcuts_replace_all(handle, many.data(), static_cast<uint32_t>(many.size()),
                                        &replacement) == KWEB_SHORTCUTS_STATUS_LIMIT_EXCEEDED);

  KWEB_CHECK(kweb_shortcuts_close(handle) == KWEB_SHORTCUTS_STATUS_OK);
}

}  // namespace

int main() {
  HeaderAndArgumentValidation();
  RegistrationLifecycle();
  RendererDispatch();
  ReplacementIsAtomicAndBounded();
  std::printf("kweb shortcuts portable checks: %d failures.\n", g_failures);
  const int platform_failures = RunPlatformNativeStructureTest();
  if (platform_failures > 0) {
    std::printf("kweb shortcuts provider checks: %d failures.\n", platform_failures);
  }
  if (g_failures == 0) {
    std::printf("kweb shortcuts native checks passed.\n");
    return 0;
  }
  std::printf("%d native checks failed.\n", g_failures);
  return 1;
}
