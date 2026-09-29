# KWebShell Files Service

`kweb-service-files` exposes host-declared logical workspaces through
owner-bound opaque capabilities. Renderer code receives normalized names and
bounded metadata/bytes, never an absolute path or platform handle.

The JVM provider uses JDK 25 `java.nio.file` with no-follow validation,
bounded I/O, atomic same-filesystem move/copy publication, and a bounded
`WatchService` stream. A missing provider capability is a typed failure; no
alternate root or backend is selected.

The service is PAGE-scoped. A host creates it with one page/origin/navigation
owner and a map of logical workspace IDs to trusted absolute roots. The host
must close it on navigation or page shutdown. Persistent macOS security-scoped
bookmarks and Linux document-portal grants are not part of this contract.

`FilesBridge` is generated from `src/mainBridge/files-bridge.json` and is
installed through the existing exact-origin bridge and permission policy.
