# KWebShell Dialogs Service

`kweb-service-dialogs` binds one caller-owned Compose Desktop `ComposeWindow`
to Cocoa sheets on macOS, COM `IFileDialog` on Windows, or the XDG Desktop
Portal on Linux through a versioned C ABI and JDK 25 FFM. Open selections return read-only
opaque handles; save selections return write-only handles. Renderer clients
receive a token, file name, size, and mode, never a host path.

The handle API is bounded to 128 KiB per transfer and publishes only read, write,
truncate, and close operations. Handles are scoped to the service lifecycle,
close with their owner, and reject mode mismatches, invalid tokens, traversal,
symbolic links, and out-of-range offsets.

The generated `DialogsBridge` is installed only through the existing exact-origin
bridge and operation-level service grants. Native dialog or owner failures are
typed `dialog.*` errors; no alternate dialog backend or unrestricted filesystem
operation is selected.

## Verification

```shell
JAVA_HOME=/absolute/path/to/jdk-25 ./gradlew :kweb-service-dialogs:check \
  -PcefRoot=/absolute/path/to/extracted-cef
```

The task runs common contract tests, bridge tests, deterministic generated
sources, strict TypeScript compilation, the native C ABI tests, real picker
selection and cancellation, and the exact-origin desktop CEF fixture. The smoke
and CEF tasks load the target-labelled native provider archive rather than a
build-tree library.
