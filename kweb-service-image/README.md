# KWeb Native Image

`kweb-service-image` publishes the bounded RFC 0027 image value and codec
contract. It accepts only PNG and JPEG bytes or exact package resource IDs with
SHA-256 digests, normalizes accepted images to deterministic RGBA8 sRGB PNG,
and keeps Win32, CoreGraphics, and GdkPixbuf handles behind the native C ABI.

The service has no URL loader, ambient filesystem path, SVG execution, mutable
bitmap API, or fallback decoder. Renderer access is generated from
`src/mainBridge/image-bridge.json` and exposes only named bounded `decode` and
`encodePng` operations.

The host codec accepts at most 16 MiB of encoded data and 6,000,000 pixels, with
dimensions from 1 through 16,384. The renderer bridge limits encoded input and
output to 512 KiB so base64 responses fit the existing 1 MiB JSON transport.
Excess input or output returns `image.payload-too-large`. PNG chunks and CRCs,
identity JPEG EXIF orientation, and bounded ICC profiles are validated before
native allocation. Canonical output declares sRGB and preserves straight alpha.

The exact providers are Windows x64 `HBITMAP`, macOS arm64 `CGImageRef`, and
Linux x64 `GdkPixbuf`. The application opens the service with an explicit native
library path and installs it using `KWebNativeImage.Key`. Linux requires the
system GdkPixbuf library; missing or incompatible providers fail with
`image.platform-unavailable`.

Calls are serialized per service and execute on an IO worker. A provider allows
at most 256 live handles; excess creation returns `image.handle-limit`. Closing
the service releases its images and rejects new work. The provider library stays
resident until process exit because GdkPixbuf's global types retain its code.
Cancelling delivery after native creation releases the undelivered handle.

```shell
./gradlew :kweb-service-image:check
```

The check includes common/JVM tests, native pixel and ownership tests, real FFM
integration, generated TypeScript, and native package verification. The
`nativeImageRuntimeZip` task writes
`build/distributions/kweb-service-image-1.0.0-<target>.zip`, containing only the
tested provider under `native/<target>/` and its C ABI header. Test executables
are excluded. Provider, package and test reports are retained by hosted CI.
