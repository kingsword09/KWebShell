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
