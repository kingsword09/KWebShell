# RFC 0032: Diagnostics, crash reporting, tracing, metrics, and redacted logs

- Status: Proposed
- Priority: P1
- Owners: diagnostics module, CEF native host, release packaging
- Depends on: RFC 0003, RFC 0004, RFC 0030
- Electron migration surface: `crashReporter`, `contentTracing`, `netLog`, `app.getAppMetrics`, GPU info
- Target mapping: `REWRITE`

## Objective

Provide opt-in diagnostics across Kotlin, native host, Chromium subprocesses,
services, and managed processes with consent, redaction, retention, and explicit
upload ownership.

## Contract

Define diagnostic session IDs, component/process metrics, trace categories,
network-log policy, crash artifact metadata, redaction profile, retention, and
export handles. Capture and upload are separate operations; KWebShell never sends
data without an application-installed uploader and policy.

## Platform provider contract

Integrate Chromium crash dumps/tracing/net logs with Windows minidump metadata,
macOS crash/signpost facilities, and Linux core/minidump metadata as explicitly
packaged. OS crash services remain external facts rather than fallback uploaders.

## Acceptance

1. Controlled crashes in browser, renderer, GPU, helper, Kotlin worker, native
   service, and managed process produce attributable bounded artifacts.
2. Tracing starts/stops atomically, category allowlists work, repeated sessions
   do not leak, and large output streams to RFC 0013.
3. Network logs redact credentials, cookies, query/body data, private paths, and
   certificate secrets according to tested profiles.
4. Consent/revocation, retention expiry, disk quota, crash during capture, and
   owner shutdown are deterministic.
5. Renderer adapters expose only application-declared status/export actions;
   arbitrary extra crash parameters are schema validated.
6. Migration fixtures classify crashReporter/contentTracing/netLog methods and
   never claim Electron upload endpoint compatibility.

## Evidence

CI retains synthetic crash IDs, sanitized sample artifacts, symbol resolution
results, redaction tests, quota metrics, and zero live diagnostic sessions.

## Non-goals

No automatic telemetry upload, undeclared trace category, credential-bearing
network log, unlimited retention, renderer-selected native dump path, or claim
of compatibility with Electron crash endpoints.
