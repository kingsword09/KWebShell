#include <X11/Xlib.h>
#include <gtk/gtk.h>

#include <iostream>
#include <string>
#include <vector>

#include "host_main.h"
#include "bridge_renderer_app.h"
#include "include/base/cef_logging.h"

namespace {

int HandleXError(Display *display, XErrorEvent *event) {
  LOG(WARNING) << "X11 error: code=" << static_cast<int>(event->error_code)
               << " request=" << static_cast<int>(event->request_code)
               << " minor=" << static_cast<int>(event->minor_code);
  return 0;
}

int HandleXIoError(Display *display) {
  LOG(ERROR) << "X11 display connection terminated.";
  return 0;
}

} // namespace

int main(int argc, char *argv[]) {
  CefMainArgs main_args(argc, argv);
  CefRefPtr<CefApp> process_app =
      kwebshell::CreateBridgeRendererApplication();
  const int subprocess_exit_code =
      CefExecuteProcess(main_args, process_app, nullptr);
  if (subprocess_exit_code >= 0) {
    return subprocess_exit_code;
  }

  std::vector<std::string> arguments;
  arguments.reserve(static_cast<size_t>(argc));
  for (int index = 1; index < argc; ++index) {
    arguments.emplace_back(argv[index]);
  }

  return kwebshell::RunBrowserProcess(
      main_args, arguments, nullptr, [&argc, &argv](std::string *error) {
        gdk_set_allowed_backends("x11");
        if (!gtk_init_check(&argc, &argv)) {
          *error =
              "GTK could not initialize an X11 or XWayland display for the "
              "native-child backend.";
          return false;
        }
        XSetErrorHandler(HandleXError);
        XSetIOErrorHandler(HandleXIoError);
        return true;
      });
}
