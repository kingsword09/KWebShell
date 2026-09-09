#include "kweb_dialogs.h"
#include <cstddef>
#include <iostream>
#include <string>
#include <string_view>

namespace {
bool Rejects(kweb_dialog_request request) {
  uint64_t id = 99;
  return kweb_dialog_start(&request, &id) == KWEB_DIALOG_INVALID_REQUEST &&
         id == 0 && kweb_dialog_live_count() == 0;
}
}

int main() {
  static_assert(sizeof(kweb_dialog_request) == 80);
  static_assert(offsetof(kweb_dialog_request, owner) == 16);
  static_assert(offsetof(kweb_dialog_request, extensions) == 72);
  static_assert(sizeof(kweb_dialog_result) == 24);
  uint64_t id = 10;
  kweb_dialog_request request{};
  request.struct_size = sizeof(request);
  request.abi_version = KWEB_DIALOG_ABI_VERSION;
  const bool passed =
      kweb_dialog_abi_version() == 1 &&
      kweb_dialog_start(nullptr, &id) == KWEB_DIALOG_INVALID_REQUEST && id == 0 &&
      kweb_dialog_start(&request, &id) == KWEB_DIALOG_INVALID_REQUEST &&
      kweb_dialog_poll(0, nullptr, nullptr, 0) == KWEB_DIALOG_INVALID_REQUEST &&
      kweb_dialog_cancel(0) == KWEB_DIALOG_INVALID_HANDLE &&
      kweb_dialog_release(0) == KWEB_DIALOG_INVALID_HANDLE &&
      kweb_dialog_live_count() == 0;
  if (!passed) { std::cerr << "Dialog ABI validation failed\n"; return 1; }
  request.title = {"Pick", 4};
  if (!Rejects(request)) { std::cerr << "Missing owner accepted\n"; return 1; }
  request.owner = 1;
  for (const std::string_view bytes : {
           std::string_view("\0", 1), std::string_view("\xc0\xaf", 2),
           std::string_view("\xe0\x80\xaf", 3), std::string_view("\xed\xa0\x80", 3),
           std::string_view("\xf4\x90\x80\x80", 4), std::string_view("\xe2\x82", 2),
           std::string_view("\x80", 1)}) {
    auto invalid = request;
    invalid.title = {bytes.data(), bytes.size()};
    if (!Rejects(invalid)) { std::cerr << "Malformed UTF-8 accepted\n"; return 1; }
  }
  for (const std::string_view value : {"tar..gz", ".txt", "txt.", "_txt", "txt.*", "../txt", "TXT"}) {
    auto invalid = request;
    kweb_dialog_text extension{value.data(), value.size()};
    invalid.extension_count = 1;
    invalid.extensions = &extension;
    if (!Rejects(invalid)) { std::cerr << "Invalid filter accepted\n"; return 1; }
  }
  for (const std::string_view value : {".", "..", "file.", "file ", "a/b", "a\\b", "a:b", "a\nb", "a*b"}) {
    auto invalid = request;
    invalid.name = {value.data(), value.size()};
    if (!Rejects(invalid)) { std::cerr << "Invalid file name accepted\n"; return 1; }
  }
  for (int field = 0; field < 7; ++field) {
    auto invalid = request;
    switch (field) {
      case 0: invalid.struct_size -= 8; break;
      case 1: invalid.abi_version += 1; break;
      case 2: invalid.mode = 2; break;
      case 3: invalid.title = {nullptr, 4}; break;
      case 4: invalid.extension_count = 1; break;
      case 5: invalid.name = {"../file", 7}; break;
      case 6: invalid.extension_count = 1025; break;
    }
    if (!Rejects(invalid)) { std::cerr << "Invalid request field accepted: " << field << '\n'; return 1; }
  }
  kweb_dialog_result result{sizeof(result), KWEB_DIALOG_ABI_VERSION, 0, 0, 0};
  if (kweb_dialog_poll(99, &result, nullptr, 0) != KWEB_DIALOG_INVALID_HANDLE ||
      kweb_dialog_poll(99, &result, nullptr, 1) != KWEB_DIALOG_INVALID_REQUEST) return 1;
  ++result.abi_version;
  if (kweb_dialog_poll(99, &result, nullptr, 0) != KWEB_DIALOG_INVALID_REQUEST) return 1;
  std::cout << "Dialog ABI layout, UTF-8, filters, request versions, and ownership passed\n";
}
