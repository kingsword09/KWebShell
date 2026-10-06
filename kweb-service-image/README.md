# KWeb Native Image

`kweb-service-image` publishes the bounded RFC 0027 image value and codec
contract. It accepts only PNG and JPEG bytes or exact package resource IDs with
SHA-256 digests, normalizes accepted images to deterministic RGBA8 sRGB PNG,
and keeps Win32, CoreGraphics, and GdkPixbuf handles behind the native C ABI.

The service has no URL loader, ambient filesystem path, SVG execution, mutable
bitmap API, or fallback decoder. Renderer access is generated from
`src/mainBridge/image-bridge.json` and exposes only named bounded `decode` and
`encodePng` operations.

```shell
./gradlew :kweb-service-image:check
```
