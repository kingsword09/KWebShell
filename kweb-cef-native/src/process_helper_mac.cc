#include "include/cef_app.h"
#include "include/wrapper/cef_library_loader.h"
#include "bridge_renderer_app.h"

int main(int argc, char *argv[]) {
  CefScopedLibraryLoader library_loader;
  if (!library_loader.LoadInHelper()) {
    return 70;
  }

  const CefMainArgs main_args(argc, argv);
  CefRefPtr<CefApp> process_app =
      kwebshell::CreateBridgeRendererApplication();
  return CefExecuteProcess(main_args, process_app, nullptr);
}
