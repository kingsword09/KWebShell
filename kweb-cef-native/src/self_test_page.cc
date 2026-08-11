#include "self_test_page.h"

#include <string>

#include "include/cef_parser.h"

namespace kwebshell {

std::string BuildSelfTestUrl() {
  static constexpr char kHtml[] = R"HTML(
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <title>KWEB_SELF_TEST_LOADING</title>
  <style>
    html { width: 100%; height: 100%; margin: 0; }
    body { width: 100%; min-height: 200%; margin: 0; background: #102033; color: #f4f7fb; font: 16px system-ui; }
    #target { width: 100%; height: 100vh; display: grid; place-items: center; }
  </style>
</head>
<body tabindex="0">
  <div id="target">KWebShell native child self-test</div>
  <script>
    (() => {
      const canvas = document.createElement('canvas');
      const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
      let renderer = '';
      if (gl) {
        const extension = gl.getExtension('WEBGL_debug_renderer_info');
        renderer = extension
          ? gl.getParameter(extension.UNMASKED_RENDERER_WEBGL)
          : gl.getParameter(gl.RENDERER);
      }
      let gpuDrawPassed = false;
      if (gl) {
        gl.clearColor(0.125, 0.25, 0.5, 1);
        gl.clear(gl.COLOR_BUFFER_BIT);
        const pixel = new Uint8Array(4);
        gl.readPixels(0, 0, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, pixel);
        gpuDrawPassed = gl.getError() === gl.NO_ERROR &&
          pixel[0] >= 30 && pixel[0] <= 34 &&
          pixel[1] >= 62 && pixel[1] <= 66 &&
          pixel[2] >= 126 && pixel[2] <= 130 && pixel[3] === 255;
      }
      const softwareGpu = !gl || !gpuDrawPassed || !renderer ||
        /swiftshader|llvmpipe|lavapipe|software|basic render driver|\bwarp\b/i.test(renderer);
      if (softwareGpu) {
        document.title = `KWEB_SELF_TEST_GPU_FAIL|${encodeURIComponent(renderer || 'missing-webgl')}`;
        return;
      }

      const state = {
        resize: false,
        focus: document.activeElement === document.body,
        mouse: false,
        wheel: false,
        key: false,
      };
      const report = () => {
        if (state.resize && state.focus && state.mouse && state.wheel && state.key &&
            innerWidth >= 900 && innerHeight >= 650 && devicePixelRatio > 0) {
          document.title = `KWEB_SELF_TEST_PASS|${innerWidth}|${innerHeight}|${devicePixelRatio}|${encodeURIComponent(renderer)}`;
        } else {
          document.title = `KWEB_SELF_TEST_STATE|${Number(state.resize)}|${Number(state.focus)}|${Number(state.mouse)}|${Number(state.wheel)}|${Number(state.key)}|${innerWidth}|${innerHeight}`;
        }
      };
      addEventListener('resize', () => { state.resize = true; report(); });
      addEventListener('focus', () => { state.focus = true; report(); });
      addEventListener('focusin', () => { state.focus = true; report(); });
      addEventListener('mousedown', () => { state.mouse = true; report(); });
      addEventListener('wheel', () => { state.wheel = true; report(); });
      addEventListener('scroll', () => { state.wheel = true; report(); });
      addEventListener('keydown', event => {
        if (event.key.toLowerCase() === 'k') state.key = true;
        report();
      });
      document.body.focus();
      document.title = 'KWEB_SELF_TEST_READY';
    })();
  </script>
</body>
</html>
)HTML";

  const CefString encoded = CefURIEncode(kHtml, false);
  return "data:text/html;charset=utf-8," + encoded.ToString();
}

void SendSelfTestMouseInput(CefRefPtr<CefBrowser> browser) {
  CefMouseEvent mouse_event;
  mouse_event.x = 120;
  mouse_event.y = 120;
  mouse_event.modifiers = 0;

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  host->SendMouseMoveEvent(mouse_event, false);
  host->SendMouseClickEvent(mouse_event, MBT_LEFT, false, 1);
  host->SendMouseClickEvent(mouse_event, MBT_LEFT, true, 1);
}

void SendSelfTestWheelInput(CefRefPtr<CefBrowser> browser) {
  CefMouseEvent wheel_event;
  wheel_event.x = 120;
  wheel_event.y = 120;
  wheel_event.modifiers = 0;
  browser->GetHost()->SendMouseWheelEvent(wheel_event, 0, -120);
}

void SendSelfTestKeyboardInput(CefRefPtr<CefBrowser> browser,
                               int native_key_code) {
  CefKeyEvent key_event;
  key_event.windows_key_code = 0x4B;
  key_event.native_key_code = native_key_code;
  key_event.character = 'k';
  key_event.unmodified_character = 'k';

  CefRefPtr<CefBrowserHost> host = browser->GetHost();
  key_event.type = KEYEVENT_RAWKEYDOWN;
  host->SendKeyEvent(key_event);
  key_event.type = KEYEVENT_CHAR;
  host->SendKeyEvent(key_event);
  key_event.type = KEYEVENT_KEYUP;
  host->SendKeyEvent(key_event);
}

} // namespace kwebshell
