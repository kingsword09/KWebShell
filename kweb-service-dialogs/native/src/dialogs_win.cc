#include "dialog_operation.h"
#define NOMINMAX
#include <windows.h>
#include <shobjidl.h>
#include <wrl/client.h>
#include <string>
#include <stdexcept>
#include <vector>

namespace kwebshell::dialogs {
namespace {
using Microsoft::WRL::ComPtr;
std::wstring Wide(const std::string &value) {
  if (value.empty()) return {};
  int length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), nullptr, 0);
  if (length <= 0) throw std::runtime_error("Invalid UTF-8");
  std::wstring text(static_cast<size_t>(length), L'\0');
  MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, value.data(), static_cast<int>(value.size()), text.data(), length);
  return text;
}
std::string Utf8(const wchar_t *value) {
  int length = WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, nullptr, 0, nullptr, nullptr);
  if (length <= 1) throw std::runtime_error("Invalid selected file");
  std::string text(static_cast<size_t>(length), '\0');
  WideCharToMultiByte(CP_UTF8, WC_ERR_INVALID_CHARS, value, -1, text.data(), length, nullptr, nullptr);
  text.pop_back();
  return text;
}
struct ActiveDialog {
  Operation *operation;
  IFileDialog *dialog;
  HWND owner;
};
thread_local ActiveDialog *active = nullptr;
void CALLBACK PollDialog(HWND, UINT, UINT_PTR, DWORD) {
  if (active == nullptr) return;
  ComPtr<IOleWindow> window;
  HWND handle = nullptr;
  if (SUCCEEDED(active->dialog->QueryInterface(IID_PPV_ARGS(&window))) &&
      SUCCEEDED(window->GetWindow(&handle)) && IsWindowVisible(handle)) {
    active->operation->dialog_window.store(reinterpret_cast<uintptr_t>(handle));
    if (!active->operation->visible.load()) {
      const DWORD dialog_thread = GetCurrentThreadId();
      const DWORD foreground_thread = GetWindowThreadProcessId(GetForegroundWindow(), nullptr);
      const bool attached = foreground_thread != 0 && foreground_thread != dialog_thread &&
                            AttachThreadInput(dialog_thread, foreground_thread, TRUE);
      BringWindowToTop(handle);
      SetForegroundWindow(handle);
      SetActiveWindow(handle);
      if (attached) AttachThreadInput(dialog_thread, foreground_thread, FALSE);
    }
    active->operation->visible.store(true);
  }
  if (active->operation->cancel_requested.load() || !IsWindow(active->owner)) {
    active->dialog->Close(HRESULT_FROM_WIN32(ERROR_CANCELLED));
  }
}
}

void RequestWindowsDialogCancellation(Operation &op) {
  const HWND handle = reinterpret_cast<HWND>(op.dialog_window.load());
  if (handle != nullptr && IsWindow(handle)) {
    // IFileDialog::Show owns a modal loop on the worker STA. Posting WM_CLOSE
    // wakes that loop even when its thread timer is not being dispatched.
    PostMessageW(handle, WM_CLOSE, 0, 0);
  }
}

void RunDialog(Operation &op) {
  op.state = KWEB_DIALOG_FAILED;
  op.failure = KWEB_DIALOG_UNAVAILABLE;
  HWND owner = reinterpret_cast<HWND>(static_cast<uintptr_t>(op.owner));
  DWORD pid = 0;
  if (!IsWindow(owner) || !IsWindowVisible(owner) ||
      GetWindowThreadProcessId(owner, &pid) == 0 || pid != GetCurrentProcessId()) return;
  const HRESULT initialized = CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED | COINIT_DISABLE_OLE1DDE);
  if (FAILED(initialized)) return;
  // COM objects must be destroyed before CoUninitialize.
  try {
    [&] {
      if (op.cancel_requested.load()) { op.state = KWEB_DIALOG_CANCELLED; op.failure = 0; return; }
      ComPtr<IFileDialog> dialog;
      HRESULT status = CoCreateInstance(op.mode == 0 ? CLSID_FileOpenDialog : CLSID_FileSaveDialog,
                                       nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&dialog));
      if (FAILED(status)) return;
      FILEOPENDIALOGOPTIONS flags = FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR | FOS_DONTADDTORECENT;
      flags |= op.mode == 0 ? FOS_FILEMUSTEXIST : FOS_OVERWRITEPROMPT;
      if (FAILED(dialog->SetOptions(flags)) || FAILED(dialog->SetTitle(Wide(op.title).c_str()))) return;
      if (!op.directory.empty()) {
        ComPtr<IShellItem> folder;
        status = SHCreateItemFromParsingName(Wide(op.directory).c_str(), nullptr, IID_PPV_ARGS(&folder));
        if (FAILED(status) || FAILED(dialog->SetFolder(folder.Get()))) return;
      }
      if (!op.name.empty() && FAILED(dialog->SetFileName(Wide(op.name).c_str()))) return;
      if (!op.extensions.empty() &&
          FAILED(dialog->SetDefaultExtension(Wide(op.extensions.front()).c_str()))) return;
      std::wstring pattern;
      for (const auto &extension : op.extensions) {
        if (!pattern.empty()) pattern += L";";
        pattern += L"*." + Wide(extension);
      }
      if (!pattern.empty()) {
        COMDLG_FILTERSPEC filter{L"Allowed files", pattern.c_str()};
        if (FAILED(dialog->SetFileTypes(1, &filter))) return;
      }
      ActiveDialog context{&op, dialog.Get(), owner};
      active = &context;
      UINT_PTR timer = SetTimer(nullptr, 0, 10, PollDialog);
      if (timer == 0) { active = nullptr; return; }
      status = dialog->Show(owner);
      KillTimer(nullptr, timer);
      active = nullptr;
      op.dialog_window.store(0);
      if (status == HRESULT_FROM_WIN32(ERROR_CANCELLED) || op.cancel_requested.load()) {
        op.state = KWEB_DIALOG_CANCELLED;
        op.failure = 0;
        return;
      }
      op.failure = KWEB_DIALOG_NATIVE_FAILED;
      if (FAILED(status)) return;
      ComPtr<IShellItem> selected;
      if (FAILED(dialog->GetResult(&selected))) return;
      PWSTR path = nullptr;
      if (FAILED(selected->GetDisplayName(SIGDN_FILESYSPATH, &path))) return;
      try { op.path = Utf8(path); }
      catch (...) { CoTaskMemFree(path); throw; }
      CoTaskMemFree(path);
      op.state = KWEB_DIALOG_SELECTED;
      op.failure = 0;
    }();
  } catch (...) {
    active = nullptr;
    op.dialog_window.store(0);
    op.state = KWEB_DIALOG_FAILED;
    op.failure = KWEB_DIALOG_NATIVE_FAILED;
  }
  CoUninitialize();
}
} // namespace kwebshell::dialogs
