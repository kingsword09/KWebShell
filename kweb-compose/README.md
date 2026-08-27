# KWebShell Compose WebView

`kweb-compose` is the Compose Desktop surface layer for an existing
`KWebPage`. It does not create a browser backend and it does not transfer
frames through off-screen rendering. The page remains the CEF windowed native
child owned by `kweb-desktop`.

```kotlin
@Composable
fun WindowScope.Content(page: KWebPage) {
    val controller = rememberKWebViewController(
        page = page,
        ownership = KWebViewPageOwnership.EXTERNAL,
    )
    KWebView(controller, Modifier)
}
```

`KWebViewPageOwnership.COMPONENT` makes composition disposal close the page;
`EXTERNAL` only detaches and hides the native child, leaving page ownership with
the caller. A controller can be attached to one component at a time.

The component accepts axis-aligned, unscaled, fully visible rectangles. A
rotation, scale, Compose clip, or placement outside the `ComposeWindow` content
area is reported through `KWebViewController.placementError` as a typed error;
there is no OSR, system WebView, overlay-window, or silent fallback path.

The real runtime gate is `:kweb-compose:composeIntegrationTest`. It creates one
visible `ComposeWindow`, opens two CEF pages under the same native parent, and
drives them through Compose layout, ordinary recomposition, resize, move,
focus, minimize/restore, and ownership disposal. The test also verifies that a
component-owned page closes when removed while an externally owned page stays
open, and that CEF does not create an extra visible AWT top-level window. The
task uses the pinned native CEF artifact supplied through `-PcefRoot` and runs
under JDK 25; Linux uses an explicit Xvfb display rather than a renderer
fallback.
